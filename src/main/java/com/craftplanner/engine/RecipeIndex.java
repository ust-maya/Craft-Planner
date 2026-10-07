package com.craftplanner.engine;

import com.craftplanner.graph.ItemKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;

/**
 * Index of all usable recipes, keyed by the item they produce.
 *
 * <p>Built once per tree rebuild from {@link RecipeManager#getOrderedRecipes()}.
 * Recipes the planner cannot reasonably plan with are skipped: incomplete
 * (broken datapack) and special (non-book) recipes, armour trims (their output
 * is synthetic), and everything outside the supported types.</p>
 *
 * <p>When several recipes produce the same item they are all kept and ordered
 * by {@link #PRIORITY}, so the engine picks the "preferred" one and exposes
 * the rest as alternatives for the player to switch to.</p>
 */
public final class RecipeIndex {
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

    private static final Comparator<RecipeHolder<?>> BY_PRIORITY =
            Comparator.<RecipeHolder<?>>comparingInt(holder -> priorityOf(holder.value().getType()))
                    .thenComparing(holder -> holder.id().toString());

    private final Map<ItemKey, List<RecipeHolder<?>>> byOutput = new HashMap<>();
    private final HolderLookup.Provider registries;

    private RecipeIndex(HolderLookup.Provider registries) {
        this.registries = registries;
    }

    public static RecipeIndex build(RecipeManager manager, HolderLookup.Provider registries) {
        RecipeIndex index = new RecipeIndex(registries);
        for (RecipeHolder<?> holder : manager.getOrderedRecipes()) {
            Recipe<?> recipe = holder.value();
            if (recipe.isIncomplete() || recipe.isSpecial()) {
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
            if (!isSupported(recipe.getType())) {
                continue;
            }
            ItemStack result = recipe.getResultItem(registries);
            if (result.isEmpty()) {
                continue;
            }
            index.byOutput.computeIfAbsent(ItemKey.of(result), key -> new ArrayList<>()).add(holder);
        }
        index.byOutput.values().forEach(list -> list.sort(BY_PRIORITY));
        return index;
    }

    /** Recipes producing the given item, best candidate first. Never null. */
    public List<RecipeHolder<?>> forOutput(ItemKey key) {
        List<RecipeHolder<?>> list = byOutput.get(key);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    /**
     * True when the planner expands recipes of this type: a vanilla type from
     * {@link #SUPPORTED}, or a type whose inputs someone taught
     * {@link RecipeIngredients} to read.
     *
     * <p>The two conditions are tied together on purpose. Indexing a type the
     * engine cannot read is the failure this whole layer exists to prevent — the
     * recipe is found, chosen, and then expands to nothing. Registering a
     * provider for a modded type is therefore all it takes to make it
     * plannable.</p>
     */
    public static boolean isSupported(RecipeType<?> type) {
        return SUPPORTED.contains(type) || RecipeIngredients.hasProvider(type);
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
        int index = PRIORITY.indexOf(type);
        return index < 0 ? PRIORITY.size() : index;
    }
}
