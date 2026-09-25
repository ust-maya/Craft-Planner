package com.craftplanner.client.screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry of the {@code K} window ({@link RecipeListScreen}).
 *
 * <p>The panel is measured, not assumed: every column is as wide as the widest
 * text it has to show (its header, the {@code 12/34} recipe counter, the
 * amount), the panel grows with them up to the window width and the item-name
 * column takes what is left. That is what keeps a long translation inside the
 * frame — a panel with fixed 320 px columns was sized for ASCII, so the Russian
 * headers ("Кол-во", "Удал.") ran under the buttons and past the panel border,
 * the buttons scrolled their labels and the footer hint lost two thirds of its
 * text to a single ellipsis.</p>
 *
 * <p>Whatever still does not fit is wrapped or cut <em>inside its own
 * rectangle</em>: the hint wraps onto up to {@link #MAX_HINT_LINES} lines, the
 * summary and the deficit move onto two rows instead of overlapping, every
 * header is trimmed to its column and every button trims its own label.</p>
 *
 * <p>The class holds no widgets and never touches the renderer — the only thing
 * it needs from the game is a text-width function ({@link TextMetrics}), so the
 * arithmetic can be checked without launching Minecraft.</p>
 */
final class RecipeListLayout {

    // --- panel -------------------------------------------------------------
    /** Inner padding of the panel, used on both sides and around the footer. */
    static final int PAD = 6;
    /** Gap between two columns of a row. */
    static final int GAP = 4;
    /** Gap between the three glyph buttons. */
    static final int COLUMN_GAP = 3;
    static final int ROW_HEIGHT = 18;
    static final int HEADER = 24;
    /** Height of every button in the panel. */
    static final int BUTTON_H = 14;
    /** Cap on visible rows; the window height can lower it further. */
    static final int MAX_VISIBLE_ROWS = 16;
    static final int SCROLL_GUTTER = 5;
    /** Left edge of the name column: the panel padding plus the item icon. */
    static final int NAME_LEFT = PAD + 20;
    /** Vanilla line height; the footer rows are spaced by it. */
    static final int LINE_H = 9;

    private static final int MIN_PANEL_WIDTH = 320;
    /** Free space kept between the panel and the window edges. */
    private static final int SCREEN_MARGIN = 16;
    private static final int MIN_NAME_W = 110;
    private static final int MAX_NAME_W = 200;
    /** Floors the right-hand columns may be squeezed to on a narrow window. */
    private static final int MIN_RECIPE_W = 32;
    private static final int MIN_AMOUNT_W = 28;
    private static final int MIN_REMOVE_W = 16;
    /** Width the squeeze tries to leave for the item names before shrinking columns. */
    private static final int MIN_NAME_SQUEEZE = 40;
    /** Room the {@code -} / {@code +} / {@code x} buttons need around their glyph. */
    private static final int GLYPH_PAD = 8;
    private static final int FOOTER_TEXT_Y = 3;
    private static final int FOOTER_BOTTOM_PAD = 9;
    /** Wrapped lines the hint may occupy before it is cut with an ellipsis. */
    static final int MAX_HINT_LINES = 3;
    /** Preferred width of the footer buttons. */
    private static final int MIN_FOOTER_BUTTON_W = 64;
    /** Below this the summary yields the row to the deficit instead of shrinking further. */
    private static final int MIN_SUMMARY_W = 60;

    // --- result ------------------------------------------------------------
    final int panelWidth;
    /** Width of the item-name column; whatever the fixed columns leave over. */
    final int nameW;
    final int recipeW;
    final int amountW;
    final int removeW;
    final int minusW;
    final int plusW;
    /** Row columns, as offsets from the panel's right edge (negative = to the left). */
    final int recipeX;
    final int amountLeft;
    final int amountRight;
    final int minusX;
    final int plusX;
    final int removeX;
    /** Footer buttons: width plus offset from the panel's left edge. */
    final int clearW;
    final int closeW;
    final int oresW;
    final int clearX;
    final int closeX;
    final int oresX;
    /** Footer rows, as offsets from the top of the footer block. */
    final int hintY;
    final int buttonsY;
    final int footerH;
    /** True when the deficit is wide enough to have earned a footer row of its own. */
    final boolean deficitRow;
    final List<String> hintLines;

    /**
     * @param metrics       text width function ({@code font::width} in game)
     * @param screenWidth   width of the window in GUI pixels
     * @param itemNames     names of all planned items, in row order (empty = empty list)
     * @param maxAmount     largest requested amount, used to size the amount column
     * @param maxOptions    largest number of recipes behind one row, sizes the "N/M" button
     * @param hasDeficit    whether a deficit line has to be placed in the footer
     */
    RecipeListLayout(TextMetrics metrics, int screenWidth, List<String> itemNames, long maxAmount, int maxOptions,
            String headerRecipe, String headerAmount, String headerRemove,
            String clearLabel, String closeLabel, String oresLabel, String hint, boolean hasDeficit) {
        // A column is as wide as the wider of its header and its content. The
        // header drives the width on purpose: it is a legend, and a legend cut
        // to two characters ("Уд…") is worse than a wider column.
        int minusW = Math.max(14, metrics.width("-") + GLYPH_PAD);
        int plusW = Math.max(14, metrics.width("+") + GLYPH_PAD);
        int recipeW = Math.max(MIN_RECIPE_W,
                Math.max(metrics.width(maxOptions + "/" + maxOptions), metrics.width(headerRecipe)) + GAP);
        int amountW = Math.max(MIN_AMOUNT_W,
                Math.max(metrics.width(Long.toString(maxAmount)), metrics.width(headerAmount)) + GAP);
        int removeW = Math.max(MIN_REMOVE_W,
                Math.max(metrics.width("x"), metrics.width(headerRemove)) + GAP);

        // Footer buttons are sized from their labels too — otherwise a long
        // "Очистить всё" would be scrolled around inside a 72 px button.
        int clearW = itemNames.isEmpty()
                ? MIN_FOOTER_BUTTON_W
                : Math.max(MIN_FOOTER_BUTTON_W, metrics.width(clearLabel) + PAD);
        int closeW = Math.max(MIN_FOOTER_BUTTON_W, metrics.width(closeLabel) + PAD);
        int oresW = Math.max(MIN_FOOTER_BUTTON_W, metrics.width(oresLabel) + PAD);
        int footerNeed = 2 * PAD + clearW + oresW + closeW + 2 * GAP;

        int widestName = 0;
        for (String name : itemNames) {
            widestName = Math.max(widestName, metrics.width(name));
        }
        int wantName = Math.min(MAX_NAME_W, Math.max(MIN_NAME_W, widestName + 2));

        int maxPanel = Math.max(120, screenWidth - SCREEN_MARGIN);
        int want = Math.max(Math.max(MIN_PANEL_WIDTH, NAME_LEFT + wantName + columnRun(recipeW, amountW, removeW,
                minusW, plusW)), footerNeed);
        int panelWidth = Math.min(maxPanel, want);

        // Squeeze the data columns round-robin until the name column can keep
        // MIN_NAME_SQUEEZE pixels. Only relevant when the window is narrower
        // than the panel wants to be; the floors keep the buttons usable and
        // everything is trimmed to its box afterwards, so nothing escapes.
        int over = columnRun(recipeW, amountW, removeW, minusW, plusW)
                - (panelWidth - NAME_LEFT - MIN_NAME_SQUEEZE);
        while (over > 0) {
            boolean changed = false;
            if (recipeW > MIN_RECIPE_W) {
                recipeW--;
                over--;
                changed = true;
            }
            if (over > 0 && amountW > MIN_AMOUNT_W) {
                amountW--;
                over--;
                changed = true;
            }
            if (over > 0 && removeW > MIN_REMOVE_W) {
                removeW--;
                over--;
                changed = true;
            }
            if (!changed) {
                break;
            }
        }
        // The name column takes everything the fixed columns leave over, so the
        // row always fills the panel exactly — whether the panel ended up wider
        // than the names need (the minimum width, a wide footer) or narrower
        // (a small window).
        int nameW = Math.max(0, panelWidth - NAME_LEFT - columnRun(recipeW, amountW, removeW, minusW, plusW));

        // Row columns, laid out from the right edge inwards.
        int removeX = -(SCROLL_GUTTER + removeW);
        int plusX = removeX - COLUMN_GAP - plusW;
        int minusX = plusX - COLUMN_GAP - minusW;
        int amountRight = minusX - GAP;
        int amountLeft = amountRight - amountW;
        int recipeX = amountLeft - GAP - recipeW;

        // Footer buttons: scaled down first if the panel is too narrow to hold
        // all three side by side, then the ore switch is centred in the gap.
        int room = panelWidth - 2 * PAD - 2 * GAP;
        int footerWant = clearW + oresW + closeW;
        if (footerWant > room && footerWant > 0) {
            double scale = Math.max(0.35, room / (double) footerWant);
            clearW = Math.max(MIN_FOOTER_BUTTON_W / 2, (int) (clearW * scale));
            oresW = Math.max(MIN_FOOTER_BUTTON_W / 2, (int) (oresW * scale));
            closeW = Math.max(MIN_FOOTER_BUTTON_W / 2, (int) (closeW * scale));
        }
        int clearX = PAD;
        int closeX = panelWidth - PAD - closeW;
        int free = closeX - GAP - (clearX + clearW);
        if (oresW > free - 2 * GAP) {
            oresW = Math.max(24, free - 2 * GAP);
        }
        int oresX = clearX + clearW + Math.max(GAP, (free - oresW) / 2);

        // Footer rows: summary, an optional deficit row, the wrapped hint, then
        // the buttons. With a single hint line this reproduces the previous
        // 3 / 15 / 27 offsets exactly.
        this.deficitRow = hasDeficit;
        this.hintLines = wrapText(metrics, hint, panelWidth - 2 * PAD, MAX_HINT_LINES);
        this.hintY = FOOTER_TEXT_Y + (hasDeficit ? LINE_H : 0) + LINE_H + 3;
        this.buttonsY = this.hintY + Math.max(1, this.hintLines.size()) * LINE_H + 3;
        this.footerH = this.buttonsY + BUTTON_H + FOOTER_BOTTOM_PAD;

        this.panelWidth = panelWidth;
        this.nameW = nameW;
        this.recipeW = recipeW;
        this.amountW = amountW;
        this.removeW = removeW;
        this.minusW = minusW;
        this.plusW = plusW;
        this.recipeX = recipeX;
        this.amountLeft = amountLeft;
        this.amountRight = amountRight;
        this.minusX = minusX;
        this.plusX = plusX;
        this.removeX = removeX;
        this.clearW = clearW;
        this.closeW = closeW;
        this.oresW = oresW;
        this.clearX = clearX;
        this.closeX = closeX;
        this.oresX = oresX;
    }

    /** Width of everything between the name column and the panel's right edge. */
    static int columnRun(int recipeW, int amountW, int removeW, int minusW, int plusW) {
        return GAP + recipeW + GAP + amountW + GAP + minusW + COLUMN_GAP + plusW + COLUMN_GAP + removeW
                + SCROLL_GUTTER;
    }

    /** Cuts the text with an ellipsis so it fits the given pixel width. */
    static String trimToWidth(TextMetrics metrics, String text, int maxWidth) {
        if (metrics.width(text) <= maxWidth) {
            return text;
        }
        int ellipsisWidth = metrics.width("…");
        if (maxWidth < ellipsisWidth) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() > 0 && metrics.width(sb.toString()) + ellipsisWidth > maxWidth) {
            sb.setLength(sb.length() - 1);
        }
        return sb + "…";
    }

    /**
     * Breaks the text into at most {@code maxLines} lines that each fit
     * {@code maxWidth}; the last line is cut with an ellipsis when the text is
     * longer. Word-based, so a line never starts with a stray fragment, and a
     * single word wider than the line is cut on its own line.
     */
    static List<String> wrapText(TextMetrics metrics, String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (maxWidth <= 0 || maxLines <= 0) {
            return lines;
        }
        String remaining = text.trim();
        while (!remaining.isEmpty() && lines.size() < maxLines) {
            if (lines.size() == maxLines - 1) {
                lines.add(trimToWidth(metrics, remaining, maxWidth));
                break;
            }
            String[] words = remaining.split(" ");
            StringBuilder line = new StringBuilder();
            int used = 0;
            while (used < words.length) {
                String candidate = line.length() == 0 ? words[used] : line + " " + words[used];
                if (metrics.width(candidate) > maxWidth) {
                    break;
                }
                line.setLength(0);
                line.append(candidate);
                used++;
            }
            if (line.length() == 0) {
                // One word wider than the whole line: cut it and move on.
                lines.add(trimToWidth(metrics, words[0], maxWidth));
                remaining = remaining.length() > words[0].length()
                        ? remaining.substring(words[0].length()).trim()
                        : "";
                continue;
            }
            lines.add(line.toString());
            remaining = remaining.length() > line.length()
                    ? remaining.substring(line.length()).trim()
                    : "";
        }
        return lines;
    }

    /** Rows that fit at once for a panel in a window {@code screenHeight} tall. */
    static int visibleRows(int screenHeight, int footerH, int rowCount) {
        int byHeight = Math.max(1, (screenHeight - HEADER - footerH) / ROW_HEIGHT);
        return Math.min(Math.min(MAX_VISIBLE_ROWS, rowCount), byHeight);
    }

    /** Total height of the panel: header, rows and footer. */
    static int panelHeight(int rows, int footerH) {
        return HEADER + rows * ROW_HEIGHT + footerH;
    }

    /** Width available for the summary when the deficit shares its row. */
    static int summaryRoom(int panelWidth, int deficitWidth) {
        return panelWidth - 2 * PAD - deficitWidth - 2 * GAP;
    }

    static boolean summarySharesRow(int panelWidth, int deficitWidth) {
        return summaryRoom(panelWidth, deficitWidth) >= MIN_SUMMARY_W;
    }
}
