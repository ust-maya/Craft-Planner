package com.craftplanner.integration;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

/**
 * Soft EMI integration (Stage 5) — reflection based, so the mod loads and
 * works fine when EMI is absent and no compile-time dependency is needed.
 *
 * <p>Call chain used at runtime:</p>
 * <pre>
 *   EmiApi.getHoveredStack(true)          -> EmiStackInteraction
 *   EmiStackInteraction.getStack()        -> EmiIngredient
 *   EmiIngredient.getEmiStacks().get(0)  -> EmiStack
 *   EmiStack.getItemStack()               -> ItemStack
 * </pre>
 *
 * <p>Every reflective lookup happens once; any failure (EMI missing or a
 * changed API) disables the integration silently instead of crashing.</p>
 */
public final class EmiIntegration {

    private static final System.Logger LOGGER = System.getLogger("craftplanner|emi");

    private static final String EMI_API = "dev.emi.emi.api.EmiApi";
    private static final String EMI_INTERACTION = "dev.emi.emi.api.stack.EmiStackInteraction";
    private static final String EMI_INGREDIENT = "dev.emi.emi.api.stack.EmiIngredient";
    private static final String EMI_STACK = "dev.emi.emi.api.stack.EmiStack";

    private static boolean checked;
    private static boolean available;
    private static Method getHoveredStack;
    private static Method getStack;
    private static Method getEmiStacks;
    private static Method getItemStack;
    /** {@code EmiApi.isSearchFocused()}, or null when this EMI build has no such method. */
    private static Method isSearchFocused;

    private EmiIntegration() {
    }

    /** True when EMI's API is on the classpath and usable. */
    public static synchronized boolean isPresent() {
        if (!checked) {
            checked = true;
            available = resolve();
        }
        return available;
    }

    /**
     * Whether EMI's own search box holds the keyboard.
     *
     * <p>EMI draws its search field inside its own screen, so a planner key
     * pressed while the player types a query is a letter, not a command. The
     * query is reflective like the rest of this class: a build of EMI without
     * {@code EmiApi.isSearchFocused()} keeps the cached method {@code null} and
     * answers "not focused", which is what the planner did before the check
     * existed.</p>
     */
    public static boolean isSearchFocused() {
        if (!isPresent()) {
            return false;
        }
        Method method = isSearchFocused;
        if (method == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(method.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.log(System.Logger.Level.WARNING, "EMI search-focus lookup failed", e);
            return false;
        }
    }

    /** Item currently hovered in an EMI panel, or empty. */
    public static Optional<ItemStack> hovered() {
        if (!isPresent()) {
            return Optional.empty();
        }
        try {
            Object interaction = getHoveredStack.invoke(null, Boolean.TRUE);
            if (interaction == null) {
                return Optional.empty();
            }
            Object ingredient = getStack.invoke(interaction);
            if (ingredient == null) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            List<Object> stacks = (List<Object>) getEmiStacks.invoke(ingredient);
            if (stacks == null || stacks.isEmpty()) {
                return Optional.empty();
            }
            ItemStack stack = (ItemStack) getItemStack.invoke(stacks.get(0));
            return stack == null || stack.isEmpty() ? Optional.empty() : Optional.of(stack.copy());
        } catch (ReflectiveOperationException | ClassCastException e) {
            LOGGER.log(System.Logger.Level.WARNING, "EMI hovered-stack lookup failed", e);
            available = false;
            return Optional.empty();
        }
    }

    private static boolean resolve() {
        try {
            Class<?> api = Class.forName(EMI_API);
            Class<?> interaction = Class.forName(EMI_INTERACTION);
            Class<?> ingredient = Class.forName(EMI_INGREDIENT);
            Class<?> stack = Class.forName(EMI_STACK);

            getHoveredStack = api.getMethod("getHoveredStack", boolean.class);
            getStack = interaction.getMethod("getStack");
            getEmiStacks = ingredient.getMethod("getEmiStacks");
            getItemStack = stack.getMethod("getItemStack");
            // The search-focus query is looked up separately and tolerated absent:
            // it is a courtesy check for the hotkeys, not part of the capture
            // chain, and disabling the whole integration over it would throw away
            // working functionality on a build of EMI that only lacks this method.
            try {
                isSearchFocused = api.getMethod("isSearchFocused");
            } catch (NoSuchMethodException absent) {
                isSearchFocused = null;
            }
            return true;
        } catch (ClassNotFoundException expected) {
            return false; // EMI not installed
        } catch (NoSuchMethodException changed) {
            LOGGER.log(System.Logger.Level.WARNING, "EMI API changed, integration disabled", changed);
            return false;
        }
    }
}
