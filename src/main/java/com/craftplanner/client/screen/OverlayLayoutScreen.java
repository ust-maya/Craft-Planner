package com.craftplanner.client.screen;

import com.craftplanner.client.Keybinds;
import com.craftplanner.client.overlay.OverlayPlacement;
import com.craftplanner.client.overlay.TreeCollapse;
import com.craftplanner.client.overlay.TreeOverlayRenderer;
import com.craftplanner.graph.RecipeNode;
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
 * press {@code +} or {@code -} for the text size, and scroll the plan with the
 * wheel or by holding the scrollbar at the panel's right edge. A fold mark folds
 * or unfolds its branch and a recipe badge opens the picker for that row — the
 * HUD cannot be clicked at all, so this screen is where its tree stays usable.
 * A click outside or {@code Esc} keeps the result — {@link #removed()} commits it
 * whichever way the screen is left — and {@code Shift} with the right button
 * restores the automatic layout, the modifier keeping an ordinary right click from
 * wiping a sized panel.</p>
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
            // The reset throws away the size and position the player dragged, so
            // it asks for a modifier: an ordinary right click used to wipe a whole
            // layout on the way to the panel's corner.
            if (hasShiftDown()) {
                OverlayPlacement.resetAndSave();
                this.minecraft.setScreen(null);
            }
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        int mx = (int) mouseX;
        int my = (int) mouseY;
        // The panel drawn behind this screen keeps its one HUD affordance
        // clickable: the scrollbar at its right edge is HUD geometry, and holding
        // it scrolls the plan instead of dragging the window out from under the
        // cursor. The resize corner above keeps priority where the two meet, or
        // the bar would steal the grip it is drawn next to.
        if (OverlayPlacement.overGrip(mx, my)) {
            OverlayPlacement.startSize(mx, my);
        } else if (TreeOverlayRenderer.grabScroll(mx, my)) {
            // The grab took the click; the drag follows in mouseDragged.
        } else if (TreeOverlayRenderer.hitFold(mx, my) != null) {
            // A fold mark of the panel behind this screen. This editor is a screen,
            // so it is the one place HUD geometry can be aimed at — which is what
            // keeps a fold mark from being a mark that leads nowhere.
            TreeCollapse.toggle(TreeOverlayRenderer.hitFold(mx, my));
        } else if (TreeOverlayRenderer.hitChoice(mx, my) != null) {
            RecipeNode node = TreeOverlayRenderer.hitChoice(mx, my);
            this.minecraft.setScreen(new RecipePickerScreen(node.item(), this));
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
        if (TreeOverlayRenderer.scrolling()) {
            TreeOverlayRenderer.dragScroll((int) mouseY);
            return true;
        }
        OverlayPlacement.dragTo((int) mouseX, (int) mouseY, width, height);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        TreeOverlayRenderer.releaseScroll();
        OverlayPlacement.endDrag();
        return true;
    }

    /** The wheel scrolls the plan, exactly as it does in the HUD. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        TreeOverlayRenderer.scroll(scrollY > 0 ? -1 : 1);
        return true;
    }

    /**
     * {@code +} and {@code -} grow or shrink the text — the wheel is busy
     * scrolling, and resizing a panel against rows the player cannot see yet is
     * guesswork. The numpad works too, and a held key repeats, so the density
     * can be walked through quickly.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_EQUAL || keyCode == GLFW.GLFW_KEY_KP_ADD) {
            OverlayPlacement.zoom(1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_MINUS || keyCode == GLFW.GLFW_KEY_KP_SUBTRACT) {
            OverlayPlacement.zoom(-1);
            return true;
        }
        // Vanilla stops polling key mappings once a screen is open, so the key
        // that started the editor has to be answered here for it to be a toggle.
        if (Keybinds.EDIT_LAYOUT.matches(keyCode, scanCode)) {
            this.minecraft.setScreen(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        // Leaving the editor mid-drag never comes with a release event, and a grab
        // left over would move the plan instead of the panel in the next session.
        TreeOverlayRenderer.releaseScroll();
        OverlayPlacement.applyAndSave();
        super.removed();
    }
}
