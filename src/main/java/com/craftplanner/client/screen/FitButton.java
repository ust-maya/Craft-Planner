package com.craftplanner.client.screen;

import com.craftplanner.client.UiPalette;
import com.craftplanner.client.UiShapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * The mod's button: a flat chip that trims its label instead of scrolling it.
 *
 * <p>Two deliberate departures from vanilla. The label is cut with an ellipsis
 * first, because {@code AbstractButton} keeps an over-long label inside the
 * widget with a scissor and animates it back and forth — on a dense panel that
 * reads as "the text does not fit". And the chrome is the same flat plate the
 * hand-drawn rows use, because the vanilla sprite button painted over these
 * panels looked like it belonged to another mod.</p>
 */
final class FitButton extends Button {

    FitButton(int x, int y, int width, int height, Component label, Button.OnPress onPress) {
        super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
    }

    /**
     * A button of the given box and label, with a tooltip when one is worth
     * showing — the shape every panel in this mod builds its widgets from.
     */
    static Button create(int x, int y, int width, int height, Component label,
            Button.OnPress onPress, Component tooltip) {
        FitButton button = new FitButton(x, y, width, height, label, onPress);
        if (tooltip != null) {
            button.setTooltip(Tooltip.create(tooltip));
        }
        return button;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int background = !this.active ? UiPalette.CHIP_DISABLED
                : this.isHovered() ? UiPalette.CHIP_HOVER_BG : UiPalette.CHIP_BG;
        int border = this.isFocused() || (this.active && this.isHovered())
                ? UiPalette.CHIP_HOVER_BORDER : UiPalette.CHIP_BORDER;
        UiShapes.chip(graphics, this.getX(), this.getY(), this.getWidth(), this.getHeight(),
                background, border);
        int textColor = this.active ? (this.isHovered() ? 0xFFFFFFFF : UiPalette.TEXT)
                : 0xFF808080;
        // AbstractButton holds no font field in 1.21.1 — its own renderWidget
        // resolves the instance font the same way.
        this.renderString(graphics, Minecraft.getInstance().font, textColor);
    }

    @Override
    public void renderString(GuiGraphics graphics, Font font, int color) {
        String label = RecipeListLayout.trimToWidth(font::width, this.getMessage().getString(),
                this.getWidth() - 4);
        graphics.drawCenteredString(font, label, this.getX() + this.getWidth() / 2,
                this.getY() + (this.getHeight() - RecipeListLayout.LINE_H) / 2 + 1, color);
    }
}
