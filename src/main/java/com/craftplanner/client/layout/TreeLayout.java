package com.craftplanner.client.layout;

import com.craftplanner.graph.RecipeEdge;
import com.craftplanner.graph.RecipeNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Layered ("tidy tree") layout for the craft tree (Stage 8).
 *
 * <p>Depth defines the row, and the horizontal position is assigned in
 * post-order: every leaf takes the next free slot, every inner node is
 * centred above its children. That keeps siblings ordered, never overlaps
 * branches and is cheap enough to recompute on every frame.</p>
 */
public final class TreeLayout {

    public static final int SLOT_WIDTH = 30;
    public static final int ROW_HEIGHT = 34;
    public static final int ICON_SIZE = 16;

    private final List<PlacedNode> nodes = new ArrayList<>();
    private final Map<RecipeNode, PlacedNode> byNode = new HashMap<>();
    private int cursor;
    private int width;
    private int height;

    private TreeLayout() {
    }

    public static TreeLayout of(List<RecipeNode> roots) {
        TreeLayout layout = new TreeLayout();
        layout.cursor = 0;
        for (int branch = 0; branch < roots.size(); branch++) {
            layout.place(roots.get(branch), 0, branch);
            layout.cursor += SLOT_WIDTH / 2; // gap between independent trees
        }
        for (PlacedNode placed : layout.nodes) {
            layout.width = Math.max(layout.width, placed.x() + SLOT_WIDTH);
            layout.height = Math.max(layout.height, placed.y() + ROW_HEIGHT);
        }
        return layout;
    }

    private int place(RecipeNode node, int depth, int branch) {
        int y = depth * ROW_HEIGHT;
        int x;
        if (node.isLeaf()) {
            x = cursor;
            cursor += SLOT_WIDTH;
        } else {
            int sum = 0;
            int count = 0;
            for (RecipeEdge edge : node.children()) {
                sum += place(edge.child(), depth + 1, branch);
                count++;
            }
            x = sum / Math.max(1, count);
        }
        PlacedNode placed = new PlacedNode(node, x, y, branch);
        nodes.add(placed);
        byNode.put(node, placed);
        return x;
    }

    public List<PlacedNode> nodes() {
        return nodes;
    }

    public PlacedNode of(RecipeNode node) {
        return byNode.get(node);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }
}
