package com.craftplanner.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * How the recipe picker's three columns share the room a window leaves them.
 *
 * <p>The rows are compared by eye, so the columns have to stay readable rather
 * than fill the panel: the yield is never cut, the machine's name gives way
 * first because its icon already stands next to it, and the inputs follow down to
 * their floor. These tests hold the three contracts the layout relies on: the
 * order the columns give way in, the floors they stop at, and that the widths
 * never ask for more than the panel has — not even on a window so narrow that
 * the machine's column disappears.</p>
 */
class PickerColumnsTest {

    private static final int GAP = 6;
    private static final int MIN_MACHINE = 40;
    private static final int MIN_INPUTS = 60;

    @Test
    void everyColumnKeepsItsRoomWhenThePanelIsWideEnough() {
        assertFit(300, new PickerColumns(100, 20, 80));
    }

    @Test
    void theMachineGivesWayBeforeTheInputs() {
        assertFit(200, new PickerColumns(100, 20, 68));
    }

    @Test
    void theMachineStopsAtItsFloorAndTheInputsStartShrinking() {
        assertFit(150, new PickerColumns(78, 20, 40));
    }

    @Test
    void theMachineIsDroppedWhenNeitherFloorFits() {
        assertFit(100, new PickerColumns(74, 20, 0));
    }

    @Test
    void anAlreadyNarrowMachineIsNotStretchedToItsFloor() {
        assertFit(150, 100, 20, 30, new PickerColumns(88, 20, 30));
    }

    @Test
    void aWindowNarrowerThanAnythingStillGivesPositiveWidths() {
        assertFit(10, new PickerColumns(6, 20, 0));
    }

    /** The default wants: 100 px of inputs, 20 px of yield, 80 px of machine. */
    private static void assertFit(int room, PickerColumns expected) {
        assertFit(room, 100, 20, 80, expected);
    }

    private static void assertFit(int room, int inputsWant, int yieldW, int machineWant,
            PickerColumns expected) {
        PickerColumns columns = PickerColumns.fit(room, inputsWant, yieldW, machineWant, GAP,
                MIN_MACHINE, MIN_INPUTS);
        assertEquals(expected, columns);
        int shared = Math.max(0, room - yieldW - 2 * GAP);
        assertTrue(columns.inputsW() + columns.machineW() <= shared + GAP,
                () -> "columns ask for " + (columns.inputsW() + columns.machineW())
                        + " px in a room of " + shared);
    }
}
