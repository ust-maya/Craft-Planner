package com.craftplanner.engine;

import com.craftplanner.Config;
import com.craftplanner.graph.IngredientUtil;
import com.craftplanner.graph.ItemKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Index of all usable recipes, keyed by the item they produce.
 *
 * <p>Built once per recipe set from {@link RecipeManager#getOrderedRecipes()}.
 * Vanilla recipes the planner cannot work with are skipped: incomplete (broken
 * datapack) and special (non-book) recipes, and armour trims, whose output is
 * synthetic. Everything outside {@link #SUPPORTED} is a modded recipe type and
 * is judged by a different rule — see below.</p>
 *
 * <p><strong>Modded types are indexed by what can be read, not by a whitelist.</strong>
 * A machine recipe is usually marked {@link Recipe#isSpecial()} by its mod — the
 * flag means "no vanilla book screen", not "unusable" — so the vanilla special
 * guard would drop every machine in the pack. Instead a modded recipe enters the
 * index when it has both a non-empty item output and non-empty, readable inputs:
 * a type that keeps its ingredients in private fields simply produces nothing and
 * is left out, exactly as before. {@code recipes.indexModded} turns the whole
 * modded layer off, and {@code recipes.moddedPriority} decides whether a machine
 * outranks the crafting table.</p>
 *
 * <p>When several recipes produce the same item they are all kept and ordered by
 * {@link #compare}: the cheapest first — the recursive unit cost of the recipe's
 * ingredients (see {@link #computeCosts}) — then the type rank, the number of
 * input slots, whether the recipe asks for a machine-renamed item, and the id as
 * tie-breakers. The engine picks the first surviving
 * candidate and exposes the rest as alternatives for the player to switch to.</p>
 *
 * <p>A machine hop that turns one item into one item saves nothing, so a chain of
 * them ending in mined material is not a plan: {@code recipes.cutRenameChains}
 * prices and treats {@code sand} as raw material instead of showing
 * {@code stone -> cobblestone -> gravel -> sand}. See {@link #renameCuts}.</p>
 *
 * <p>Known cost model: only item inputs are counted. A machine's energy, fluid
 * and processing time are not part of the cost, and a recipe with several item
 * outputs is indexed under each guaranteed one (probabilistic outputs stay out
 * of the plan) — so a machine branch is planned as "these items at this cost",
 * not as a full cost sheet.</p>
 */
public final class RecipeIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger("craftplanner/RecipeIndex");
    /** Recipe types whose inspection threw — logged once each, rebuilt per index. */
    private static final Set<RecipeType<?>> UNREADABLE = new HashSet<>();

    /** Recipe types the planner expands. */
    public static final Set<RecipeType<?>> SUPPORTED = Set.of(
            RecipeType.CRAFTING,
            RecipeType.SMELTING,
            RecipeType.BLASTING,
            RecipeType.SMOKING,
            RecipeType.CAMPFIRE_COOKING,
            RecipeType.STONECUTTING,
            RecipeType.SMITHING);

    /** Preferred type order: hand crafting first, furnaces after. */
    public static final List<RecipeType<?>> PRIORITY = List.of(
            RecipeType.CRAFTING,
            RecipeType.STONECUTTING,
            RecipeType.SMITHING,
            RecipeType.SMELTING,
            RecipeType.BLASTING,
            RecipeType.SMOKING,
            RecipeType.CAMPFIRE_COOKING);

    private final Map<ItemKey, List<RecipeHolder<?>>> byOutput = new HashMap<>();
    /**
     * Ingredients read once per recipe, at index time. {@link Recipe#getIngredients()}
     * is not free — a machine recipe rebuilds its list from its own slots on every
     * call — and the engine asks for the same recipe several times per node while
     * choosing, so the answer is remembered here.
     */
    private final Map<RecipeHolder<?>, NonNullList<Ingredient>> inputs = new HashMap<>();
    /**
     * Guaranteed item outputs per recipe: item key -> amount produced by one
     * operation. Read once at index time; the first entry is the recipe's
     * primary result, the rest are secondary outputs a machine hands out
     * alongside it (MI heat exchangers, dyes, assembler parts).
     */
    private final Map<RecipeHolder<?>, Map<ItemKey, Integer>> outputCounts = new HashMap<>();
    /**
     * Unit cost of every indexed output: the cheapest way to obtain one of it,
     * measured in raw-material units and craft steps. Filled by
     * {@link #computeCosts()} before the candidate lists are sorted.
     */
    private final Map<ItemKey, Cost> unitCosts = new HashMap<>();
    /** Memoised {@link #recipeCost} answers per output key, valid once {@code unitCosts} is final. */
    private final Map<RecipeHolder<?>, Map<ItemKey, Cost>> recipeCosts = new HashMap<>();
    /**
     * Items the tree must stop at although a recipe exists: their cheapest honest
     * way of obtaining them is a chain of modded machine hops that hand out one
     * item for one, ending at something nothing produces at all — mined
     * material. Grinding stone into cobblestone, cobblestone into gravel and
     * gravel into sand costs exactly what sand costs and adds three rows to the
     * plan, so sand is raw material and the furnace step above it is the plan.
     */
    private final Set<ItemKey> renameCuts = new HashSet<>();
    /** Memoised {@link #bestHonestProducer} answers, including the "none" verdict. */
    private final Map<ItemKey, RecipeHolder<?>> honestProducers = new HashMap<>();
    /** Memoised {@link #cheapestProducer} answers — the chain {@link #chainReaches} follows. */
    private final Map<ItemKey, RecipeHolder<?>> cheapestProducers = new HashMap<>();
    private final HolderLookup.Provider registries;

    /**
     * Cache of the last built index. Building one walks every recipe of the
     * manager — tens of thousands in a large modpack — so it must happen once
     * per recipe set, not once per tree rebuild. The cache is keyed by the
     * {@link RecipeManager} instance (a new world/connection brings a new
     * one) and invalidated on {@code RecipesUpdatedEvent} (datapack reload).
     */
    private static RecipeIndex cached;
    private static RecipeManager cachedManager;

    private RecipeIndex(HolderLookup.Provider registries) {
        this.registries = registries;
    }

    /**
     * The index for {@code manager}, rebuilt only when the manager changed
     * since the last call. {@link #invalidateCache()} covers the remaining
     * case: the same manager reloaded from disk.
     */
    public static RecipeIndex cachedFor(RecipeManager manager, HolderLookup.Provider registries) {
        RecipeIndex index = cached;
        if (index == null || cachedManager != manager) {
            index = build(manager, registries);
            cached = index;
            cachedManager = manager;
        }
        return index;
    }

    /** Drops the cached index so the next {@link #cachedFor} rebuilds it. */
    public static void invalidateCache() {
        cached = null;
        cachedManager = null;
    }

    public static RecipeIndex build(RecipeManager manager, HolderLookup.Provider registries) {
        long started = System.nanoTime();
        RecipeIndex index = new RecipeIndex(registries);
        for (RecipeHolder<?> holder : manager.getOrderedRecipes()) {
            Recipe<?> recipe = holder.value();
            if (recipe.isIncomplete()) {
                continue;
            }
            if (recipe instanceof SmithingTrimRecipe) {
                // getResultItem() is synthetic here: an iron chestplate carrying
                // a trim component built from the first pattern in the registry.
                // Indexing it would file all 18 trim recipes under one phantom
                // key that no hovered stack ever matches. A trim is decoration,
                // not a cost, so the recipes are left out entirely.
                continue;
            }
            boolean vanilla = SUPPORTED.contains(recipe.getType());
            if (vanilla) {
                // For vanilla types isSpecial() is what it has always meant: a
                // book-less recipe with nothing to plan (enchanting, maps, ...).
                if (recipe.isSpecial()) {
                    continue;
                }
            } else if (!Config.indexModdedRecipes()) {
                continue;
            }
            ItemStack result;
            NonNullList<Ingredient> ingredients;
            try {
                result = recipe.getResultItem(registries);
                ingredients = RecipeIngredients.of(recipe);
            } catch (RuntimeException | LinkageError e) {
                // A mod is free to implement these however it likes, and a
                // machine recipe that throws on inspection is not worth losing
                // the whole index — and with it the tree — over. The type is
                // logged once and its recipes stay unplanned, which is what they
                // were before this mod indexed them at all.
                if (UNREADABLE.add(recipe.getType())) {
                    LOGGER.warn("Craft Planner skipped recipes of type {} because inspecting "
                            + "one of them ({}) failed.", recipe.getType(),
                            recipe.getClass(), e);
                }
                continue;
            }
            if (result.isEmpty()) {
                continue;
            }
            if (!vanilla && ingredients.isEmpty()) {
                // A modded type whose inputs are hidden in its own fields reads
                // as an empty recipe. Planning it would expand the branch to
                // nothing and leave the player with an item that looks craftable
                // from thin air, so it stays out of the index — exactly the
                // failure RecipeIngredients exists to detect.
                continue;
            }
            index.inputs.put(holder, ingredients);
            // A recipe is filed under every item it guarantees, not only the
            // first: an MI heat exchanger producing an ingot plus argon and
            // helium was previously visible only as "the ingot", so the byborn
            // gases stayed unplanned and a recipe looked cheaper than it is.
            Map<ItemKey, Integer> perOutput = new LinkedHashMap<>();
            for (ItemStack output : RecipeOutputs.guaranteed(recipe, result)) {
                ItemKey outKey = ItemKey.of(output);
                // The MI list repeats the primary result as its first entry —
                // one filing per item key, never two identical candidates.
                if (perOutput.containsKey(outKey)) {
                    continue;
                }
                perOutput.put(outKey, Math.max(1, output.getCount()));
                index.byOutput.computeIfAbsent(outKey, key -> new ArrayList<>()).add(holder);
            }
            index.outputCounts.put(holder, perOutput);
        }
        index.computeCosts(Set.of());
        // Machine hops that rename rather than consume are found on the priced
        // graph and are then priced as the raw material they behave like, so the
        // items above them stop counting the grind as a step of the plan.
        if (Config.cutRenameChains()) {
            index.renameCuts.addAll(index.computeRenameCuts());
            // The renaming items are leaves now, so every price and every chain
            // walk built before the cut set existed has to be recomputed.
            index.recipeCosts.clear();
            index.honestProducers.clear();
            index.cheapestProducers.clear();
            index.computeCosts(index.renameCuts);
        }
        for (Map.Entry<ItemKey, List<RecipeHolder<?>>> entry : index.byOutput.entrySet()) {
            ItemKey produced = entry.getKey();
            entry.getValue().sort((a, b) -> index.compare(a, b, produced));
        }
        LOGGER.debug("Craft Planner indexed {} recipes for {} outputs in {} ms.",
                index.inputs.size(), index.byOutput.size(),
                (System.nanoTime() - started) / 1_000_000);
        return index;
    }

    /**
     * Preferred order for one output item: the cheapest recipe first (the
     * recursive unit cost of its ingredients, see {@link #computeCosts}),
     * then the type rank, then the number of inputs, then whether it asks for a
     * machine-renamed item, then the id for a stable
     * tie-break. Cost before type is what keeps a chain like
     * {@code plate <- double ingot <- 2x ingot} from beating the direct
     * {@code plate <- ingot} step just because it has fewer slots. Counting
     * inputs after the type matters for machine packs, which register a plain
     * recipe and a {@code _with_tool} twin of the same output: the shorter one
     * is the recipe the player actually means. Asking for a renaming last of all
     * keeps a tie from being broken by the grind chain the tree will not expand.
     */
    private int compare(RecipeHolder<?> a, RecipeHolder<?> b, ItemKey produced) {
        int byCost = rankCost(a, produced).compareTo(rankCost(b, produced));
        if (byCost != 0) {
            return byCost;
        }
        int byType = Integer.compare(priorityOf(a.value().getType()), priorityOf(b.value().getType()));
        if (byType != 0) {
            return byType;
        }
        int byInputs = Integer.compare(inputsOf(a).size(), inputsOf(b).size());
        if (byInputs != 0) {
            return byInputs;
        }
        // A grind chain costs the same as the item it ends on, so ties are common
        // once its own rows are priced as raw material. Ask for the honest
        // ingredient first and let the renamed one be the alternative.
        int byCuts = Boolean.compare(touchesCut(a), touchesCut(b));
        return byCuts != 0 ? byCuts : a.id().toString().compareTo(b.id().toString());
    }

    /** True when any candidate of any slot of {@code holder} is a renamed raw material. */
    private boolean touchesCut(RecipeHolder<?> holder) {
        for (Slot slot : slotsOf(holder)) {
            for (ItemKey candidate : slot.candidates()) {
                if (renameCuts.contains(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Upper bound on the relaxation passes of {@link #computeCosts()}. */
    private static final int COST_PASSES = 48;

    /**
     * Unit cost of an item: the raw-material units the cheapest chain consumes
     * per crafted piece, and the craft steps along that chain. Comparing is
     * lexicographic: fewer units first, then fewer steps.
     */
    static record Cost(double units, int steps) implements Comparable<Cost> {
        /** An item the plan cannot decompose further counts as one of itself. */
        static final Cost LEAF = new Cost(1, 0);
        /** Not priced yet; anything unknown outranks every real cost. */
        static final Cost UNKNOWN = new Cost(Double.MAX_VALUE, Integer.MAX_VALUE);

        boolean isUnknown() {
            return units == Double.MAX_VALUE && steps == Integer.MAX_VALUE;
        }

        @Override
        public int compareTo(Cost other) {
            int byUnits = Double.compare(units, other.units);
            return byUnits != 0 ? byUnits : Integer.compare(steps, other.steps);
        }
    }

    /** The ingredient slots of one recipe as candidate keys and their counts. */
    private static final record Slot(List<ItemKey> candidates, List<Integer> counts) {
    }

    private final Map<RecipeHolder<?>, List<Slot>> slots = new HashMap<>();

    /**
     * Fills {@link #unitCosts} by relaxation: leaves — items no indexed recipe
     * produces, base materials like planks, and the {@code forcedLeaves} rename
     * cuts the tree never expands — cost one unit; every output starts unknown
     * and is lowered to its cheapest recipe until a pass changes nothing. Items
     * that never lower are members of a producer cycle with no leaf anywhere (a
     * mined raw metal whose only indexed "producer" is the unpacking of its own
     * block): they end up as leaves — one unit of itself, freely obtainable
     * outside the plan. They must not be priced as unreachable: that would mark
     * the entire material tree hanging above them unresolved and hand the choice
     * back to type and id order, laundering included. The laundering defense is
     * the ladder and the depth comparison, not a poison price.
     */
    private void computeCosts(Set<ItemKey> forcedLeaves) {
        for (ItemKey key : byOutput.keySet()) {
            unitCosts.put(key, isLeafCost(key, forcedLeaves) ? Cost.LEAF : Cost.UNKNOWN);
        }
        for (int pass = 0; pass < COST_PASSES; pass++) {
            boolean changed = false;
            for (Map.Entry<ItemKey, List<RecipeHolder<?>>> entry : byOutput.entrySet()) {
                ItemKey key = entry.getKey();
                if (isLeafCost(key, forcedLeaves)) {
                    continue;
                }
                Cost current = unitCosts.get(key);
                for (RecipeHolder<?> holder : entry.getValue()) {
                    Cost cost = recipeCost(holder, key);
                    if (cost.compareTo(current) < 0) {
                        current = cost;
                        changed = true;
                    }
                }
                unitCosts.put(key, current);
            }
            if (!changed) {
                break;
            }
        }
        for (Map.Entry<ItemKey, Cost> entry : unitCosts.entrySet()) {
            if (entry.getValue().isUnknown()) {
                entry.setValue(Cost.LEAF);
            }
        }
    }

    /** True when the item costs one unit of itself and the tree stops at it. */
    private static boolean isLeafCost(ItemKey key, Set<ItemKey> forcedLeaves) {
        return BaseMaterials.isBaseMaterial(key) || forcedLeaves.contains(key);
    }

    /**
     * The items whose cheapest honest producer only renames mined material.
     * Computed before the candidate lists are sorted, from the prices the first
     * relaxation pass produced — see {@link #renameCuts} for what counts as a
     * rename and {@link #isRenameChain} for the walk.
     */
    private Set<ItemKey> computeRenameCuts() {
        Set<ItemKey> cuts = new HashSet<>();
        Map<ItemKey, Boolean> memo = new HashMap<>();
        for (ItemKey key : byOutput.keySet()) {
            if (isRenameChain(key, memo, new HashSet<>(), 0)) {
                cuts.add(key);
            }
        }
        if (!cuts.isEmpty()) {
            // The set is small (a dozen items in a machine pack) and it is the
            // first thing to check when a branch ends earlier than expected.
            List<String> ids = new ArrayList<>(cuts.size());
            for (ItemKey cut : cuts) {
                ids.add(cut.id().toString());
            }
            Collections.sort(ids);
            LOGGER.debug("Craft Planner treats {} machine-renamed items as raw material: {}",
                    cuts.size(), ids);
        }
        return cuts;
    }

    /** How many renaming hops deep a chain may run before it stops being a renaming. */
    private static final int RENAME_CHAIN_LIMIT = 16;

    /**
     * True when the cheapest honest way to obtain {@code key} is one modded
     * machine item for one {@code key}, whose own ingredient is
     * {@link #isRawLike raw like mined material}.
     *
     * <p>Both halves matter: a 1:1 hop that dead-ends in a produced item is a
     * step of a real supply chain — a plate pressed from an ingot the furnace
     * makes from raw metal — and stays in the plan. A hop that hands out two or
     * more items, or takes two or more, changes how much material the plan needs
     * and stays too. Vanilla types are never renamings: a furnace or a crafting
     * table step is what the player came to the planner to see.</p>
     *
     * <p>Only a {@code true} verdict is memoised: it was reached with at least as
     * little budget left as any later caller will have, so it stays true, while a
     * {@code false} one may simply have run out of {@code depth}.</p>
     */
    private boolean isRenameChain(ItemKey key, Map<ItemKey, Boolean> memo, Set<ItemKey> visiting,
            int depth) {
        if (Boolean.TRUE.equals(memo.get(key))) {
            return true;
        }
        if (depth >= RENAME_CHAIN_LIMIT || !visiting.add(key)) {
            // Either too deep to call a renaming, or a cycle: the walk never
            // dead-ends, so nothing here is mined material and the branch keeps
            // expanding.
            return false;
        }
        boolean chain = false;
        RecipeHolder<?> producer = bestHonestProducer(key);
        if (producer != null && isNeutralHop(producer, key)) {
            chain = true;
            for (ItemKey input : slotsOf(producer).get(0).candidates()) {
                if (!isRawLike(input, memo, visiting, depth)) {
                    chain = false;
                    break;
                }
            }
        }
        visiting.remove(key);
        if (chain) {
            memo.put(key, Boolean.TRUE);
        }
        return chain;
    }

    /**
     * True when {@code key} is something the plan cannot honestly ask for beyond
     * itself: nothing produces it (stone, iron ore), only laundering loops do
     * (stone is "smelted" out of cobblestone, which is ground out of stone), it
     * is a base material, or it is the next hop of the same renaming chain.
     */
    private boolean isRawLike(ItemKey key, Map<ItemKey, Boolean> memo, Set<ItemKey> visiting,
            int depth) {
        if (BaseMaterials.isBaseMaterial(key) || !byOutput.containsKey(key)) {
            return true;
        }
        return bestHonestProducer(key) == null
                || isRenameChain(key, memo, visiting, depth + 1);
    }

    /**
     * The cheapest candidate that launders nothing for {@code key} — the recipe
     * the tree is allowed to expand. {@code sandstone -> 4 sand} cannot pose as
     * the honest producer of sand while its grind chain is being judged, because
     * sandstone is sawn from sand. Only the laundering test is applied here: the
     * path and pack-pair tests of {@code CraftTreeBuilder} depend on the branch
     * being expanded, which an index-wide verdict cannot know. Null when every
     * candidate launders.
     */
    private RecipeHolder<?> bestHonestProducer(ItemKey key) {
        if (honestProducers.containsKey(key)) {
            return honestProducers.get(key);
        }
        RecipeHolder<?> best = null;
        Cost bestCost = null;
        for (RecipeHolder<?> option : byOutput.getOrDefault(key, List.of())) {
            if (launders(option, key)) {
                continue;
            }
            Cost cost = rankCost(option, key);
            if (best == null || cost.compareTo(bestCost) < 0) {
                best = option;
                bestCost = cost;
            }
        }
        honestProducers.put(key, best);
        return best;
    }

    /** True when an ingredient of {@code recipe} is itself made out of what it produces. */
    private boolean launders(RecipeHolder<?> recipe, ItemKey produced) {
        for (Slot slot : slotsOf(recipe)) {
            for (ItemKey candidate : slot.candidates()) {
                if (chainReaches(candidate, produced)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True for one machine hop that changes the shape of an item and nothing
     * else: a modded recipe type, one ingredient slot asking for a single item,
     * and one output handing out a single piece of it. A tag in that slot is
     * fine, as long as every item it matches is taken one at a time.
     */
    private boolean isNeutralHop(RecipeHolder<?> holder, ItemKey produced) {
        if (SUPPORTED.contains(holder.value().getType())) {
            return false;
        }
        List<Slot> recipeSlots = slotsOf(holder);
        if (recipeSlots.size() != 1) {
            return false;
        }
        Slot slot = recipeSlots.get(0);
        for (int count : slot.counts()) {
            if (count != 1) {
                return false;
            }
        }
        Map<ItemKey, Integer> outputs = outputCounts.get(holder);
        return outputs != null && outputs.size() == 1 && outputs.getOrDefault(produced, 0) == 1;
    }

    /**
     * True when the tree must end at {@code key}: the cheapest way to it is a
     * modded renaming chain of mined material. The row keeps its recipe and its
     * alternatives, so the player can pin the machine step back into the plan.
     */
    public boolean isRenameCut(ItemKey key) {
        return key != null && renameCuts.contains(key);
    }

    /**
     * Cost of one unit of {@code produced} from {@code holder}: every slot
     * takes its cheapest accepted stack — which is also the branch a tag
     * ingredient resolves to — the units are summed and split by how much of
     * {@code produced} one operation hands out, and the chain grows one step
     * past its deepest ingredient.
     */
    private Cost recipeCost(RecipeHolder<?> holder, ItemKey produced) {
        List<Slot> recipeSlots = slotsOf(holder);
        if (recipeSlots.isEmpty()) {
            return Cost.UNKNOWN; // no readable ingredient — nothing to price
        }
        double units = 0;
        int steps = 0;
        for (Slot slot : recipeSlots) {
            Cost best = Cost.UNKNOWN;
            int count = 1;
            for (int i = 0; i < slot.candidates().size(); i++) {
                Cost candidate = costOf(slot.candidates().get(i));
                if (candidate.compareTo(best) < 0) {
                    best = candidate;
                    count = slot.counts().get(i);
                }
            }
            if (best.isUnknown()) {
                return Cost.UNKNOWN;
            }
            units += best.units * count;
            steps = Math.max(steps, best.steps);
        }
        Map<ItemKey, Integer> outputs = outputCounts.get(holder);
        int batch = outputs == null ? 1 : Math.max(1, outputs.getOrDefault(produced, 1));
        return new Cost(units / batch, steps + 1);
    }

    /**
     * The recipe cost used for sorting one output list; valid once
     * {@link #computeCosts} has finished. Package-visible for the choice
     * trace ({@code debug.traceChoices}).
     */
    Cost rankCost(RecipeHolder<?> holder, ItemKey produced) {
        return recipeCosts.computeIfAbsent(holder, key -> new HashMap<>())
                .computeIfAbsent(produced, key -> recipeCost(holder, key));
    }

    /** The unit cost of an item; anything the index cannot produce is raw material. */
    Cost costOf(ItemKey key) {
        if (BaseMaterials.isBaseMaterial(key)) {
            return Cost.LEAF;
        }
        Cost cost = unitCosts.get(key);
        return cost == null ? Cost.LEAF : cost;
    }

    /**
     * True when the cheapest chain of {@code from} needs {@code target} somewhere
     * below it. The tree asks this to recognise laundering recipes: producing an
     * item by taking apart something that is itself made out of that very item
     * ({@code copper ingot <- nuggets <- tiny dust <- ring <- rod <- ingot}).
     * The walk follows only the cheapest producer of each item and is bounded,
     * so a pathological graph costs a bounded visit count, not the tree.
     */
    public boolean chainReaches(ItemKey from, ItemKey target) {
        ArrayDeque<ItemKey> queue = new ArrayDeque<>();
        Set<ItemKey> seen = new HashSet<>();
        queue.add(from);
        int visits = 0;
        while (!queue.isEmpty() && visits++ < 64) {
            ItemKey item = queue.poll();
            if (!seen.add(item)) {
                continue;
            }
            if (item.equals(target)) {
                return true;
            }
            List<RecipeHolder<?>> producers = byOutput.get(item);
            if (producers == null || producers.isEmpty()
                    || BaseMaterials.isBaseMaterial(item) || renameCuts.contains(item)) {
                continue; // raw material: the chain ends here
            }
            RecipeHolder<?> cheapest = cheapestProducer(item);
            if (cheapest == null) {
                continue;
            }
            for (Slot slot : slotsOf(cheapest)) {
                queue.addAll(slot.candidates());
            }
        }
        return false;
    }

    /**
     * The candidate {@link #compare} puts first — the producer whose chain the
     * tree follows. Same answer as {@code byOutput.get(key).get(0)} once the
     * lists are sorted, and the same answer while they are not, which the rename
     * walk needs because it runs before the sort.
     */
    private RecipeHolder<?> cheapestProducer(ItemKey item) {
        if (cheapestProducers.containsKey(item)) {
            return cheapestProducers.get(item);
        }
        RecipeHolder<?> best = null;
        for (RecipeHolder<?> option : byOutput.getOrDefault(item, List.of())) {
            if (best == null || compare(option, best, item) < 0) {
                best = option;
            }
        }
        cheapestProducers.put(item, best);
        return best;
    }

    /**
     * The accepted stack of {@code ingredient} with the lowest unit cost — the
     * branch the tree expands by default; declaration order breaks ties. Null
     * when the ingredient matches nothing.
     */
    public ItemStack cheapestStack(Ingredient ingredient) {
        ItemStack best = null;
        Cost bestCost = null;
        for (ItemStack stack : IngredientUtil.stacks(ingredient)) {
            Cost cost = costOf(ItemKey.of(stack));
            if (best == null || cost.compareTo(bestCost) < 0) {
                best = stack;
                bestCost = cost;
            }
        }
        return best;
    }

    /** Position of the cheapest stack within the ingredient's candidate list. */
    public int cheapestCandidate(Ingredient ingredient) {
        int bestIndex = -1;
        Cost bestCost = null;
        int i = 0;
        for (ItemStack stack : IngredientUtil.stacks(ingredient)) {
            Cost cost = costOf(ItemKey.of(stack));
            if (bestIndex < 0 || cost.compareTo(bestCost) < 0) {
                bestIndex = i;
                bestCost = cost;
            }
            i++;
        }
        return Math.max(0, bestIndex);
    }

    /** Candidate keys and counts per ingredient slot, resolved once per recipe. */
    private List<Slot> slotsOf(RecipeHolder<?> holder) {
        List<Slot> cached = slots.get(holder);
        if (cached != null) {
            return cached;
        }
        List<Slot> built = new ArrayList<>();
        for (Ingredient ingredient : inputsOf(holder)) {
            if (IngredientUtil.isEmpty(ingredient)) {
                continue;
            }
            List<ItemKey> candidates = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            for (ItemStack stack : IngredientUtil.stacks(ingredient)) {
                candidates.add(ItemKey.of(stack));
                counts.add(Math.max(1, stack.getCount()));
            }
            if (!candidates.isEmpty()) {
                built.add(new Slot(candidates, counts));
            }
        }
        slots.put(holder, built);
        return built;
    }

    /** The ingredients of an indexed recipe, read once when the index was built. */
    public NonNullList<Ingredient> inputsOf(RecipeHolder<?> holder) {
        NonNullList<Ingredient> ingredients = inputs.get(holder);
        return ingredients != null ? ingredients : RecipeIngredients.of(holder.value());
    }

    /** Recipes producing the given item, best candidate first. Never null. */
    public List<RecipeHolder<?>> forOutput(ItemKey key) {
        List<RecipeHolder<?>> list = byOutput.get(key);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    public boolean hasRecipe(ItemKey key) {
        return byOutput.containsKey(key);
    }

    public int recipeCount() {
        int total = 0;
        for (List<RecipeHolder<?>> list : byOutput.values()) {
            total += list.size();
        }
        return total;
    }

    public int outputCount() {
        return byOutput.size();
    }

    /** Registry access the index was built with — needed to resolve outputs. */
    public HolderLookup.Provider registries() {
        return registries;
    }

    private static int priorityOf(RecipeType<?> type) {
        int vanilla = PRIORITY.indexOf(type);
        if (vanilla >= 0) {
            return vanilla;
        }
        // Where a machine recipe sits against the vanilla ones. Hand crafting
        // stays first by default, so switching a modded pack on never silently
        // replaces the recipe the player was already planning with.
        return switch (Config.moddedPriority()) {
            case "first" -> -1;
            case "before_smelting" -> PRIORITY.indexOf(RecipeType.SMELTING);
            default -> PRIORITY.size();
        };
    }
}
