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
        GRAPH.addRoot(ItemKey.of(stack), Math.max(1, amount));
        rebuild();
    }

    public static void setAmount(ItemKey key, long amount) {
        GRAPH.setRootAmount(key, Math.max(1, amount));
        rebuild();
    }

    public static void remove(ItemKey key) {
        GRAPH.removeRoot(key);
        PREFERENCES.remove(key);
        rebuild();
    }

    public static void clear() {
        GRAPH.clear();
        PREFERENCES.clear();
        rebuild();
    }

    /** Picks another recipe for an item (Stage 6) and re-expands the tree. */
    public static void selectRecipe(ItemKey key, RecipeHolder<?> recipe) {
        if (recipe == null) {
            PREFERENCES.remove(key);
        } else {
            PREFERENCES.put(key, recipe.id());
        }
        rebuild();
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
        hideOresOverride = !hideOres();
        rebuild();
    }

    /** Drops the runtime switch so the config value applies again. */
    public static void resetHideOresOverride() {
        hideOresOverride = null;
    }

    /** Re-expands all roots using the recipes of the current level. */
    public static void rebuild() {
        Minecraft mc = Minecraft.getInstance();
        CraftTreeBuilder.rebuild(GRAPH, mc.level, PREFERENCES, hideOres());
        refreshAvailability();
    }

    /**
     * Re-scans the inventory (Stage 9). Called after every rebuild and once
     * per second from the client tick handler, so the colours stay fresh
     * while the player moves items around.
     */
    public static void refreshAvailability() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            availability = Collections.emptyMap();
            return;
        }
        availability = ResourceCheck.evaluate(
                GRAPH.aggregateRequirements(), InventorySnapshot.of(player));
    }
}
