package com.craftplanner.client.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResourceCheckTest {

    @Test
    void totalMissingSaturatesInsteadOfOverflowing() {
        List<Availability> availability = List.of(
            new Availability(Long.MAX_VALUE, 0),
            new Availability(Long.MAX_VALUE, 0));

        assertEquals(Long.MAX_VALUE, ResourceCheck.totalMissing(availability));
    }

    @Test
    void aRecipeThatConsumesNothingIsCovered() {
        assertEquals(Availability.Status.OK, ResourceCheck.combined(List.of()));
    }

    @Test
    void everyMaterialCoveredMakesTheRecipeCovered() {
        assertEquals(Availability.Status.OK, ResourceCheck.combined(
                List.of(Availability.Status.OK, Availability.Status.OK)));
    }

    @Test
    void nothingPartlyThereMakesTheRecipeMissing() {
        assertEquals(Availability.Status.MISSING, ResourceCheck.combined(
                List.of(Availability.Status.MISSING, Availability.Status.MISSING)));
    }

    @Test
    void oneMissingAmongSeveralMakesTheRecipePartial() {
        assertEquals(Availability.Status.PARTIAL, ResourceCheck.combined(
                List.of(Availability.Status.OK, Availability.Status.MISSING)));
    }

    @Test
    void aSinglePartialMaterialKeepsTheRecipePartial() {
        assertEquals(Availability.Status.PARTIAL,
                ResourceCheck.combined(List.of(Availability.Status.PARTIAL)));
    }
}