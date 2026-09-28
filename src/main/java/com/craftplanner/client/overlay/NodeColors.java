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
 * <p>Craftable nodes keep a neutral colour because the tree itself produces
 * them; leaves (raw materials, and roots that have no recipe) are coloured
 * from the inventory comparison: green when covered, amber when partly
 * there, red when nothing is carried. A cycle guard is painted magenta so it
 * is never confused with the amber "partly there" state.</p>
 */
public final class NodeColors {

    public static final int RAW = 0xFF9A9A9A;
    public static final int INTERMEDIATE = 0xFF8FD3FF;
    public static final int ROOT = 0xFFFFE08F;
    public static final int CYCLE = 0xFFFF7FD0;
    public static final int HAVE = 0xFF7BE08A;
    public static final int PARTIAL = 0xFFE0C040;
    public static final int MISSING = 0xFFE06060;
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
        if (state.satisfied()) {
            return HAVE;
        }
        return partlyCovered(state) ? PARTIAL : MISSING;
    }

    /**
     * Stage 10: {@code resources.partialThresholdPercent} decides how much of
     * a material has to be in the inventory before it counts as "partly there"
     * (yellow) instead of "missing" (red). With the default of 0 the old rule
     * applies: anything above zero is yellow.
     */
    private static boolean partlyCovered(Availability state) {
        if (state.available() <= 0) {
            return false;
        }
        int percent = Config.partialThresholdPercent();
        if (percent <= 0) {
            return true;
        }
        return state.available() * 100L >= state.required() * percent;
    }
}
