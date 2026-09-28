package com.craftplanner.client.overlay;

import java.util.HashSet;
import java.util.Set;

/**
 * Which branches of the tree are folded up — view state only.
 *
 * <p>A branch is named by the chain of items that leads to it, not by its node:
 * the engine re-expands the graph in place on every recipe change and recreates
 * the child nodes, so an identity or a row index would forget what the player
 * folded as soon as they picked another recipe. The item chain stays the same
 * while the branch stays the same, which is exactly the lifetime a fold should
 * have.</p>
 *
 * <p>Folding hides rows in the panel and nothing else: the shopping list, the
 * totals and the availability colours are computed from the full tree, so a
 * folded branch is still planned and still counted.</p>
 */
public final class TreeCollapse {

    private static final Set<String> FOLDED = new HashSet<>();

    private TreeCollapse() {
    }

    public static boolean isFolded(String path) {
        return FOLDED.contains(path);
    }

    public static void toggle(String path) {
        if (!FOLDED.remove(path)) {
            FOLDED.add(path);
        }
    }
}
