package com.craftplanner.engine;

import com.craftplanner.Config;
import com.craftplanner.graph.CraftGraph;
import com.craftplanner.graph.CraftMath;
import com.craftplanner.graph.IngredientUtil;
import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.NodeKind;
import com.craftplanner.graph.RecipeNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recursive recipe-expansion engine.
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
 *       "4 oak planks" instead of walking on to the log they were sawn from;</li>
 *   <li>an item the player cut the tree at (the "cut here" row of the recipe
 *       picker) ends the same way, for every place it appears in the plan:
 *       leather stays "2 leather to gather" instead of walking on to rabbit
 *       hide. This is the player's own answer, so it also wins over a pinned
 *       recipe.</li>
 *   <li>an item the player already carries is taken from the inventory
 *       before it is planned to be made ({@link Stock}): only what is left
 *       is expanded, and a fully covered item ends the branch while keeping
 *       its recipe.</li>
 *   <li>recipes of mods and machines the player switched off
 *       ({@link SourceFilter}) are never options; an item left without any
 *       ends as {@link NodeKind#RAW}.</li>
 * </ul>
 */
public final class CraftTreeBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger("craftplanner/CraftTreeBuilder");

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
    /**
     * Items the player cut the tree at: the branch ends there instead of
     * expanding into their ingredients.
     */
    private final Set<ItemKey> cuts;
    /** Drops ore-like resources from the tree. */
    private final ResourceFilter filter;
    /** What the player carries, spent as the tree is expanded; see {@link Stock}. */
    private final Stock<ItemKey> stock;
    /** Mods and machines the player switched off; their recipes are never options. */
    private final SourceFilter sources;
    private int nodeCount;

    public CraftTreeBuilder(RecipeIndex index, Map<ItemKey, ResourceLocation> preferences, ResourceFilter filter,
            Set<ItemKey> cuts, Stock<ItemKey> stock, SourceFilter sources) {
        this.index = index;
        this.filter = filter == null ? ResourceFilter.NONE : filter;
        this.cuts = cuts == null ? Set.of() : cuts;
        this.stock = stock == null ? Stock.empty() : stock;
        this.sources = sources == null ? SourceFilter.NONE : sources;
        if (preferences != null) {
            this.preferences.putAll(preferences);
        }
    }

    /**
     * Same as above, with the items the player cut the tree at (the "cut here"
     * row of the recipe picker) and what the player carries: {@code inventory}
     * answers how many of an item there are, {@code null} plans as if the bag
     * were empty — the recipe picker's previews ask for exactly that.
     * {@code sources} keeps the recipes of switched-off mods and machines out
     * of every choice.
     */
    public static void rebuild(CraftGraph graph, Level level, Map<ItemKey, ResourceLocation> preferences,
            boolean hideOreLike, Set<ItemKey> cuts, Function<ItemKey, Long> inventory, SourceFilter sources) {
        if (level == null) {
            return;
        }
        // The index is cached per RecipeManager: a rebuild after an add, a
        // preference change or the ore switch re-expands the tree over the
        // existing index instead of re-walking every recipe in the game.
        RecipeIndex index = RecipeIndex.cachedFor(level.getRecipeManager(), level.registryAccess());
        new CraftTreeBuilder(index, preferences, ResourceFilter.of(hideOreLike), cuts,
                inventory == null ? Stock.empty() : new Stock<>(inventory), sources).rebuild(graph);
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
        // The graph keeps its roots across rebuilds; what they took last time is
        // forgotten until this rebuild decides again.
        node.setFromInventory(0);
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

        List<RecipeHolder<?>> options = allowed(index.forOutput(node.item()));
        if (options.isEmpty()) {
            // Nothing left to make it with: no recipe at all, or every one comes
            // from a mod or machine the player switched off. A goal keeps its node
            // across rebuilds, so the recipe of the last rebuild goes with the branch —
            // otherwise the recipe window would still offer it.
            node.clearRecipe();
            node.clearAlternatives();
            markTerminal(node, NodeKind.RAW);
            return;
        }

        RecipeHolder<?> chosen = chooseRecipe(options, preferences.get(node.item()), node.item(), path);
        // A null pick means every recipe for this item takes apart something made
        // out of it (hay_block -> 9 wheat, stone -> cobblestone): the item is a
        // base material and the branch ends here. A player cut is the same ending
        // asked for by hand ("I will gather the leather myself, do not plan
        // rabbit hide"), and it survives a pinned recipe — the player asked for
        // it. The recipes are still recorded so the list screen can show which
        // one applies and — when the item has several — let the player force one
        // of them.
        boolean cutHere = cuts.contains(node.item());
        boolean baseMaterial = chosen == null || cutHere;
        if (chosen == null) {
            // Nothing survived the ladder; the first candidate is still the true
            // description of where the item comes from.
            chosen = options.get(0);
        }
        if (Config.traceChoices()) {
            trace(node.item(), options, chosen, baseMaterial, cutHere, path);
        }

        // Inputs are read through the index, which memoised them when it was
        // built: smithing recipes keep their slots in private fields, machine
        // recipes rebuild their list on every call, and neither must be parsed
        // again per candidate per node — see RecipeIngredients and RecipeIndex.
        NonNullList<Ingredient> ingredients = index.inputsOf(chosen);

        // A recipe the engine cannot read must not be recorded at all. Writing it
        // onto the node would leave the list screen offering a recipe button for a
        // branch that never expands — a recipe with no result — so the item is
        // treated as raw material instead. The pick from a previous rebuild is
        // dropped with it, because the graph keeps its nodes across rebuilds.
        List<RecipeFluids.Requirement> fluidInputs = index.fluidInputsOf(chosen);
        if (!baseMaterial && ingredients.isEmpty() && fluidInputs.isEmpty()) {
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

        node.setRecipe(chosen, index.outputCountOf(chosen, node.item()));
        node.clearChildren();

        if (baseMaterial) {
            markTerminal(node, NodeKind.RAW);
            return;
        }

        // What the player already carries is used before anything is crafted:
        // ten books in the bag mean no leather and no paper for three of them.
        // Taken after the recipe is recorded, so a covered row still offers its
        // recipe choice. Goals take from the bag as well — the plan then shows
        // what is left to make on top of what is already there. Raw materials
        // do not take here: they are compared with the inventory afterwards,
        // minus what the assemblies have taken (PlannerState).
        node.setFromInventory(stock.take(node.item(), node.required()));
        if (node.toMake() == 0) {
            if (Config.traceChoices()) {
                LOGGER.debug("   {} x{} is covered by the inventory — not expanded",
                        node.item(), node.required());
            }
            return; // children were cleared above; the row keeps its recipe
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
            ItemStack representative = index.cheapestStack(ingredient);
            if (representative == null) {
                continue; // tag resolves to nothing — broken datapack, skip
            }

            ItemKey childKey = ItemKey.of(representative);
            if (filter.hides(childKey)) {
                // Ore-like resources are mined rather than crafted. Leaving the
                // node out makes the branch end here, so the item that needed
                // the ore becomes the raw material itself (redstone instead of
                // deepslate redstone ore) and the shopping list stays useful.
                if (Config.traceChoices()) {
                    LOGGER.debug("   drop ingredient {} of {} — hidden by the ore filter",
                            childKey, node.item());
                }
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
            child.selectCandidate(index.cheapestCandidate(group.ingredient));

            node.addChild(child);
            nodeCount++;
            children++;
            expand(child, path);
        }

        // Fluid ingredients have no player inventory source in the planner yet,
        // so they are explicit gather-only leaves rather than expandable graph
        // resources. Repeated tanks for the same fluid are combined in mB.
        Map<Fluid, Long> groupedFluids = new LinkedHashMap<>();
        for (RecipeFluids.Requirement requirement : fluidInputs) {
            groupedFluids.merge(requirement.fluid(), requirement.amount(), CraftMath::saturatingAdd);
        }
        for (Map.Entry<Fluid, Long> entry : groupedFluids.entrySet()) {
            long amount = CraftMath.saturatingMul(crafts, entry.getValue());
            node.addChild(RecipeNode.fluidRequirement(entry.getKey(), amount, node.depth() + 1));
            nodeCount++;
            children++;
        }
        path.remove(node.item());

        if (children == 0) {
            // Every ingredient was dropped before a node was created: a resource
            // the ore filter hides, or a tag that resolves to nothing in a broken
            // datapack. There is nothing left to expand, so the item is
            // effectively a raw material.
            markTerminal(node, NodeKind.RAW);
        }
    }

    /** The recipes of an item that the player has not switched off; see {@link SourceFilter}. */
    private List<RecipeHolder<?>> allowed(List<RecipeHolder<?>> all) {
        if (sources.isEmpty()) {
            return all;
        }
        List<RecipeHolder<?>> kept = new ArrayList<>(all.size());
        for (RecipeHolder<?> option : all) {
            ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(option.value().getType());
            if (!sources.hides(option.id().toString(), type == null ? "" : type.toString())) {
                kept.add(option);
            }
        }
        return kept;
    }

    /**
     * Picks the recipe used to produce the node's item.
     *
     * <p>Order of preference:
     * <ol>
     *   <li>an explicit player choice (the recipe picked in the picker screen);</li>
     *   <li>the cheapest candidate (the index sorts by unit cost) that launders
     *       nothing, neither reverses a trivial pack/unpack pair nor consumes
     *       an item already on the current path;</li>
     *   <li>a recipe that launders nothing and does not consume a path item;</li>
     *   <li>a recipe that does not reverse a pack/unpack pair and does not
     *       consume a path item;</li>
     *   <li>a recipe whose ingredients are not made out of {@code current} —
     *       it may still consume a path item, which then ends as a loop marker;</li>
     *   <li>{@code null} — every recipe takes apart something made out of the
     *       item itself (a storage block, or cobblestone from stone while stone
     *       is smelted from cobblestone), so the item is gathered: it is treated
     *       as a base material.</li>
     * </ol>
     *
     * <p>Steps 2–5 all keep the path test: a recipe whose ingredient is an
     * ancestor of the node is the back edge of a loop, and the older ladder
     * let it through on the second and third pass — the very hole through
     * which a laundering chain re-entered the tree after the first pass
     * rejected it.</p>
     *
     * <p>Without steps 2–6 a storage block pair ({@code redstone_block <->
     * redstone}, {@code hay_block <-> wheat}) is expanded in both directions at
     * once, and the matching pack recipe walks straight back to the item it
     * started from. The same holds for a two-way conversion that is not a storage
     * block at all: MI's macerator turns stone into cobblestone, the furnace turns
     * it back.</p>
     *
     * <p>Visibility is deliberately <em>not</em> part of this ladder. An older
     * revision preferred recipes with at least one unhidden ingredient, and
     * with {@code filters.hideOres} that turned ore-born metals into a
     * laundering chain ({@code ingot <- nuggets <- dust <- ring <- rod}): every
     * honest recipe asks for hidden ore, so the ladder skipped to the first
     * scrap-taking-apart recipe whose far end quietly returns to the very
     * ingot. The honest recipe now stays chosen: its ore child is dropped and
     * the item itself ends the branch as raw material — exactly the contract
     * of the ore filter.</p>
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
            if (!launders(option, current)
                    && !reversesPackPair(option, current)
                    && !consumesPathItem(option, path)) {
                return option;
            }
        }
        for (RecipeHolder<?> option : options) {
            if (!launders(option, current) && !consumesPathItem(option, path)) {
                return option;
            }
        }
        for (RecipeHolder<?> option : options) {
            if (!reversesPackPair(option, current) && !consumesPathItem(option, path)) {
                return option;
            }
        }
        // The last resort may still take a recipe that consumes an ancestor —
        // that ends one level down as a visible loop marker — but never one whose
        // ingredient is itself made out of this item: cobblestone from stone, when
        // stone is smelted from cobblestone, can only lead back to where it began.
        // Such an item is gathered, not crafted, so the branch ends here; the
        // recipes stay recorded and the player can still pin one by hand.
        for (RecipeHolder<?> option : options) {
            if (!reversesPackPair(option, current)) {
                return option;
            }
        }
        return null;
    }

    /**
     * One block per expanded item for {@code debug.traceChoices}: the pick,
     * every candidate with its rank cost and the three rejection tests, and
     * the player's pinned choice when there is one. The tests are evaluated
     * again here, so tracing costs what it says on the box — keep the switch
     * off outside a debugging session.
     */
    private void trace(ItemKey current, List<RecipeHolder<?>> options,
                       RecipeHolder<?> chosen, boolean baseMaterial, boolean cutHere,
                       Set<ItemKey> path) {
        ResourceLocation pinned = preferences.get(current);
        LOGGER.debug("Craft Planner choice for {}{}: {}{} of {} recipes",
                current, pinned == null ? "" : " (pinned to " + pinned + ")",
                baseMaterial
                        ? (cutHere ? "none — the player cut the tree here"
                                : "none — treated as a base material")
                        : chosen.id(),
                baseMaterial ? " (still recorded as the row's recipe)" : "",
                options.size());
        for (RecipeHolder<?> option : options) {
            LOGGER.debug("   {} cost={} laundered={} packReversal={} backEdge={}{}",
                    option.id(), describeCost(option, current),
                    launders(option, current), reversesPackPair(option, current),
                    consumesPathItem(option, path),
                    option == chosen ? "   <-- chosen" : "");
        }
    }

    /** The sort key of one recipe in human terms: raw-material units and steps. */
    private String describeCost(RecipeHolder<?> option, ItemKey produced) {
        RecipeIndex.Cost cost = index.rankCost(option, produced);
        if (cost.isUnknown()) {
            return "unpriced";
        }
        return String.format("%.4f units / %d steps", cost.units(), cost.steps());
    }

    /**
     * True when the recipe produces {@code current} by breaking down something
     * whose own cheapest chain needs {@code current} — a scrap loop, not a
     * supply chain. See {@link RecipeIndex#chainReaches}.
     */
    private boolean launders(RecipeHolder<?> recipe, ItemKey current) {
        for (Ingredient ingredient : index.inputsOf(recipe)) {
            for (ItemKey candidate : IngredientUtil.keys(ingredient)) {
                if (index.chainReaches(candidate, current)) {
                    return true;
                }
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
        for (Ingredient ingredient : index.inputsOf(recipe)) {
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
    private boolean consumes(RecipeHolder<?> recipe, ItemKey item) {
        for (Ingredient ingredient : index.inputsOf(recipe)) {
            ItemKey key = IngredientUtil.representativeKey(ingredient);
            if (key != null && key.equals(item)) {
                return true;
            }
        }
        return false;
    }

    /** True when one of {@code recipe}'s ingredients is already on the path. */
    private boolean consumesPathItem(RecipeHolder<?> recipe, Set<ItemKey> path) {
        if (path.isEmpty()) {
            return false;
        }
        for (Ingredient ingredient : index.inputsOf(recipe)) {
            ItemKey key = IngredientUtil.representativeKey(ingredient);
            if (key != null && path.contains(key)) {
                return true;
            }
        }
        return false;
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
