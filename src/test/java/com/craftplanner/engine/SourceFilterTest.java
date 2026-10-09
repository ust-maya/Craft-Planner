package com.craftplanner.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The rule that keeps a switched-off mod or machine out of the plan.
 *
 * <p>Two ways a recipe belongs to a mod, and both must count: the mod that wrote
 * the recipe file, and the mod whose machine runs it. A filter that hid only the
 * machines would leave Modern Industrialization's crafting-table recipes in the
 * plan while its macerator was gone. Switching back on must give the recipes
 * straight back, so the toggles are checked both ways.</p>
 */
class SourceFilterTest {

    private static final String MACERATOR = "modern_industrialization:macerator";
    private static final String MIXER = "modern_industrialization:mixer";
    private static final String MI = "modern_industrialization";

    @Test
    void nothingIsHiddenByDefault() {
        assertTrue(SourceFilter.NONE.isEmpty());
        assertFalse(SourceFilter.NONE.hides("minecraft:stone", "minecraft:smelting"));
    }

    @Test
    void aSwitchedOffMachineHidesOnlyItsOwnRecipes() {
        SourceFilter filter = SourceFilter.NONE.withType(MACERATOR, true);
        assertTrue(filter.hides(
                "modern_industrialization:vanilla_recipes/macerator/stone_to_cobblestone", MACERATOR));
        assertFalse(filter.hides("modern_industrialization:vanilla_recipes/mixer/cobblestone", MIXER));
    }

    @Test
    void aSwitchedOffModHidesTheRecipesItWroteEvenOnAVanillaMachine() {
        assertTrue(SourceFilter.NONE.withMod(MI, true)
                .hides("modern_industrialization:materials/tin/craft/rotor", "minecraft:crafting"));
    }

    @Test
    void aSwitchedOffModHidesItsMachinesAndLeavesVanillaAlone() {
        SourceFilter filter = SourceFilter.NONE.withMod(MI, true);
        assertTrue(filter.hides("kubejs:custom", MACERATOR));
        assertFalse(filter.hides("minecraft:stone", "minecraft:smelting"));
    }

    @Test
    void switchingAMachineBackOnGivesItExactlyBack() {
        assertTrue(SourceFilter.NONE.withType(MACERATOR, true).withType(MACERATOR, false).isEmpty());
    }

    @Test
    void anIdWithoutANamespaceBelongsToMinecraft() {
        assertEquals("minecraft", SourceFilter.namespaceOf("stone"));
        assertEquals("mi", SourceFilter.namespaceOf("mi:x"));
    }
}
