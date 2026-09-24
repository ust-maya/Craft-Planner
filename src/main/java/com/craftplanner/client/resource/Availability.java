package com.craftplanner.client.resource;

/**
 * Comparison of a required amount against what the player has (Stage 9).
 */
public record Availability(long required, long available) {

    public enum Status {
        /** Nothing required (or everything already covered). */
        OK,
        /** Some of it is there, the rest has to be gathered. */
        PARTIAL,
        /** Nothing of it is there. */
        MISSING
    }

    public boolean satisfied() {
        return available >= required;
    }

    /** How much more has to be obtained; 0 when satisfied. */
    public long missing() {
        return satisfied() ? 0 : required - available;
    }

    public Status status() {
        if (satisfied()) {
            return Status.OK;
        }
        return available > 0 ? Status.PARTIAL : Status.MISSING;
    }
}
