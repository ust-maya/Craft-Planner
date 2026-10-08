package com.craftplanner.engine;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
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
 * {@link #register} is the extension point for a recipe type whose inputs are
 * <em>not</em> reachable through {@link Recipe#getIngredients()} — the vanilla
 * smithing table being the example. While {@code recipes.indexModded} is on
 * (the default) a modded type does not need a provider to be planned: it is
 * indexed whenever its inputs read as non-empty, and this class is what reads
 * them.</p>
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
     * Registers how the inputs of {@code type} are read. Without a provider the
     * type is read through {@link Recipe#getIngredients()}, which is enough for
     * most machine recipes; this is the escape hatch for the ones that keep
     * their slots in their own fields.
     */
    public static void register(RecipeType<?> type, Provider provider) {
        PROVIDERS.put(type, provider);
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
            NonNullList<Ingredient> base;
            if (provider != null) {
                NonNullList<Ingredient> resolved = provider.ingredients(recipe);
                base = resolved != null ? resolved : recipe.getIngredients();
            } else {
                base = recipe.getIngredients();
            }
            return multiplied(recipe, base);
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
     * Shapes that keep their per-operation count outside the ingredient list.
     * Modern Industrialization's forge-hammer recipe is the known case:
     * {@code getIngredients()} returns the single input slot, while the amount
     * the hammer actually consumes lives in a separate {@code count()} field —
     * {@code 2 copper ingots -> 1 double ingot} would otherwise read as 1:1,
     * and the engine would see a chain that magically doubles copper.
     */
    private static NonNullList<Ingredient> multiplied(Recipe<?> recipe, NonNullList<Ingredient> ingredients) {
        int count = externalCount(recipe);
        if (count <= 1 || ingredients.size() != 1) {
            return ingredients;
        }
        NonNullList<Ingredient> expanded = NonNullList.create();
        for (int i = 0; i < count; i++) {
            expanded.add(ingredients.get(0));
        }
        return expanded;
    }

    /**
     * The {@code count()} accessor per recipe class, empty for a class that has none.
     * The accessor is what is cached, never the value: every forge-hammer recipe is
     * one class, and caching the count of whichever recipe was read first gave all
     * of them that one count — {@code 2 tin ingots -> 1 plate} read as 1:1.
     */
    private static final Map<Class<?>, Optional<Method>> COUNT_ACCESSORS = new HashMap<>();

    /**
     * The {@code count()} of a recognised hammer-shaped recipe, or 1 when the
     * type has no external count. Reflection, because MI is a soft dependency:
     * the class name is checked before the method, so a mod that merely has a
     * {@code count()} method of a different shape is left alone.
     */
    private static int externalCount(Recipe<?> recipe) {
        Method accessor = COUNT_ACCESSORS
                .computeIfAbsent(recipe.getClass(), RecipeIngredients::countAccessor)
                .orElse(null);
        if (accessor == null) {
            return 1;
        }
        try {
            Object raw = accessor.invoke(recipe);
            return raw instanceof Integer n && n > 1 ? n : 1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.debug("Craft Planner could not read count() from a {} recipe.", recipe.getClass(), e);
            return 1;
        }
    }

    /**
     * The {@code count()} method of a recognised hammer-shaped recipe class. The
     * class name is checked before the method, so a mod that merely has a
     * {@code count()} of a different meaning is left alone.
     */
    private static Optional<Method> countAccessor(Class<?> cls) {
        if (!isMiHammer(cls)) {
            return Optional.empty();
        }
        try {
            return Optional.of(cls.getMethod("count"));
        } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
            LOGGER.debug("Craft Planner found no count() on {}.", cls, e);
            return Optional.empty();
        }
    }

    /** Modern Industrialization's forge hammer, recognised by name: MI is a soft dependency. */
    private static boolean isMiHammer(Class<?> cls) {
        return cls.getName().startsWith("aztech.modern_industrialization.")
                && cls.getSimpleName().toLowerCase().contains("hammer");
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
