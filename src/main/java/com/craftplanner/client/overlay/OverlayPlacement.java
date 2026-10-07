package com.craftplanner.client.overlay;

import com.craftplanner.Config;

/**
 * Geometry of the overlay panel: where it is drawn, how large it is and how
 * dense its text is — all three editable in game with the mouse.
 *
 * <p>Every value is a GUI-scaled pixel, the same space the HUD is drawn in, so
 * a panel positioned at {@code (x, y)} stays where the player put it whatever
 * the window resolution is. {@link #AUTO} means "no preference": the renderer
 * sizes the panel from its own content and pins it to the top-left margin,
 * which is also what a fresh install shows.</p>
 *
 * <p>Editing works on a live copy. {@link #begin()} captures the rectangle
 * that is on screen right now, so grabbing the panel never makes it jump; the
 * copy is thrown away by {@link #resetAndSave()} and committed to
 * {@code overlay.positionX/positionY/width/height} (plus {@code overlay.scale}
 * for the text density) by {@link #applyAndSave()}, which is what makes the
 * layout survive a restart.</p>
 */
public final class OverlayPlacement {

    /** "Not pinned": the panel is placed and sized by the renderer. */
    public static final int AUTO = -1;

    private static final double SCALE_MIN = 0.1;
    private static final double SCALE_MAX = 1.0;
    /** One mouse wheel notch of the text-size control. */
    private static final double SCALE_STEP = 0.05;

    /** Smallest panel the resize grip may be dragged down to. */
    private static final int MIN_W = 64;
    private static final int MIN_H = 32;

    // --- committed geometry (mirrors the config file) ----------------------
    private static int x = AUTO;
    private static int y = AUTO;
    private static int w = AUTO;
    private static int h = AUTO;
    private static boolean loaded;

    // --- live geometry, only meaningful while editing ----------------------
    private static boolean editing;
    private static int liveX;
    private static int liveY;
    private static int liveW;
    private static int liveH;
    /** Filled by {@link #begin()} from the configured density. */
    private static double liveScale;

    /** The grab: cursor at press time plus the geometry it started from. */
    private static boolean moving;
    private static boolean sizing;
    private static int grabX;
    private static int grabY;
    private static int startX;
    private static int startY;
    private static int startW;
    private static int startH;

    // --- what is on screen, reported by the renderer every frame -----------
    private static int drawnX;
    private static int drawnY;
    private static int drawnW;
    private static int drawnH;

    /** Size of the resize grip square, in GUI-scaled pixels. */
    public static final int GRIP = 7;

    private OverlayPlacement() {
    }

    public static boolean editing() {
        return editing;
    }

    /** Pinned left/top, or {@link #AUTO} when the panel keeps the margin. */
    public static int x() {
        ensureLoaded();
        return editing ? liveX : x;
    }

    public static int y() {
        ensureLoaded();
        return editing ? liveY : y;
    }

    /** Pinned panel width/height, or {@link #AUTO} to size it from content. */
    public static int w() {
        ensureLoaded();
        return editing ? liveW : w;
    }

    public static int h() {
        ensureLoaded();
        return editing ? liveH : h;
    }

    /**
     * Text density to draw with — the live value while editing, the configured
     * one otherwise.
     */
    public static double density() {
        ensureLoaded();
        return editing ? liveScale : Config.overlayScale();
    }

    /**
     * Whether the player pinned the panel size. A pinned panel is allowed to
     * clip its content instead of falling back to another layout just to show
     * everything.
     */
    public static boolean pinned() {
        return w() != AUTO || h() != AUTO;
    }

    /** Remembers the rectangle the renderer drew, so hits and drags use it. */
    public static void reportDrawn(int left, int top, int panelW, int panelH) {
        drawnX = left;
        drawnY = top;
        drawnW = panelW;
        drawnH = panelH;
    }

    public static boolean overPanel(int mx, int my) {
        return drawnW > 0 && mx >= drawnX && mx <= drawnX + drawnW
                && my >= drawnY && my <= drawnY + drawnH;
    }

    /** Bottom-right corner of the panel: the resize grip. */
    public static boolean overGrip(int mx, int my) {
        return drawnW > 0 && mx >= drawnX + drawnW - GRIP - 2 && mx <= drawnX + drawnW + 2
                && my >= drawnY + drawnH - GRIP - 2 && my <= drawnY + drawnH + 2;
    }

    public static int gripLeft() {
        return drawnX + drawnW - GRIP;
    }

    public static int gripTop() {
        return drawnY + drawnH - GRIP;
    }

    /** Side of the grip square, so the renderer draws exactly the hit box. */
    public static int gripSize() {
        return GRIP;
    }

    /**
     * The panel the player is looking at, if any. Editing a layout that is not
     * drawn would mean dragging a rectangle that does not exist.
     */
    public static boolean hasPanel() {
        return drawnW > 0 && drawnH > 0;
    }

    /** Starts editing from exactly what is on screen, so the panel never jumps. */
    public static void begin() {
        if (!hasPanel()) {
            return;
        }
        liveX = drawnX;
        liveY = drawnY;
        liveW = drawnW;
        liveH = drawnH;
        liveScale = Config.overlayScale();
        editing = true;
    }

    public static void applyAndSave() {
        if (!editing) {
            return;
        }
        x = liveX;
        y = liveY;
        w = liveW;
        h = liveH;
        Config.saveOverlayGeometry(x, y, w, h, liveScale);
        editing = false;
        moving = false;
        sizing = false;
    }

    /** Right click: forget the layout and go back to the automatic one. */
    public static void resetAndSave() {
        x = AUTO;
        y = AUTO;
        w = AUTO;
        h = AUTO;
        Config.resetOverlayGeometry();
        editing = false;
        moving = false;
        sizing = false;
    }

    public static void startMove(int mx, int my) {
        startDrag(mx, my, true);
    }

    public static void startSize(int mx, int my) {
        startDrag(mx, my, false);
    }

    private static void startDrag(int mx, int my, boolean move) {
        moving = move;
        sizing = !move;
        grabX = mx;
        grabY = my;
        startX = liveX;
        startY = liveY;
        startW = liveW;
        startH = liveH;
    }

    public static void endDrag() {
        moving = false;
        sizing = false;
    }

    /** Applies the drag to the live rectangle; fed by every mouse-drag event. */
    public static void dragTo(int mx, int my, int screenW, int screenH) {
        if (!editing) {
            return;
        }
        if (moving) {
            liveX = Math.max(0, Math.min(startX + mx - grabX, screenW - liveW));
            liveY = Math.max(0, Math.min(startY + my - grabY, screenH - liveH));
        } else if (sizing) {
            liveW = clamp(startW + mx - grabX, MIN_W, screenW - liveX);
            liveH = clamp(startH + my - grabY, MIN_H, screenH - liveY);
        }
    }

    /** {@code value} kept inside {@code [min, max]}, even for a degenerate range. */
    private static int clamp(int value, int min, int max) {
        return max < min ? min : Math.max(min, Math.min(max, value));
    }

    /** Wheel while editing: the text density, one notch at a time. */
    public static void zoom(int notches) {
        double next = liveScale + notches * SCALE_STEP;
        liveScale = Math.max(SCALE_MIN, Math.min(SCALE_MAX, Math.round(next * 100) / 100.0));
    }

    /** Current live density, for the on-screen readout. */
    public static double liveDensity() {
        return liveScale;
    }

    /** Drops the cache so a hand-edited config file is picked up again. */
    public static void reloadFromConfig() {
        x = Config.overlayX();
        y = Config.overlayY();
        w = Config.overlayW();
        h = Config.overlayH();
        loaded = true;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            reloadFromConfig();
        }
    }
}
