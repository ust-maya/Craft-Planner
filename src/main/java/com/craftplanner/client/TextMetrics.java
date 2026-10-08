package com.craftplanner.client;

/**
 * The only thing the planner's layouts need from the game: the width of a text
 * in GUI pixels.
 *
 * <p>Every measured surface ({@code RecipeListLayout}, the quantity popup and
 * the craft tree's {@code TreeMetrics}/{@code TreePanel}) takes its sizes
 * through this interface, in game {@code font::width}, which is what lets the
 * geometry be checked without launching Minecraft.</p>
 */
@FunctionalInterface
public interface TextMetrics {

    int width(String text);

    /**
     * Cuts text with an ellipsis so it fits {@code maxWidth}, and gives up
     * entirely when not even the ellipsis fits, rather than returning a string
     * that still overflows. The result is therefore never wider than
     * {@code maxWidth} — the contract every column and row label relies on.
     *
     * <p>The cut point is found by searching the prefix length rather than by
     * shortening one character at a time: this runs per label per frame, and the
     * old loop measured the whole shrinking string each step — a long row label
     * in a narrow column cost as many width calls as it had characters, so a
     * hundred-character label cost five thousand. Width grows with the prefix, so
     * the search can halve the remaining range every step.</p>
     */
    default String trim(String text, int maxWidth) {
        if (width(text) <= maxWidth) {
            return text;
        }
        int ellipsis = width("…");
        if (maxWidth < ellipsis) {
            return "";
        }
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (width(text.substring(0, mid)) + ellipsis <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + "…";
    }
}
