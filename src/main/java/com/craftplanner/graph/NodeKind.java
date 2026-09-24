package com.craftplanner.graph;

/**
 * Role of a node inside the craft tree. Drives the colour coding in the
 * overlay (Stage 8): intermediate nodes are craftable, raw nodes are not.
 */
public enum NodeKind {
    /** A target the player added explicitly. */
    ROOT,
    /** Craftable node with a recipe and children. */
    INTERMEDIATE,
    /** Leaf without a recipe — raw material that has to be gathered. */
    RAW,
    /** Node that would recurse into itself; expansion stopped here. */
    CYCLE
}
