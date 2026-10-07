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
     */
    default String trim(String text, int maxWidth) {
        if (width(text) <= maxWidth) {
            return text;
        }
        int ellipsis = width("…");
        if (maxWidth < ellipsis) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() > 0 && width(sb.toString()) + ellipsis > maxWidth) {
            sb.setLength(sb.length() - 1);
        }
        return sb + "…";
    }
}
