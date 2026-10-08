package com.craftplanner.client.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.craftplanner.client.resource.Availability.Status;
import org.junit.jupiter.api.Test;

/**
 * The three-way verdict on a material, and the threshold that moves a barely
 * covered one back into "missing".
 *
 * <p>The HUD colours a row, the K window colours its amounts and the tree window
 * colours its lines — three surfaces that used to decide "partly available"
 * between themselves, one of them honouring {@code resources.partialThresholdPercent}
 * and the two others not. The verdict now lives here, so the tests below are the
 * tests for all three.</p>
 */
class AvailabilityTest {

    @Test
    void enoughIsEnough() {
        assertEquals(Status.OK, new Availability(10, 10).status(0));
        assertEquals(Status.OK, new Availability(10, 40).status(50));
        assertEquals(Status.OK, new Availability(0, 0).status(0));
    }

    @Test
    void nothingIsMissing() {
        assertEquals(Status.MISSING, new Availability(10, 0).status(0));
        assertEquals(Status.MISSING, new Availability(10, 0).status(50));
    }

    @Test
    void withoutAThresholdAnyStockCountsAsPartial() {
        assertEquals(Status.PARTIAL, new Availability(10, 3).status(0));
        // status() is the threshold-free reading the config default asks for.
        assertEquals(Status.PARTIAL, new Availability(10, 1).status());
    }

    @Test
    void aThresholdKeepsAFractionOfThePlanRed() {
        assertEquals(Status.MISSING, new Availability(10, 3).status(50));
        assertEquals(Status.PARTIAL, new Availability(10, 5).status(50));
        // The comparison is ">=", so exactly the threshold still counts.
        assertEquals(Status.PARTIAL, new Availability(10, 8).status(80));
    }

    @Test
    void aSaturatedRequirementDoesNotOverflowTheCompare() {
        // required * percent would overflow an int multiply, and a saturated plan
        // reaches Long.MAX_VALUE routinely.
        assertEquals(Status.MISSING, new Availability(Long.MAX_VALUE, 1).status(50));
        assertEquals(Status.OK, new Availability(Long.MAX_VALUE, Long.MAX_VALUE).status(50));
    }

    @Test
    void missingIsWhatIsLeftToGather() {
        assertEquals(0, new Availability(10, 12).missing());
        assertEquals(7, new Availability(10, 3).missing());
    }
}
