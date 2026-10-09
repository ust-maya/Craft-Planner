package com.craftplanner.client.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InventorySnapshotTest {

    @Test
    void plainRequirementCountsAllStacksOfThatItemMonotonically() {
        // Exact plain stacks are part of the coarse item total, not a reason
        // to ignore component-bearing variants of the same item.
        assertEquals(5, InventorySnapshot.available(false, 2, 5));
        assertEquals(7, InventorySnapshot.available(false, 2, 7));
    }

    @Test
    void componentRequirementUsesOnlyItsExactVariant() {
        assertEquals(2, InventorySnapshot.available(true, 2, 7));
    }

    @Test
    void twoEmptyScansHoldTheSameStacks() {
        assertTrue(InventorySnapshot.of(null).sameCounts(InventorySnapshot.of(null)));
    }

    @Test
    void aScanIsNeverTheSameAsNoScanAtAll() {
        assertFalse(InventorySnapshot.of(null).sameCounts(null));
    }
}