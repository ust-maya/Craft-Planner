package com.craftplanner.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Container for all trees the player has added. Keeps the list of roots and
 * derives the aggregated shopping list from the current tree state.
 *
 * <p>The graph holds no recipe-manager dependency: the traversal engine
 * (Stage 3) builds the nodes, this class only stores and aggregates them.</p>
 */
public final class CraftGraph {
    private final List<RecipeNode> roots = new ArrayList<>();

    public List<RecipeNode> roots() {
        return Collections.unmodifiableList(roots);
    }

    /** Adds a new target, or merges the amount into an existing root. */
    public RecipeNode addRoot(ItemKey item, long amount) {
        for (RecipeNode root : roots) {
            if (root.item().equals(item)) {
                root.setRequired(root.required() + amount);
                return root;
            }
        }
        RecipeNode node = RecipeNode.root(item, amount);
        roots.add(node);
        return node;
    }

    public void setRootAmount(ItemKey item, long amount) {
        for (RecipeNode root : roots) {
            if (root.item().equals(item)) {
                root.setRequired(amount);
                return;
            }
        }
    }

    public boolean removeRoot(ItemKey item) {
        return roots.removeIf(root -> root.item().equals(item));
    }

    public void clear() {
        roots.clear();
    }

    public boolean isEmpty() {
        return roots.isEmpty();
    }

    /** Depth-first walk over every node, roots first. */
    public void forEachNode(Consumer<RecipeNode> action) {
        for (RecipeNode root : roots) {
            visit(root, action);
        }
    }

    private void visit(RecipeNode node, Consumer<RecipeNode> action) {
        action.accept(node);
        for (RecipeEdge edge : node.children()) {
            visit(edge.child(), action);
        }
    }

    /**
     * Aggregated demand of raw materials: every leaf (node without a recipe)
     * contributes its required amount, equal items are summed. That is the
     * "shopping list" shown in the overlay.
     */
    public Map<ItemKey, Long> aggregateRequirements() {
        Map<ItemKey, Long> totals = new LinkedHashMap<>();
        forEachNode(node -> {
            if (node.isLeaf()) {
                totals.merge(node.item(), node.required(), CraftMath::saturatingAdd);
            }
        });
        return totals;
    }

    /** Every distinct item used by the tree with its total demand. */
    public Map<ItemKey, Long> aggregateAll() {
        Map<ItemKey, Long> totals = new LinkedHashMap<>();
        forEachNode(node -> totals.merge(node.item(), node.required(), CraftMath::saturatingAdd));
        return totals;
    }

    public int nodeCount() {
        int[] count = {0};
        forEachNode(node -> count[0]++);
        return count[0];
    }

    public int maxDepth() {
        int[] max = {0};
        forEachNode(node -> max[0] = Math.max(max[0], node.depth()));
        return max[0];
    }
}
