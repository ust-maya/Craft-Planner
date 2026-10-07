package com.craftplanner.engine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.material.Fluid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads fluid ingredients from machine recipes without making any machine mod a
 * required dependency. Modern Industrialization exposes these as {@code fluidInputs}
 * records with a {@code getInputFluids()}, {@code amount()} and
 * {@code probability()} accessor; other recipe types remain unaffected.
 */
public final class RecipeFluids {

    private static final Logger LOGGER = LoggerFactory.getLogger("craftplanner/RecipeFluids");
    private static final Map<Class<?>, Field> INPUT_FIELDS = new HashMap<>();
    private static final Map<Class<?>, Method[]> ACCESSORS = new HashMap<>();
    private static final Set<Class<?>> WARNED = new HashSet<>();

    private RecipeFluids() {
    }

    /** One guaranteed fluid ingredient, in millibuckets per recipe operation. */
    public record Requirement(Fluid fluid, long amount) {
    }

    /**
     * Fluid ingredients of a recognized machine recipe. Alternatives in a
     * fluid ingredient resolve to its first registered fluid for display; an
     * uncertain/probabilistic input is omitted rather than reported as certain.
     */
    public static List<Requirement> guaranteedInputs(Recipe<?> recipe) {
        Class<?> cls = recipe.getClass();
        if (!cls.getName().startsWith("aztech.modern_industrialization.")) {
            return List.of();
        }
        Field field = inputField(cls);
        if (field == null) {
            return List.of();
        }
        try {
            if (!(field.get(recipe) instanceof List<?> entries)) {
                return List.of();
            }
            List<Requirement> requirements = new ArrayList<>();
            for (Object entry : entries) {
                if (entry == null) {
                    continue;
                }
                Method[] accessors = accessors(entry.getClass());
                if (accessors == null) {
                    continue;
                }
                Object probability = accessors[2].invoke(entry);
                if (!(probability instanceof Number chance) || chance.floatValue() < 1.0f) {
                    continue;
                }
                Object fluids = accessors[0].invoke(entry);
                Object amount = accessors[1].invoke(entry);
                if (!(fluids instanceof List<?> candidates) || candidates.isEmpty()
                        || !(amount instanceof Number quantity) || quantity.longValue() <= 0) {
                    continue;
                }
                Object first = candidates.get(0);
                if (first instanceof Fluid fluid) {
                    requirements.add(new Requirement(fluid, quantity.longValue()));
                }
            }
            return List.copyOf(requirements);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (WARNED.add(cls)) {
                LOGGER.warn("Craft Planner could not read fluid inputs of a {} recipe.", cls, e);
            }
            return List.of();
        }
    }

    private static Field inputField(Class<?> cls) {
        if (INPUT_FIELDS.containsKey(cls)) {
            return INPUT_FIELDS.get(cls);
        }
        Field field;
        try {
            field = cls.getField("fluidInputs");
        } catch (NoSuchFieldException e) {
            field = null;
        }
        INPUT_FIELDS.put(cls, field);
        return field;
    }

    /** Methods are on MI's FluidInput record, and are resolved once per record type. */
    private static Method[] accessors(Class<?> cls) {
        if (ACCESSORS.containsKey(cls)) {
            return ACCESSORS.get(cls);
        }
        Method[] methods;
        try {
            methods = new Method[] {
                    cls.getMethod("getInputFluids"), cls.getMethod("amount"), cls.getMethod("probability")
            };
        } catch (NoSuchMethodException e) {
            methods = null;
        }
        ACCESSORS.put(cls, methods);
        return methods;
    }
}
