package com.craftplanner.client.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Panel sizing and scroll mapping.
 *
 * <p>The overlay previously had four interacting rules — configured density,
 * automatic shrink-to-fit down to eight percent, a pinned panel that clips, and
 * a node-link view that falls back to the list layout — and only the first and
 * third of them were the player's choice. These tests pin the surviving two.</p>
 */
class TreePanelTest {

    private static TreePanel.Request auto(int availW, int availH, double density, int contentW, int rows) {
        return new TreePanel.Request(availW, availH, density, TreePanel.AUTO, TreePanel.AUTO,
                contentW, 12, 0, rows, 5);
    }

    @Test
    void aPanelThatDoesNotFitClipsInsteadOfShrinkingTheText() {
        TreePanel.Plan plan = TreePanel.plan(auto(200, 400, 0.8, 1_200, 40), 0);
        assertEquals(0.8, plan.scale(), 1e-9);
        assertTrue(plan.clipped(), "the widest row cannot be shown whole");
        assertEquals(200, plan.panelW());
        // The label room is what the renderer trims to: never negative, never
        // wider than the panel.
        assertTrue(plan.contentW() >= TreePanel.MIN_CONTENT_W);
    }

    @Test
    void aPanelThatFitsIsSizedFromItsContent() {
        TreePanel.Plan plan = TreePanel.plan(auto(1_000, 1_000, 1.0, 300, 10), 0);
        assertEquals(1.0, plan.scale(), 1e-9);
        assertEquals(300 + 2 * 5 + TreeMetrics.SCROLL_GUTTER, plan.panelW());
        assertTrue(!plan.clipped());
    }

    @Test
    void densityAboveFullSizeIsCappedAndBelowTheFloorIsRaised() {
        assertEquals(TreeMetrics.MAX_SCALE,
                TreePanel.plan(auto(1_000, 1_000, 1.4, 300, 10), 0).scale(), 1e-9);
        assertEquals(TreeMetrics.MIN_READABLE_SCALE,
                TreePanel.plan(auto(1_000, 1_000, 0.1, 300, 10), 0).scale(), 1e-9);
    }

    @Test
    void aPinnedPanelNeverLosesItsLabelColumnOrItsRows() {
        // 64x32 was the smallest the resize grip could drag the panel to, and at
        // that size the old renderer drew a panel with no visible row at all.
        TreePanel.Request pinned = new TreePanel.Request(1_000, 1_000, 0.65, 70, 20,
                300, 12, 0, 30, 5);
        TreePanel.Plan plan = TreePanel.plan(pinned, 0);
        assertEquals(70, plan.panelW());
        assertTrue(plan.contentW() >= TreePanel.MIN_CONTENT_W);
        assertTrue(plan.visibleRows() >= 1);
        assertTrue(plan.contentHeight() >= plan.pitch());
    }

    @Test
    void visibleRowsAndMaxScrollAgreeWithTheRowCount() {
        TreePanel.Plan plan = TreePanel.plan(auto(1_000, 200, 0.65, 300, 40), 0);
        int trackH = plan.contentHeight();
        assertEquals(trackH / plan.pitch(), plan.visibleRows());
        assertEquals(40 - plan.visibleRows(), plan.maxScroll());
        // Asking for more than exists comes back clamped, and a negative scroll
        // comes back as the top of the plan.
        assertEquals(plan.maxScroll(), TreePanel.plan(auto(1_000, 200, 0.65, 300, 40), 9_999).scroll());
        assertEquals(0, TreePanel.plan(auto(1_000, 200, 0.65, 300, 40), -500).scroll());
    }

    @Test
    void aPlanThatFitsItsOwnPanelHasNoScrollAtAll() {
        // Sizing left the padding out of the natural height, so an automatic
        // panel was always one row short of the rows it held and grew a
        // scrollbar over a plan that was fully visible.
        TreePanel.Plan plan = TreePanel.plan(auto(1_000, 1_000, 1.0, 300, 3), 7);
        assertEquals(3, plan.visibleRows());
        assertEquals(0, plan.maxScroll());
        assertEquals(0, plan.scroll());
        assertEquals(100, TreePanel.thumbHeight(100, plan.visibleRows(), 3));
    }

    @Test
    void theThumbAlwaysStaysInsideItsTrack() {
        int trackTop = 30;
        int trackH = 180;
        int rows = 137;
        int visible = 12;
        int max = rows - visible;
        int thumb = TreePanel.thumbHeight(trackH, visible, rows);
        assertTrue(thumb >= TreeMetrics.MIN_THUMB_H);
        for (int scroll = 0; scroll <= max; scroll++) {
            int top = TreePanel.thumbTop(trackTop, trackH, thumb, scroll, max);
            assertTrue(top >= trackTop, "thumb left the track at scroll " + scroll);
            assertTrue(top + thumb <= trackTop + trackH, "thumb passed the bottom at " + scroll);
        }
        assertEquals(trackTop, TreePanel.thumbTop(trackTop, trackH, thumb, 0, max));
        assertEquals(trackTop + trackH - thumb,
                TreePanel.thumbTop(trackTop, trackH, thumb, max, max));
    }

    @Test
    void grabbingTheThumbKeepsItUnderTheCursor() {
        int trackTop = 0;
        int trackH = 240;
        int rows = 90;
        int visible = 10;
        int max = rows - visible;
        int thumb = TreePanel.thumbHeight(trackH, visible, rows);
        for (int scroll = 0; scroll <= max; scroll++) {
            int center = TreePanel.thumbTop(trackTop, trackH, thumb, scroll, max) + thumb / 2;
            int back = TreePanel.scrollAt(trackTop, trackH, thumb, max, center);
            assertTrue(Math.abs(back - scroll) <= 1,
                    "scroll " + scroll + " came back as " + back);
        }
        assertEquals(0, TreePanel.scrollAt(trackTop, trackH, thumb, 0, 120));
    }
}
