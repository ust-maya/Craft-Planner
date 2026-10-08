package com.craftplanner.graph;

/**
 * Amount arithmetic for the craft tree. Counts are kept as {@code long}:
 * deep trees with big multipliers overflow {@code int} easily, and saturating
 * is better than silently wrapping into negatives.
 */
public final class CraftMath {

    private CraftMath() {
    }

    /** How many craft operations are needed to obtain {@code required} items. */
    public static long craftsFor(long required, long producedPerCraft) {
        if (required <= 0 || producedPerCraft <= 0) {
            return 0;
        }
        return ceilDiv(required, producedPerCraft);
    }

    /**
     * Divides rounding up. {@code Math.ceilDiv} rather than {@code (value + divisor - 1)
     * / divisor}: the summand overflows for the saturated amounts a deep tree can
     * reach, and the quotient then comes out negative.
     */
    public static long ceilDiv(long value, long divisor) {
        if (divisor <= 0) {
            return 0;
        }
        return Math.ceilDiv(value, divisor);
    }

    /** Multiplies, clamping to {@link Long#MAX_VALUE} instead of overflowing. */
    public static long saturatingMul(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** Adds, clamping to {@link Long#MAX_VALUE} instead of overflowing. */
    public static long saturatingAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
