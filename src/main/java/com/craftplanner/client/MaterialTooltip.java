package com.craftplanner.client;

import com.craftplanner.client.resource.Availability;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * The tooltip a material answers with: what the plan asks, what the inventory
 * already holds, what is still missing.
 *
 * <p>The HUD's shopping list, its footer icons and the tree window's rows all
 * point at the same three numbers, and each of them used to assemble them out of
 * the same translation keys in its own file. The pair that drifted first was the
 * wording of "have": a tooltip that says one thing in the corner of the screen and
 * another in the middle of it is worse than no tooltip, because the player now has
 * two claims to compare.</p>
 *
 * <p>What the surfaces genuinely do not share — a recipe line under the numbers,
 * a count of hidden rows — stays with the surface. This class produces the
 * material's own lines and nothing else.</p>
 */
public final class MaterialTooltip {

    private MaterialTooltip() {
    }

    /**
     * The material's lines: its name, then the plan's demand, then the part the
     * inventory covers and the part it does not.
     *
     * @param name    the item or fluid, already resolved to a component
     * @param amount  the demanded amount as the row prints it, unit included
     * @param state   what the inventory holds against that demand, {@code null}
     *                when there is no comparison to make — a fluid has no player
     *                inventory, and an unchecked one says nothing about any material
     */
    public static List<Component> lines(Component name, String amount, Availability state) {
        List<Component> lines = new ArrayList<>();
        lines.add(name.copy());
        lines.add(Component.translatable("overlay.craftplanner.material.required", amount));
        if (state == null) {
            return lines;
        }
        lines.add(Component.translatable("overlay.craftplanner.material.have",
                Long.toString(state.available())));
        // Only a shortfall gets its own line: a material already in stock has
        // nothing left to report, and "missing: 0" reads as an error.
        if (state.missing() > 0) {
            lines.add(Component.translatable("overlay.craftplanner.material.missing",
                    Long.toString(state.missing())));
        }
        return lines;
    }
}
