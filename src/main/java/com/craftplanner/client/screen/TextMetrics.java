package com.craftplanner.client.screen;

/**
 * The only thing the window layouts need from the game: the width of a text in
 * GUI pixels.
 *
 * <p>Both {@link RecipeListLayout} and {@link QuantityPopupScreen.Layout} take
 * their measurements through this interface (in game it is {@code font::width}),
 * which is what lets the geometry be checked without launching Minecraft.</p>
 */
@FunctionalInterface
interface TextMetrics {

    int width(String text);
}
