package com.craftplanner.client.screen;

/**
 * Widths of the recipe picker's three columns — inputs, yield, machine — for the
 * room a window of a given width leaves them.
 *
 * <p>The yield is a few characters and is never cut: it is what tells two
 * otherwise equal recipes apart. When the room runs short the machine's name
 * gives way first, down to its floor, because the row's icon already names the
 * machine; then the inputs, down to theirs; and when not even both floors fit,
 * the machine's name is dropped and the inputs take its room. Pure arithmetic,
 * so it is tested without a game.</p>
 *
 * @param inputsW  width of the inputs column, 0 or more
 * @param yieldW   width of the yield column
 * @param machineW width of the machine column; 0 means the column is not drawn
 */
record PickerColumns(int inputsW, int yieldW, int machineW) {

    static PickerColumns fit(int room, int inputsWant, int yieldW, int machineWant, int gap,
            int minMachine, int minInputs) {
        int shared = Math.max(0, room - yieldW - 2 * gap);
        int machine = machineWant;
        int inputs = inputsWant;
        if (inputs + machine > shared) {
            machine = Math.max(Math.min(minMachine, machineWant), shared - inputs);
        }
        if (inputs + machine > shared) {
            inputs = Math.max(minInputs, shared - machine);
        }
        if (inputs + machine > shared) {
            machine = 0;
            inputs = Math.max(0, Math.min(inputsWant, shared + gap));
        }
        return new PickerColumns(inputs, yieldW, machine);
    }
}
