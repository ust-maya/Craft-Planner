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
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

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
     * in Stage 6). Keyed by recipe id, because {@link RecipeHolder} instances
     * belong to the level that produced them and go stale on reload.
     */
    private static final Map<ItemKey, ResourceLocation> PREFERENCES = new HashMap<>();
    /**
     * Runtime switch for {@code filters.hideOres}. {@code null} means "follow
     * the config"; the K window sets it so the player can flip the filter
     * without restarting the game.
     */
    private static Boolean hideOresOverride;
    private static Map<ItemKey, Availability> availability = Collections.emptyMap();
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
            GRAPH.removeRoot(key);
            PREFERENCES.remove(key);
            rebuild();
        } finally {
            LOCK.unlock();
        }
    }

    public static void clear() {
        LOCK.lock();
        try {
            GRAPH.clear();
            PREFERENCES.clear();
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
     * Picks another recipe for an item (Stage 6). The re-expansion is debounced
     * by {@link #REBUILD_DEBOUNCE_MS} — see {@link #tick()}.
     */
    public static void selectRecipe(ItemKey key, RecipeHolder<?> recipe) {
        LOCK.lock();
        try {
            if (recipe == null) {
                PREFERENCES.remove(key);
            } else {
                PREFERENCES.put(key, recipe.id());
            }
            pendingRebuildAt = System.currentTimeMillis() + REBUILD_DEBOUNCE_MS;
        } finally {
            LOCK.unlock();
        }
    }

    /** Recipe choice per item, as passed to {@link CraftTreeBuilder}. */
    public static Map<ItemKey, ResourceLocation> preferences() {
        return Collections.unmodifiableMap(PREFERENCES);
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
            Minecraft mc = Minecraft.getInstance();
            CraftTreeBuilder.rebuild(GRAPH, mc.level, PREFERENCES, hideOres());
            graphVersion++;
            refreshAvailability();
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Re-scans the inventory (Stage 9). Called after every rebuild and once
     * per second from the client tick handler, so the colours stay fresh
     * while the player moves items around.
     */
    public static void refreshAvailability() {
        LOCK.lock();
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null) {
                availability = Collections.emptyMap();
                return;
            }
            availability = ResourceCheck.evaluate(
                    GRAPH.aggregateRequirements(), InventorySnapshot.of(player));
        } finally {
            LOCK.unlock();
        }
    }
}
