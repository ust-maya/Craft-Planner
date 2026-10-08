package com.craftplanner.client;

import com.craftplanner.client.overlay.NodeColors;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The colour key of the tree rows, defined once for every surface that draws one.
 *
 * <p>A plan's rows encode their meaning in colour — what the inventory covers,
 * what still has to be made, where the branch stops — and the key that names
 * those colours has to be where the player can look it up: in the HUD, in the
 * node-link overlay, and in the tree window. Three call sites each wrote the
 * list out by itself, so a colour added in one of them stayed unexplained in the
 * other two, and the HUD's copy had quietly lost the amber of "partly there"
 * while the window still offered it.</p>
 *
 * <p>Placement stays with the caller, because the three surfaces genuinely
 * differ: the HUD right-aligns one line against the title inside a scaled pose,
 * the node-link view wraps under the title and rings its swatches, the window
 * flows the key left to right through its footer. What all three take from here
 * is the same colours, in the same order, measured the same way — which is the
 * part that was allowed to drift.</p>
 */
public final class UiLegend {

    /** Side of a colour swatch, in layout pixels. */
    public static final int SQUARE = 6;
    /** Air between the swatch and its word. */
    public static final int TEXT_GAP = 2;
    /** Air between one entry and the next. */
    public static final int ENTRY_GAP = 6;

    /**
     * One entry per colour a row can be drawn in, in the order the key reads
     * them: the three states of the inventory first, then the three kinds of row
     * that say something about the plan rather than the bag.
     *
     * <p>The loop colour is left out on purpose. It is the only one that marks the
     * shape of the plan instead of its state, the row carrying it already names
     * itself when the cursor rests on it, and no single word in either shipped
     * language says "this branch was stopped because the item is already being
     * made above it".</p>
     */
    private static final String[] KEYS = {
        "overlay.craftplanner.legend.have",
        "overlay.craftplanner.legend.partial",
        "overlay.craftplanner.legend.missing",
        "overlay.craftplanner.legend.intermediate",
        "overlay.craftplanner.legend.raw",
        "overlay.craftplanner.legend.root",
    };

    private static final int[] COLORS = {
        NodeColors.HAVE, NodeColors.PARTIAL, NodeColors.MISSING,
        NodeColors.INTERMEDIATE, NodeColors.RAW, NodeColors.ROOT,
    };

    /**
     * The measured key, rebuilt only when the font or the language changes: both
     * belong in the key, because the widths come from one font instance and the
     * words are read in whatever language the player picked.
     */
    private static List<Entry> entries;
    private static Font entriesFont;
    private static String entriesLanguage;

    private UiLegend() {
    }

    /**
     * One entry: its colour, its word, and the room it takes including the air
     * after it — so a line of entries is a sum of these, with no extra term.
     */
    public record Entry(int color, Component label, int width) {
    }

    /** The entries in draw order, measured with {@code font}. */
    public static List<Entry> entries(Font font) {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        if (entries != null && entriesFont == font && language.equals(entriesLanguage)) {
            return entries;
        }
        List<Entry> built = new ArrayList<>(KEYS.length);
        for (int i = 0; i < KEYS.length; i++) {
            Component label = Component.translatable(KEYS[i]);
            built.add(new Entry(COLORS[i], label,
                    SQUARE + TEXT_GAP + font.width(label) + ENTRY_GAP));
        }
        entries = List.copyOf(built);
        entriesFont = font;
        entriesLanguage = language;
        return entries;
    }

    /** Room the whole key takes on one line, including the air after its last entry. */
    public static int totalWidth(Font font) {
        return rowWidth(font, 0, entries(font).size());
    }

    /** Room the entries {@code from} (inclusive) to {@code to} (exclusive) take. */
    public static int rowWidth(Font font, int from, int to) {
        List<Entry> shown = entries(font);
        int total = 0;
        for (int i = from; i < to; i++) {
            total += shown.get(i).width();
        }
        return total;
    }

    /**
     * How many entries fit in a line of {@code room} pixels, starting from the
     * first. The trailing air of the last entry does not count: it is the gap to
     * a next entry, and there is none.
     */
    public static int split(Font font, int room) {
        List<Entry> shown = entries(font);
        int used = 0;
        int fitted = 0;
        for (int i = 0; i < shown.size(); i++) {
            int next = used + shown.get(i).width() - ENTRY_GAP;
            if (next > room) {
                break;
            }
            used = next + ENTRY_GAP;
            fitted++;
        }
        return fitted;
    }

    /**
     * Draws the key from {@code (x, y)} left to right onto at most two lines, and
     * gives up on the remainder when even those run out: half a key is worse than
     * none, because the player would read the missing colours as ones they
     * already know.
     *
     * <p>{@code y} is the line of the text; the swatch hangs two pixels below its
     * top, which is where every surface that has ever drawn this key put it.</p>
     */
    public static void draw(GuiGraphics graphics, Font font, int x, int y, int maxWidth) {
        draw(graphics, font, x, y, maxWidth, font.lineHeight + 2);
    }

    /**
     * The same with the distance between two lines asked for rather than guessed:
     * an overlay that draws inside a scaled pose has its own line pitch, and the
     * font's own height is not it.
     */
    public static void draw(GuiGraphics graphics, Font font, int x, int y, int maxWidth,
            int lineStep) {
        int line = 0;
        int used = 0;
        for (Entry entry : entries(font)) {
            if (used + entry.width() > maxWidth && used > 0) {
                line++;
                used = 0;
            }
            if (line > 1) {
                return;
            }
            int entryY = y + line * lineStep;
            graphics.fill(x + used, entryY + 2, x + used + SQUARE, entryY + 2 + SQUARE,
                    entry.color());
            graphics.drawString(font, entry.label(), x + used + SQUARE + TEXT_GAP, entryY,
                    NodeColors.TEXT, false);
            used += entry.width();
        }
    }

    /**
     * Part of the key on one line, ending flush with {@code rightEdge} — what the
     * two overlays want, since the title owns the left of that same line. As in
     * {@link #draw}, {@code y} is the line of the text and the swatch hangs two
     * pixels below its top.
     *
     * @param ring draws a hairline around each swatch; the node-link view sits its
     *             key on a brighter plate than the list does, and the outline is
     *             what keeps a grey swatch from reading as a hole in the panel
     */
    public static void drawRow(GuiGraphics graphics, Font font, int from, int to, int rightEdge,
            int y, boolean ring) {
        List<Entry> shown = entries(font);
        int x = rightEdge - rowWidth(font, from, to);
        for (int i = from; i < to; i++) {
            Entry entry = shown.get(i);
            graphics.fill(x, y + 2, x + SQUARE, y + 2 + SQUARE, entry.color());
            if (ring) {
                UiShapes.ring(graphics, x, y + 2, SQUARE, SQUARE, UiPalette.CHIP_BORDER);
            }
            graphics.drawString(font, entry.label(), x + SQUARE + TEXT_GAP, y,
                    NodeColors.TEXT, false);
            x += entry.width();
        }
    }
}
