package com.craftplanner.engine;

import com.craftplanner.Config;
import com.craftplanner.graph.CraftGraph;
import com.craftplanner.graph.CraftMath;
import com.craftplanner.graph.IngredientUtil;
import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.NodeKind;
import com.craftplanner.graph.RecipeNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

/**
 * Recursive recipe-expansion engine (Stage 3).
 *
 * <p>Given the roots of a {@link CraftGraph}, it walks the recipe tree:
 * for every node it looks up the recipes producing that item, picks the
 * preferred one, multiplies ingredient amounts by the number of required
 * craft operations and expands the children down to raw materials.</p>
 *
 * <p>Ingredient slots are grouped by the item they resolve to: a recipe that
 * consumes the same item in several slots — four redstone for one target —
 * produces a single child carrying the summed amount, not one duplicate row per
 * slot. The tree is a shopping list, so equal siblings are always merged.</p>
 *
 * <p>Safety rules:
 * <ul>
 *   <li>cycle protection — an ingredient that is already present on the
 *       current path never becomes an edge at all (see {@link #expand}), so
 *       the tree can never draw an {@code A -> B -> A} loop; if nothing is
 *       left to expand the item ends the branch as {@link NodeKind#RAW};</li>
 *   <li>{@link #MAX_DEPTH} — hard stop for pathological chains;</li>
 *   <li>{@link #MAX_NODES} — hard stop so one bad datapack cannot freeze
 *       the client;</li>
 *   <li>missing recipes and empty tag ingredients just end the branch as
 *       {@link NodeKind#RAW};</li>
 *   <li>ore-like resources are dropped from the tree by {@link ResourceFilter}
 *       (switch: {@code filters.hideOres}) — the branch ends at the item that
 *       needed them, which then counts as raw material;</li>
 *   <li>base materials are never expanded either: planks end the branch as
 *       {@link NodeKind#RAW} (see {@link BaseMaterials}), so the tree stops at
 *       "4 oak planks" instead of walking on to the log they were sawn from.</li>
 * </ul>
 */
public final class CraftTreeBuilder {

    /** Defaults of {@code limits.maxDepth} / {@code limits.maxNodes} in {@code Config}. */
    public static final int MAX_DEPTH = 24;
    public static final int MAX_NODES = 4000;

    private final RecipeIndex index;
    /**
     * Player's recipe choice per item, stored as recipe ids: the holders
     * themselves belong to the level that produced them and become stale as
     * soon as the world is reloaded, while the ids stay valid.
     */
    private final Map<ItemKey, ResourceLocation> preferences = new HashMap<>();
    /** Drops ore-like resources from the tree (Stage 11). */
    private final ResourceFilter filter;
    private int nodeCount;

    public CraftTreeBuilder(RecipeIndex index) {
        this(index, Map.of(), ResourceFilter.NONE);
    }

    public CraftTreeBuilder(RecipeIndex index, Map<ItemKey, ResourceLocation> preferences) {
        this(index, preferences, ResourceFilter.NONE);
    }

    public CraftTreeBuilder(RecipeIndex index, Map<ItemKey, ResourceLocation> preferences, ResourceFilter filter) {
        this.index = index;
        this.filter = filter == null ? ResourceFilter.NONE : filter;
        if (preferences != null) {
            this.preferences.putAll(preferences);
        }
    }

    /** Convenience entry point: builds an index for the level and expands. */
    public static void rebuild(CraftGraph graph, Level level) {
        rebuild(graph, level, Map.of(), false);
    }

    /** Same as above, honouring the recipe choices the player made. */
    public static void rebuild(CraftGraph graph, Level level, Map<ItemKey, ResourceLocation> preferences) {
        rebuild(graph, level, preferences, false);
    }

    /**
     * Same as above, with the "hide ore-like resources" switch
     * ({@code filters.hideOres}, see {@link ResourceFilter}).
     */
    public static void rebuild(CraftGraph graph, Level level, Map<ItemKey, ResourceLocation> preferences,
            boolean hideOreLike) {
        if (level == null) {
            return;
        }
        // The index is cached per RecipeManager: a rebuild after an add, a
        // preference change or the ore switch re-expands the tree over the
        // existing index instead of re-walking every recipe in the game.
        RecipeIndex index = RecipeIndex.cachedFor(level.getRecipeManager(), level.registryAccess());
        new CraftTreeBuilder(index, preferences, ResourceFilter.of(hideOreLike)).rebuild(graph);
    }

    /** Expands every root of the graph in place. */
    public void rebuild(CraftGraph graph) {
        nodeCount = 0;
        Set<ItemKey> path = new HashSet<>();
        for (RecipeNode root : graph.roots()) {
            expand(root, path);
        }
    }

    private void expand(RecipeNode node, Set<ItemKey> path) {
        if (node.depth() >= Config.maxDepth() || nodeCount >= Config.maxNodes()) {
            markTerminal(node, NodeKind.RAW);
            return;
        }
        if (path.contains(node.item())) {
            markTerminal(node, NodeKind.CYCLE);
            return;
        }
        if (BaseMaterials.isBaseMaterial(node.item())) {
            // Planks and the like are gathered in bulk, not planned: the branch
            // ends here and the item itself becomes the raw material, so no
            // recipe is looked up and no "craft it from ..." step appears. See
            // BaseMaterials for why the log -> planks step is left out.
            markTerminal(node, NodeKind.RAW);
            return;
        }

        List<RecipeHolder<?>> options = index.forOutput(node.item());
        if (options.isEmpty()) {
            markTerminal(node, NodeKind.RAW);
            return;
        }

        RecipeHolder<?> chosen = chooseRecipe(options, preferences.get(node.item()), node.item(), path);
        // A null pick means every recipe for this item merely takes a storage
        // block apart (hay_block -> 9 wheat): the item is a base material and
        // the branch ends here. The recipes are still recorded so the list
        // screen can show which one applies and — when the item has several —
        // let the player force one.
        boolean baseMaterial = chosen == null;
        if (baseMaterial) {
            chosen = options.get(0);
        }

        // Inputs are read through the type-aware layer, never through
        // Recipe#getIngredients() directly: smithing recipes hold their slots in
        // private fields and would come back empty (see RecipeIngredients).
        NonNullList<Ingredient> ingredients = RecipeIngredients.of(chosen.value());

        // A recipe the engine cannot read must not be recorded at all. Writing it
        // onto the node would leave the list screen offering a recipe button for a
        // branch that never expands — a recipe with no result — so the item is
        // treated as raw material instead. The pick from a previous rebuild is
        // dropped with it, because the graph keeps its nodes across rebuilds.
        if (!baseMaterial && ingredients.isEmpty()) {
            node.clearRecipe();
            node.clearAlternatives();
            markTerminal(node, NodeKind.RAW);
            return;
        }

        int chosenIndex = Math.max(0, options.indexOf(chosen));
        node.setSelectedRecipeIndex(chosenIndex);
        node.clearAlternatives();
        for (RecipeHolder<?> option : options) {
            if (option != chosen) {
                node.addAlternative(option);
            }
        }

        ItemStack result = chosen.value().getResultItem(index.registries());
        node.setRecipe(chosen, result.getCount());
        node.clearChildren();

        if (baseMaterial) {
            markTerminal(node, NodeKind.RAW);
            return;
        }

        long crafts = node.crafts();

        path.add(node.item());

        // An ingredient may occupy several slots at once: the target needs four
        // separate redstone slots. The slots are grouped by item *before* any
        // node is created, so the tree gets one child carrying the summed amount
        // instead of four identical "redstone x1" rows — the tree is a shopping
        // list, not a preview of the crafting grid. Grouping first also means the
        // shared child is expanded once, with the total demand, so its own
        // crafting steps are computed on the real amount rather than per slot.
        // Insertion order is preserved, so children keep the recipe's order.
        Map<ItemKey, GroupedSlot> grouped = new LinkedHashMap<>();
        for (int slot = 0; slot < ingredients.size(); slot++) {
            Ingredient ingredient = ingredients.get(slot);
            if (IngredientUtil.isEmpty(ingredient)) {
                continue;
            }
            ItemStack representative = IngredientUtil.representative(ingredient);
            if (representative == null) {
                continue; // tag resolves to nothing — broken datapack, skip
            }

            ItemKey childKey = ItemKey.of(representative);
            if (path.contains(childKey)) {
                // Back edge: this ingredient is already being produced higher up
                // this very branch. Adding it would render A -> B -> A, which
                // reads as "ingredients and results swapped" (the classic
                // hay_block -> wheat -> hay_block storage loop). The branch
                // simply ends here — the item is already counted above.
                continue;
            }
            if (filter.hides(childKey)) {
                // Ore-like resources are mined rather than crafted. Leaving the
                // node out makes the branch end here, so the item that needed
                // the ore becomes the raw material itself (redstone instead of
                // deepslate redstone ore) and the shopping list stays useful.
                continue;
            }

            grouped.computeIfAbsent(childKey, key -> new GroupedSlot(ingredient))
                    .addPerCraft(Math.max(1, representative.getCount()));
        }

        int children = 0;
        for (Map.Entry<ItemKey, GroupedSlot> entry : grouped.entrySet()) {
            GroupedSlot group = entry.getValue();
            RecipeNode child = new RecipeNode(
                    entry.getKey(),
                    CraftMath.saturatingMul(crafts, group.perCraft),
                    node.depth() + 1,
                    NodeKind.INTERMEDIATE);

            for (ItemKey candidate : IngredientUtil.keys(group.ingredient)) {
                child.addCandidate(candidate);
            }
            child.selectCandidate(0);

            node.addChild(child);
            nodeCount++;
            children++;
            expand(child, path);
        }
        path.remove(node.item());

        if (children == 0) {
            // Every ingredient was dropped before a node was created: either a
            // back edge (a storage pack/unpack pair such as hay_block <-> wheat)
            // or a resource the filter hides. There is nothing left to expand,
            // so the item is effectively a raw material.
            markTerminal(node, NodeKind.RAW);
        }
    }

    /**
     * Picks the recipe used to produce the node's item.
     *
     * <p>Order of preference:
     * <ol>
     *   <li>an explicit player choice (recipe switch in the list screen);</li>
     *   <li>a recipe that keeps at least one visible ingredient, neither
     *       reverses a trivial pack/unpack pair nor consumes an item already on
     *       the current path;</li>
     *   <li>a recipe that at least keeps a visible ingredient and does not
     *       reverse a pack/unpack pair;</li>
     *   <li>a recipe that at least does not reverse a pack/unpack pair;</li>
     *   <li>a recipe that at least is not a bare "unpack" step;</li>
     *   <li>{@code null} — every recipe only takes a storage block apart, so
     *       the item has to be treated as a base material.</li>
     * </ol>
     *
     * <p>Without steps 2–5 a storage block pair ({@code redstone_block <->
     * redstone}, {@code hay_block <-> wheat}) is expanded in both directions at
     * once: the engine picks the unpack recipe because crafting outranks
     * smelting, and the matching pack recipe then walks straight back to the
     * item it started from. The "keeps a visible ingredient" part keeps the
     * ore filter from declaring a craftable item raw: when one of several
     * recipes asks only for hidden resources, the next recipe wins.</p>
     */
    private RecipeHolder<?> chooseRecipe(List<RecipeHolder<?>> options,
                                         ResourceLocation preferredId,
                                         ItemKey current,
                                         Set<ItemKey> path) {
        if (preferredId != null) {
            for (RecipeHolder<?> option : options) {
                if (option.id().equals(preferredId)) {
                    return option;
                }
            }
        }
        for (RecipeHolder<?> option : options) {
            if (hasVisibleIngredient(option)
                    && !reversesPackPair(option, current)
                    && !consumesPathItem(option, path)) {
                return option;
            }
        }
        for (RecipeHolder<?> option : options) {
            if (hasVisibleIngredient(option) && !reversesPackPair(option, current)) {
                return option;
            }
        }
        for (RecipeHolder<?> option : options) {
            if (!reversesPackPair(option, current)) {
                return option;
            }
        }
        for (RecipeHolder<?> option : options) {
            if (!isStorageUnpack(option, current)) {
                return option;
            }
        }
        return null;
    }

    /**
     * True when at least one ingredient of the recipe survives the filter, i.e.
     * the recipe would actually add something to the tree. Always true while
     * the filter is off.
     */
    private boolean hasVisibleIngredient(RecipeHolder<?> recipe) {
        for (Ingredient ingredient : RecipeIngredients.of(recipe.value())) {
            ItemKey key = IngredientUtil.representativeKey(ingredient);
            if (key != null && !filter.hides(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code recipe} produces {@code current} by taking apart an item
     * that is itself made out of {@code current} — the signature of a trivial
     * two-node cycle such as {@code redstone <-> redstone_block},
     * {@code coal <-> coal_block} or {@code hay_block <-> wheat}.
     */
    private boolean reversesPackPair(RecipeHolder<?> recipe, ItemKey current) {
        for (Ingredient ingredient : RecipeIngredients.of(recipe.value())) {
            ItemKey ingredientKey = IngredientUtil.representativeKey(ingredient);
            if (ingredientKey == null) {
                continue;
            }
            if (ingredientKey.equals(current)) {
                return true;
            }
            for (RecipeHolder<?> producer : index.forOutput(ingredientKey)) {
                if (consumes(producer, current)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when one of {@code recipe}'s ingredients is exactly {@code item}. */
    private static boolean consumes(RecipeHolder<?> recipe, ItemKey item) {
        for (Ingredient ingredient : RecipeIngredients.of(recipe.value())) {
            ItemKey key = IngredientUtil.representativeKey(ingredient);
            if (key != null && key.equals(item)) {
                return true;
            }
        }
        return false;
    }

    /** True when one of {@code recipe}'s ingredients is already on the path. */
    private static boolean consumesPathItem(RecipeHolder<?> recipe, Set<ItemKey> path) {
        if (path.isEmpty()) {
            return false;
        }
        for (Ingredient ingredient : RecipeIngredients.of(recipe.value())) {
            ItemKey key = IngredientUtil.representativeKey(ingredient);
            if (key != null && path.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True for a bare "unpack" step: one unit of a storage block turned into
     * several loose items ({@code hay_block -> 9 wheat}, {@code redstone_block
     * -> 9 redstone}). Together with {@link #reversesPackPair} this pins down
     * the direction that must not be used to explain where an item comes from —
     * the block being taken apart is crafted out of that very item.
     */
    private boolean isStorageUnpack(RecipeHolder<?> recipe, ItemKey current) {
        if (!reversesPackPair(recipe, current)) {
            return false;
        }
        ItemStack result = recipe.value().getResultItem(index.registries());
        if (result.isEmpty() || result.getCount() <= 1) {
            return false;
        }
        int inputs = 0;
        for (Ingredient ingredient : RecipeIngredients.of(recipe.value())) {
            if (IngredientUtil.isEmpty(ingredient)) {
                continue;
            }
            inputs++;
            ItemStack representative = IngredientUtil.representative(ingredient);
            if (representative == null || representative.getCount() > 1) {
                return false;
            }
        }
        return inputs == 1;
    }

    /**
     * The ingredient slots of one recipe that resolve to the same item, folded
     * into a single child node.
     *
     * <p>{@code perCraft} accumulates across the slots, so four redstone slots
     * become one ingredient consuming four redstone per craft operation.</p>
     */
    private static final class GroupedSlot {
        private final Ingredient ingredient;
        private long perCraft;

        GroupedSlot(Ingredient ingredient) {
            this.ingredient = ingredient;
        }

        void addPerCraft(long amount) {
            perCraft = CraftMath.saturatingAdd(perCraft, amount);
        }
    }

    private static void markTerminal(RecipeNode node, NodeKind kind) {
        node.clearChildren();
        node.setKind(node.depth() == 0 ? NodeKind.ROOT : kind);
    }
}
