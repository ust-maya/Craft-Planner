package com.craftplanner.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The one piece of text arithmetic every measured surface shares: cut a label so
 * it fits, or say that it cannot.
 *
 * <p>{@code trim} used to shorten the string one character at a time and measure
 * the whole remainder each step, which the tree ran on every label of every
 * frame — a long row in a narrow column cost a number of width calls quadratic in
 * its length. The behaviour is unchanged and must stay exactly as it was, so what
 * these tests hold is the contract: the result is never wider than the room the
 * caller offered, and an over-narrow room returns nothing rather than a lying
 * ellipsis.</p>
 */
class TextMetricsTest {

    /** Six pixels per character, the way a fixed-width bitmap font measures. */
    private static final TextMetrics SIX_PER_CHAR = text -> text.length() * 6;

    @Test
    void textThatFitsComesBackUntouched() {
        assertEquals("anvil", SIX_PER_CHAR.trim("anvil", 60));
        assertEquals("anvil", SIX_PER_CHAR.trim("anvil", 30));
    }

    @Test
    void trimmedTextNeverOverflowsTheRoom() {
        String trimmed = SIX_PER_CHAR.trim("comparator", 40);
        assertTrue(trimmed.endsWith("…"), "it was cut: " + trimmed);
        assertTrue(SIX_PER_CHAR.width(trimmed) <= 40,
                "a cut label must fit: " + trimmed + " is " + SIX_PER_CHAR.width(trimmed));
        // Five characters cost 30 px and the ellipsis another 6; a sixth would
        // need 42, so "compa" is the longest prefix with room for its cut mark.
        assertEquals("compa…", trimmed);
    }

    @Test
    void thewidestfittingprefixIsTheOneKept() {
        // "co" is 12 px, the ellipsis another 6: 18 px of a 20 px room. Adding a
        // character would need 24, so two is the longest prefix that keeps its room.
        assertEquals("co…", SIX_PER_CHAR.trim("comparator", 20));
    }

    @Test
    void aRoomTooNarrowForTheEllipsisYieldsNothing() {
        assertEquals("", SIX_PER_CHAR.trim("comparator", 5));
        // Exactly the ellipsis's own width is enough to keep it.
        assertEquals("…", SIX_PER_CHAR.trim("comparator", 6));
    }

    @Test
    void anEmptyLabelIsAlreadyShortEnough() {
        assertEquals("", SIX_PER_CHAR.trim("", 0));
        assertEquals("", SIX_PER_CHAR.trim("", 40));
    }

    @Test
    void trimmingIsIdempotent() {
        String once = SIX_PER_CHAR.trim("crafting table", 50);
        assertEquals(once, SIX_PER_CHAR.trim(once, 50));
    }
}
