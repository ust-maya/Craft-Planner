package com.craftplanner.client;

import com.craftplanner.Constants;
import com.craftplanner.client.screen.CraftTreeScreen;
import com.craftplanner.client.screen.OverlayLayoutScreen;
import com.craftplanner.client.screen.QuantityPopupScreen;
import com.craftplanner.client.screen.RecipeListScreen;
import com.craftplanner.engine.RecipeIndex;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Handles the mod keys on the game event bus, client side only.
 *
 * <p>Stage 6: {@code ADD_RECIPE} captures the stack under the cursor through
 * {@link ItemCapture} and opens the quantity popup, which adds the root to
 * the planner graph; {@code OPEN_RECIPES} opens the list screen where the
 * added targets are edited (Stage 7); {@code TOGGLE_OVERLAY} switches the
 * tree overlay drawn by {@code TreeOverlayRenderer} (Stage 8).</p>
 *
 * <p>Two complementary hooks are needed because vanilla only drives
 * {@link KeyMapping#consumeClick()} while no screen is open —
 * {@code KeyboardHandler} calls {@code KeyMapping.click()} inside
 * {@code if (this.minecraft.screen == null)}. Presses made inside a GUI
 * (inventory, chest, EMI/JEI) never reach the mappings, so they are read
 * from {@link InputEvent.Key} instead, which fires for every key event.</p>
 */
@EventBusSubscriber(modid = Constants.MODID, value = Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        // Stage 9: keep the availability colours fresh (once per second).
        if (mc.level.getGameTime() % 20 == 0) {
            PlannerState.refreshAvailability();
        }

        // Runs recipe-change rebuilds deferred by the selectRecipe debounce.
        PlannerState.tick();

        // Only fires with no screen open — see the class javadoc.
        while (Keybinds.ADD_RECIPE.consumeClick()) {
            openQuantityPopup(mc);
        }

        while (Keybinds.TOGGLE_OVERLAY.consumeClick()) {
            OverlayState.toggle();
        }

        while (Keybinds.OPEN_RECIPES.consumeClick()) {
            mc.setScreen(new RecipeListScreen());
        }

        while (Keybinds.OPEN_TREE.consumeClick()) {
            mc.setScreen(new CraftTreeScreen());
        }

        // The editor arranges the panel that is on screen, so the first press on
        // a hidden overlay only reveals it. It is a screen, and screens close
        // themselves: the key is answered again inside OverlayLayoutScreen.
        while (Keybinds.EDIT_LAYOUT.consumeClick()) {
            if (!OverlayState.isVisible() || PlannerState.graph().isEmpty()) {
                OverlayState.setVisible(true);
            } else {
                mc.setScreen(new OverlayLayoutScreen());
            }
        }
    }

    /**
     * Recipes synced/reloaded (world join, datapack reload): the cached index
     * points at stale holders, so drop it and re-expand the current plan
     * against the new recipe set.
     */
    @SubscribeEvent
    public static void onRecipesUpdated(RecipesUpdatedEvent event) {
        RecipeIndex.invalidateCache();
        PlannerState.rebuild();
    }

    /** Same actions as above, but for presses made while a GUI is open. */
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        // Release and auto-repeat are ignored, so holding a key acts once.
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (mc.level == null || screen == null || !readsItemsFrom(screen)) {
            return;
        }

        int keyCode = event.getKey();
        int scanCode = event.getScanCode();
        if (matches(Keybinds.ADD_RECIPE, keyCode, scanCode)) {
            openQuantityPopup(mc);
        } else if (matches(Keybinds.TOGGLE_OVERLAY, keyCode, scanCode)) {
            OverlayState.toggle();
        } else if (matches(Keybinds.OPEN_RECIPES, keyCode, scanCode)) {
            mc.setScreen(new RecipeListScreen());
        } else if (matches(Keybinds.OPEN_TREE, keyCode, scanCode)) {
            mc.setScreen(new CraftTreeScreen());
        }
    }

    private static void openQuantityPopup(Minecraft mc) {
        ItemCapture.capture(mc).ifPresent(stack -> mc.setScreen(new QuantityPopupScreen(stack)));
    }

    /** Key mapping check used by vanilla: same key plus the same modifier. */
    private static boolean matches(KeyMapping mapping, int keyCode, int scanCode) {
        return mapping.matches(keyCode, scanCode)
                && mapping.getKeyModifier().isActive(mapping.getKeyConflictContext());
    }

    /**
     * Screens the planner is allowed to read items from: containers
     * (inventory, chest, creative picker) and the recipe viewers, recognised
     * by package because they are soft dependencies. Every other screen —
     * chat, anvil, books, command blocks — keeps the key for typing.
     */
    private static boolean readsItemsFrom(Screen screen) {
        if (screen instanceof QuantityPopupScreen || screen instanceof RecipeListScreen
                || screen instanceof CraftTreeScreen) {
            return false;
        }
        if (screen instanceof AbstractContainerScreen<?>) {
            return true;
        }
        String className = screen.getClass().getName();
        return className.startsWith("dev.emi.") || className.startsWith("mezz.jei.");
    }
}
