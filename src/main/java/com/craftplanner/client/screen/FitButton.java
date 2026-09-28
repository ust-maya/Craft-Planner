package com.craftplanner.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * Vanilla button that trims its own label instead of scrolling it.
 *
 * <p>{@code AbstractButton} keeps an over-long label inside the widget with a
 * scissor and animates it back and forth, which on a dense panel reads as "the
 * text does not fit". Cutting the label with an ellipsis first makes the
 * guarantee explicit: whatever width the layout ends up handing out, no label
 * can leave its button.</p>
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
    public void renderString(GuiGraphics graphics, Font font, int color) {
        String label = RecipeListLayout.trimToWidth(font::width, this.getMessage().getString(),
                this.getWidth() - 4);
        graphics.drawCenteredString(font, label, this.getX() + this.getWidth() / 2,
                this.getY() + (this.getHeight() - RecipeListLayout.LINE_H) / 2 + 1, color);
    }
}
