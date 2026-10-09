package com.craftplanner.client.tree;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What the fold buttons of a tree window change: everything, or one level.
 *
 * <p>A level is a row's depth. "One level more" opens the shallowest folded
 * rows on screen and keeps their own branches closed, so exactly one new level
 * appears; "one level less" closes the deepest open rows on screen. Both work
 * on the whole tree as a list of paths and depths in draw order — every row,
 * nothing folded — so the three windows that carry the buttons, each keeping
 * its folds its own way, share one tested answer.</p>
 */
public final class FoldSteps {

    /** One row of the whole tree: its fold key ({@code null} for a leaf) and its depth. */
    public record Entry(String path, int depth) {
    }

    /** Paths to fold and paths to open, applied together. */
    public record Change(Set<String> fold, Set<String> unfold) {

        public static final Change NONE = new Change(Set.of(), Set.of());

        public boolean isEmpty() {
            return fold.isEmpty() && unfold.isEmpty();
        }
    }

    private FoldSteps() {
    }

    /** The rows of a fully open flattening, as entries. */
    public static <N> List<Entry> entriesOf(List<TreeRow<N>> rows) {
        List<Entry> entries = new ArrayList<>(rows.size());
        for (TreeRow<N> row : rows) {
            entries.add(new Entry(row.path(), row.depth()));
        }
        return entries;
    }

    /** Every branch of the tree, shown or not. */
    public static Set<String> allPaths(List<Entry> entries) {
        Set<String> paths = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (entry.path() != null) {
                paths.add(entry.path());
            }
        }
        return paths;
    }

    /** Opens the shallowest folded level on screen and nothing below it. */
    public static Change expandLevel(List<Entry> entries, Predicate<String> folded) {
        List<Integer> shown = visible(entries, folded);
        int level = Integer.MAX_VALUE;
        for (int i : shown) {
            Entry entry = entries.get(i);
            if (entry.path() != null && folded.test(entry.path())) {
                level = Math.min(level, entry.depth());
            }
        }
        if (level == Integer.MAX_VALUE) {
            return Change.NONE;
        }
        Set<String> unfold = new LinkedHashSet<>();
        Set<String> fold = new LinkedHashSet<>();
        for (int i : shown) {
            Entry entry = entries.get(i);
            if (entry.depth() != level || entry.path() == null || !folded.test(entry.path())) {
                continue;
            }
            unfold.add(entry.path());
            // Its children that branch further stay closed, or a branch opened
            // and closed long ago would come back whole instead of one level.
            for (int j = i + 1; j < entries.size() && entries.get(j).depth() > level; j++) {
                Entry child = entries.get(j);
                if (child.depth() == level + 1 && child.path() != null) {
                    fold.add(child.path());
                }
            }
        }
        return new Change(fold, unfold);
    }

    /** Closes the deepest open level on screen. */
    public static Change collapseLevel(List<Entry> entries, Predicate<String> folded) {
        List<Integer> shown = visible(entries, folded);
        int level = -1;
        for (int i : shown) {
            Entry entry = entries.get(i);
            if (entry.path() != null && !folded.test(entry.path())) {
                level = Math.max(level, entry.depth());
            }
        }
        if (level < 0) {
            return Change.NONE;
        }
        Set<String> fold = new LinkedHashSet<>();
        for (int i : shown) {
            Entry entry = entries.get(i);
            if (entry.depth() == level && entry.path() != null && !folded.test(entry.path())) {
                fold.add(entry.path());
            }
        }
        return new Change(fold, Set.of());
    }

    /**
     * Indices of the entries a panel shows under {@code folded}: everything not
     * inside a folded branch. Entries come in draw order, so a branch is the run
     * of deeper entries after its row.
     */
    static List<Integer> visible(List<Entry> entries, Predicate<String> folded) {
        List<Integer> shown = new ArrayList<>();
        int hiddenBelow = Integer.MAX_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.depth() > hiddenBelow) {
                continue;
            }
            hiddenBelow = Integer.MAX_VALUE;
            shown.add(i);
            if (entry.path() != null && folded.test(entry.path())) {
                hiddenBelow = entry.depth();
            }
        }
        return shown;
    }
}
