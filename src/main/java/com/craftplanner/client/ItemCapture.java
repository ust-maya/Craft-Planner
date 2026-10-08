package com.craftplanner.client;

import com.craftplanner.engine.RecipeIndex;
import com.craftplanner.graph.ItemKey;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import com.craftplanner.integration.ViewerIntegration;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Grabs the {@link ItemStack} the player is pointing at (Stage 4).
 *
 * <p>Priority:
 * <ol>
 *   <li>item hovered inside EMI/JEI (Stage 5, soft dependency);</li>
 *   <li>slot under the mouse inside an open container screen
 *       (inventory, chest, creative picker, any modded GUI);</li>
 *   <li>stack carried on the cursor, if no GUI is open.</li>
 * </ol>
 *
 * <p>Whatever is found is then read for its <em>recipe</em>, not for its history:
 * see {@link #plannable}.</p>
 */
public final class ItemCapture {

    private ItemCapture() {
    }

    /** Stack under the cursor, or empty when there is nothing to capture. */
    public static Optional<ItemStack> capture(Minecraft mc) {
        Optional<ItemStack> fromViewer = ViewerIntegration.hovered();
        if (fromViewer.isPresent()) {
            return plannable(mc, fromViewer.get());
        }

        Screen screen = mc.screen;
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            Optional<ItemStack> fromSlot = hoveredSlot(containerScreen);
            if (fromSlot.isPresent()) {
                return plannable(mc, fromSlot.get());
            }
        }
        return carriedStack(mc).flatMap(stack -> plannable(mc, stack));
    }

    /**
     * The stack the plan can actually build from the captured one.
     *
     * <p>A damaged iron pickaxe and a fresh one are the same item and a different
     * {@link ItemKey}: the key carries the component patch, and recipes are indexed
     * by item alone. Captured as it stands, the worn tool therefore reads as
     * something no recipe makes — the tree said "raw material" about an item the
     * player was holding the recipe of on screen. A renamed or enchanted stack hits
     * the same wall. So the components are dropped exactly when that is the only
     * thing between the item and its recipe, and kept in every case where the item
     * is planned as itself.</p>
     */
    private static Optional<ItemStack> plannable(Minecraft mc, ItemStack stack) {
        if (mc.level == null || stack.getComponentsPatch().isEmpty()) {
            // Nothing to lose: the captured key already is the key of the item.
            return Optional.of(stack);
        }
        RecipeIndex index = RecipeIndex.cachedFor(mc.level.getRecipeManager(),
                mc.level.registryAccess());
        ItemKey captured = ItemKey.of(stack);
        if (!index.forOutput(captured).isEmpty()) {
            return Optional.of(stack);
        }
        ItemKey plain = ItemKey.of(stack.getItem());
        if (index.forOutput(plain).isEmpty()) {
            return Optional.of(stack);
        }
        return Optional.of(new ItemStack(stack.getItem(), Math.max(1, stack.getCount())));
    }

    /** Slot the mouse currently points at, ignoring empty and ghost slots. */
    public static Optional<ItemStack> hoveredSlot(AbstractContainerScreen<?> screen) {
        Slot slot = screen.getSlotUnderMouse();
        if (slot == null || !slot.hasItem()) {
            return Optional.empty();
        }
        ItemStack stack = slot.getItem();
        return stack.isEmpty() ? Optional.empty() : Optional.of(stack.copy());
    }

    /** Item the player drags with the cursor (works outside GUIs too). */
    public static Optional<ItemStack> carriedStack(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || player.containerMenu == null) {
            return Optional.empty();
        }
        ItemStack carried = player.containerMenu.getCarried();
        return carried.isEmpty() ? Optional.empty() : Optional.of(carried.copy());
    }
}
