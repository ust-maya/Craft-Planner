package com.craftplanner.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.craftplanner.client.overlay.NodeColors;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The one accessibility gate the overlay could not see it needed.
 *
 * <p>The HUD paints over gameplay, so every one of its colours is really a
 * composite: panel fill blended with whatever the world behind it is. A
 * translucent grey that reads fine on a screenshot of a dark cave fails badly
 * over snow, and the overlay shipped text colours that never reached AA — the
 * hotkey line that explains the panel, and the red that says "you are missing
 * this". This test fixes the composite math and checks the tokens against the
 * worst case a player can point the panel at.</p>
 *
 * <p>Modal surfaces are exempt from the world term: they sit behind a scrim and
 * over an near-opaque panel.</p>
 */
class UiPaletteContrastTest {

    /** A bright overcast sky: the lightest thing the HUD can be seen against. */
    private static final int BRIGHT_WORLD = 0xFFFAFAFA;
    /** A dark cave, the other end of the range. */
    private static final int DARK_WORLD = 0xFF0A0A0A;

    /** WCAG AA for normal text. */
    private static final double TEXT_AA = 4.5;
    /** WCAG AA for large text and for graphical objects (bars, marks, lines). */
    private static final double GRAPHICS_AA = 3.0;

    /** The HUD's own backgrounds, composited over the worlds that break them. */
    private static final List<String> HUD_BACKGROUNDS = List.of(
            "panel over bright world",
            "header band over bright world",
            "panel over dark world");

    @Test
    void hudLabelColoursHoldAaOnEveryHudBackground() {
        List<Ink> labels = List.of(
                new Ink("row text", UiPalette.TEXT),
                new Ink("muted hotkey line", UiPalette.HUD_MUTED),
                new Ink("goal", NodeColors.ROOT),
                new Ink("sub-assembly", NodeColors.INTERMEDIATE),
                new Ink("material covered", NodeColors.HAVE),
                new Ink("material partly there", NodeColors.PARTIAL),
                new Ink("material missing", NodeColors.MISSING),
                new Ink("unknown material", NodeColors.RAW),
                new Ink("cycle guard", NodeColors.CYCLE));
        for (Ink label : labels) {
            for (String background : HUD_BACKGROUNDS) {
                int bg = hudBackground(background);
                double ratio = contrast(label.argb(), bg);
                assertTrue(ratio >= TEXT_AA,
                        () -> label.name() + " on " + background + ": " + round(ratio) + ":1");
            }
        }
    }

    @Test
    void branchBarsAndStateMarksHoldTheGraphicsMinimum() {
        List<Integer> inks = List.of(
                NodeColors.branch(0), NodeColors.branch(1), NodeColors.branch(2),
                NodeColors.branch(3), NodeColors.branch(4), NodeColors.branch(5),
                UiPalette.MARK_FOLDED, UiPalette.MARK_NEUTRAL, UiPalette.MARK_CUT,
                UiPalette.ACCENT_LINE, UiPalette.SELECT_BAR);
        for (int ink : inks) {
            double ratio = contrast(ink, hudBackground("panel over bright world"));
            assertTrue(ratio >= GRAPHICS_AA, () -> hex(ink) + ": " + round(ratio) + ":1");
        }
    }

    @Test
    void theFoldedPlateKeepsItsOwnTextReadable() {
        // The amber plate a folded branch turns into carries a dark "+", and the
        // plate is itself translucent over a panel that is translucent over the
        // world — three layers, so the worst case is the bright one.
        int plate = composite(UiPalette.MARKER_FOLDED_BG,
                hudBackground("panel over bright world"));
        double ratio = contrast(UiPalette.MARKER_FOLDED_TEXT, plate);
        assertTrue(ratio >= TEXT_AA, () -> "folded plate: " + round(ratio) + ":1");
    }

    @Test
    void modalTextHoldsAaOnItsOwnPanel() {
        int bg = composite(UiPalette.MODAL_PANEL_BG, DARK_WORLD);
        for (int ink : List.of(UiPalette.TEXT, UiPalette.MUTED)) {
            double ratio = contrast(ink, bg);
            assertTrue(ratio >= TEXT_AA, () -> hex(ink) + ": " + round(ratio) + ":1");
        }
    }

    @Test
    void translucentColoursBlendTheWayTheRendererPutsThemDown() {
        // Guards the helpers the rest of this class relies on: an opaque black
        // stays black, a fully transparent colour paints nothing, half alpha is
        // the midpoint, and the byte order is the ARGB one the palette uses.
        assertEquals(255, red(0xFFFF7A66), "red is bits 16-23");
        assertEquals(122, green(0xFFFF7A66));
        assertEquals(102, blue(0xFFFF7A66));
        assertEquals(1.0, alpha(0xFF123456), 1e-9);
        assertEquals(0.0, alpha(0x00FFFFFF), 1e-9);
        assertEquals(0, red(composite(0xFF000000, 0xFFFFFFFF)), "opaque black over white");
        assertEquals(0, red(composite(0x00FFFFFF, 0xFF000000)), "no alpha, no paint");
        assertEquals(128, red(composite(0x80FFFFFF, 0xFF000000)), "half alpha over black");
    }

    /** A colour with the name the failure should report. */
    private record Ink(String name, int argb) {
    }

    private static int hudBackground(String which) {
        return switch (which) {
            case "panel over bright world" -> composite(UiPalette.HUD_PANEL_BG, BRIGHT_WORLD);
            case "header band over bright world" -> composite(UiPalette.HUD_HEADER_BG, BRIGHT_WORLD);
            default -> composite(UiPalette.HUD_PANEL_BG, DARK_WORLD);
        };
    }

    /** {@code top} painted over {@code bottom}, per channel, the way alpha blending does. */
    private static int composite(int top, int bottom) {
        double a = alpha(top);
        int r = (int) Math.round(a * red(top) + (1 - a) * red(bottom));
        int g = (int) Math.round(a * green(top) + (1 - a) * green(bottom));
        int b = (int) Math.round(a * blue(top) + (1 - a) * blue(bottom));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static double alpha(int argb) {
        return (argb >>> 24) / 255.0;
    }

    private static int red(int argb) {
        return (argb >>> 16) & 0xFF;
    }

    private static int green(int argb) {
        return (argb >>> 8) & 0xFF;
    }

    private static int blue(int argb) {
        return argb & 0xFF;
    }

    /**
     * WCAG contrast of an ink over a background. The ink is composited first:
     * a translucent grey is not the colour it looks like in a palette listing,
     * and a gate that ignored that would wave through exactly the HUD text that
     * fails once the world shows through it.
     */
    private static double contrast(int ink, int background) {
        double inkL = luminance(composite(ink, background));
        double backgroundL = luminance(background);
        double light = Math.max(inkL, backgroundL);
        double dark = Math.min(inkL, backgroundL);
        return (light + 0.05) / (dark + 0.05);
    }

    /** WCAG relative luminance of an opaque sRGB colour. */
    private static double luminance(int argb) {
        return 0.2126 * linear(red(argb))
                + 0.7152 * linear(green(argb))
                + 0.0722 * linear(blue(argb));
    }

    private static double linear(int channel) {
        double srgb = channel / 255.0;
        return srgb <= 0.04045 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
    }

    private static String hex(int argb) {
        return String.format("#%08X", argb);
    }

    private static String round(double value) {
        return String.format("%.2f", value);
    }
}
