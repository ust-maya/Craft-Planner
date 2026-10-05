package com.craftplanner.client;

/**
 * Design tokens for every Craft Planner surface.
 *
 * <p>The HUD overlay stays deliberately more transparent than the modal
 * panels because it paints over gameplay, so the tokens come in two groups —
 * {@code HUD_*} and {@code MODAL_*} — but both share the same text, muted and
 * amber accent values: "selected" and "in use" must read identically whether
 * the player is looking at the overlay, the K list or the picker.</p>
 */
public final class UiPalette {

    /** Panel fill of the modal screens: near-opaque so the world never bleeds through. */
    public static final int MODAL_PANEL_BG = 0xF0101010;
    public static final int MODAL_PANEL_BORDER = 0xFF404040;
    /** Full-screen dim behind a modal panel; same on every modal screen. */
    public static final int MODAL_SCRIM = 0x80000000;
    /** Row background of the list screens. */
    public static final int MODAL_ROW_STRIPE = 0x20FFFFFF;
    public static final int MODAL_ROW_HOVER = 0x28FFFFFF;
    /** Amber of the tree's fold markers, so "in use" reads the same everywhere. */
    public static final int MODAL_ROW_SELECTED = 0x40E0A030;

    /** Panel fill of the HUD overlay: semi-transparent on purpose. */
    public static final int HUD_PANEL_BG = 0xC0101010;
    public static final int HUD_PANEL_BORDER = 0x90606060;

    public static final int TEXT = 0xFFE0E0E0;
    public static final int MUTED = 0xFFA0A0A0;
    /** Muted in the HUD carries the panel's own translucency. */
    public static final int HUD_MUTED = 0x80C8C8C8;

    /** Scrollbar thumb: quiet until hovered or grabbed. */
    public static final int SCROLL_BAR = 0x90A0A0A0;
    public static final int SCROLL_BAR_HOT = 0xF0E8E8E8;

    /** Fold marker box: subtle when open, amber when folded. */
    public static final int MARKER_BG = 0x50FFFFFF;
    public static final int MARKER_FOLDED_BG = 0xC0E0A030;
    public static final int MARKER_FOLDED_TEXT = 0xFF1E1200;

    /** Chrome of the O layout editor, drawn over the overlay. */
    public static final int EDIT_BORDER = 0xC0E0A030;
    public static final int EDIT_GRIP = 0xE0E0A030;
    public static final int EDIT_GRIP_HOVER = 0xE0FFE070;
    public static final int EDIT_TEXT = 0xFFEEEEEE;

    /** Red flash signalling a value was clamped (borders, stepper buttons). */
    public static final int CLAMP_FLASH = 0xFFFF5555;

    private UiPalette() {
    }
}
