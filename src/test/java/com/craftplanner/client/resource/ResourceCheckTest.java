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
}