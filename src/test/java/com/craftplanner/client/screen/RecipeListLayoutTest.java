package com.craftplanner.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.craftplanner.client.TextMetrics;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How the footer hints of the measured panels are broken into lines.
 *
 * <p>A hint is a row of entries — "N — add · G — tree" — and the reader has to
 * see which key does what. Word wrapping only knew about spaces, so it could cut
 * a key off from its own action and leave a lone "·" at the end of the line, which
 * reads as a broken panel rather than as a hint. These tests hold the two
 * contracts the panels rely on: a line break lands between whole entries, and a
 * hint that cannot fit in the lines offered says so with an ellipsis instead of
 * dropping the rest silently.</p>
 */
class RecipeListLayoutTest {

    /** One pixel per character, so the widths below can be read as lengths. */
    private static final TextMetrics ONE_PER_CHAR = String::length;
    private static final String SEPARATOR = " · ";

    @Test
    void wordWrapBreaksOnSpacesAndStopsAtTheLineCap() {
        assertEquals(List.of("one two", "three", "four"),
                RecipeListLayout.wrapText(ONE_PER_CHAR, "one two three four", 9, 3));
    }

    @Test
    void anEmptyTextWrapsIntoNoLines() {
        assertEquals(List.of(), RecipeListLayout.wrapText(ONE_PER_CHAR, "", 10, 3));
    }

    @Test
    void entriesWithRoomToSpareStayOnOneLine() {
        assertEquals(List.of("aa · bb"),
                RecipeListLayout.wrapSegments(ONE_PER_CHAR, "aa · bb", 20, 2, SEPARATOR));
    }

    @Test
    void theBreakFallsBetweenEntriesAndNotInsideOne() {
        assertEquals(List.of("aaaa · bbbb", "cccc"),
                RecipeListLayout.wrapSegments(ONE_PER_CHAR, "aaaa · bbbb · cccc", 11, 3, SEPARATOR));
    }

    @Test
    void noLineStartsOrEndsWithTheSeparator() {
        List<String> lines = RecipeListLayout.wrapSegments(ONE_PER_CHAR,
                "aa · bb · cc · dd", 7, 3, SEPARATOR);
        for (String line : lines) {
            String stripped = line.strip();
            assertTrue(!stripped.startsWith("·"), "dangling separator at the head: " + line);
            assertTrue(!stripped.endsWith("·"), "dangling separator at the tail: " + line);
        }
    }

    @Test
    void textWithoutSeparatorsWrapsLikeWords() {
        assertEquals(RecipeListLayout.wrapText(ONE_PER_CHAR, "one two three four", 9, 3),
                RecipeListLayout.wrapSegments(ONE_PER_CHAR, "one two three four", 9, 3, SEPARATOR));
    }

    @Test
    void entriesThatCannotAllFitEndInTheEllipsis() {
        List<String> lines = RecipeListLayout.wrapSegments(ONE_PER_CHAR, "aa · bb · cc", 2, 2,
                SEPARATOR);
        assertEquals(2, lines.size());
        assertTrue(lines.get(lines.size() - 1).endsWith("…"),
                "dropped entries must show: " + lines);
        for (String line : lines) {
            assertTrue(ONE_PER_CHAR.width(line) <= 2, "line overflows the panel: " + line);
        }
    }

    @Test
    void aPanelWithNoRoomForTheHintGetsNoLines() {
        assertEquals(List.of(), RecipeListLayout.wrapSegments(ONE_PER_CHAR, "aa · bb", 0, 2,
                SEPARATOR));
    }

    private static RecipeListLayout footerOf(int screenWidth, List<String> names, String ambiguous) {
        return new RecipeListLayout(ONE_PER_CHAR, screenWidth, names, 64, 2, "Recipe", "Amount", "Del",
                "Clear all", "Close", "Ores hidden", "Sources...", ambiguous, "", false);
    }

    /** Left edge, width of every footer button that is present, in the order they are drawn. */
    private static int[][] footerButtons(RecipeListLayout l) {
        List<int[]> buttons = new java.util.ArrayList<>();
        buttons.add(new int[] {l.clearX, l.clearW});
        if (l.ambiguousW > 0) {
            buttons.add(new int[] {l.ambiguousX, l.ambiguousW});
        }
        buttons.add(new int[] {l.sourcesX, l.sourcesW});
        buttons.add(new int[] {l.oresX, l.oresW});
        buttons.add(new int[] {l.closeX, l.closeW});
        return buttons.toArray(new int[0][]);
    }

    @Test
    void theSourcesButtonIsThereEvenWithAnEmptyPlan() {
        RecipeListLayout l = footerOf(800, List.of(), "");
        assertTrue(l.sourcesW > 0, "the way back to the switched-off machines must not depend on the plan");
    }

    @Test
    void footerButtonsNeverOverlapOrLeaveThePanel() {
        for (int screenWidth : new int[] {800, 400, 330, 250}) {
            for (String ambiguous : new String[] {"", "Choose recipes (3)"}) {
                RecipeListLayout l = footerOf(screenWidth, List.of("Iron ingot"), ambiguous);
                int[][] buttons = footerButtons(l);
                int previousEnd = 0;
                for (int[] b : buttons) {
                    assertTrue(b[0] >= previousEnd, "buttons overlap at screen width " + screenWidth
                            + " (ambiguous='" + ambiguous + "'): " + b[0] + " < " + previousEnd);
                    previousEnd = b[0] + b[1];
                }
                assertTrue(previousEnd <= l.panelWidth, "footer leaves the panel at screen width " + screenWidth);
            }
        }
    }
}
