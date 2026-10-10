package com.craftplanner.client.screen;

import com.craftplanner.client.TextMetrics;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the entries of the recipe window's ingredients column go: each item's
 * icon, then its name and amount, left to right, cut to the column's width.
 *
 * <p>The column used to be one line of text, and a name read alone — "Copper
 * Plate ×1" — is slower to match against the inventory than the icon the player
 * sees in it. When the room runs out the entry that does not fit is shortened
 * with an ellipsis; when not even its icon and an ellipsis fit, a lone ellipsis
 * takes its place, and when not even that fits the entry before it is shortened
 * instead — a row never ends on a whole name while more ingredients follow.
 * Pure arithmetic over {@link TextMetrics}, so it is tested without a game.</p>
 */
final class PickerInputs {

    /** One entry: whether an icon stands before it, and its words. */
    record Part(boolean icon, String text) {
    }

    /**
     * An entry as placed, offsets from the column's left edge.
     *
     * @param index which part it is, or {@code -1} for the lone ellipsis
     * @param x     where its icon goes (where its text goes when it has none)
     * @param textX where its text goes
     * @param text  its words, shortened when the column is too narrow
     */
    record Placed(int index, int x, int textX, String text) {
    }

    private PickerInputs() {
    }

    /** Room every entry takes uncut, gaps included. */
    static int width(TextMetrics metrics, List<Part> parts, int iconW, int gap) {
        int total = 0;
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            total += (i > 0 ? gap : 0) + (part.icon() ? iconW : 0) + metrics.width(part.text());
        }
        return total;
    }

    /** The entries that fit in {@code room} pixels; see the class comment for the cut. */
    static List<Placed> layout(TextMetrics metrics, List<Part> parts, int iconW, int gap, int room) {
        List<Placed> placed = new ArrayList<>(parts.size());
        int ellipsis = metrics.width("…");
        int x = 0;
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            int textX = x + (part.icon() ? iconW : 0);
            int width = metrics.width(part.text());
            if (textX + width <= room) {
                placed.add(new Placed(i, x, textX, part.text()));
                x = textX + width + gap;
                continue;
            }
            if (textX + ellipsis <= room) {
                placed.add(new Placed(i, x, textX, metrics.trim(part.text(), room - textX)));
            } else if (x + ellipsis <= room) {
                placed.add(new Placed(-1, x, x, "…"));
            } else if (!placed.isEmpty()) {
                Placed last = placed.remove(placed.size() - 1);
                String shorter = metrics.trim(last.text() + "…", room - last.textX());
                if (!shorter.isEmpty()) {
                    placed.add(new Placed(last.index(), last.x(), last.textX(), shorter));
                }
            }
            break;
        }
        return placed;
    }
}
