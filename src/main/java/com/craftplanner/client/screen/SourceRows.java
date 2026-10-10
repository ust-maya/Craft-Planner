package com.craftplanner.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Which rows of the recipe sources window are on screen: every mod, and the
 * machines under it only while the player has that mod opened.
 *
 * <p>A modpack lists dozens of mods and hundreds of machines, and a list that
 * shows all of them at once makes the one mod the player came for a long scroll
 * away. The rule is generic over the row type so it is tested without a game:
 * the window's own rows carry an {@code ItemStack}, which needs the registries.</p>
 */
final class SourceRows {

    private SourceRows() {
    }

    /**
     * The rows in their order, minus the machines of every mod not in {@code opened}.
     *
     * @param isMod  whether a row is a mod's own row, which is always shown
     * @param modOf  the mod a row belongs to; a mod row belongs to itself
     * @param opened the mods whose machines the player asked to see
     */
    static <T> List<T> visible(List<T> rows, Predicate<T> isMod, Function<T, String> modOf,
            Set<String> opened) {
        List<T> shown = new ArrayList<>(rows.size());
        for (T row : rows) {
            if (isMod.test(row) || opened.contains(modOf.apply(row))) {
                shown.add(row);
            }
        }
        return shown;
    }
}
