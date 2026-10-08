package com.craftplanner.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Amount arithmetic, the part of the engine that a huge plan exercises.
 *
 * <p>A million beacons is a legitimate request, and the tree multiplies counts
 * down every branch until they saturate at {@link Long#MAX_VALUE}. Ordinary
 * division and multiplication on those values overflow into negatives, which the
 * tree then renders as "you need -1844674407370955161 items". These tests pin the
 * saturating behaviour instead.</p>
 */
class CraftMathTest {

    @Test
    void ceilDivRoundsUpOnlyWhenThereIsARemainder() {
        assertEquals(4, CraftMath.ceilDiv(10, 3));
        assertEquals(3, CraftMath.ceilDiv(9, 3));
    }

    @Test
    void ceilDivOfNothingIsZeroCrafts() {
        assertEquals(0, CraftMath.ceilDiv(0, 3));
        // A negative amount would round towards +infinity, not towards zero; the
        // tree never asks for a negative amount, and craftsFor filters one out.
        assertEquals(-2, CraftMath.ceilDiv(-7, 3));
        assertEquals(0, CraftMath.craftsFor(-7, 3));
    }

    @Test
    void ceilDivSurvivesTheSaturatedMaximum() {
        long expected = Long.MAX_VALUE / 2 + 1;
        long result = CraftMath.ceilDiv(Long.MAX_VALUE, 2);
        assertTrue(result > 0, "an overflow would come out negative");
        assertEquals(expected, result);
    }

    @Test
    void ceilDivByAnImpossibleStackIsNotADivisionAtAll() {
        assertEquals(0, CraftMath.ceilDiv(5, 0));
        assertEquals(0, CraftMath.ceilDiv(5, -1));
    }

    @Test
    void multiplicationSaturatesInsteadOfWrapping() {
        assertEquals(Long.MAX_VALUE, CraftMath.saturatingMul(Long.MAX_VALUE, 2));
        assertEquals(12, CraftMath.saturatingMul(3, 4));
        assertEquals(0, CraftMath.saturatingMul(7, 0));
    }

    @Test
    void additionSaturatesInsteadOfWrapping() {
        assertEquals(Long.MAX_VALUE, CraftMath.saturatingAdd(Long.MAX_VALUE, 1));
        assertEquals(Long.MAX_VALUE, CraftMath.saturatingAdd(Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(7L, CraftMath.saturatingAdd(3, 4));
    }

    @Test
    void craftsForIgnoresEmptyRequestsAndEmptyRecipes() {
        assertEquals(0, CraftMath.craftsFor(0, 4));
        assertEquals(0, CraftMath.craftsFor(10, 0));
        assertEquals(3, CraftMath.craftsFor(10, 4));
    }
}
