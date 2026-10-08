package com.craftplanner.client.overlay;

import com.craftplanner.client.tree.TreeMetrics;
import com.craftplanner.client.tree.TreePanel;
import com.craftplanner.graph.RecipeNode;
import java.util.ArrayList;
import java.util.List;

/**
 * The hit map of the craft-plan HUD: every fold toggle, recipe badge and
 * scrollbar position the last drawn frame produced, and the answers to
 * "what is under this point".
 *
 * <p>Moved out of {@link TreeOverlayRenderer} without touching its logic.
 * A HUD cannot be clicked — the game grabs the mouse while no screen is open —
 * so all of this is only reachable from the layout editor ({@code O}), which
 * routes its clicks through here. The scroll offset itself stays in
 * {@link TreeOverlayRenderer}; these queries read and write it through the
 * two package-private accessors there.</p>
 */
public final class HudHitMap {

    /** Where the last drawn frame put the scrollbar, {@code null} when the plan fits. */
    private static ScrollTrack scrollTrack;
    /** True while the layout editor holds that scrollbar. */
    private static boolean scrollGrab;

    /**
     * The fold toggles and recipe badges of the last drawn frame, in screen
     * pixels.
     *
     * <p>A HUD cannot be clicked: while no screen is open the game grabs the
     * mouse and turns movement into camera yaw. The layout editor ({@code O}) is
     * a screen, it is transparent, and the panel stays visible behind it — so
     * that is the one place these markers are reachable. They are therefore
     * collected as they are drawn, exactly like {@link #scrollTrack}, and a
     * marker left over from a previous frame would keep answering a click for a
     * panel that is no longer on screen.</p>
     */
    private static final List<Hotspot> HOTSPOTS = new ArrayList<>();

    /**
     * One drawn control of the tree panel: which row it belongs to, where the tree
     * was drawn, and what it acts on. Exactly one of the two payloads is set.
     *
     * <p>The row is stored as an index into the plan rather than as a rectangle,
     * because the band is rebuilt against {@link TreeOverlayRenderer#treeScroll()}
     * when it is asked about, not against the scroll the frame was drawn with.
     * Input is handled before the frame is drawn, so a wheel notch and a click
     * landing in the same tick would otherwise fold the row the player was looking
     * at before the wheel, not the one under the cursor.</p>
     *
     * @param fold  branch key to toggle, {@code null} for a recipe badge
     * @param node  item whose recipes the badge would open, {@code null} for a fold
     * @param badge the badge's box in layout pixels, {@code null} for a fold
     */
    record Hotspot(int originX, int originY, double scale, String fold, RecipeNode node,
            int depth, int row, TreeMetrics.Rect badge) {

        boolean contains(int mx, int my) {
            return this.badge != null
                    ? TreeMetrics.toScreen(this.badge, this.scale, this.originX, this.originY)
                            .contains(mx, my)
                    : TreeMetrics.toggleHit(this.depth, this.row, TreeOverlayRenderer.treeScroll(),
                            this.scale, this.originX, this.originY).contains(mx, my);
        }
    }

    /**
     * The scrollbar in screen pixels, with the mapping from a cursor row back to a
     * scroll offset.
     */
    record ScrollTrack(int x, int y, int w, int h, int barH, int maxScroll) {

        boolean contains(int mx, int my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }

        /** Rows to scroll so the middle of the thumb lands under {@code my}. */
        int scrollAt(int my) {
            return TreePanel.scrollAt(y, h, barH, maxScroll, my);
        }
    }

    /** Forgets the markers and the scrollbar of the frame that was just drawn. */
    static void clear() {
        scrollTrack = null;
        HOTSPOTS.clear();
    }

    /** Records one drawn fold toggle or recipe badge. */
    static void add(Hotspot spot) {
        HOTSPOTS.add(spot);
    }

    /** Remembers where this frame put the scrollbar. */
    static void setTrack(ScrollTrack track) {
        scrollTrack = track;
    }

    /** True while the layout editor holds the scrollbar. */
    static boolean isGrabbed() {
        return scrollGrab;
    }

    /**
     * The branch folded by a click at this point, or {@code null}.
     *
     * <p>Callers must only ask while the layout editor is open: the panel behind
     * it is the only place a HUD row can be aimed at, which is why the markers
     * stopped being interactive in the overlay itself.</p>
     */
    public static String hitFold(int mx, int my) {
        for (Hotspot spot : HOTSPOTS) {
            if (spot.fold() != null && spot.contains(mx, my)) {
                return spot.fold();
            }
        }
        return null;
    }

    /** The item whose recipe picker a click on a badge should open, or {@code null}. */
    public static RecipeNode hitChoice(int mx, int my) {
        for (Hotspot spot : HOTSPOTS) {
            if (spot.node() != null && spot.contains(mx, my)) {
                return spot.node();
            }
        }
        return null;
    }

    /**
     * Takes a click on the scrollbar: the thumb jumps under the cursor and stays
     * there until the drag is released, the way a browser track behaves.
     *
     * @return true when the point was the scrollbar and the click is consumed
     */
    public static boolean grabScroll(int mx, int my) {
        ScrollTrack track = scrollTrack;
        if (track == null || !track.contains(mx, my)) {
            return false;
        }
        scrollGrab = true;
        TreeOverlayRenderer.setTreeScroll(track.scrollAt(my));
        return true;
    }

    /** Follows the cursor with the thumb while a grab is live. */
    public static void dragScroll(int my) {
        ScrollTrack track = scrollTrack;
        if (track != null) {
            TreeOverlayRenderer.setTreeScroll(track.scrollAt(my));
        }
    }

    /** Drops the scrollbar, on the release that ends the drag. */
    public static void releaseScroll() {
        scrollGrab = false;
    }

    /**
     * True while the scrollbar is held: the drag then moves the plan, not the
     * panel the editor is otherwise dragging.
     */
    public static boolean scrolling() {
        return scrollGrab;
    }

    private HudHitMap() {
    }
}
