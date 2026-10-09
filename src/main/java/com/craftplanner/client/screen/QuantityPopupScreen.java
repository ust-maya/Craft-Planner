package com.craftplanner.client.screen;

import com.craftplanner.Config;
import com.craftplanner.client.PlannerState;
import com.craftplanner.client.TextMetrics;
import com.craftplanner.client.UiPalette;
import com.craftplanner.client.UiShapes;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * Modal popup shown right after an item is captured: icon, name,
 * quantity field with {@code +/-} steppers and Add / Cancel.
 *
 * <p>{@code Esc} cancels, {@code Enter} confirms, mouse wheel over the panel
 * steps the amount by 1 (with shift — by 10).</p>
 *
 * <p>Layout (2026-09-25): the panel is measured from the font instead of being a
 * fixed 176×110 box. The hint is the widest text of the window — 334 px in
 * Russian against a 176 px panel — and it used to be drawn centred and
 * untrimmed, so it ran ~80 px past the border on both sides (the English one
 * overflowed as well). It is now wrapped onto at most two lines, the panel
 * widens until that fits, and the extra line grows the panel and pushes the
 * button row down, so nothing overlaps and nothing leaves the frame. The
 * controls keep their density (14 px rows) and every offset stays named in
 * {@link Layout}.</p>
 */
public class QuantityPopupScreen extends Screen {

    private static final String KEY_TITLE = "screen.craftplanner.quantity.title";
    private static final String KEY_FIELD = "screen.craftplanner.quantity.field";
    private static final String KEY_ADD = "screen.craftplanner.quantity.add";
    private static final String KEY_CANCEL = "screen.craftplanner.quantity.cancel";
    private static final String KEY_HINT = "screen.craftplanner.quantity.hint";

    /** Preferred panel size; the real one is measured in {@link Layout}. */
    static final int PANEL_WIDTH = 176;
    static final int PANEL_HEIGHT = 110;
    /** Inner padding of the panel, used on both sides. */
    static final int PAD = 6;

    static final int FIELD_WIDTH = 76;
    static final int FIELD_HEIGHT = 14;
    static final int BUTTON_WIDTH = 72;
    static final int BUTTON_HEIGHT = 14;
    static final int STEP_HEIGHT = 14;
    static final int STEP_WIDTH = 38;
    static final int STEP_GAP = 3;
    private static final int FLASH_PERIOD_MS = 120;
    private static final int FLASH_DURATION_MS = 800;
    private static final int CLAMP_FLASH = UiPalette.CLAMP_FLASH;

    // Vertical offsets inside the panel, top-down. Named so the layout stays
    // readable after the density pass that shrank every element.
    static final int TITLE_Y = 4;
    static final int HEADER_Y = 16;
    static final int FIELD_Y = 36;
    static final int STEP_Y = 54;
    static final int HINT_Y = 72;
    static final int BUTTON_Y = 90;
    /** Horizontal offsets of the header row, measured from the panel's centre. */
    static final int ICON_DX = -30;
    static final int NAME_DX = -10;
    /** Side margin of the Add / Cancel pair. */
    static final int BUTTON_MARGIN = 10;

    private static final int TEXT = UiPalette.TEXT;
    private static final int MUTED = UiPalette.MUTED;
    private static final int DIM = UiPalette.MODAL_SCRIM;
    private static final int PANEL_BG = UiPalette.MODAL_PANEL_BG;
    private static final int PANEL_BORDER = UiPalette.MODAL_PANEL_BORDER;

    private final ItemStack stack;
    private final String itemName;
    private EditBox amountField;
    private long amount;
    /** The four steppers, with the step each one takes without Shift. */
    private final List<Stepper> steppers = new ArrayList<>();
    /** Whether the stepper labels currently show the Shift step. */
    private boolean shiftShown;
    /** Measured geometry of the current panel; rebuilt by {@link #computeLayout()}. */
    private Layout layout;
    /** {@code font::width}, refreshed in {@link #init()} — the layout's only input. */
    private TextMetrics metrics;
    /** Deadline of the "value was clamped" border flash (see {@link #render}). */
    private long clampFlashUntil;
    /** Guards the field's responder against the write-back {@link #writeField} triggers. */
    private boolean updatingField;

    public QuantityPopupScreen(ItemStack stack) {
        super(Component.translatable(KEY_TITLE));
        this.stack = stack.copy();
        this.itemName = stack.getHoverName().getString();
        this.amount = 1;
    }

    @Override
    protected void init() {
        this.metrics = this.font::width;
        computeLayout();
        Layout l = this.layout;
        int left = panelLeft();
        int top = panelTop();
        int centerX = left + l.panelWidth / 2;

        this.amountField = new EditBox(this.font,
                centerX - FIELD_WIDTH / 2, top + FIELD_Y, FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable(KEY_FIELD));
        this.amountField.setValue(Long.toString(this.amount));
        // The field must accept exactly the digits of the configured ceiling,
        // so input is never cut before the clamping logic gets to react.
        this.amountField.setMaxLength(Long.toString(Config.maxAmount()).length());
        this.amountField.setFilter(text -> text.isEmpty() || text.matches("\\d+"));
        this.amountField.setResponder(this::onFieldChanged);
        addWidget(this.amountField);
        setInitialFocus(this.amountField);

        // One tidy row of steppers beneath the field: -10, -1, +1, +10.
        this.steppers.clear();
        int stepY = top + STEP_Y;
        int stepX = left + (l.panelWidth - (4 * STEP_WIDTH + 3 * STEP_GAP)) / 2;
        for (int i = 0; i < STEPPER_DELTAS.length; i++) {
            long delta = STEPPER_DELTAS[i];
            addRenderableWidget(stepper(delta, stepX + i * (STEP_WIDTH + STEP_GAP), stepY));
        }
        this.shiftShown = false;
        syncStepperLabels();

        int buttonsY = top + l.buttonY;
        addRenderableWidget(FitButton.create(left + BUTTON_MARGIN, buttonsY,
                l.buttonWidth, BUTTON_HEIGHT, Component.translatable(KEY_ADD),
                button -> confirm(), null));
        addRenderableWidget(FitButton.create(left + l.panelWidth - l.buttonWidth - BUTTON_MARGIN,
                buttonsY, l.buttonWidth, BUTTON_HEIGHT, Component.translatable(KEY_CANCEL),
                button -> onClose(), null));
    }

    /** Measures the panel for the current texts. */
    private void computeLayout() {
        this.layout = new Layout(this.metrics, this.width, this.title.getString(), this.itemName,
                tr(KEY_ADD), tr(KEY_CANCEL), tr(KEY_HINT));
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    /** The four steps the stepper row offers, in the order it draws them. */
    private static final long[] STEPPER_DELTAS = {-10, -1, 1, 10};

    private Button stepper(long delta, int x, int y) {
        // Shift multiplies the step by 10, matching the wheel behaviour the
        // hint promises (±1, Shift — ±10).
        Button button = FitButton.create(x, y, STEP_WIDTH, STEP_HEIGHT,
                Component.literal(stepperLabel(delta, false)),
                b -> step(hasShiftDown() ? delta * 10 : delta), null);
        this.steppers.add(new Stepper(button, delta));
        return button;
    }

    /** A stepper and the step it takes without Shift. */
    private record Stepper(Button button, long delta) {
    }

    /**
     * The label of a stepper: the step it takes <em>as the modifier is held right
     * now</em>. The row used to read ±1 and ±10 while Shift made it move by ten
     * times that, so the hint promised a big step the buttons never showed.
     */
    private static String stepperLabel(long delta, boolean shift) {
        long step = shift ? delta * 10 : delta;
        return step > 0 ? "+" + step : Long.toString(step);
    }

    /** Re-labels the steppers when the player puts Shift down or lets it go. */
    private void syncStepperLabels() {
        boolean shift = hasShiftDown();
        if (shift == this.shiftShown) {
            return;
        }
        this.shiftShown = shift;
        for (Stepper stepper : this.steppers) {
            stepper.button().setMessage(Component.literal(stepperLabel(stepper.delta(), shift)));
        }
    }

    @Override
    public void tick() {
        syncStepperLabels();
    }

    private void onFieldChanged(String value) {
        if (this.updatingField) {
            return;
        }
        if (value.isEmpty()) {
            // Mid-edit: the player cleared the field to type a new number. The old
            // amount stays, and {@link #confirm()} refuses to add nothing.
            return;
        }
        try {
            long parsed = Long.parseLong(value);
            long clamped = clamp(parsed);
            this.amount = clamped;
            if (clamped != parsed) {
                // Show the ceiling in the field rather than a number the window
                // will quietly ignore when Add is pressed.
                writeField(clamped);
            }
        } catch (NumberFormatException ignored) {
            // The filter admits digits only; an over-long literal is not a number.
        }
    }

    /** Writes the amount into the field without letting the responder re-enter. */
    private void writeField(long value) {
        if (this.amountField == null) {
            return;
        }
        this.updatingField = true;
        try {
            this.amountField.setValue(Long.toString(value));
        } finally {
            this.updatingField = false;
        }
    }

    private void step(long delta) {
        setAmount(this.amount + delta);
    }

    private void setAmount(long value) {
        this.amount = clamp(value);
        writeField(this.amount);
    }

    /** Clamps to {@code [1, quantity.maxAmount]} and flashes the field when the ceiling was hit. */
    private long clamp(long value) {
        long max = Config.maxAmount();
        if (value > max) {
            this.clampFlashUntil = System.currentTimeMillis() + FLASH_DURATION_MS;
            return max;
        }
        return Math.max(1, value);
    }

    private void confirm() {
        if (this.amountField != null && this.amountField.getValue().isEmpty()) {
            // Nothing was typed, so there is no amount to add: Enter and the Add
            // button both wait for a number instead of planning the old one.
            return;
        }
        long requested = clamp(this.amount);
        PlannerState.add(this.stack, requested);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable("message.craftplanner.added", requested, this.stack.getHoverName()),
                    true);
        }
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.layout == null) {
            // Defensive: init() always measures first, but render must never
            // depend on that order.
            if (this.metrics == null) {
                this.metrics = this.font::width;
            }
            computeLayout();
        }
        Layout l = this.layout;
        TextMetrics m = this.metrics;
        int left = panelLeft();
        int top = panelTop();
        int centerX = left + l.panelWidth / 2;
        int innerW = l.panelWidth - 2 * PAD;
        Font font = this.font;

        // Background first, widgets afterwards — the panel would otherwise be
        // painted over the steppers and the buttons.
        graphics.fill(0, 0, this.width, this.height, DIM);
        UiShapes.panel(graphics, left, top, l.panelWidth, l.panelHeight, PANEL_BG,
                UiPalette.MODAL_BORDER_OUTER, PANEL_BORDER);
        // The band closes the title + icon header; the field row starts below it.
        UiShapes.headerBand(graphics, left, top, l.panelWidth, FIELD_Y - 6,
                UiPalette.MODAL_HEADER_BG, UiPalette.ACCENT_LINE, PAD);

        // Icon + item name, the name cut to the room the measured panel gives it.
        graphics.renderItem(this.stack, centerX + ICON_DX, top + HEADER_Y);
        graphics.drawString(font, RecipeListLayout.trimToWidth(m, this.itemName, l.nameWidth),
                centerX + NAME_DX, top + HEADER_Y + 4, TEXT, false);
        graphics.drawCenteredString(font,
                RecipeListLayout.trimToWidth(m, this.title.getString(), innerW),
                centerX, top + TITLE_Y, TEXT);

        super.render(graphics, mouseX, mouseY, partialTick);

        this.amountField.render(graphics, mouseX, mouseY, partialTick);
        drawClampFlash(graphics);

        for (int i = 0; i < l.hintLines.size(); i++) {
            if (i == 0) {
                UiShapes.divider(graphics, left + PAD, left + l.panelWidth - PAD,
                        top + HINT_Y - 4, UiPalette.SECTION_DIVIDER);
            }
            graphics.drawCenteredString(font,
                    RecipeListLayout.trimToWidth(m, l.hintLines.get(i), innerW),
                    centerX, top + HINT_Y + i * RecipeListLayout.LINE_H, MUTED);
        }
    }

    /**
     * Blinking border around the amount field after a value was clamped to
     * {@code quantity.maxAmount}, so the ceiling is visible instead of the
     * number silently "bouncing back".
     */
    private void drawClampFlash(GuiGraphics graphics) {
        long now = System.currentTimeMillis();
        if (now >= this.clampFlashUntil) {
            return;
        }
        if ((now / FLASH_PERIOD_MS) % 2 != 0) {
            return; // blink: alternate between lit and dark frames
        }
        int x1 = this.amountField.getX() - 1;
        int y1 = this.amountField.getY() - 1;
        int x2 = this.amountField.getX() + this.amountField.getWidth() + 1;
        int y2 = this.amountField.getY() + this.amountField.getHeight() + 1;
        UiShapes.ring(graphics, x1, y1, x2 - x1, y2 - y1, CLAMP_FLASH);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Intentionally empty: the vanilla blurred background would be drawn on
        // top of our panel. render() paints its own dim instead.
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isMouseOverPanel((int) mouseX, (int) mouseY)) {
            return false;
        }
        long direction = scrollY > 0 ? 1 : -1;
        step(direction * (hasShiftDown() ? 10 : 1));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            confirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private boolean isMouseOverPanel(int mouseX, int mouseY) {
        Layout l = this.layout;
        int left = panelLeft();
        int top = panelTop();
        return mouseX >= left && mouseX <= left + l.panelWidth
                && mouseY >= top && mouseY <= top + l.panelHeight;
    }

    private int panelLeft() {
        return (this.width - this.layout.panelWidth) / 2;
    }

    private int panelTop() {
        return (this.height - this.layout.panelHeight) / 2;
    }

    /**
     * Measured geometry of the popup — the same idea as
     * {@link RecipeListLayout}: a {@link TextMetrics} is the only input, so the
     * arithmetic can be checked without a running client.
     */
    static final class Layout {

        /** Lines the hint may occupy before the panel stops growing. */
        private static final int MAX_HINT_LINES = 2;
        /** Free space kept between the panel and the window edges. */
        private static final int SCREEN_MARGIN = 16;
        /** Room a button label needs inside its button. */
        private static final int BUTTON_PAD = 8;

        final int panelWidth;
        final int panelHeight;
        /** Width of the Add / Cancel buttons; grown so their labels fit. */
        final int buttonWidth;
        /** Top of the button row; drops when the hint wraps onto a second line. */
        final int buttonY;
        /** Room for the item name in the header row. */
        final int nameWidth;
        final List<String> hintLines;

        Layout(TextMetrics m, int screenWidth, String title, String itemName, String addLabel,
                String cancelLabel, String hint) {
            // The fixed rows set a lower bound on the width: the stepper row is
            // the widest of them, then the button pair, then the title.
            int buttonWidth = Math.max(BUTTON_WIDTH,
                    Math.max(m.width(addLabel), m.width(cancelLabel)) + BUTTON_PAD);
            int minWidth = Math.max(PANEL_WIDTH, Math.max(
                    Math.max(m.width(title), FIELD_WIDTH) + 2 * PAD,
                    Math.max(4 * STEP_WIDTH + 3 * STEP_GAP, 2 * buttonWidth + 2 * BUTTON_MARGIN) + 2 * PAD));
            int maxWidth = Math.max(PANEL_WIDTH, screenWidth - SCREEN_MARGIN);

            // The hint is the widest text in every shipped language; widen the
            // panel until it fits in MAX_HINT_LINES lines (or the window runs
            // out, in which case the last line is cut with an ellipsis).
            int width = Math.min(minWidth, maxWidth);
            List<String> lines = RecipeListLayout.wrapText(m, hint, width - 2 * PAD, MAX_HINT_LINES);
            while (width < maxWidth && !String.join(" ", lines).equals(hint.trim())) {
                width = Math.min(maxWidth, width + 8);
                lines = RecipeListLayout.wrapText(m, hint, width - 2 * PAD, MAX_HINT_LINES);
            }

            this.panelWidth = width;
            this.hintLines = lines;
            this.buttonWidth = buttonWidth;
            this.nameWidth = width / 2 - PAD - NAME_DX;
            int extraLines = Math.max(0, lines.size() - 1);
            this.buttonY = BUTTON_Y + extraLines * RecipeListLayout.LINE_H;
            this.panelHeight = PANEL_HEIGHT + extraLines * RecipeListLayout.LINE_H;
        }
    }
}
