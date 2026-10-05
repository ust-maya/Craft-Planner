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
    public static final int MODAL_PANEL_BG = 0xF0141414;
    public static final int MODAL_PANEL_BORDER = 0xFF4A4A4A;
    /** Dark ring hugging the panel edge; reads as depth without any gradient. */
    public static final int MODAL_BORDER_OUTER = 0xFF000000;
    /** Full-screen dim behind a modal panel; same on every modal screen. */
    public static final int MODAL_SCRIM = 0x80000000;
    /** Header band inside a panel, separated from the body by {@link #ACCENT_LINE}. */
    public static final int MODAL_HEADER_BG = 0xF51C1C1C;
    /** Row background of the list screens. */
    public static final int MODAL_ROW_STRIPE = 0x12FFFFFF;
    public static final int MODAL_ROW_HOVER = 0x2EFFFFFF;
    /** Amber of the tree's fold markers, so "in use" reads the same everywhere. */
    public static final int MODAL_ROW_SELECTED = 0x40E0A030;

    /** Panel fill of the HUD overlay: semi-transparent on purpose. */
    public static final int HUD_PANEL_BG = 0xC0141414;
    public static final int HUD_PANEL_BORDER = 0x90787878;
    public static final int HUD_BORDER_OUTER = 0x90000000;
    public static final int HUD_HEADER_BG = 0xB31C1C1C;

    public static final int TEXT = 0xFFE0E0E0;
    public static final int MUTED = 0xFFA0A0A0;
    /** Muted in the HUD carries the panel's own translucency. */
    public static final int HUD_MUTED = 0x80C8C8C8;

    /** 1 px separators and underlines; the shared accent of every surface. */
    public static final int ACCENT_LINE = 0xFFFFC83D;
    public static final int HUD_ACCENT_LINE = 0xB3FFC83D;
    public static final int SECTION_DIVIDER = 0x2EFFFFFF;
    public static final int HUD_SECTION_DIVIDER = 0x22FFFFFF;
    /** Left-edge marker of the row under the cursor in the list screen. */
    public static final int SELECT_BAR = 0xFFFFC83D;

    /** Chip buttons: the flat-dark replacement of the vanilla sprite button. */
    public static final int CHIP_BG = 0xE62A2A2A;
    public static final int CHIP_BORDER = 0xE6555555;
    public static final int CHIP_HOVER_BG = 0xF53A3A3A;
    public static final int CHIP_HOVER_BORDER = 0xFFFFC83D;
    public static final int CHIP_DISABLED = 0x66202020;

    /** Scrollbar: quiet track under a thumb that brightens when hovered or grabbed. */
    public static final int SCROLL_TRACK = 0x22FFFFFF;
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
