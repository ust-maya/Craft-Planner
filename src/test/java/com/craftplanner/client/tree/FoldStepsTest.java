package com.craftplanner.client.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.craftplanner.client.tree.FoldSteps.Change;
import com.craftplanner.client.tree.FoldSteps.Entry;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * The fold steps computed on a plain list of paths and depths — proof that the
 * level buttons' logic needs neither Minecraft nor a rendered tree.
 *
 * <p>The tree under test, in draw order: A(0, {@code "A"}) → B(1, {@code "A/B"})
 * → D(2, {@code "A/B/D"}) → G(3, a leaf); C(1, a leaf); F(0, a leaf). Foldedness
 * is a set of paths, the way a window keeps it.</p>
 */
class FoldStepsTest {

    private static final List<Entry> TREE = List.of(
            new Entry("A", 0),
            new Entry("A/B", 1),
            new Entry("A/B/D", 2),
            new Entry(null, 3),
            new Entry(null, 1),
            new Entry(null, 0));

    private static Predicate<String> folded(String... paths) {
        Set<String> set = Set.of(paths);
        return set::contains;
    }

    @Test
    void allPathsCollectsEveryBranchOfTheWholeTree() {
        assertEquals(Set.of("A", "A/B", "A/B/D"), FoldSteps.allPaths(TREE));
    }

    @Test
    void expandingAFullyOpenTreeChangesNothing() {
        assertTrue(FoldSteps.expandLevel(TREE, folded()).isEmpty());
    }

    @Test
    void expandLevelOpensTheShallowestFoldAndKeepsItsBranchClosed() {
        Change change = FoldSteps.expandLevel(TREE, folded("A", "A/B", "A/B/D"));
        assertEquals(Set.of("A"), change.unfold());
        assertEquals(Set.of("A/B"), change.fold());
    }

    @Test
    void expandLevelOpensTheDeepestVisibleFoldOneLevelDeep() {
        Change change = FoldSteps.expandLevel(TREE, folded("A/B", "A/B/D"));
        assertEquals(Set.of("A/B"), change.unfold());
        assertEquals(Set.of("A/B/D"), change.fold());
    }

    @Test
    void expandLevelBelowABranchWithLeafChildrenFoldsNothingNew() {
        Change change = FoldSteps.expandLevel(TREE, folded("A/B/D"));
        assertEquals(Set.of("A/B/D"), change.unfold());
        assertTrue(change.fold().isEmpty(), "D's only child is a leaf");
    }

    @Test
    void collapseLevelClosesTheDeepestOpenBranch() {
        Change change = FoldSteps.collapseLevel(TREE, folded());
        assertEquals(Set.of("A/B/D"), change.fold());
        assertTrue(change.unfold().isEmpty());
    }

    @Test
    void collapseLevelSkipsHiddenRowsAndClosesTheShallowestShownOnes() {
        Change change = FoldSteps.collapseLevel(TREE, folded("A/B"));
        assertEquals(Set.of("A"), change.fold());
    }

    @Test
    void collapsingAFullyFoldedTreeChangesNothing() {
        assertTrue(FoldSteps.collapseLevel(TREE, folded("A", "A/B", "A/B/D")).isEmpty());
    }
}
