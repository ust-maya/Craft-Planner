package com.craftplanner.client;

import net.minecraft.client.gui.GuiGraphics;

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

    /**
     * Filled caret: {@code right} makes it point right (a closed branch), else it
     * points down (an open one). 1.21.1 draws no polygons, so the triangle is a
     * stack of horizontal fills — which is also why a shape rather than a glyph
     * carries the state: two pixel rows of the bitmap font are an unreadable
     * dash, two pixel rows of a filled triangle are still a triangle.
     */
    public static void caret(GuiGraphics graphics, int left, int top, int size, boolean right,
            int color) {
        float middle = (size - 1) / 2f;
        for (int i = 0; i < size; i++) {
            if (right) {
                int len = Math.max(1, Math.round(size - Math.abs(i - middle) * 2));
                graphics.fill(left, top + i, left + len, top + i + 1, color);
            } else {
                int len = Math.max(1, size - i);
                int x = left + (size - len) / 2;
                graphics.fill(x, top + i, x + len, top + i + 1, color);
            }
        }
    }

    /** Hollow 1 px rectangle. */
    public static void ring(GuiGraphics graphics, int left, int top, int width, int height,
            int color) {
        graphics.fill(left, top, left + width, top + BORDER_PX, color);
        graphics.fill(left, top + height - BORDER_PX, left + width, top + height, color);
        graphics.fill(left, top, left + BORDER_PX, top + height, color);
        graphics.fill(left + width - BORDER_PX, top, left + width, top + height, color);
    }
}
