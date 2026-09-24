package com.craftplanner.client.screen;

import com.craftplanner.client.PlannerState;
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
 * Modal popup shown right after an item is captured (Stage 6): icon, name,
 * quantity field with {@code +/-} steppers and Add / Cancel.
 *
 * <p>{@code Esc} cancels, {@code Enter} confirms, mouse wheel over the panel
 * steps the amount by 1 (with shift — by 10).</p>
 *
 * <p>Stage 12: the panel is laid out densely (176×110, 14 px controls) with the
 * vertical offsets named as constants, so the layout stays readable. The font is
 * the vanilla 9 px — the controls are vanilla widgets that draw their own labels.</p>
 */
public class QuantityPopupScreen extends Screen {

    private static final int PANEL_WIDTH = 176;
    private static final int PANEL_HEIGHT = 110;
    private static final int FIELD_WIDTH = 76;
    private static final int FIELD_HEIGHT = 14;
    private static final int BUTTON_WIDTH = 72;
    private static final int BUTTON_HEIGHT = 14;
    private static final int STEP_HEIGHT = 14;
    private static final int STEP_WIDTH = 38;
    private static final int STEP_GAP = 3;
    private static final long MAX_AMOUNT = 99_999L;

    // Vertical offsets inside the panel, top-down. Named so the layout stays
    // readable after the density pass that shrank every element.
    private static final int TITLE_Y = 4;
    private static final int HEADER_Y = 16;
    private static final int FIELD_Y = 36;
    private static final int STEP_Y = 54;
    private static final int HINT_Y = 72;
    private static final int BUTTON_Y = 90;
    /** Side margin of the Add / Cancel pair. */
    private static final int BUTTON_MARGIN = 10;

    private static final int TEXT = 0xFFE0E0E0;
    private static final int MUTED = 0xFFA0A0A0;
    private static final int DIM = 0x80000000;
    private static final int PANEL_BG = 0xF0101010;
    private static final int PANEL_BORDER = 0xFF404040;

    private final ItemStack stack;
    private final String itemName;
    private EditBox amountField;
    private long amount;

    public QuantityPopupScreen(ItemStack stack) {
        super(Component.translatable("screen.craftplanner.quantity.title"));
        this.stack = stack.copy();
        this.itemName = stack.getHoverName().getString();
        this.amount = 1;
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        int centerX = left + PANEL_WIDTH / 2;

        this.amountField = new EditBox(this.font,
                centerX - FIELD_WIDTH / 2, top + FIELD_Y, FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable("screen.craftplanner.quantity.field"));
        this.amountField.setValue(Long.toString(this.amount));
        this.amountField.setMaxLength(6);
        this.amountField.setFilter(text -> text.isEmpty() || text.matches("\\d+"));
        this.amountField.setResponder(this::onFieldChanged);
        addWidget(this.amountField);
        setInitialFocus(this.amountField);

        // One tidy row of steppers beneath the field: -10, -1, +1, +10.
        int stepY = top + STEP_Y;
        int stepX = left + (PANEL_WIDTH - (4 * STEP_WIDTH + 3 * STEP_GAP)) / 2;
        addRenderableWidget(stepper("-10", stepX, stepY, -10));
        addRenderableWidget(stepper("-1", stepX + (STEP_WIDTH + STEP_GAP), stepY, -1));
        addRenderableWidget(stepper("+1", stepX + 2 * (STEP_WIDTH + STEP_GAP), stepY, 1));
        addRenderableWidget(stepper("+10", stepX + 3 * (STEP_WIDTH + STEP_GAP), stepY, 10));

        int buttonsY = top + BUTTON_Y;
        addRenderableWidget(Button.builder(Component.translatable("screen.craftplanner.quantity.add"),
                button -> confirm())
                .bounds(left + BUTTON_MARGIN, buttonsY, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("screen.craftplanner.quantity.cancel"),
                button -> onClose())
                .bounds(left + PANEL_WIDTH - BUTTON_WIDTH - BUTTON_MARGIN, buttonsY, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    private Button stepper(String label, int x, int y, long delta) {
        return Button.builder(Component.literal(label), button -> step(delta))
                .bounds(x, y, STEP_WIDTH, STEP_HEIGHT)
                .build();
    }

    private void onFieldChanged(String value) {
        if (value.isEmpty()) {
            return;
        }
        try {
            this.amount = Math.max(1, Math.min(MAX_AMOUNT, Long.parseLong(value)));
        } catch (NumberFormatException ignored) {
            // filter allows digits only; keep the previous value
        }
    }

    private void step(long delta) {
        setAmount(this.amount + delta);
    }

    private void setAmount(long value) {
        this.amount = Math.max(1, Math.min(MAX_AMOUNT, value));
        if (this.amountField != null) {
            this.amountField.setValue(Long.toString(this.amount));
        }
    }

    private void confirm() {
        long requested = Math.max(1, Math.min(MAX_AMOUNT, this.amount));
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
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        int centerX = left + PANEL_WIDTH / 2;
        Font font = this.font;

        // Background first, widgets afterwards — the panel would otherwise be
        // painted over the steppers and the buttons.
        graphics.fill(0, 0, this.width, this.height, DIM);
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, PANEL_BG);
        graphics.fill(left, top, left + PANEL_WIDTH, top + 1, PANEL_BORDER);
        graphics.fill(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, PANEL_BORDER);
        graphics.fill(left, top, left + 1, top + PANEL_HEIGHT, PANEL_BORDER);
        graphics.fill(left + PANEL_WIDTH - 1, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, PANEL_BORDER);

        // Icon + item name (truncated so it never runs into the panel edge).
        graphics.renderItem(this.stack, centerX - 30, top + HEADER_Y);
        String name = trimToWidth(font, this.itemName, PANEL_WIDTH / 2 - 14);
        graphics.drawString(font, name, centerX - 10, top + HEADER_Y + 4, TEXT, false);
        graphics.drawCenteredString(font, this.title, centerX, top + TITLE_Y, TEXT);

        super.render(graphics, mouseX, mouseY, partialTick);

        this.amountField.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font,
                Component.translatable("screen.craftplanner.quantity.hint"),
                centerX, top + HINT_Y, MUTED);
    }

    /** Cuts the item name with an ellipsis so it fits the given pixel width. */
    private static String trimToWidth(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() > 1 && font.width(sb.toString() + "…") > maxWidth) {
            sb.setLength(sb.length() - 1);
        }
        return sb + "…";
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
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        return mouseX >= left && mouseX <= left + PANEL_WIDTH
                && mouseY >= top && mouseY <= top + PANEL_HEIGHT;
    }
}
