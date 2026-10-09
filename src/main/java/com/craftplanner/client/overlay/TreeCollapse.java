package com.craftplanner.client.overlay;

import java.util.Collection;
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
    /** Bumped by every change to {@link #FOLDED}; see {@link #version()}. */
    private static long version;
    /**
     * Read-only view of {@link #FOLDED}, handed out instead of the live set so
     * the row cache can hold it without a later toggle changing what an
     * already-built list of rows meant.
     */
    private static Set<String> snapshot = Set.of();

    private TreeCollapse() {
    }

    public static boolean isFolded(String path) {
        return FOLDED.contains(path);
    }

    /** The folded branches, as the layout's {@code TreeFlatten} wants them. */
    public static Set<String> foldedPaths() {
        return snapshot;
    }

    /**
     * A count of the folds made, changed by every fold and every unfold.
     *
     * <p>The row caches key on this: a fold changes which rows exist without
     * changing the plan, so the graph version alone cannot tell them the list is
     * stale. It is deliberately not {@code FOLDED.size()} — closing one branch and
     * opening another keeps that count identical, and a cache keyed on it would
     * keep drawing the row layout from before both clicks.</p>
     */
    public static long version() {
        return version;
    }

    public static void toggle(String path) {
        if (!FOLDED.remove(path)) {
            FOLDED.add(path);
        }
        publish();
    }

    /** Folds every branch in {@code paths}, leaving the goals readable. */
    public static void foldAll(Collection<String> paths) {
        FOLDED.addAll(paths);
        publish();
    }

    /** Unfolds everything: the whole plan at once. */
    public static void unfoldAll() {
        FOLDED.clear();
        publish();
    }

    /** One step of the fold buttons: what to fold and what to open, under one version. */
    public static void apply(Collection<String> fold, Collection<String> unfold) {
        FOLDED.removeAll(unfold);
        FOLDED.addAll(fold);
        publish();
    }

    /** Republishes the snapshot and moves the version the caches read. */
    private static void publish() {
        snapshot = Set.copyOf(FOLDED);
        version++;
    }
}
