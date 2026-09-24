package com.craftplanner.client.layout;

import com.craftplanner.graph.RecipeNode;

/** A node with the coordinates assigned by {@link TreeLayout}. */
public record PlacedNode(RecipeNode node, int x, int y) {
}
