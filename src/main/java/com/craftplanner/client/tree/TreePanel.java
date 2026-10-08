package com.craftplanner.client.tree;

/**
 * Sizes a craft-tree panel and maps its scroll position.
 *
 * <p>The overlay and the window both need the same answer to the same question —
 * how wide and tall can this panel be, how many rows fit in it, and what does
 * the scrollbar mean — and they had it computed twice, in two idioms, with the
 * overlay's version able to shrink the text to eight percent of its size. One
 * implementation is what lets {@code TreePanelTest} decide whether the numbers
 * are usable before any of it reaches the screen.</p>
 */
public final class TreePanel {

    /** Unset marker for a pinned size, mirroring {@code OverlayPlacement.AUTO}. */
    public static final int AUTO = -1;
    /** Least label room a plan is allowed to leave itself, in layout pixels. */
    public static final int MIN_CONTENT_W = 60;
    /** Inner padding of the HUD panel, in layout pixels. */
    public static final int HUD_PAD = 5;
    /** Rows one wheel notch moves; at a single row the old panel barely stirred. */
    public static final int WHEEL_ROWS = 3;

    private TreePanel() {
    }

    /**
     * What is being asked.
     *
     * @param availW           width the panel may occupy
     * @param availH           height the panel may occupy
     * @param density          requested text density, clamped by the caller's config
     * @param pinnedW          width the player gave the panel, or {@link #AUTO}
     * @param pinnedH          height the player gave the panel, or {@link #AUTO}
     * @param naturalContentW  widest row of the tree, in layout pixels
     * @param headerH          title band, in layout pixels
     * @param footerH          shopping list and hints, in layout pixels
     * @param rowCount         rows the flattened tree holds
     * @param pad              inner padding of the panel, in layout pixels
     */
    public record Request(int availW, int availH, double density, int pinnedW, int pinnedH,
            int naturalContentW, int headerH, int footerH, int rowCount, int pad) {
    }

    /**
     * The resulting geometry. Content positions are <em>relative to the panel's
     * top-left corner</em>, so the caller can size the panel, clamp it to the
     * screen and only then decide where it goes — without the sizing needing to
     * know the position, which previously forced the width formula to be written
     * out twice (once here, once in the overlay) and let the two drift apart.
     *
     * @param clipped    the widest row does not fit: labels are trimmed and the
     *                   panel says the plan belongs in the window
     * @param contentW   label width available at {@link #scale()}
     * @param scroll     the scroll position, clamped to what the panel can show
     */
    public record Plan(double scale, int pitch, int panelW, int panelH, int contentLeft,
            int contentTop, int contentBottom, int contentW, boolean clipped,
            int visibleRows, int maxScroll, int scroll) {

        /** Height of the row window the tree is drawn into. */
        public int contentHeight() {
            return Math.max(0, contentBottom - contentTop);
        }

        /** The same geometry placed at {@code left}, {@code top}. */
        public Plan at(int left, int top) {
            return new Plan(scale, pitch, panelW, panelH, left + contentLeft, top + contentTop,
                    top + contentBottom, contentW, clipped, visibleRows, maxScroll, scroll);
        }
    }

    /** Resolves density, panel size, the content window and the scroll clamp. */
    public static Plan plan(Request r, int requestedScroll) {
        double scale = TreeMetrics.readableScale(r.density());
        int pad = Math.max(1, Math.round(r.pad() * (float) scale));
        int chrome = 2 * pad + TreeMetrics.SCROLL_GUTTER;

        int body = Math.max(1, Math.round(r.naturalContentW() * (float) scale));
        int panelW = r.pinnedW() == AUTO
                ? Math.min(r.availW(), body + chrome)
                : Math.min(r.availW(), Math.max(1, r.pinnedW()));
        // Layout pixels of label room the panel actually leaves, rounded down so
        // a label can never be cut by a pixel of rounding.
        int contentW = Math.max(MIN_CONTENT_W,
                (int) Math.floor((panelW - chrome) / (float) scale));

        // The padding belongs to the natural height as much as to the measured
        // one: leaving it out sized an automatic panel a row short, so a plan
        // that fitted the screen still showed a scrollbar.
        int naturalH = r.headerH() + r.rowCount() * TreeMetrics.ROW_H + r.footerH() + 2 * r.pad();
        int panelH = r.pinnedH() == AUTO
                ? Math.min(r.availH(), Math.max(1, Math.round(naturalH * (float) scale)))
                : Math.min(r.availH(), Math.max(1, r.pinnedH()));

        int contentTop = pad + Math.round(r.headerH() * (float) scale);
        int layoutH = (int) Math.floor(panelH / scale);
        int contentBottom = Math.round((layoutH - r.pad() - r.footerH()) * (float) scale);
        int pitch = TreeMetrics.rowPitch(scale);
        // A panel too short for even one row still has to be able to draw it:
        // the window is grown by a pitch instead of ending up empty.
        if (contentBottom - contentTop < pitch) {
            contentBottom = contentTop + pitch;
        }

        int visibleRows = Math.max(1, (contentBottom - contentTop) / pitch);
        int maxScroll = Math.max(0, r.rowCount() - visibleRows);
        return new Plan(scale, pitch, panelW, panelH, pad, contentTop, contentBottom,
                contentW, body > panelW - chrome, visibleRows, maxScroll,
                clampScroll(requestedScroll, r.rowCount(), visibleRows));
    }

    /** Scroll kept inside what the panel can show; {@code rowCount} may be zero. */
    public static int clampScroll(int scroll, int rowCount, int visibleRows) {
        return Math.max(0, Math.min(scroll, Math.max(0, rowCount - visibleRows)));
    }

    /** Height of the scrollbar thumb: as tall as the visible part of the plan. */
    public static int thumbHeight(int trackH, int visibleRows, int rowCount) {
        if (rowCount <= 0) {
            return trackH;
        }
        return Math.max(TreeMetrics.MIN_THUMB_H, trackH * visibleRows / rowCount);
    }

    /** Top of the thumb for the current scroll. */
    public static int thumbTop(int trackTop, int trackH, int thumbH, int scroll, int maxScroll) {
        if (maxScroll <= 0) {
            return trackTop;
        }
        return trackTop + (trackH - thumbH) * scroll / maxScroll;
    }

    /**
     * Scroll offset that puts the middle of the thumb under {@code my}: a grabbed
     * thumb stays where the cursor is rather than jumping its own height away.
     */
    public static int scrollAt(int trackTop, int trackH, int thumbH, int maxScroll, int my) {
        if (maxScroll <= 0) {
            return 0;
        }
        int travel = Math.max(1, trackH - thumbH);
        return Math.max(0, Math.min(maxScroll,
                Math.round((my - trackTop - thumbH / 2f) * maxScroll / travel)));
    }

    /**
     * Scroll offset after the thumb was dragged {@code dy} pixels from where it was
     * grabbed. Relative to the grab rather than to the cursor, so taking hold of
     * the thumb never moves it: only moving the mouse does.
     *
     * @param grabScroll scroll offset at the moment the thumb was grabbed
     * @param dy         cursor movement since the grab, positive downwards
     */
    public static int scrollDragged(int grabScroll, int dy, int trackH, int thumbH, int maxScroll) {
        if (maxScroll <= 0) {
            return 0;
        }
        int travel = Math.max(1, trackH - thumbH);
        return Math.max(0, Math.min(maxScroll,
                grabScroll + Math.round((float) dy * maxScroll / travel)));
    }
}
