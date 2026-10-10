package com.craftplanner.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.craftplanner.client.TextMetrics;
import com.craftplanner.client.screen.PickerInputs.Part;
import com.craftplanner.client.screen.PickerInputs.Placed;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How the ingredients column shares its width between icons and names.
 *
 * <p>An icon is not optional reading: an entry that loses its name still has to
 * say that something was lost, and a row that ends on a whole name while more
 * ingredients follow is a row that lies. These tests walk the column from its
 * full width down to none, one pixel of shortage at a time, to show which of the
 * three give-up rules takes over where. Text is one pixel per character, so the
 * arithmetic is the assertion.</p>
 */
class PickerInputsTest {

    private static final TextMetrics ONE_PER_CHAR = String::length;

    private static final int ICON = 10;
    private static final int GAP = 2;

    private static final Part P0 = new Part(false, "x");
    private static final Part P1 = new Part(true, "abc");
    private static final Part P2 = new Part(true, "defg");

    private static final List<Part> PARTS = List.of(P0, P1, P2);

    @Test
    void theWholeColumnIsItsEntriesWithIconsAndGaps() {
        assertEquals(32, PickerInputs.width(ONE_PER_CHAR, PARTS, ICON, GAP));
    }

    @Test
    void enoughRoomPutsEveryEntryAtItsPlace() {
        assertEquals(List.of(new Placed(0, 0, 0, "x"), new Placed(1, 3, 13, "abc"),
                new Placed(2, 18, 28, "defg")), layout(32));
    }

    @Test
    void aPixelShortOfANameCutsThatName() {
        assertEquals(List.of(new Placed(0, 0, 0, "x"), new Placed(1, 3, 13, "abc"),
                new Placed(2, 18, 28, "de…")), layout(31));
    }

    @Test
    void aColumnWithRoomForNoNameOfItsOwnShowsALoneEllipsis() {
        assertEquals(List.of(new Placed(0, 0, 0, "x"), new Placed(1, 3, 13, "abc"),
                new Placed(-1, 18, 18, "…")), layout(28));
    }

    @Test
    void notEvenTheIconFitsSoTheEntryBeforeIsShortened() {
        assertEquals(List.of(new Placed(0, 0, 0, "x"), new Placed(1, 3, 13, "abc…")), layout(18));
    }

    @Test
    void aNameThatBarelyFitsIsCutRatherThanDropped() {
        assertEquals(List.of(new Placed(0, 0, 0, "x"), new Placed(1, 3, 13, "a…")), layout(15));
    }

    @Test
    void noRoomOrNoEntriesShowNothing() {
        assertEquals(List.of(), layout(0));
        assertEquals(0, PickerInputs.width(ONE_PER_CHAR, List.of(), ICON, GAP));
        assertEquals(List.of(), PickerInputs.layout(ONE_PER_CHAR, List.of(), ICON, GAP, 32));
    }

    private static List<Placed> layout(int room) {
        return PickerInputs.layout(ONE_PER_CHAR, PARTS, ICON, GAP, room);
    }
}
