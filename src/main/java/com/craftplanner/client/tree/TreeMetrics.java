package com.craftplanner.client.tree;

/**
 * Geometry of one craft-tree row, shared by the HUD overlay and the
 * {@code CraftTreeScreen} window.
 *
 * <p>Everything here is arithmetic on integers and a scale factor, with no
 * Minecraft type in sight, so the numbers that decide whether the tree is
 * usable can be asserted in a test instead of being discovered by eye in
 * game — which is how a 0.23 density came to ship as the default: nine-pixel
 * bitmap glyphs scaled to 0.23 are a two-pixel dash, and nothing checked that.</p>
 *
 * <p><b>Two coordinate spaces, and every method says which one it returns.</b>
 * Drawing happens inside a scaled pose, where a row is always {@link #ROW_H}
 * layout pixels and an icon always {@link #ICON} — those numbers do not depend
 * on the density, which is what keeps nested columns from drifting by a rounding
 * pixel per level. Hit testing happens in screen pixels, where the same row is
 * {@link #rowPitch(double)} tall; {@link #toScreen} is the only bridge between
 * the two. Mixing them up is the bug class that made the overlay's markers
 * land on the wrong rows.</p>
 *
 * <p>Two rules shape the layout:</p>
 * <ul>
 *   <li><b>Text is never scaled below {@link #MIN_READABLE_SCALE}.</b> A panel
 *     that cannot fit its content clips and says so, instead of shrinking the
 *     glyphs into noise.</li>
 *   <li><b>A row's controls sit inside that row.</b> The goal bar, the fold
 *     toggle and the icon are columns of the row they belong to, offset by
 *     depth — not a rail at the other end of the panel, where a click had to
 *     guess which of six stacked rows it aimed at.</li>
 * </ul>
 */
public final class TreeMetrics {

    /**
     * Smallest scale whose glyphs stay readable. Minecraft's font is a 9 px
     * bitmap, so this is the lowest density that still leaves six pixel rows per
     * character — below it a {@code -} is a dash and {@code 2/3} is noise.
     */
    public static final double MIN_READABLE_SCALE = 0.65;
    /** Full size: the window's density, and the ceiling for the overlay. */
    public static final double MAX_SCALE = 1.0;

    /** Row height in layout pixels: a 16 px icon plus two pixels of air. */
    public static final int ROW_H = 18;
    public static final int ICON = 16;
    /** Horizontal step per level of nesting, in layout pixels. */
    public static final int INDENT = 14;
    /** Air before the first column of a row. */
    public static final int LEFT_PAD = 2;
    /** Width of the goal accent bar that owns a root and its whole branch. */
    public static final int BAR_W = 3;
    public static final int BAR_GAP = 2;
    /** Fold toggle plate: the smallest square a 9 px glyph fits into. */
    public static final int TOGGLE_W = 11;
    public static final int TOGGLE_H = 11;
    public static final int TOGGLE_GAP = 2;
    /** Gap between the icon and the label. */
    public static final int LABEL_GAP = 4;
    /** Zone at the panel's right edge kept for the scrollbar, in screen pixels. */
    public static final int SCROLL_GUTTER = 8;
    /** Width of the scrollbar thumb painted inside that zone. */
    public static final int SCROLL_BAR_W = 3;
    /** Shortest thumb a cursor can still be placed on. */
    public static final int MIN_THUMB_H = 8;
    /** Narrowest fold-toggle hit band, so a dense panel stays clickable. */
    public static final int MIN_TOGGLE_HIT_W = 9;

    private TreeMetrics() {
    }

    /** A rectangle in one of the two spaces; see the class documentation. */
    public record Rect(int x, int y, int w, int h) {

        public boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /**
     * A layout rectangle at the given origin and density, in screen pixels —
     * the space a cursor, a tooltip anchor and a hit test live in.
     *
     * <p>Widths and heights are floored at one pixel: a control that measures
     * zero after rounding cannot be clicked at all, which is how the overlay's
     * markers disappeared from a short panel.</p>
     */
    public static Rect toScreen(Rect layout, double scale, int originX, int originY) {
        return new Rect(originX + Math.round(layout.x() * (float) scale),
                originY + Math.round(layout.y() * (float) scale),
                Math.max(1, Math.round(layout.w() * (float) scale)),
                Math.max(1, Math.round(layout.h() * (float) scale)));
    }

    /**
     * Density the tree may be drawn at: the requested one, kept inside the
     * readable band. There is no "shrink to fit" here on purpose.
     */
    public static double readableScale(double requested) {
        return Math.max(MIN_READABLE_SCALE, Math.min(MAX_SCALE, requested));
    }

    /** Height of one row in <em>screen</em> pixels at {@code scale}. */
    public static int rowPitch(double scale) {
        return Math.max(1, Math.round(ROW_H * (float) scale));
    }

    // --- layout space: draw with these, inside the scaled pose ---------------

    /** Left edge of the goal bar of a row at {@code depth}. */
    public static int barX(int depth) {
        return LEFT_PAD + depth * INDENT;
    }

    /** Left edge of the fold toggle of a row at {@code depth}. */
    public static int toggleX(int depth) {
        return barX(depth) + BAR_W + BAR_GAP;
    }

    /** Left edge of the icon of a row at {@code depth}. */
    public static int iconX(int depth) {
        return toggleX(depth) + TOGGLE_W + TOGGLE_GAP;
    }

    /** Left edge of the label of a row at {@code depth}. */
    public static int labelX(int depth) {
        return iconX(depth) + ICON + LABEL_GAP;
    }

    /**
     * X of the vertical guide a row's children hang off: the centre of the row's
     * own icon, so the line reads as growing out of the item.
     */
    public static int spineX(int depth) {
        return iconX(depth) + ICON / 2;
    }

    /** Bottom edge of a row's icon, i.e. where its guide starts. */
    public static int iconBottom(int index, int scroll) {
        return iconY(index, scroll) + ICON;
    }

    /**
     * A row's amount as both surfaces draw it: the availability sign, then the
     * number. Measuring this string rather than the number alone is what makes the
     * amount column wide enough for the sign, in the window and in the HUD alike —
     * the sign is only worth having if it never pushes the number out of the panel.
     */
    public static String markedAmount(TreeRow.Status status, String amount) {
        String mark = status.mark();
        return mark.isEmpty() ? amount : mark + " " + amount;
    }

    /**
     * "have/need" for a partly covered material: the part of the demand already in
     * the inventory, never shown as more than the demand or less than nothing.
     */
    public static String partialAmount(long have, long need) {
        return Math.max(0, Math.min(have, need)) + "/" + need;
    }

    /** Pixel width a row of {@code depth} needs to show a label of {@code labelW}. */
    public static int rowWidth(int depth, int labelW) {
        return labelX(depth) + labelW;
    }

    /** Width left of a label of {@code depth} inside a content area {@code contentW} wide. */
    public static int labelRoom(int contentW, int depth) {
        return Math.max(0, contentW - labelX(depth));
    }

    /** Top of row {@code index} in layout pixels, given the scroll. */
    public static int rowTop(int index, int scroll) {
        return (index - scroll) * ROW_H;
    }

    /** Vertical centre of row {@code index}. */
    public static int rowCenter(int index, int scroll) {
        return rowTop(index, scroll) + ROW_H / 2;
    }

    /** Top of the icon of row {@code index}, centred in the row. */
    public static int iconY(int index, int scroll) {
        return rowTop(index, scroll) + (ROW_H - ICON) / 2;
    }

    /** Baseline-top of the label of row {@code index}, centred on its line. */
    public static int textY(int index, int scroll, int fontLineHeight) {
        return rowTop(index, scroll) + (ROW_H - fontLineHeight) / 2;
    }

    /** The fold toggle plate of a row, in layout pixels. */
    public static Rect toggleRect(int depth, int index, int scroll) {
        return new Rect(toggleX(depth), rowTop(index, scroll) + (ROW_H - TOGGLE_H) / 2,
                TOGGLE_W, TOGGLE_H);
    }

    /** The icon of a row, in layout pixels. */
    public static Rect iconRect(int depth, int index, int scroll) {
        return new Rect(iconX(depth), iconY(index, scroll), ICON, ICON);
    }

    // --- screen space: hit-test with these ----------------------------------

    /**
     * Index of the row band {@code my} falls in, or {@code -1} outside the
     * content area.
     *
     * <p>Rows are contiguous bands of {@link #rowPitch(double)} pixels, so a
     * cursor is in exactly one of them and no tolerance radius is needed. The
     * overlay used to resolve a click to the nearest row centre within eleven
     * pixels, which at the old four-pixel pitch covered six rows.</p>
     */
    public static int rowAt(int contentTop, int contentBottom, int scroll, double scale, int my) {
        int pitch = rowPitch(scale);
        if (my < contentTop || my >= contentBottom) {
            return -1;
        }
        return scroll + (my - contentTop) / pitch;
    }

    /** The fold toggle of a row in screen pixels, its height the whole row band. */
    public static Rect toggleHit(int depth, int index, int scroll, double scale,
            int originX, int originY) {
        Rect plate = toScreen(toggleRect(depth, index, scroll), scale, originX, originY);
        int pitch = rowPitch(scale);
        int top = originY + (index - scroll) * pitch;
        return new Rect(plate.x(), top, Math.max(plate.w(), MIN_TOGGLE_HIT_W), pitch);
    }
}
