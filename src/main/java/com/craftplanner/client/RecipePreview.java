package com.craftplanner.client;

import com.craftplanner.engine.CraftTreeBuilder;
import com.craftplanner.graph.CraftGraph;
import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.RecipeNode;
import com.craftplanner.client.tree.TreeFlatten;
import com.craftplanner.client.tree.TreeRow;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * What one recipe <em>would</em> cost, expanded as a tree.
 *
 * <p>The recipe picker used to answer "which recipe?" with a flat list of costs
 * — three rows reading {@code 4 iron}, {@code 2 iron + 1 gold} and {@code 12 iron},
 * each with its machine. Those numbers are what a player compares first, and they
 * are also the point at which the comparison stops helping: two options can cost
 * the same raw materials and pull in completely different things further down, and
 * the only way to see that is to look at the tree each one produces. So each option
 * can be expanded into its own dependency tree, drawn with the plan's own
 * geometry, and the options are then compared by their branches instead of by a
 * one-line total.</p>
 *
 * <p>The preview is built by the real engine rather than by walking the recipe
 * ingredients here. That matters for two reasons: the alternatives a nested item
 * offers are resolved the same way the plan resolves them — including the recipe
 * choices the player has already pinned and the branches they have cut — and the
 * preview therefore cannot promise a cost the plan would not actually deliver.
 * It goes into a throwaway {@link CraftGraph}: the plan is never re-expanded, so
 * opening this window cannot change what the plan holds.</p>
 */
public final class RecipePreview {

    private RecipePreview() {
    }

    /**
     * Previews by recipe and item, dropped whenever the plan changes.
     *
     * <p>Every preview depends on the player's recipe choices and cuts, so a
     * plan change invalidates all of them. Keyed on {@link PlannerState#graphVersion()}
     * rather than a timer: switching a recipe is the one thing that invalidates it,
     * and it already bumps the version.</p>
     *
     * <p>The item is part of the key because the recipe id is not: one recipe can
     * be offered as the way to make several things — a machine that yields both an
     * ingot and a gas — and the tree below it is expanded for the item that was
     * asked for, not for whatever the recipe lists first.</p>
     */
    private static final Map<PreviewKey, List<TreeRow<RecipeNode>>> CACHE = new HashMap<>();
    private static long cachedVersion = -1;

    /** A preview depends on both the recipe and the item it is asked to make. */
    private record PreviewKey(ResourceLocation recipe, ItemKey item) {
    }

    /**
     * The dependencies of one option, as rows the tree layout can draw.
     *
     * <p>The root row is dropped: it is the item the option row above already
     * shows, and repeating it would make the player read the same name twice to
     * reach the part they came for.</p>
     */
    public static List<TreeRow<RecipeNode>> of(ItemKey item, RecipeHolder<?> recipe) {
        long version = PlannerState.graphVersion();
        if (version != cachedVersion) {
            CACHE.clear();
            cachedVersion = version;
        }
        return CACHE.computeIfAbsent(new PreviewKey(recipe.id(), item), key -> build(item, recipe));
    }

    private static List<TreeRow<RecipeNode>> build(ItemKey item, RecipeHolder<?> recipe) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return List.of();
        }
        Map<ItemKey, ResourceLocation> preferences = new HashMap<>(PlannerState.preferences());
        // Only this item's choice moves: everything below it stays on whatever the
        // plan decided, so the branch is comparable with the one it replaces.
        preferences.put(item, recipe.id());

        CraftGraph preview = new CraftGraph();
        preview.addRoot(item, 1);
        CraftTreeBuilder.rebuild(preview, mc.level, preferences, PlannerState.hideOres(),
                PlannerState.cuts());

        List<TreeRow<RecipeNode>> rows =
                TreeFlatten.flatten(preview.roots(), PlanRows.source(), Set.of());
        return rows.size() <= 1 ? List.of() : List.copyOf(rows.subList(1, rows.size()));
    }
}