package com.craftplanner.client.resource;

import com.craftplanner.graph.CraftMath;

/**
 * Comparison of a required amount against what the player has.
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

    /**
     * How the requirement reads to the player: covered, partly there, or absent.
     *
     * <p>Any amount at all counts as partial — see
     * {@link #status(int)} for the variant that lets the player set a floor.</p>
     */
    public Status status() {
        return status(0);
    }

    /**
     * Same as {@link #status()}, but "partial" only from {@code thresholdPercent}
     * of the requirement on.
     *
     * <p>One iron ingot in a plan that needs nine hundred is not progress the
     * player can act on; {@code resources.partialThresholdPercent} lets them say
     * that a branch is only worth calling partly covered once a working share of
     * it is in the inventory. Below the floor it stays red.</p>
     *
     * <p>The threshold is a parameter rather than a read of {@code Config} so
     * this stays testable without a game: the caller passes
     * {@code Config.partialThresholdPercent()}.</p>
     */
    public Status status(int thresholdPercent) {
        if (satisfied()) {
            return Status.OK;
        }
        if (available <= 0) {
            return Status.MISSING;
        }
        if (thresholdPercent <= 0) {
            return Status.PARTIAL;
        }
        // Saturating, not a plain multiply: a plan can ask for Long.MAX_VALUE of a
        // material, and the product would then wrap negative and read as covered.
        return CraftMath.saturatingMul(available, 100) >= CraftMath.saturatingMul(required, thresholdPercent)
                ? Status.PARTIAL : Status.MISSING;
    }
}
