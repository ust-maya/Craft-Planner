package com.craftplanner.client.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Flattening the tree into draw rows, over a fake node type — proof that the
 * layout core needs neither Minecraft nor a renderer.
 */
class TreeFlattenTest {

    private record Node(String name, List<Node> children) {
    }

    private static Node leaf(String name) {
        return new Node(name, List.of());
    }

    /**
     * A: B(D, E), C
     * F (a second goal, a bare leaf)
     */
    private static Node plan() {
        return new Node("A", List.of(
                new Node("B", List.of(leaf("D"), leaf("E"))),
                leaf("C")));
    }

    private static final class FakeSource implements TreeSource<Node> {

        @Override
        public List<Node> childrenOf(Node node) {
            return node.children();
        }

        @Override
        public String pathOf(String parentPath, Node node) {
            return parentPath == null ? node.name() : parentPath + "/" + node.name();
        }

        @Override
        public TreeRow<Node> rowFor(Node node, int depth, int branch, String path, boolean foldable) {
            return new TreeRow<>(node, depth, branch, path, TreeRow.Kind.MATERIAL,
                    TreeRow.Status.UNKNOWN, TreeRow.Badge.NONE, 0, 0, node.name(), 1, foldable);
        }
    }

    private static List<TreeRow<Node>> flatten(List<Node> roots, String... folded) {
        Set<String> set = new HashSet<>();
        for (String path : folded) {
            set.add(path);
        }
        return TreeFlatten.flatten(roots, new FakeSource(), set);
    }

    private static List<Node> roots() {
        return List.of(plan(), leaf("F"));
    }

    @Test
    void anOpenTreeDrawsEveryNodeInOrder() {
        List<TreeRow<Node>> rows = flatten(roots());
        assertEquals(List.of("A", "B", "D", "E", "C", "F"),
                rows.stream().map(TreeRow::name).toList());
        assertEquals(List.of(0, 1, 2, 2, 1, 0),
                rows.stream().map(TreeRow::depth).toList());
    }

    @Test
    void eachGoalKeepsItsOwnBranchIndex() {
        List<TreeRow<Node>> rows = flatten(roots());
        // The branch index colours the connectors: A, B, D, E and C belong to
        // goal 0, F to goal 1.
        assertEquals(List.of(0, 0, 0, 0, 0, 1),
                rows.stream().map(TreeRow::branch).toList());
    }

    @Test
    void aLeafHasNoFoldPathAndADeepNodeChainsItsKey() {
        List<TreeRow<Node>> rows = flatten(roots());
        assertNull(rows.get(2).path(), "D is a leaf: nothing to fold");
        assertFalse(rows.get(2).foldable());
        assertEquals("A/B", rows.get(1).path());
        assertEquals("A", rows.get(0).path());
        assertNull(rows.get(5).path(), "a root with no children cannot fold");
    }

    @Test
    void theSpineOfAParentReachesItsLastDrawnChild() {
        List<TreeRow<Node>> rows = flatten(roots());
        assertEquals(4, rows.get(0).lastChildIndex(), "A's spine runs to C");
        assertTrue(rows.get(0).expands(0));
        assertEquals(3, rows.get(1).lastChildIndex(), "B's spine runs to E");
        assertEquals(2, rows.get(2).lastChildIndex(), "a leaf points at itself");
        assertFalse(rows.get(2).expands(2));
    }

    @Test
    void foldingABranchHidesItsRowsAndCountsThemOnTheParent() {
        List<TreeRow<Node>> rows = flatten(roots(), "A/B");
        assertEquals(List.of("A", "B", "C", "F"),
                rows.stream().map(TreeRow::name).toList());
        assertEquals(2, rows.get(1).hiddenRows(), "B hides D and E");
        assertTrue(rows.get(1).folded());
        assertFalse(rows.get(1).expands(1), "nothing is drawn below a folded row");
        assertEquals(2, rows.get(0).lastChildIndex(), "A's spine now stops at C");
    }

    @Test
    void foldingARootHidesItsWholeSubtree() {
        List<TreeRow<Node>> rows = flatten(roots(), "A");
        assertEquals(List.of("A", "F"), rows.stream().map(TreeRow::name).toList());
        assertEquals(4, rows.get(0).hiddenRows(), "B, D, E and C");
    }

    @Test
    void aFoldSurvivesARebuildThatRecreatesEveryNode() {
        // The graph is re-expanded in place and the engine builds new node
        // objects, so a fold keyed by identity or row index would forget what
        // the player closed. The key has to come out of the item chain.
        List<TreeRow<Node>> before = flatten(roots(), "A/B");
        List<TreeRow<Node>> after = flatten(roots(), before.get(1).path());
        assertEquals(before.stream().map(TreeRow::name).toList(),
                after.stream().map(TreeRow::name).toList());
        assertEquals(before.get(1).hiddenRows(), after.get(1).hiddenRows());
    }

    @Test
    void anEmptyPlanFlattensToNoRows() {
        assertTrue(TreeFlatten.flatten(List.of(), new FakeSource(), Set.of()).isEmpty());
    }
}
