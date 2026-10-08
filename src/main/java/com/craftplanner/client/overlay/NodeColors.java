package com.craftplanner.client.overlay;

import com.craftplanner.Config;
import com.craftplanner.client.resource.Availability;
import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.NodeKind;
import com.craftplanner.graph.RecipeNode;
import java.util.Map;

/**
 * Colour coding of tree nodes (Stage 8, availability added in Stage 9).
 *
 * <p>Two encodings, deliberately kept apart: the <em>label</em> of a row says
 * what the inventory covers (green / amber / red) or what the node is (goal,
 * sub-assembly, cycle), while the <em>goal bar and connectors</em> say which
 * planned item the row belongs to, from {@link #BRANCH}. A row therefore never
 * carries two different meanings in one colour, and the legend only has to name
 * the label colours.</p>
 *
 * <p>Every label colour is a text colour and has to hold WCAG AA against the
 * HUD panel composited over a bright world — {@code UiPaletteContrastTest}
 * checks that. The two that did not ({@link #MISSING} at 3.5:1 and {@link #RAW}
 * at 4.4:1) are the ones a player actually needed to read.</p>
 */
public final class NodeColors {

    public static final int RAW = 0xFFB8B8B8;
    public static final int INTERMEDIATE = 0xFF8FD3FF;
    public static final int ROOT = 0xFFFFE08F;
    public static final int CYCLE = 0xFFFF7FD0;
    public static final int HAVE = 0xFF7BE08A;
    public static final int PARTIAL = 0xFFE0C040;
    public static final int MISSING = 0xFFFF7A66;
    public static final int TEXT = 0xFFE0E0E0;

    /**
     * Colours of the tree connectors, one per planned goal. A machine recipe
     * from a big pack nests five levels deep and shares its sub-assemblies with
     * the next goal; one grey web of lines then makes it unreadable, while
     * cycling a handful of hues lets the eye follow a single branch through the
     * whole panel. Fully opaque and saturated: the lines sit on the dark panel
     * behind the icons, and a translucent grey one simply disappears there.
     */
    private static final int[] BRANCH = {
        0xFF5AA9FF, 0xFF66E07A, 0xFFFFC83D, 0xFFFF7A5C, 0xFFC77DFF, 0xFF3FD8CE
    };

    /** Connector colour of the {@code rootIndex}-th planned item. */
    public static int branch(int rootIndex) {
        return BRANCH[Math.floorMod(rootIndex, BRANCH.length)];
    }

    private NodeColors() {
    }

    public static int forNode(RecipeNode node) {
        NodeKind kind = node.kind();
        return switch (kind) {
            case ROOT -> ROOT;
            case RAW -> RAW;
            case CYCLE -> CYCLE;
            case INTERMEDIATE -> INTERMEDIATE;
        };
    }

    /**
     * Stage 9: raw materials are coloured by availability — green when the
     * player already has enough, yellow when partly, red when nothing.
     * Craftable nodes keep their neutral colour: they are produced by the tree.
     */
    public static int forNode(RecipeNode node, Map<ItemKey, Availability> availability) {
        if (node.isFluidRequirement()) {
            return RAW;
        }
        if (node.kind() == NodeKind.CYCLE) {
            return CYCLE;
        }
        if (node.kind() != NodeKind.RAW && !node.isLeaf()) {
            return forNode(node);
        }
        var state = availability.get(node.item());
        if (state == null) {
            return RAW;
        }
        return forAvailability(state);
    }

    /**
     * Colour of a raw material by itself: green when covered, amber when
     * partly, red when nothing is carried. Used both for tree leaves and for
     * the amounts in the footer shopping list, so the same number reads the
     * same way in both places.
     */
    public static int forAvailability(Availability state) {
        return switch (state.status(Config.partialThresholdPercent())) {
            case OK -> HAVE;
            case PARTIAL -> PARTIAL;
            case MISSING -> MISSING;
        };
    }
}
