package com.craftplanner.client.screen;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The four fold buttons every tree window carries — collapse all, expand all,
 * one level less, one level more — built in one place so the three windows
 * label, size and order them the same way. The level buttons are short ("-1",
 * "+1") and say what they do in their tooltips: they share a row with the
 * window's own buttons, and on a large GUI scale every pixel of it counts.
 */
final class FoldButtons {

    /** Air between two of the buttons, the footer gap of the tree window. */
    static final int GAP = 4;
    private static final int PAD = 6;
    private static final int MIN_W = 60;
    private static final int MIN_LEVEL_W = 20;
    private static final String[] KEYS = {
        "screen.craftplanner.tree.collapse.all",
        "screen.craftplanner.tree.expand.all",
        "screen.craftplanner.tree.collapse.level",
        "screen.craftplanner.tree.expand.level",
    };
    private static final String[] TOOLTIPS = {
        "screen.craftplanner.tree.collapse.tooltip",
        "screen.craftplanner.tree.expand.tooltip",
        "screen.craftplanner.tree.collapse.level.tooltip",
        "screen.craftplanner.tree.expand.level.tooltip",
    };

    /** What the buttons do; each window keeps its folds its own way. */
    interface Actions {
        void collapseAll();

        void expandAll();

        void collapseLevel();

        void expandLevel();
    }

    private FoldButtons() {
    }

    private static int widthOf(Font font, int index) {
        return Math.max(index < 2 ? MIN_W : MIN_LEVEL_W,
                font.width(Component.translatable(KEYS[index])) + PAD);
    }

    /** Room the four buttons take in a row, gaps between them included. */
    static int width(Font font) {
        int total = 3 * GAP;
        for (int i = 0; i < KEYS.length; i++) {
            total += widthOf(font, i);
        }
        return total;
    }

    /** The buttons in draw order, at 0,0 — {@link #place} puts them where the row is. */
    static List<Button> create(Font font, int height, Actions actions) {
        Button.OnPress[] presses = {
            button -> actions.collapseAll(),
            button -> actions.expandAll(),
            button -> actions.collapseLevel(),
            button -> actions.expandLevel(),
        };
        Button[] buttons = new Button[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            buttons[i] = FitButton.create(0, 0, widthOf(font, i), height,
                    Component.translatable(KEYS[i]), presses[i], Component.translatable(TOOLTIPS[i]));
        }
        return List.of(buttons);
    }

    /** Lays the buttons out left to right from {@code x}. */
    static void place(List<Button> buttons, int x, int y) {
        for (Button button : buttons) {
            button.setX(x);
            button.setY(y);
            x += button.getWidth() + GAP;
        }
    }
}
