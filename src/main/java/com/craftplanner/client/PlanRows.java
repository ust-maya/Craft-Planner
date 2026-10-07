package com.craftplanner.client;

import com.craftplanner.Config;
import com.craftplanner.client.overlay.TreeCollapse;
import com.craftplanner.client.resource.Availability;
import com.craftplanner.client.tree.TreeFlatten;
import com.craftplanner.client.tree.TreeRow;
import com.craftplanner.client.tree.TreeSource;
import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.NodeKind;
import com.craftplanner.graph.RecipeEdge;
import com.craftplanner.graph.RecipeNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bridges the planner's craft graph into the tree layout's row model.
 *
 * <p>This is the only place the graph is read for drawing: the overlay and the
 * {@code CraftTreeScreen} window both flatten through it, so a row means the
 * same thing in both — same fold key, same badge, same availability. Before
 * this existed each surface re-derived those from {@link RecipeNode} on its own
 * terms, and the two views disagreed about which branches were folded.</p>
 *
 * <p>The flattening is done once per rebuild of the plan rather than once per
 * frame. The overlay used to rebuild every row object and every branch path
 * string in {@code RenderGuiEvent.Post}, and to measure the widest label by
 * building each label string per frame on top of that — on a machine pack's
 * hundred-row plan that is hundreds of throwaway objects and font measurements
 * sixty times a second, which is what the panel felt as stutter while scrolling.
 * The cache in {@link #rows()} is dropped when
 * {@link PlannerState#graphVersion()} moves.</p>
 */
public final class PlanRows {

    private static final Adapter ADAPTER = new Adapter();

    /**
     * How the layout reads a node, for a tree that is not the plan's own.
     *
     * <p>The recipe picker expands one <em>alternative</em> recipe into a tree of
     * its own so its dependencies can be read. That tree is not in
     * {@link #rows()}, but it is drawn by the same geometry and has to mean the
     * same thing, so it is read through the same adapter rather than a second one
     * that could drift from it.</p>
     */
    public static TreeSource<RecipeNode> source() {
        return ADAPTER;
    }

    /**
     * The flattened plan, plus the three things it was derived from. The overlay
     * draws sixty times a second and the graph only moves when the plan changes,
     * so the rows are rebuilt on a change instead of per frame. A fold is the
     * second key: it is view state with no generation counter, and it changes
     * which rows exist. The inventory comparison is the third — it is re-scanned
     * once per second without touching the graph, and a row's colour comes out of
     * that scan, so caching the rows without it would freeze the colours until
     * the next edit of the plan.
     */
    private static List<TreeRow<RecipeNode>> cached = List.of();
    private static long cachedGraphVersion = -1;
    private static int cachedFoldCount = -1;
    private static Map<ItemKey, Availability> cachedAvailability = null;

    private PlanRows() {
    }

    /** Rows of the current plan, in draw order, honouring the folded branches. */
    public static List<TreeRow<RecipeNode>> rows() {
        long graphVersion = PlannerState.graphVersion();
        int foldCount = TreeCollapse.foldCount();
        Map<ItemKey, Availability> availability = PlannerState.availability();
        if (graphVersion != cachedGraphVersion || foldCount != cachedFoldCount
                || availability != cachedAvailability) {
            cachedGraphVersion = graphVersion;
            cachedFoldCount = foldCount;
            cachedAvailability = availability;
            ADAPTER.availability = availability;
            cached = TreeFlatten.flatten(PlannerState.graph().roots(), ADAPTER,
                    TreeCollapse.foldedPaths());
        }
        return cached;
    }

    /** Reads a {@link RecipeNode} as the layout needs it. */
    private static final class Adapter implements TreeSource<RecipeNode> {

        private Map<ItemKey, Availability> availability = Map.of();

        @Override
        public List<RecipeNode> childrenOf(RecipeNode node) {
            List<RecipeEdge> edges = node.children();
            List<RecipeNode> children = new ArrayList<>(edges.size());
            for (RecipeEdge edge : edges) {
                children.add(edge.child());
            }
            return children;
        }

        @Override
        public String pathOf(String parentPath, RecipeNode node) {
            return parentPath == null ? node.item().toString() : parentPath + "/" + node.item();
        }

        @Override
        public TreeRow<RecipeNode> rowFor(RecipeNode node, int depth, int branch, String path,
                boolean foldable) {
            ItemKey item = node.item();
            return new TreeRow<>(node, depth, branch, path, kindOf(node), statusOf(node, item),
                    badgeOf(node, item), node.recipe() == null ? 0 : node.alternatives().size() + 1,
                    node.selectedRecipeIndex() + 1, item.name().getString(), node.required(), foldable);
        }

        private static TreeRow.Kind kindOf(RecipeNode node) {
            return switch (node.kind()) {
                case ROOT -> TreeRow.Kind.ROOT;
                case INTERMEDIATE -> TreeRow.Kind.ASSEMBLY;
                case CYCLE -> TreeRow.Kind.CYCLE;
                case RAW -> TreeRow.Kind.MATERIAL;
            };
        }

        /**
         * Only a gathered material has a stock to compare: a sub-assembly is
         * produced by the plan itself, so painting its line yellow would say
         * something the plan does not ask.
         */
        private TreeRow.Status statusOf(RecipeNode node, ItemKey item) {
            if (node.kind() != NodeKind.RAW && !node.isLeaf()) {
                return TreeRow.Status.UNKNOWN;
            }
            Availability state = availability.get(item);
            if (state == null) {
                return TreeRow.Status.UNKNOWN;
            }
            if (state.satisfied()) {
                return TreeRow.Status.COVERED;
            }
            return state.available() > 0 ? TreeRow.Status.PARTIAL : TreeRow.Status.MISSING;
        }

        private static TreeRow.Badge badgeOf(RecipeNode node, ItemKey item) {
            if (PlannerState.isCut(item)) {
                return TreeRow.Badge.CUT;
            }
            // {@code overlay.badges} off leaves the tree as the engine builds it:
            // the pick is still made, only the affordance to change it goes away —
            // in both surfaces, because both read it from here.
            if (!Config.badgesEnabled() || node.recipe() == null) {
                return TreeRow.Badge.NONE;
            }
            return node.alternatives().isEmpty()
                    ? TreeRow.Badge.SINGLE
                    : TreeRow.Badge.MULTIPLE;
        }
    }
}
