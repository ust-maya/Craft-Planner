package com.craftplanner.client;

import com.craftplanner.Config;
import com.craftplanner.client.resource.Availability;
import com.craftplanner.client.resource.InventorySnapshot;
import com.craftplanner.client.resource.ResourceCheck;
import com.craftplanner.engine.CraftTreeBuilder;
import com.craftplanner.graph.CraftGraph;
import com.craftplanner.graph.ItemKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.material.Fluid;

/**
 * Client-side planner state: the single {@link CraftGraph} the player builds
 * up, plus the mutations the UI performs on it.
 *
 * <p>Every mutation rebuilds the trees through
 * {@link CraftTreeBuilder}, so the graph is always in a consistent state for
 * the overlay (Stage 8) and the list screen (Stage 7). The same rebuild
 * re-scans the inventory and stores the per-material {@link Availability}
 * used for the green/red colouring and the "still needed" totals (Stage 9).</p>
 */
public final class PlannerState {

    private static final CraftGraph GRAPH = new CraftGraph();
    /**
     * Recipe choice per item (Stage 3 alternative selection, wired to the UI
     * in Stage 6). One entry per item, holding the recipe id rather than the
     * {@link RecipeHolder}: the holder belongs to the level that produced it
     * and goes stale on reload. The player's pick is pinned for as long as the
     * item is in the plan: nothing short of {@link #remove(ItemKey)} or
     * {@link #clear()} takes it back.
     */
    private static final Map<ItemKey, ResourceLocation> PREFERENCES = new HashMap<>();
    /**
     * Items the player cut the tree at: the branch ends there, the item itself
     * is planned as material to gather. Keyed by item like {@link #PREFERENCES}
     * and saved next to them, so a cut applies wherever the item appears and is
     * still there after a restart.
     */
    private static final Set<ItemKey> CUTS = new LinkedHashSet<>();
    /**
     * Whether {@link RecipeChoices} and {@link RecipeCuts} have been merged into
     * the live maps.
     * Loaded on the first rebuild rather than at mod construction: at that point
     * the item registry still does not know about the items of the other mods,
     * and every saved choice would read as "item no longer exists".
     */
    private static boolean choicesLoaded;
    /**
     * Runtime switch for {@code filters.hideOres}. {@code null} means "follow
     * the config"; the K window sets it so the player can flip the filter
     * without restarting the game.
     */
    private static Boolean hideOresOverride;
    private static Map<ItemKey, Availability> availability = Collections.emptyMap();
    private static boolean inventoryScanned;
    /** Changes only when a refreshed inventory changes material availability. */
    private static long availabilityVersion;
    /** Fluid requirements are separate from item inventory availability, in mB. */
    private static Map<Fluid, Long> fluidRequirements = Collections.emptyMap();
    /**
     * Bumped on every {@link #rebuild()}, i.e. whenever the tree structure or
     * the amounts changed. Derived views (the overlay's {@code TreeLayout})
     * cache against it instead of recomputing each frame.
     */
    private static long graphVersion;
    /**
     * Trailing-edge debounce for {@link #selectRecipe}: rapid cycling through
     * the alternatives of one item must not rebuild the whole tree once per
     * click. {@code 0} means no rebuild is pending.
     */
    private static long pendingRebuildAt;
    private static final long REBUILD_DEBOUNCE_MS = 200L;
    /**
     * Every mutation today runs on the client thread, but the tick handler,
     * screen widgets and config events all reach this class from different
     * callbacks; the lock is a cheap insurance against a future path that
     * iterates {@code PREFERENCES} while another rebuilds the graph.
     */
    private static final ReentrantLock LOCK = new ReentrantLock();
    /**
     * Whether the K-window onboarding hint ("hover an item and press N") has
     * already been shown once. The hint is only useful before the player has
     * added their first recipe by following it, so the first closing of the
     * window retires it for the session.
     */
    private static boolean listHintSeen;

    private PlannerState() {
    }

    public static CraftGraph graph() {
        return GRAPH;
    }

    /** Availability of the raw materials, refreshed by every rebuild. */
    public static Map<ItemKey, Availability> availability() {
        return availability;
    }

    /** Monotonic counter for views that need to refresh inventory-derived rows. */
    public static long availabilityVersion() {
        return availabilityVersion;
    }

    /** Whether the latest availability state came from a live player inventory. */
    public static boolean inventoryScanned() {
        return inventoryScanned;
    }

    /** Aggregated fluid requirements for the current plan, in millibuckets. */
    public static Map<Fluid, Long> fluidRequirements() {
        return fluidRequirements;
    }

    /** Adds (or merges) a target and rebuilds the tree. */
    public static void add(ItemStack stack, long amount) {
        LOCK.lock();
        try {
            GRAPH.addRoot(ItemKey.of(stack), Math.max(1, amount));
            rebuild();
        } finally {
            LOCK.unlock();
        }
    }

    public static void setAmount(ItemKey key, long amount) {
        LOCK.lock();
        try {
            GRAPH.setRootAmount(key, Math.max(1, amount));
            rebuild();
        } finally {
            LOCK.unlock();
        }
    }

    public static void remove(ItemKey key) {
        LOCK.lock();
        try {
            ensureChoicesLoaded();
            GRAPH.removeRoot(key);
            PREFERENCES.remove(key);
            CUTS.remove(key);
            RecipeChoices.write(PREFERENCES);
            RecipeCuts.write(CUTS);
            rebuild();
        } finally {
            LOCK.unlock();
        }
    }

    public static void clear() {
        LOCK.lock();
        try {
            ensureChoicesLoaded();
            GRAPH.clear();
            PREFERENCES.clear();
            CUTS.clear();
            RecipeChoices.write(PREFERENCES);
            RecipeCuts.write(CUTS);
            rebuild();
        } finally {
            LOCK.unlock();
        }
    }

    /** True once the K window has been closed at least this session. */
    public static boolean listHintSeen() {
        return listHintSeen;
    }

    public static void markListHintSeen() {
        listHintSeen = true;
    }

    /**
     * Picks another recipe for an item (Stage 6) and keeps that pick: the item
     * is planned with this recipe until it leaves the plan, whereupon
     * {@link #remove(ItemKey)} or {@link #clear()} drops the choice with it.
     * The re-expansion is debounced by {@link #REBUILD_DEBOUNCE_MS} — see
     * {@link #tick()}.
     */
    public static void selectRecipe(ItemKey key, RecipeHolder<?> recipe) {
        LOCK.lock();
        try {
            ensureChoicesLoaded();
            PREFERENCES.put(key, recipe.id());
            // The choice is the one piece of the plan the player expects to still
            // be there after a restart, so it is written down as it is made.
            RecipeChoices.write(PREFERENCES);
            pendingRebuildAt = System.currentTimeMillis() + REBUILD_DEBOUNCE_MS;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Stops the tree at an item (or lets it expand again). The item stays in the
     * plan and starts counting as material to gather; its own ingredients are no
     * longer planned. Like a recipe choice it applies wherever the item appears.
     */
    public static void toggleCut(ItemKey key) {
        LOCK.lock();
        try {
            ensureChoicesLoaded();
            if (!CUTS.remove(key)) {
                CUTS.add(key);
            }
            RecipeCuts.write(CUTS);
            pendingRebuildAt = System.currentTimeMillis() + REBUILD_DEBOUNCE_MS;
        } finally {
            LOCK.unlock();
        }
    }

    /** True when the branch must end at this item. */
    public static boolean isCut(ItemKey key) {
        return CUTS.contains(key);
    }

    /**
     * The recipe choices, as a copy.
     *
     * <p>A what-if expansion of some <em>other</em> recipe has to start from the
     * choices the player actually made — otherwise the preview would silently
     * fall back to the engine's ranking for every item below the one being
     * compared, and would contradict the plan it sits next to.</p>
     */
    public static Map<ItemKey, ResourceLocation> preferences() {
        LOCK.lock();
        try {
            ensureChoicesLoaded();
            return Map.copyOf(PREFERENCES);
        } finally {
            LOCK.unlock();
        }
    }

    /** The cut items, as a copy; see {@link #preferences()} for why a what-if needs them. */
    public static Set<ItemKey> cuts() {
        LOCK.lock();
        try {
            ensureChoicesLoaded();
            return Set.copyOf(CUTS);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Whether ore-like resources are currently kept out of the tree: the
     * runtime switch when the player used the K window, the config value
     * otherwise.
     */
    public static boolean hideOres() {
        Boolean override = hideOresOverride;
        return override != null ? override : Config.hideOres();
    }

    /** Flips the "hide ore-like resources" switch and re-expands the trees. */
    public static void toggleHideOres() {
        LOCK.lock();
        try {
            hideOresOverride = !hideOres();
            rebuild();
        } finally {
            LOCK.unlock();
        }
    }

    /** Drops the runtime switch so the config value applies again. */
    public static void resetHideOresOverride() {
        hideOresOverride = null;
    }

    /** Monotonic counter identifying the current tree structure. */
    public static long graphVersion() {
        return graphVersion;
    }

    /**
     * Runs the debounced recipe-change rebuild once its window has elapsed.
     * Called every client tick from {@code ClientEvents}.
     */
    public static void tick() {
        LOCK.lock();
        try {
            long at = pendingRebuildAt;
            if (at != 0L && System.currentTimeMillis() >= at) {
                rebuild();
            }
        } finally {
            LOCK.unlock();
        }
    }

    /** Re-expands all roots using the recipes of the current level. */
    public static void rebuild() {
        LOCK.lock();
        try {
            pendingRebuildAt = 0L;
            ensureChoicesLoaded();
            Minecraft mc = Minecraft.getInstance();
            CraftTreeBuilder.rebuild(GRAPH, mc.level, PREFERENCES, hideOres(), CUTS);
            graphVersion++;
            refreshAvailability();
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Merges the saved recipe choices and tree cuts once, before a tree is ever
     * expanded against them.
     */
    private static void ensureChoicesLoaded() {
        if (choicesLoaded) {
            return;
        }
        choicesLoaded = true;
        PREFERENCES.putAll(RecipeChoices.read());
        CUTS.addAll(RecipeCuts.read());
    }

    /**
     * Re-scans the inventory (Stage 9). Called after every rebuild and once
     * per second from the client tick handler, so the colours stay fresh
     * while the player moves items around.
     */
    public static void refreshAvailability() {
        LOCK.lock();
        try {
            fluidRequirements = GRAPH.aggregateFluidRequirements();
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null) {
                replaceAvailability(Collections.emptyMap(), false);
                return;
            }
            replaceAvailability(ResourceCheck.evaluate(
                    GRAPH.aggregateRequirements(), InventorySnapshot.of(player)), true);
        } finally {
            LOCK.unlock();
        }
    }

    private static void replaceAvailability(Map<ItemKey, Availability> next, boolean scanned) {
        if (!availability.equals(next) || inventoryScanned != scanned) {
            availability = next;
            inventoryScanned = scanned;
            availabilityVersion++;
        }
    }
}
