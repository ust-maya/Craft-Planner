package com.craftplanner.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ingredient slots of a recipe, read in a way the planner can use.
 *
 * <p>{@link Recipe#getIngredients()} is <em>not</em> a universal contract: in
 * 1.21.1 it defaults to an empty list and only the types that actually keep
 * their inputs in that shape override it. The smithing recipes are the vanilla
 * example — {@code SmithingTransformRecipe} and {@code SmithingTrimRecipe}
 * hold their inputs in the private fields {@code template} / {@code base} /
 * {@code addition} and never override {@code getIngredients()} (the fields are
 * opened through the access transformer in {@code META-INF}).</p>
 *
 * <p>Reading such a recipe through {@code getIngredients()} yields zero slots,
 * which the engine cannot tell apart from "this item has no recipe": the
 * expansion loop runs zero times, the node is marked raw and a craftable item
 * silently becomes something the player is told to go and gather. Every
 * ingredient read in the engine must therefore go through {@link #of}.</p>
 *
 * <p>The class is a registry, not a per-instance object: providers are keyed by
 * {@link RecipeType} and the resolver is stateless, so it needs no injection.
 * {@link #register} is the extension point for modded recipe types — a type
 * with a registered provider also counts as supported by
 * {@link RecipeIndex#isSupported}, which keeps "the planner indexes this type"
 * and "the planner knows how to read its inputs" from drifting apart.</p>
 */
public final class RecipeIngredients {

    private static final Logger LOGGER = LoggerFactory.getLogger("craftplanner/RecipeIngredients");

    /** Recipe types whose read failure was already logged — of() runs per candidate per rebuild. */
    private static final Set<RecipeType<?>> WARNED = new HashSet<>();

    /**
     * Reads the inputs of one recipe type.
     *
     * <p>Returning {@code null} means "no opinion": the caller falls back to
     * {@link Recipe#getIngredients()}, so a provider only has to handle the
     * shape it was registered for.</p>
     */
    @FunctionalInterface
    public interface Provider {
        NonNullList<Ingredient> ingredients(Recipe<?> recipe);
    }

    private static final Map<RecipeType<?>, Provider> PROVIDERS = new HashMap<>();

    static {
        register(RecipeType.SMITHING, RecipeIngredients::smithing);
    }

    private RecipeIngredients() {
    }

    /**
     * Registers how the inputs of {@code type} are read. Registering a type also
     * makes {@link RecipeIndex#isSupported} accept it, so a modded recipe type
     * becomes plannable with this single call.
     */
    public static void register(RecipeType<?> type, Provider provider) {
        PROVIDERS.put(type, provider);
    }

    /** True when the inputs of {@code type} need a provider to be readable. */
    public static boolean hasProvider(RecipeType<?> type) {
        return PROVIDERS.containsKey(type);
    }

    /**
     * The ingredient slots of {@code recipe}, never {@code null}.
     *
     * <p>Equivalent to {@link Recipe#getIngredients()} for every type without a
     * provider, so the common path is unchanged.</p>
     *
     * <p>Reading is fail-safe: a modded {@code getIngredients()} with side
     * effects, or smithing fields renamed by a Minecraft/NeoForge update, must
     * degrade to "this item has no readable recipe" — the engine then ends the
     * branch at a raw material — instead of crashing the client. The failure is
     * logged once per recipe.</p>
     */
    public static NonNullList<Ingredient> of(Recipe<?> recipe) {
        try {
            Provider provider = PROVIDERS.get(recipe.getType());
            if (provider != null) {
                NonNullList<Ingredient> resolved = provider.ingredients(recipe);
                if (resolved != null) {
                    return resolved;
                }
            }
            return recipe.getIngredients();
        } catch (RuntimeException | LinkageError e) {
            if (WARNED.add(recipe.getType())) {
                LOGGER.warn("Craft Planner could not read the ingredients of a {} recipe ({})."
                        + " Such items will be treated as raw materials in the tree.",
                        recipe.getType(), recipe.getClass(), e);
            }
            return NonNullList.create();
        }
    }

    /**
     * The three smithing slots, in the order the smithing table shows them:
     * template, base, addition. The template is a real cost — the upgrade
     * template is consumed by the upgrade — so it is planned like any other
     * ingredient.
     */
    private static NonNullList<Ingredient> smithing(Recipe<?> recipe) {
        if (recipe instanceof SmithingTransformRecipe transform) {
            return NonNullList.of(Ingredient.EMPTY, transform.template, transform.base, transform.addition);
        }
        if (recipe instanceof SmithingTrimRecipe trim) {
            return NonNullList.of(Ingredient.EMPTY, trim.template, trim.base, trim.addition);
        }
        return recipe.getIngredients();
    }
}
