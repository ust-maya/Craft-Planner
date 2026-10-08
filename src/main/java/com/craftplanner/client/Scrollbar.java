package com.craftplanner.client;

import com.craftplanner.client.tree.TreeMetrics;
import com.craftplanner.client.tree.TreePanel;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The one scrollbar every planner surface draws and aims with.
 *
 * <p>Four of them were written out separately — the HUD panel, the goal list, the
 * recipe picker and the batch picker — and the tree window made a fifth when it
 * split off the HUD. Each computed the same three numbers a slightly different
 * way: how tall the thumb is, how far down it sits, and where a cursor has to be
 * to count as grabbing it. That is how the same drag behaves differently in two
 * windows, and the thumb of one list kept a floor of six pixels while another kept
 * eight for no reason anyone remembered.</p>
 *
 * <p>The arithmetic itself is not here: {@link TreePanel} owns the mapping between
 * a pixel and a scroll position, and is tested. This class only places it and
 * paints it, in the single size and palette the mod uses.</p>
 */
public final class Scrollbar {

    /** Width of the painted bar, shared with the layout's scroll gutter. */
    public static final int WIDTH = TreeMetrics.SCROLL_BAR_W;
    /** Air around the bar a cursor still counts as touching it. */
    public static final int HIT = 3;

    private Scrollbar() {
    }

    /**
     * The bar as it is drawn this frame: the track it lives in and the thumb
     * inside it, all in the pixels the caller draws in.
     */
    public record Geometry(int x, int trackTop, int trackHeight, int thumbTop, int thumbHeight) {
    }

    /**
     * Places the thumb.
     *
     * @param x             left edge of the painted bar
     * @param trackTop      first pixel of the scrollable area
     * @param trackHeight   height of that area
     * @param visibleRows   rows the area shows at once
     * @param rowCount      rows it holds altogether
     * @param scroll        the first visible row
     * @param maxScroll     {@code rowCount - visibleRows}, already clamped to zero
     * @param minThumbHeight the shortest thumb the bar may shrink to
     */
    public static Geometry of(int x, int trackTop, int trackHeight, int visibleRows, int rowCount,
            int scroll, int maxScroll, int minThumbHeight) {
        int thumbHeight = Math.max(minThumbHeight,
                TreePanel.thumbHeight(trackHeight, visibleRows, rowCount));
        return new Geometry(x, trackTop, trackHeight,
                TreePanel.thumbTop(trackTop, trackHeight, thumbHeight, scroll, maxScroll),
                thumbHeight);
    }

    /** The thumb as it always looks: the quiet grey of a bar nobody is touching. */
    public static void draw(GuiGraphics graphics, Geometry bar) {
        draw(graphics, bar, false);
    }

    /**
     * Paints the track and the thumb, brighter while the cursor is on the bar's
     * column — the one piece of feedback that says the thumb can be grabbed
     * rather than only scrolled over.
     */
    public static void draw(GuiGraphics graphics, Geometry bar, boolean hot) {
        graphics.fill(bar.x(), bar.trackTop(), bar.x() + WIDTH,
                bar.trackTop() + bar.trackHeight(), UiPalette.SCROLL_TRACK);
        graphics.fill(bar.x(), bar.thumbTop(), bar.x() + WIDTH,
                bar.thumbTop() + bar.thumbHeight(),
                hot ? UiPalette.SCROLL_BAR_HOT : UiPalette.SCROLL_BAR);
    }

    /** Whether the cursor is on the bar's column, thumb or track alike. */
    public static boolean overColumn(Geometry bar, int mx, int my) {
        return mx >= bar.x() - HIT && mx < bar.x() + WIDTH + HIT
                && my >= bar.trackTop() && my < bar.trackTop() + bar.trackHeight();
    }

    /** Whether the cursor is on the thumb itself, which is the part a drag moves. */
    public static boolean overThumb(Geometry bar, int mx, int my) {
        return overColumn(bar, mx, my) && my >= bar.thumbTop() && my < bar.thumbTop() + bar.thumbHeight();
    }

    /**
     * The row a click or a drag at {@code my} jumps to: the thumb keeps its own
     * height under the cursor rather than snapping its top to it.
     */
    public static int scrollAt(Geometry bar, int my, int maxScroll) {
        return TreePanel.scrollAt(bar.trackTop(), bar.trackHeight(), bar.thumbHeight(), maxScroll, my);
    }
}
