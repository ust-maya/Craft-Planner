package com.craftplanner.client;

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
 */
public final class ItemCapture {

    private ItemCapture() {
    }

    /** Stack under the cursor, or empty when there is nothing to capture. */
    public static Optional<ItemStack> capture(Minecraft mc) {
        Optional<ItemStack> fromViewer = ViewerIntegration.hovered();
        if (fromViewer.isPresent()) {
            return fromViewer;
        }

        Screen screen = mc.screen;
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            Optional<ItemStack> fromSlot = hoveredSlot(containerScreen);
            if (fromSlot.isPresent()) {
                return fromSlot;
            }
        }
        return carriedStack(mc);
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
