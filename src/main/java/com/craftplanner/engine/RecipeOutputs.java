package com.craftplanner.engine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The guaranteed item outputs of a recipe.
 *
 * <p>{@link Recipe#getResultItem(net.minecraft.core.HolderLookup.Provider)} is
 * one stack, and for vanilla types that is the whole story. Machine mods are
 * different: Modern Industrialization's {@code MachineRecipe} keeps its item
 * outputs in a public list of its own records (stack, amount, probability),
 * and only the first guaranteed entry surfaces as the vanilla result. The
 * rest — the argon and helium of a heat exchanger, the second plate of an
 * assembler press — were invisible to the planner, which filed the recipe
 * under one item only and priced that item as if the by-products did not
 * exist.</p>
 *
 * <p>Only outputs with probability 1 are returned: a guaranteed plan is
 * planned from guaranteed results (a probabilistic bonus must not lower the
 * price of the sure output). The reading is reflection, guarded by the MI
 * package name, the same soft-dependency arrangement
 * {@link RecipeIngredients} uses for the hammer count: no MI, no class loads,
 * and a shape that stops matching degrades to "primary output only", never
 * to a crash.</p>
 */
public final class RecipeOutputs {

    private static final Logger LOGGER = LoggerFactory.getLogger("craftplanner/RecipeOutputs");

    /** Classes whose extra-output shape was already resolved (null = none). */
    private static final Map<Class<?>, Field> OUTPUT_FIELDS = new HashMap<>();
    /** Element accessors of the MI output record (null = unusable element). */
    private static final Map<Class<?>, Method[]> ELEMENT_ACCESSORS = new HashMap<>();
    /** Classes whose read failure was already logged. */
    private static final Set<Class<?>> WARNED = new HashSet<>();

    private RecipeOutputs() {
    }

    /**
     * The primary result first, then every further guaranteed item output of
     * the recipe, never empty (the primary stack may itself be empty only if
     * the caller left it out of the index anyway).
     */
    public static List<ItemStack> guaranteed(Recipe<?> recipe, ItemStack primary) {
        List<ItemStack> outputs = new ArrayList<>();
        outputs.add(primary);
        Field field = itemOutputsField(recipe.getClass());
        if (field == null) {
            return outputs;
        }
        try {
            if (!(field.get(recipe) instanceof List<?> list)) {
                return outputs;
            }
            for (Object entry : list) {
                ItemStack stack = guaranteedStack(entry);
                if (stack != null && !stack.isEmpty()) {
                    outputs.add(stack);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (WARNED.add(recipe.getClass())) {
                LOGGER.warn("Craft Planner could not read the item outputs of a {} recipe."
                        + " Only its vanilla result will be indexed.", recipe.getClass(), e);
            }
        }
        return outputs;
    }

    /** The {@code itemOutputs} list field of a recognised MI machine recipe. */
    private static Field itemOutputsField(Class<?> cls) {
        if (OUTPUT_FIELDS.containsKey(cls)) {
            return OUTPUT_FIELDS.get(cls);
        }
        Field field = null;
        if (cls.getName().startsWith("aztech.modern_industrialization.")) {
            try {
                field = cls.getField("itemOutputs");
            } catch (NoSuchFieldException e) {
                field = null;
            }
        }
        OUTPUT_FIELDS.put(cls, field);
        return field;
    }

    /**
     * One list element as a guaranteed stack: {@code getStack()} of the output
     * record, dropped unless {@code probability()} is 1. Null when the element
     * is not a readable output record or its bonus is probabilistic.
     */
    private static ItemStack guaranteedStack(Object entry) throws ReflectiveOperationException {
        if (entry == null) {
            return null;
        }
        Class<?> cls = entry.getClass();
        Method[] accessors = ELEMENT_ACCESSORS.containsKey(cls)
                ? ELEMENT_ACCESSORS.get(cls)
                : resolveAccessors(cls);
        if (accessors == null) {
            return null;
        }
        Object probability = accessors[1].invoke(entry);
        if (!(probability instanceof Float p) || p < 1.0f) {
            return null;
        }
        Object stack = accessors[0].invoke(entry);
        return stack instanceof ItemStack item ? item : null;
    }

    private static Method[] resolveAccessors(Class<?> cls) {
        Method[] accessors = null;
        try {
            accessors = new Method[] {cls.getMethod("getStack"), cls.getMethod("probability")};
        } catch (NoSuchMethodException e) {
            accessors = null;
        }
        ELEMENT_ACCESSORS.put(cls, accessors);
        return accessors;
    }
}
