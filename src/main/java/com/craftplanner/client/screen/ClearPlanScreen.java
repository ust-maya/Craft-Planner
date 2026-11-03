package com.craftplanner.client.screen;

import com.craftplanner.client.PlannerState;
import com.craftplanner.client.UiPalette;
import com.craftplanner.client.UiShapes;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The question every clear-plan action goes through.
 *
 * <p>Clearing is the one planner change that cannot be undone: it drops every
 * goal, the recipe choices made for them and the items used as material, and
 * writes the emptied state to disk. A keybinding makes that a single key press
 * away, so the key cannot act on its own — it opens this window instead, and
 * only the confirm button calls {@link PlannerState#clear()}.</p>
 *
 * <p>The safe answer is the one the keyboard starts on: Tab reaches the confirm
 * button and Escape, like the cancel button, returns to whichever window asked
 * the question — the plan window, the recipe list, a container, or the game.</p>
 */
public final class ClearPlanScreen extends Screen {

    private static final String KEY_TITLE = "screen.craftplanner.clear.title";
    private static final String KEY_MESSAGE = "screen.craftplanner.clear.message";
    private static final String KEY_CONFIRM = "screen.craftplanner.clear.confirm";
    private static final String KEY_CANCEL = "screen.craftplanner.clear.cancel";

    private static final int PAD = 10;
    private static final int HEADER_H = 20;
    private static final int LINE_H = RecipeListLayout.LINE_H;
    private static final int BUTTON_H = RecipeListLayout.BUTTON_H;
    private static final int GAP = 6;
    private static final int MIN_PANEL_W = 220;
    private static final int MAX_MESSAGE_W = 300;
    private static final int MAX_MESSAGE_LINES = 4;

    /** The window to go back to — {@code null} means the game. */
    private final Screen returnTo;
    private List<String> messageLines = List.of();
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    public ClearPlanScreen(Screen returnTo) {
        super(Component.translatable(KEY_TITLE));
        this.returnTo = returnTo;
    }

    @Override
    protected void init() {
        int wrapW = Math.max(120, Math.min(MAX_MESSAGE_W, this.width - 2 * PAD - 16));
        this.messageLines = RecipeListLayout.wrapText(this.font::width,
                Component.translatable(KEY_MESSAGE).getString(), wrapW, MAX_MESSAGE_LINES);
        Component confirm = Component.translatable(KEY_CONFIRM);
        Component cancel = Component.translatable(KEY_CANCEL);
        int confirmW = Math.max(70, this.font.width(confirm) + PAD);
        int cancelW = Math.max(60, this.font.width(cancel) + PAD);
        int buttonsW = cancelW + GAP + confirmW;

        int contentW = Math.max(this.font.width(this.title), buttonsW);
        for (String line : this.messageLines) {
            contentW = Math.max(contentW, this.font.width(line));
        }
        this.panelWidth = Math.min(this.width - 16, Math.max(MIN_PANEL_W, contentW + 2 * PAD));
        this.panelHeight = HEADER_H + PAD + this.messageLines.size() * LINE_H + PAD + BUTTON_H + PAD;
        this.panelLeft = (this.width - this.panelWidth) / 2;
        this.panelTop = (this.height - this.panelHeight) / 2;

        int buttonsY = this.panelTop + this.panelHeight - PAD - BUTTON_H;
        int buttonsX = this.panelLeft + (this.panelWidth - buttonsW) / 2;
        // Cancel first, and focused: Enter on a window the player may have opened
        // by accident must not wipe the plan.
        Button cancelButton = addRenderableWidget(FitButton.create(buttonsX, buttonsY, cancelW,
                BUTTON_H, cancel, button -> onClose(), null));
        addRenderableWidget(FitButton.create(buttonsX + cancelW + GAP, buttonsY, confirmW,
                BUTTON_H, confirm, button -> clearAndGoBack(), null));
        setInitialFocus(cancelButton);
    }

    /** The plan is painted by hand, so vanilla's blur and dim would only compete. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, UiPalette.MODAL_SCRIM);
        UiShapes.panel(graphics, this.panelLeft, this.panelTop, this.panelWidth, this.panelHeight,
                UiPalette.MODAL_PANEL_BG, UiPalette.MODAL_BORDER_OUTER, UiPalette.MODAL_PANEL_BORDER);
        UiShapes.headerBand(graphics, this.panelLeft, this.panelTop, this.panelWidth, HEADER_H - 3,
                UiPalette.MODAL_HEADER_BG, UiPalette.ACCENT_LINE, RecipeListLayout.PAD);
        graphics.drawString(this.font, this.title, this.panelLeft + PAD, this.panelTop + 4,
                UiPalette.TEXT, false);
        int y = this.panelTop + HEADER_H + PAD;
        for (String line : this.messageLines) {
            graphics.drawString(this.font, line, this.panelLeft + PAD, y, UiPalette.MUTED, false);
            y += LINE_H;
        }
        // The buttons are the only widgets; the panel and its text are above them.
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void clearAndGoBack() {
        PlannerState.clear();
        this.minecraft.setScreen(this.returnTo);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.returnTo);
    }
}
