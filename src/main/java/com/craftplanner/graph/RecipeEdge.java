package com.craftplanner.graph;

/**
 * One ingredient of a node's recipe: the child node the parent consumes.
 *
 * <p>The total demand for the child is {@code parent.crafts() * perCraft}, which
 * the engine stores on the child node itself as its required amount.</p>
 */
public final class RecipeEdge {
    private final RecipeNode child;

    public RecipeEdge(RecipeNode child) {
        this.child = child;
    }

    public RecipeNode child() {
        return child;
    }
}
