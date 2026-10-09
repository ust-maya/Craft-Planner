package com.craftplanner.integration;

import java.util.Optional;
import net.minecraft.world.item.ItemStack;

/**
 * Façade over the recipe-viewer integrations.
 *
 * <p>Asks the viewers in priority order (EMI first — it exposes a proper
 * runtime API without a plugin) and falls back to "nothing" so the caller
 * can continue with plain inventory capture.</p>
 */
public final class ViewerIntegration {

    private ViewerIntegration() {
    }

    /**
     * Whether a viewer owns the keyboard right now because its search box has
     * focus.
     *
     * <p>JEI and EMI draw their search field inside their own overlay rather
     * than as a widget of the open screen, so vanilla's focused-widget check
     * does not see it and the viewer has to be asked. Neither answer is
     * essential — a viewer that cannot say is simply not taken into account —
     * so a missing method or a failure inside one is reported as "not focused"
     * rather than thrown at the key handler.</p>
     */
    public static boolean isSearchFocused() {
        return EmiIntegration.isSearchFocused() || JeiIntegration.isSearchFocused();
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
