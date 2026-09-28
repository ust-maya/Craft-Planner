package com.craftplanner.client.layout;

import com.craftplanner.graph.RecipeNode;

/**
 * A node with the coordinates assigned by {@link TreeLayout}.
 *
 * @param branch index of the planned item the node belongs to, so the node-link
 *               view can draw every goal's edges in its own colour
 */
public record PlacedNode(RecipeNode node, int x, int y, int branch) {
}
