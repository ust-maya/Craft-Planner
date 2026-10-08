package com.craftplanner.client.screen;

import net.minecraft.client.gui.screens.Screen;

/**
 * Which open screens are the planner's own windows.
 *
 * <p>The HUD tree is drawn under every screen, dimmed by the window's scrim —
 * and next to a window that shows the same plan, the dimmed copy only competes
 * with it for attention. The layout editor is not one of these: editing the
 * overlay is what it is open for.</p>
 */
public final class PlannerScreens {

    private PlannerScreens() {
    }

    /** True for the list, the tree, the two pickers and the quantity popup. */
    public static boolean isModal(Screen screen) {
        return screen instanceof RecipeListScreen || screen instanceof CraftTreeScreen
                || screen instanceof BatchPickerScreen || screen instanceof RecipePickerScreen
                || screen instanceof QuantityPopupScreen;
    }
}
