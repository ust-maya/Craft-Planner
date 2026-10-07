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

    /**
     * Panel fill of the HUD overlay: semi-transparent on purpose, but no longer
     * transparent enough to wash out its own text — the composite of panel and
     * a bright sky is what the contrast of every HUD colour has to survive, and
     * at the old {@code 0xC0} it beat the muted greys below AA.
     */
    public static final int HUD_PANEL_BG = 0xD9141414;
    public static final int HUD_PANEL_BORDER = 0x90787878;
    public static final int HUD_BORDER_OUTER = 0x90000000;
    /**
     * Header band of the HUD panel. More opaque than the body on purpose: the
     * band lifts whatever bright world is behind it, and the title and the
     * colour legend are the two lines that have to stay legible on it — at the
     * old {@code 0xB3} the muted legend text fell to 3.1:1 over a bright sky.
     */
    public static final int HUD_HEADER_BG = 0xE61C1C1C;

    public static final int TEXT = 0xFFE0E0E0;
    public static final int MUTED = 0xFFA0A0A0;
    /**
     * Muted text of the HUD. Opaque and light: it sits on the panel <em>and</em>
     * whatever the world behind it is, and the hotkey line and the {@code +N}
     * marker are the only place the overlay explains itself.
     */
    public static final int HUD_MUTED = 0xFFB4B4B4;

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
    /** White on hover: the chip lifts, the label lifts with it. */
    public static final int CHIP_TEXT_HOVER = 0xFFFFFFFF;
    /** Keeps ~6:1 over the disabled composite, where the old grey fell under AA. */
    public static final int CHIP_TEXT_DISABLED = 0xFFB0B0B0;

    /** Scrollbar: quiet track under a thumb that brightens when hovered or grabbed. */
    public static final int SCROLL_TRACK = 0x22FFFFFF;
    public static final int SCROLL_BAR = 0x90A0A0A0;
    public static final int SCROLL_BAR_HOT = 0xF0E8E8E8;

    /** Folded chips keep the amber plate so a collapsed branch reads at a glance. */
    public static final int MARKER_FOLDED_BG = 0xC0E0A030;
    public static final int MARKER_FOLDED_TEXT = 0xFF1E1200;

    /**
     * Fold caret and cut mark of a tree row. Opaque and light: they are the only
     * thing that says "this row has children" on a HUD panel, and a translucent
     * grey figure a few pixels wide disappears into the panel behind it.
     */
    public static final int MARK_FOLDED = 0xFFFFC83D;
    public static final int MARK_NEUTRAL = 0xFFB0B0B0;
    /** Red, not a branch hue: "this branch stops here" must not read as a goal. */
    public static final int MARK_CUT = 0xFFFF7A66;

    /**
     * Tree window: the band that makes a planned goal read as a heading, the
     * neutral guide line a row hangs off its parent by, and the ring around the
     * row the keyboard is on.
     *
     * <p>The hierarchy is carried by background and geometry, not by font size:
     * Minecraft's bitmap font has no sizes to step between, and the overlay used
     * to draw its title at the same scale as its rows so that nothing could
     * "look larger than the tree it heads" — which is how a tree lost every cue
     * telling the player where one goal ended and the next began.</p>
     */
    public static final int TREE_ROOT_BAND = 0x29FFC83D;
    public static final int TREE_GUIDE = 0x59FFFFFF;
    /** Row under the keyboard cursor: the window's only always-visible focus ring. */
    public static final int TREE_FOCUS_BG = 0x26FFFFFF;
    public static final int TREE_FOCUS_BORDER = 0xFFFFC83D;

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
