package com.craftplanner.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * Shape recipes shared by every Craft Planner surface.
 *
 * <p>Minecraft 1.21.1 gives an immediate-mode GUI only integer rectangles —
 * no rounded corners, no tinted sprites — so the flat-dark look is built from
 * layered fills: a dark outer ring for edge definition, a lighter inner ring
 * for the panel line, header bands closed by a one-pixel accent underline, and
 * chips (flat bordered boxes) wherever the vanilla sprite button would clash
 * with the hand-drawn panels.</p>
 *
 * <p>Geometry helpers only: they take absolute screen pixels and never read
 * game state, so the pure-geometry rule of the screen classes holds.</p>
 */
public final class UiShapes {

    /** Height of each border ring drawn by {@link #panel}. */
    public static final int BORDER_PX = 1;

    private UiShapes() {
    }

    /**
     * Panel body plus the double border: {@code outer} ring on the very edge,
     * {@code inner} one pixel inside it. Replaces the single-ring recipe every
     * surface used to inline.
     */
    public static void panel(GuiGraphics graphics, int left, int top, int width, int height,
            int background, int outer, int inner) {
        graphics.fill(left, top, left + width, top + height, background);
        ring(graphics, left, top, width, height, outer);
        ring(graphics, left + BORDER_PX, top + BORDER_PX,
                width - 2 * BORDER_PX, height - 2 * BORDER_PX, inner);
    }

    /** Header band across the panel top, closed by a 1 px accent underline. */
    public static void headerBand(GuiGraphics graphics, int left, int top, int width,
            int bandHeight, int band, int accent, int accentInset) {
        graphics.fill(left + BORDER_PX, top + BORDER_PX,
                left + width - BORDER_PX, top + BORDER_PX + bandHeight, band);
        graphics.fill(left + accentInset, top + BORDER_PX + bandHeight,
                left + width - accentInset, top + BORDER_PX + bandHeight + BORDER_PX, accent);
    }

    /** Flat bordered button plate; text/icon is the caller's to paint on top. */
    public static void chip(GuiGraphics graphics, int left, int top, int width, int height,
            int background, int border) {
        graphics.fill(left, top, left + width, top + height, background);
        ring(graphics, left, top, width, height, border);
    }

    /** Horizontal 1 px separator between two blocks of content. */
    public static void divider(GuiGraphics graphics, int fromX, int toX, int y, int color) {
        graphics.fill(fromX, y, toX, y + BORDER_PX, color);
    }

    /** Hollow 1 px rectangle. */
    public static void ring(GuiGraphics graphics, int left, int top, int width, int height,
            int color) {
        graphics.fill(left, top, left + width, top + BORDER_PX, color);
        graphics.fill(left, top + height - BORDER_PX, left + width, top + height, color);
        graphics.fill(left, top, left + BORDER_PX, top + height, color);
        graphics.fill(left + width - BORDER_PX, top, left + width, top + height, color);
    }

    // --- the two tree controls ----------------------------------------------

    /**
     * The fold control of a tree row, as it is drawn everywhere.
     *
     * <p>Three surfaces needed this — the tree window, the recipe window and the
     * alternatives window — and three copies of it is how they would have drifted
     * into three different buttons.</p>
     *
     * <p>The mark is drawn as rules rather than as a {@code -} and {@code +} from
     * the font. A bitmap glyph is four or five pixels of it and shrinks with the
     * density, which is what made the control hard to find in the first place; two
     * rules are the same size at every density and need no metrics.</p>
     */
    public static void foldButton(GuiGraphics graphics, int left, int top, int width, int height,
            boolean folded, boolean aimed) {
        chip(graphics, left, top, width, height,
                folded ? UiPalette.MARKER_FOLDED_BG
                        : aimed ? UiPalette.FOLD_BTN_HOVER_BG : UiPalette.FOLD_BTN_BG,
                aimed ? UiPalette.FOLD_BTN_HOVER_BORDER : UiPalette.FOLD_BTN_BORDER);

        int ink = folded ? UiPalette.MARKER_FOLDED_TEXT : UiPalette.FOLD_BTN_TEXT;
        int centreY = top + height / 2;
        graphics.hLine(left + 2, left + width - 3, centreY, ink);
        if (folded) {
            graphics.vLine(left + width / 2, top + 3, top + height - 4, ink);
        }
    }

    /**
     * The recipe choice of a tree row: a button with the recipe's machine on it.
     *
     * <p>It used to carry a crafting table on every row, which said "recipe" and
     * nothing else — a column of identical tables drew the eye and told the player
     * nothing. The machine says what clicking will change: which block this part is
     * made in. The count of alternatives stays in the tooltip.</p>
     *
     * <p>The plate is the caller's: an amber ring means the row offers a real
     * choice, and a plain ring means there is only the one recipe.</p>
     */
    public static void recipeButton(GuiGraphics graphics, int left, int top, int size,
            int background, int border, ItemStack icon) {
        chip(graphics, left, top, size, size, background, border);
        graphics.renderItem(icon, left + (size - 16) / 2, top + (size - 16) / 2);
    }
}
