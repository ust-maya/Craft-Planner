package com.craftplanner.client.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.craftplanner.client.TextMetrics;
import org.junit.jupiter.api.Test;

/**
 * Geometry of a tree row. Each test names the overlay behaviour it is meant to
 * make impossible again.
 */
class TreeMetricsTest {

    /** Every density the config and the layout editor can produce. */
    private static final double[] ALL_DENSITIES = {
            0.05, 0.1, 0.15, 0.23, 0.3, 0.45, 0.5, 0.65, 0.7, 0.8, 0.9, 1.0
    };

    @Test
    void glyphsStaySixPixelsTallAtEveryDensity() {
        // The bitmap font is nine pixels tall; the old default of 0.23 rendered
        // it at two, which is the single biggest reason the panel read as noise.
        for (double density : ALL_DENSITIES) {
            double scale = TreeMetrics.readableScale(density);
            assertTrue(Math.round(9 * scale) >= 6,
                    () -> "density " + density + " leaves a " + Math.round(9 * scale) + "px glyph");
        }
        assertEquals(1.0, TreeMetrics.readableScale(1.0), 1e-9);
    }

    @Test
    void everyRowIsTallEnoughForItsOwnControlPlate() {
        // ROOMY_PITCH used to decide per frame whether a row could carry its "-"
        // or "2/3": at the default pitch it never could, so the controls were
        // geometry that meant nothing. With the readable floor a plate always fits.
        for (double density : ALL_DENSITIES) {
            int pitch = TreeMetrics.rowPitch(TreeMetrics.readableScale(density));
            assertTrue(pitch >= TreeMetrics.TOGGLE_H,
                    () -> "pitch " + pitch + " cannot hold an 11px plate");
        }
    }

    @Test
    void columnsStepByExactlyOneIndentInLayoutPixels() {
        for (int depth = 0; depth < 12; depth++) {
            assertEquals(TreeMetrics.INDENT,
                    TreeMetrics.iconX(depth + 1) - TreeMetrics.iconX(depth));
            assertEquals(TreeMetrics.INDENT,
                    TreeMetrics.labelX(depth + 1) - TreeMetrics.labelX(depth));
        }
    }

    @Test
    void aRowBandResolvesToExactlyOneRow() {
        // hitFold/hitChoice picked the nearest row centre within 11px, covering
        // six rows at the old pitch; bands cannot.
        int pitch = TreeMetrics.rowPitch(TreeMetrics.readableScale(0.65));
        int firstVisible = 4;
        int contentTop = 0;
        int visible = 5;
        int contentBottom = contentTop + visible * pitch;

        for (int my = contentTop; my < contentBottom; my++) {
            int row = TreeMetrics.rowAt(contentTop, contentBottom, firstVisible, 0.65, my);
            int expected = firstVisible + (my - contentTop) / pitch;
            assertEquals(expected, row, "cursor at y=" + my);
        }
        assertEquals(-1, TreeMetrics.rowAt(contentTop, contentBottom, firstVisible, 0.65, contentBottom));
        assertEquals(-1, TreeMetrics.rowAt(contentTop, contentBottom, firstVisible, 0.65, contentTop - 1));
    }

    @Test
    void foldToggleHitsOfConsecutiveRowsNeverOverlap() {
        for (double density : ALL_DENSITIES) {
            double scale = TreeMetrics.readableScale(density);
            int pitch = TreeMetrics.rowPitch(scale);
            for (int depth = 0; depth < 6; depth++) {
                TreeMetrics.Rect a = TreeMetrics.toggleHit(depth, 0, 0, scale, 10, 20);
                TreeMetrics.Rect b = TreeMetrics.toggleHit(depth, 1, 0, scale, 10, 20);
                assertEquals(a.y() + a.h(), b.y(), "bands must be contiguous");
                int lastPixelOfA = a.y() + a.h() - 1;
                assertTrue(a.contains(a.x(), lastPixelOfA), "depth " + depth + ": own band misses itself");
                assertFalse(b.contains(b.x(), lastPixelOfA),
                        "density " + density + ": row " + depth + " steals the row above");
                assertEquals(pitch, a.h());
                assertTrue(a.w() >= TreeMetrics.MIN_TOGGLE_HIT_W);
                assertTrue(a.x() >= 10, "toggle must stay inside the panel");
                assertEquals(a.x(), b.x(), depth + ": same column for every row");
            }
        }
    }

    @Test
    void theFoldToggleHitBoxCoversEveryPixelOfThePlateItDraws() {
        // The reason "collapse does nothing": the caret was drawn from
        // toggleRect while clicks were tested against a band computed from a
        // different origin, so the aimable area sat beside the visible mark
        // instead of under it. Whichever density, scaling the same plate and the
        // same band has to leave the mark inside the band.
        for (double density : ALL_DENSITIES) {
            double scale = TreeMetrics.readableScale(density);
            int originX = 12;
            int originY = 40;
            for (int depth = 0; depth < 8; depth++) {
                for (int row = 0; row < 4; row++) {
                    TreeMetrics.Rect plate = TreeMetrics.toScreen(
                            TreeMetrics.toggleRect(depth, row, 0), scale, originX, originY);
                    TreeMetrics.Rect hit = TreeMetrics.toggleHit(depth, row, 0, scale, originX, originY);
                    assertTrue(hit.contains(plate.x(), plate.y()),
                            "density " + density + " depth " + depth + ": band misses the plate's first pixel");
                    assertTrue(hit.contains(plate.x() + plate.w() - 1, plate.y() + plate.h() - 1),
                            "density " + density + " depth " + depth + ": band misses the plate's last pixel");
                }
            }
        }
    }

    @Test
    void theFoldToggleHitBoxStartsWhereThePlateDoes() {
        // A band wider than its plate is fine — a taller row band is easier to
        // aim at — but one that starts to the left of the mark would swallow the
        // icon column and make a label click fold the row above it.
        double scale = TreeMetrics.readableScale(0.65);
        for (int depth = 0; depth < 8; depth++) {
            TreeMetrics.Rect plate = TreeMetrics.toScreen(
                    TreeMetrics.toggleRect(depth, 0, 0), scale, 10, 20);
            TreeMetrics.Rect hit = TreeMetrics.toggleHit(depth, 0, 0, scale, 10, 20);
            assertEquals(plate.x(), hit.x(), "depth " + depth);
        }
    }

    @Test
    void platesOfFoldToggleAndIconNeverLandOnEachOther() {
        // The control rail placed the badge plate right-aligned while measuring
        // its column as centred, so the hovered row's two plates overlapped.
        for (int depth = 0; depth < 10; depth++) {
            TreeMetrics.Rect toggle = TreeMetrics.toggleRect(depth, depth, 0);
            TreeMetrics.Rect icon = TreeMetrics.iconRect(depth, depth, 0);
            assertTrue(toggle.x() + toggle.w() <= icon.x(),
                    "depth " + depth + ": toggle " + toggle + " reaches into " + icon);
        }
    }

    @Test
    void labelRoomNeverGoesNegativeAndNeverLeaksPastThePanel() {
        for (int contentW = 60; contentW < 600; contentW += 7) {
            for (int depth = 0; depth < 12; depth++) {
                int room = TreeMetrics.labelRoom(contentW, depth);
                assertTrue(room >= 0, "contentW=" + contentW + " depth=" + depth);
                if (contentW >= TreeMetrics.labelX(depth)) {
                    // A row takes exactly the room the panel leaves it: the
                    // label is trimmed to that, never wider, never one pixel
                    // short of the column the panel was sized for.
                    assertEquals(contentW, TreeMetrics.rowWidth(depth, room),
                            "contentW=" + contentW + " depth=" + depth);
                } else {
                    // A panel narrower than the row's own indent has no room for
                    // a label at all; the renderer shows the icon and trims the
                    // text to nothing rather than letting the row escape the frame.
                    assertEquals(0, room, "contentW=" + contentW + " depth=" + depth);
                }
            }
        }
    }

    @Test
    void toScreenNeverCollapsesAControlToZeroPixels() {
        TreeMetrics.Rect plate = TreeMetrics.toggleRect(3, 7, 2);
        TreeMetrics.Rect screen = TreeMetrics.toScreen(plate, 0.65, 4, 60);
        assertTrue(screen.w() >= 1 && screen.h() >= 1);
        assertTrue(screen.contains(screen.x(), screen.y()));
    }

    @Test
    void trimNeverLeaksPastItsBox() {
        TextMetrics metrics = text -> text.length() * 2;
        String label = "Deepslate Redstone Ore x 128";
        assertEquals(label, metrics.trim(label, 1_000));
        for (int maxWidth = 0; maxWidth < label.length() * 2; maxWidth++) {
            String trimmed = metrics.trim(label, maxWidth);
            assertTrue(metrics.width(trimmed) <= maxWidth,
                    maxWidth + "px got \"" + trimmed + "\" (" + metrics.width(trimmed) + "px)");
        }
        assertEquals("", metrics.trim(label, 1));
    }
}
