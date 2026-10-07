package com.craftplanner.client.tree;

import java.util.List;

/**
 * How the tree layout reads a node: its children, its fold key and its row.
 *
 * <p>The mod implements this over {@code RecipeNode}; a test can implement it
 * over any structure at all, which is what keeps {@link TreeFlatten} free of
 * Minecraft types and therefore checkable without a game.</p>
 */
public interface TreeSource<N> {

    /** Children of a node in display order; empty for a leaf. */
    List<N> childrenOf(N node);

    /**
     * Key of the branch a node owns: the chain of items leading to it.
     *
     * <p>The key has to keep naming the same branch across rebuilds — the graph
     * is re-expanded in place and recreates its nodes, so neither an identity
     * nor a row index survives. An item chain does.</p>
     */
    String pathOf(String parentPath, N node);

    /**
     * The row for a node. {@code path} is {@code null} for a leaf, and
     * {@code foldable} says whether the row owns a branch; the rest of the row's
     * meaning (kind, availability, badge) is the implementation's to decide.
     */
    TreeRow<N> rowFor(N node, int depth, int branch, String path, boolean foldable);
}
