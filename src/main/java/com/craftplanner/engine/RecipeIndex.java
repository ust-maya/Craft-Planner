package com.craftplanner.engine;

import com.craftplanner.Config;
import com.craftplanner.graph.ItemKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
 * {@link #PRIORITY}, so the engine picks the "preferred" one and exposes the rest
 * as alternatives for the player to switch to.</p>
 *
 * <p>Known cost model: only item inputs are read. A machine's energy, fluid and
 * processing time are not part of the plan, and a recipe with several item outputs
 * contributes only the first one — so a machine branch is planned as "these items,
 * this machine", not as a full cost sheet.</p>
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
            index.byOutput.computeIfAbsent(ItemKey.of(result), key -> new ArrayList<>()).add(holder);
        }
        index.byOutput.values().forEach(list -> list.sort(index::compare));
        return index;
    }

    /**
     * Preferred order for one output item: the type rank first, then the number
     * of inputs, then the id for a stable tie-break. Counting inputs after the
     * type matters for machine packs, which register a plain recipe and a
     * {@code _with_tool} twin of the same output: the shorter one is the
     * recipe the player actually means.
     */
    private int compare(RecipeHolder<?> a, RecipeHolder<?> b) {
        int byType = Integer.compare(priorityOf(a.value().getType()), priorityOf(b.value().getType()));
        if (byType != 0) {
            return byType;
        }
        int byInputs = Integer.compare(inputsOf(a).size(), inputsOf(b).size());
        return byInputs != 0 ? byInputs : a.id().toString().compareTo(b.id().toString());
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
