package com.craftplanner.client.screen;

import com.craftplanner.client.Keybinds;
import com.craftplanner.client.overlay.OverlayPlacement;
import com.craftplanner.client.overlay.TreeCollapse;
import com.craftplanner.client.overlay.TreeOverlayRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The transparent screen the overlay layout editor runs in.
 *
 * <p>It paints nothing and exists only to take the mouse away from the player.
 * While no screen is open the game grabs the cursor: it is invisible, pinned to
 * the centre of the window, moving it turns the camera and clicking swings the
 * player's arm — so a panel dragged with the mouse is impossible in plain HUD.
 * Opening a screen releases the cursor, clears the held movement keys and stops
 * attacking, which is exactly the input state arranging a window needs.</p>
 *
 * <p>The panel itself is still drawn by the HUD, which Minecraft renders behind
 * an open screen, so the editor sees the very rectangle it is moving and only
 * has to keep its own background undimmed for it to stay readable.</p>
 *
 * <p>Drag inside the panel to move it, its bottom-right corner to resize it,
 * use the wheel for the text size. A click outside or {@code Esc} keeps the
 * result — {@link #removed()} commits it whichever way the screen is left —
 * and right click restores the automatic layout.</p>
 */
public final class OverlayLayoutScreen extends Screen {

    public OverlayLayoutScreen() {
        super(Component.translatable("key.craftplanner.edit"));
    }

    @Override
    protected void init() {
        OverlayPlacement.begin();
    }

    /** No dimming and no blur: the panel being arranged is the whole content. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    /** The world keeps running; a frozen game would make no sense to arrange. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            OverlayPlacement.resetAndSave();
            this.minecraft.setScreen(null);
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        int mx = (int) mouseX;
        int my = (int) mouseY;
        // The panel drawn behind this screen keeps its fold markers clickable —
        // arranging a huge tree without collapsing branches would be pointless.
        String fold = TreeOverlayRenderer.hitFold(mx, my);
        if (fold != null) {
            TreeCollapse.toggle(fold);
            return true;
        }
        if (OverlayPlacement.overGrip(mx, my)) {
            OverlayPlacement.startSize(mx, my);
        } else if (OverlayPlacement.overPanel(mx, my)) {
            OverlayPlacement.startMove(mx, my);
        } else {
            // Empty desktop means the layout is finished as it stands.
            this.minecraft.setScreen(null);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        OverlayPlacement.dragTo((int) mouseX, (int) mouseY, width, height);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        OverlayPlacement.endDrag();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        OverlayPlacement.zoom(scrollY > 0 ? 1 : -1);
        return true;
    }

    /**
     * Vanilla stops polling key mappings once a screen is open, so the key that
     * started the editor has to be answered here for it to work as a toggle.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (Keybinds.EDIT_LAYOUT.matches(keyCode, scanCode)) {
            this.minecraft.setScreen(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        OverlayPlacement.applyAndSave();
        super.removed();
    }
}
