package com.craftplanner.integration;

import java.util.Optional;
import net.minecraft.world.item.ItemStack;

/**
 * Façade over the recipe-viewer integrations (Stage 5).
 *
 * <p>Asks the viewers in priority order (EMI first — it exposes a proper
 * runtime API without a plugin) and falls back to "nothing" so the caller
 * can continue with plain inventory capture.</p>
 */
public final class ViewerIntegration {

    private ViewerIntegration() {
    }

    public static boolean emiPresent() {
        return EmiIntegration.isPresent();
    }

    public static boolean jeiPresent() {
        return JeiIntegration.isPresent();
    }

    /** Item hovered inside a recipe viewer, or empty when none is installed. */
    public static Optional<ItemStack> hovered() {
        if (EmiIntegration.isPresent()) {
            Optional<ItemStack> stack = EmiIntegration.hovered();
            if (stack.isPresent()) {
                return stack;
            }
        }
        if (JeiIntegration.isPresent()) {
            Optional<ItemStack> stack = JeiIntegration.hovered();
            if (stack.isPresent()) {
                return stack;
            }
        }
        return Optional.empty();
    }
}
