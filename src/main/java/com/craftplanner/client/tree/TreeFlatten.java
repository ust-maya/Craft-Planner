package com.craftplanner.client.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Flattens a craft tree into the rows a panel draws, honouring folded branches.
 *
 * <p>Both surfaces that show the plan — the compact HUD overlay and the
 * {@code CraftTreeScreen} window — walk the tree through this class, so a row's
 * depth, its last child and the number of rows a fold hides are computed one
 * single time and cannot drift apart between the two views.</p>
 *
 * <p>A folded branch is a view decision only: the rows it hides are still in
 * the plan, still counted in the shopping list and still coloured by
 * availability. The count of hidden rows is reported on the folding row so the
 * panel can say what it is not showing.</p>
 */
public final class TreeFlatten {

    private TreeFlatten() {
    }

    /** Rows in draw order for every root, skipping the children of folded branches. */
    public static <N> List<TreeRow<N>> flatten(List<N> roots, TreeSource<N> source, Set<String> folded) {
        return flatten(roots, source, folded::contains);
    }

    /**
     * Same, with the fold decided per path. A window whose branches start folded
     * keeps the paths the player <em>opened</em> and folds everything else —
     * {@code path -> !opened.contains(path)} — which a set of folded paths cannot
     * say about a branch nobody has looked at yet.
     */
    public static <N> List<TreeRow<N>> flatten(List<N> roots, TreeSource<N> source, Predicate<String> folded) {
        List<TreeRow<N>> rows = new ArrayList<>();
        for (int branch = 0; branch < roots.size(); branch++) {
            place(roots.get(branch), 0, null, branch, source, folded, rows);
        }
        return rows;
    }

    /**
     * @return the row index the node was drawn at, so the parent can find the
     *         last row of its children for the vertical guide
     */
    private static <N> int place(N node, int depth, String parentPath, int branch,
            TreeSource<N> source, Predicate<String> folded, List<TreeRow<N>> out) {
        int index = out.size();
        List<N> children = source.childrenOf(node);
        // Only a row with children can fold, so the key is built for exactly
        // the rows that need one.
        String path = children.isEmpty() ? null : source.pathOf(parentPath, node);
        TreeRow<N> row = source.rowFor(node, depth, branch, path, !children.isEmpty());
        out.add(row);

        if (path != null && folded.test(path)) {
            row.setSpan(index, countRows(children, source));
            return index;
        }
        int last = index;
        for (N child : children) {
            last = place(child, depth + 1, path, branch, source, folded, out);
        }
        // Without children the row points at itself: nothing to draw below it.
        row.setSpan(children.isEmpty() ? index : last, 0);
        return index;
    }

    /** Rows a folded branch would draw, children included. */
    private static <N> int countRows(List<N> children, TreeSource<N> source) {
        int total = 0;
        for (N child : children) {
            total += 1 + countRows(source.childrenOf(child), source);
        }
        return total;
    }

    /**
     * Index of the last row of {@code index}'s whole subtree.
     *
     * <p>Rows come out of {@link #flatten} in depth-first order, so a subtree is
     * one contiguous run: it ends at the last row deeper than its owner. The
     * goal bar of a plan is drawn over exactly that run, which is what makes a
     * branch followable down a panel that has scrolled its parent out of
     * view.</p>
     */
    public static int subtreeEnd(List<? extends TreeRow<?>> rows, int index) {
        int depth = rows.get(index).depth();
        int end = index;
        while (end + 1 < rows.size() && rows.get(end + 1).depth() > depth) {
            end++;
        }
        return end;
    }
}
