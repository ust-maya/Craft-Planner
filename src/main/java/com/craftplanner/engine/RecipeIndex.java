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
 * input slots and the id as tie-breakers. The engine picks the first surviving
 * candidate and exposes the rest as alternatives for the player to switch to,
 * and the player stops a branch wherever they want it stopped
 * ({@code RecipeCuts}) — nothing is cut automatically.</p>
 *
 * <p>Known cost model: only item inputs are counted. A machine's energy, fluid
 * and processing time are not part of recipe ranking, though fluid inputs are
 * displayed as requirements in the tree. A recipe with several item
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
    /** Guaranteed machine-fluid inputs, read once alongside item ingredients. */
    private final Map<RecipeHolder<?>, List<RecipeFluids.Requirement>> fluidInputs = new HashMap<>();
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
            List<RecipeFluids.Requirement> fluids;
            try {
                result = recipe.getResultItem(registries);
                ingredients = RecipeIngredients.of(recipe);
                fluids = RecipeFluids.guaranteedInputs(recipe);
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
            if (!vanilla && ingredients.isEmpty() && fluids.isEmpty()) {
                // A modded type whose inputs are hidden in its own fields reads
                // as an empty recipe. Planning it would expand the branch to
                // nothing and leave the player with an item that looks craftable
                // from thin air, so it stays out of the index — exactly the
                // failure RecipeIngredients exists to detect.
                continue;
            }
            index.inputs.put(holder, ingredients);
            index.fluidInputs.put(holder, fluids);
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
        index.computeCosts();
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
     * then the type rank, then the number of inputs, then the id for a stable
     * tie-break. Cost before type is what keeps a chain like
     * {@code plate <- double ingot <- 2x ingot} from beating the direct
     * {@code plate <- ingot} step just because it has fewer slots. Counting
     * inputs after the type matters for machine packs, which register a plain
     * recipe and a {@code _with_tool} twin of the same output: the shorter one
     * is the recipe the player actually means.
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
        return a.id().toString().compareTo(b.id().toString());
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
     * produces and base materials like planks — cost one unit; every output
     * starts unknown and is lowered to its cheapest recipe until a pass changes
     * nothing. Items that never lower are members of a producer cycle with no
     * leaf anywhere (a
     * mined raw metal whose only indexed "producer" is the unpacking of its own
     * block): they end up as leaves — one unit of itself, freely obtainable
     * outside the plan. They must not be priced as unreachable: that would mark
     * the entire material tree hanging above them unresolved and hand the choice
     * back to type and id order, laundering included. The laundering defense is
     * the ladder and the depth comparison, not a poison price.
     */
    private void computeCosts() {
        for (ItemKey key : byOutput.keySet()) {
            unitCosts.put(key, BaseMaterials.isBaseMaterial(key) ? Cost.LEAF : Cost.UNKNOWN);
        }
        for (int pass = 0; pass < COST_PASSES; pass++) {
            boolean changed = false;
            for (Map.Entry<ItemKey, List<RecipeHolder<?>>> entry : byOutput.entrySet()) {
                ItemKey key = entry.getKey();
                if (BaseMaterials.isBaseMaterial(key)) {
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
            if (producers == null || producers.isEmpty() || BaseMaterials.isBaseMaterial(item)) {
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
     * The candidate {@link #compare} puts first — the producer whose chain
     * {@link #chainReaches} follows. Same answer as
     * {@code byOutput.get(key).get(0)}, because {@link #build} sorts every
     * candidate list once the costs are final.
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

    /** Guaranteed fluid ingredients of an indexed recipe, in mB per operation. */
    public List<RecipeFluids.Requirement> fluidInputsOf(RecipeHolder<?> holder) {
        return fluidInputs.getOrDefault(holder, List.of());
    }

    /**
     * How many of {@code item} one craft of {@code holder} yields; 1 when unknown.
     *
     * <p>Not the recipe's own result count: a machine recipe can pour out several
     * items at once, and the tree has to divide the demand by how much of <em>this
     * one</em> comes out of a craft, or it plans too many operations and shows the
     * by-product as something to gather as well.</p>
     */
    public int outputCountOf(RecipeHolder<?> holder, ItemKey item) {
        Map<ItemKey, Integer> outputs = outputCounts.get(holder);
        return outputs == null ? 1 : Math.max(1, outputs.getOrDefault(item, 1));
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
