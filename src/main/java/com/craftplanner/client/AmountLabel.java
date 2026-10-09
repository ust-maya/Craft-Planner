package com.craftplanner.client;

import com.craftplanner.client.resource.Availability;
import com.craftplanner.client.tree.TreeMetrics;
import com.craftplanner.client.tree.TreeRow;
import com.craftplanner.graph.RecipeNode;
import net.minecraft.network.chat.Component;

/**
 * The amount printed at the end of a row: a number of items, or a volume of fluid.
 *
 * <p>Both are said through the language files rather than by gluing a sign onto
 * the number here. The unit of a fluid is not punctuation — Russian prints it as
 * {@code мВ}, and a row reading {@code 1000 mB} inside an otherwise translated
 * panel is the kind of detail that makes a player doubt the rest of the screen.
 * The count of items carries the same problem in the other direction: the
 * {@code ×} prefix exists because "3" alone under an icon could be a stack size,
 * a recipe output or a demand, and only the sign said which.</p>
 *
 * <p>The graph package stays out of this on purpose: {@code RecipeNode} holds
 * amounts as numbers, and only the surfaces that show them to a player put words
 * around them.</p>
 */
public final class AmountLabel {

    private AmountLabel() {
    }

    /** The row's amount, in the unit its node is measured in. */
    public static String of(RecipeNode node) {
        return node.isFluidRequirement()
                ? millibuckets(node.required())
                : times(node.required());
    }

    /**
     * A row's amount with its availability sign, as every tree surface prints it.
     * A partly covered material reads "~ 2/4" — on hand of needed — rather than
     * "~ ×4", which said that something was missing but not how much. The numbers
     * are the plan's totals for the item, the same ones the row's status is judged
     * by, so a row and its sign can never disagree.
     */
    public static String marked(TreeRow<RecipeNode> row) {
        if (row.status() == TreeRow.Status.PARTIAL) {
            Availability state = PlannerState.availability().get(row.node().item());
            if (state != null) {
                return TreeMetrics.markedAmount(row.status(),
                        TreeMetrics.partialAmount(state.available(), state.required()));
            }
        }
        return TreeMetrics.markedAmount(row.status(), of(row.node()));
    }

    /** A fluid volume: the number and the unit the player's language uses for it. */
    public static String millibuckets(long amount) {
        return text("unit.craftplanner.millibuckets", Long.toString(amount));
    }

    /** A count of items, with the sign that says it is a demand. */
    public static String times(long amount) {
        return text("unit.craftplanner.times", Long.toString(amount));
    }

    /** A count of items with nothing around it, for the columns that already say it. */
    public static String bare(long amount) {
        return Long.toString(amount);
    }

    private static String text(String key, String amount) {
        return Component.translatable(key, amount).getString();
    }
}
