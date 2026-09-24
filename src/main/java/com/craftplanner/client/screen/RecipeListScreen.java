package com.craftplanner.client.screen;

import com.craftplanner.client.PlannerState;
import com.craftplanner.client.resource.Availability;
import com.craftplanner.client.resource.ResourceCheck;
import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.RecipeNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Window with all targets the player added (Stage 7, hotkey {@code K}).
 *
 * <p>Each row shows the item, its requested amount and four controls: the
 * recipe switcher of Stage 6, {@code -}, {@code +} (hold Shift for steps of
 * 10) and {@code x} to drop the target. Every change is applied immediately
 * and re-expands the trees, so the overlay and the shopping list stay in
 * sync.</p>
 *
 * <p>Stage 9: the footer compares the aggregated shopping list against the
 * player's inventory and prints how much is still missing.</p>
 *
 * <p>Stage 11: the footer also carries the ore switch — a button that flips
 * {@code filters.hideOres} at runtime, so ore blocks and raw ore materials can
 * be kept out of the tree without editing the config file and restarting.</p>
 *
 * <p>Stage 12: the panel is laid out densely — 18 px rows, 14 px buttons, 320 px
 * wide — and the visible row count is additionally clamped to the window height,
 * so a long target list cannot push the panel off screen. The font stays at the
 * vanilla 9 px: the controls are vanilla {@code Button} widgets that render
 * their own labels, so shrinking the text would mean replacing the widgets.</p>
 */
public class RecipeListScreen extends Screen {

    private static final int PANEL_WIDTH = 320;
    private static final int ROW_HEIGHT = 18;
    private static final int HEADER = 24;
    private static final int FOOTER = 50;
    /**
     * Offsets inside the footer block, measured from {@link #footerTop()}.
     * Kept in one place because the summary line, the hint and the button row
     * all share the same vertical space — the hint is drawn after the widgets,
     * so an overlap would paint text on top of a button.
     */
    private static final int FOOTER_TEXT_Y = 3;
    private static final int FOOTER_HINT_Y = 15;
    private static final int FOOTER_BUTTON_Y = 27;
    /** Cap on visible rows; the window height can lower it further. */
    private static final int MAX_VISIBLE_ROWS = 16;
    private static final int SCROLL_GUTTER = 5;
    /** Height of every button in the panel. */
    private static final int BUTTON_H = 14;
    private static final int FOOTER_BUTTON_W = 72;

    // Right-aligned control cluster (measured from the panel's right edge).
    private static final int REMOVE_W = 16;
    private static final int REMOVE_X = -SCROLL_GUTTER - REMOVE_W;          // -21
    private static final int PLUS_W = 13;
    private static final int PLUS_X = REMOVE_X - 3 - PLUS_W;               // -37
    private static final int MINUS_W = 13;
    private static final int MINUS_X = PLUS_X - 3 - MINUS_W;               // -53
    /**
     * Room reserved for the quantity. The number is right-aligned inside it, so
     * a wide amount can never creep under the recipe button — the two columns
     * are only a few pixels apart and used to overlap for 4-digit counts.
     */
    private static final int AMOUNT_W = 28;
    private static final int AMOUNT_RIGHT = MINUS_X - 4;                   // -57
    private static final int AMOUNT_LEFT = AMOUNT_RIGHT - AMOUNT_W;        // -85
    private static final int RECIPE_W = 32;
    private static final int RECIPE_X = AMOUNT_LEFT - 4 - RECIPE_W;        // -121

    /** Width of the ore-filter switch in the footer. */
    private static final int ORES_W = 100;

    private static final int TEXT = 0xFFE0E0E0;
    private static final int MUTED = 0xFFA0A0A0;
    private static final int MISSING = 0xFFE06060;
    private static final int DIM = 0x80000000;
    private static final int PANEL_BG = 0xF0101010;
    private static final int PANEL_BORDER = 0xFF404040;
    private static final int ROW_STRIPE = 0x20FFFFFF;

    private final List<RecipeNode> roots = new ArrayList<>();
    private int scroll;

    public RecipeListScreen() {
        super(Component.translatable("screen.craftplanner.list.title"));
    }

    @Override
    protected void init() {
        this.roots.clear();
        this.roots.addAll(PlannerState.graph().roots());
        this.scroll = Math.min(this.scroll, maxScroll());
        buildWidgets();
    }

    private void buildWidgets() {
        clearWidgets();
        int left = panelLeft();
        int top = panelTop();
        int right = left + PANEL_WIDTH;

        for (int i = 0; i < visibleRows(); i++) {
            RecipeNode node = this.roots.get(this.scroll + i);
            int y = top + HEADER + i * ROW_HEIGHT + 2;

            addRenderableWidget(recipeButton(node, right + RECIPE_X, y));
            addRenderableWidget(Button.builder(Component.literal("-"), button -> change(node, -1))
                    .bounds(right + MINUS_X, y, MINUS_W, BUTTON_H)
                    .tooltip(Tooltip.create(Component.translatable("screen.craftplanner.list.decrease")))
                    .build());
            addRenderableWidget(Button.builder(Component.literal("+"), button -> change(node, 1))
                    .bounds(right + PLUS_X, y, PLUS_W, BUTTON_H)
                    .tooltip(Tooltip.create(Component.translatable("screen.craftplanner.list.increase")))
                    .build());
            addRenderableWidget(Button.builder(Component.literal("x"), button -> remove(node))
                    .bounds(right + REMOVE_X, y, REMOVE_W, BUTTON_H)
                    .tooltip(Tooltip.create(Component.translatable("screen.craftplanner.list.remove.tooltip")))
                    .build());
        }

        int footerY = footerTop() + FOOTER_BUTTON_Y;
        if (!this.roots.isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("screen.craftplanner.list.clear"),
                    button -> {
                        PlannerState.clear();
                        refresh();
                    })
                    .bounds(left + 6, footerY, FOOTER_BUTTON_W, BUTTON_H)
                    .tooltip(Tooltip.create(Component.translatable("screen.craftplanner.list.clear.tooltip")))
                    .build());
        }
        addRenderableWidget(Button.builder(Component.translatable("screen.craftplanner.list.close"),
                button -> onClose())
                .bounds(right - FOOTER_BUTTON_W - 4, footerY, FOOTER_BUTTON_W, BUTTON_H)
                .build());

        // Ore filter switch: sits between the two buttons and stays available
        // even with an empty list, because it changes the tree, not the list.
        addRenderableWidget(Button.builder(oresLabel(), button -> {
            PlannerState.toggleHideOres();
            refresh();
        })
                .bounds(left + PANEL_WIDTH / 2 - ORES_W / 2, footerY, ORES_W, BUTTON_H)
                .tooltip(Tooltip.create(Component.translatable("screen.craftplanner.list.ores.tooltip")))
                .build());
    }

    /** Label of the ore switch, showing the state the tree is currently in. */
    private static Component oresLabel() {
        return Component.translatable(PlannerState.hideOres()
                ? "screen.craftplanner.list.ores.hidden"
                : "screen.craftplanner.list.ores.shown");
    }

    /**
     * "2/3" button (Stage 6): cycles the recipes that produce the row's item.
     * Inactive when the item has a single recipe; the tooltip only reports the
     * position in that list, so the recipe itself is identified by its effect
     * on the tree rather than by name.
     */
    private Button recipeButton(RecipeNode node, int x, int y) {
        List<RecipeHolder<?>> options = node.options();
        int total = options.size();
        int index = total == 0 ? 0 : Math.min(node.selectedRecipeIndex(), total - 1);

        Button button = Button.builder(Component.literal(total > 0 ? (index + 1) + "/" + total : "-"),
                pressed -> cycleRecipe(node))
                .bounds(x, y, RECIPE_W, BUTTON_H)
                .tooltip(Tooltip.create(Component.translatable(
                        "screen.craftplanner.list.recipe.tooltip", index + 1, total)))
                .build();
        button.active = total > 1;
        return button;
    }

    private void cycleRecipe(RecipeNode node) {
        List<RecipeHolder<?>> options = node.options();
        if (options.size() < 2) {
            return;
        }
        int current = Math.min(node.selectedRecipeIndex(), options.size() - 1);
        PlannerState.selectRecipe(node.item(), options.get((current + 1) % options.size()));
        refresh();
    }

    private void change(RecipeNode node, int direction) {
        long step = hasShiftDown() ? 10L : 1L;
        long amount = Math.max(1, node.required() + direction * step);
        PlannerState.setAmount(node.item(), amount);
        refresh();
    }

    private void remove(RecipeNode node) {
        PlannerState.remove(node.item());
        this.scroll = Math.min(this.scroll, maxScroll());
        refresh();
    }

    private void refresh() {
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = panelLeft();
        int top = panelTop();
        int right = left + PANEL_WIDTH;
        int bottom = top + panelHeight();

        // Background first, widgets afterwards — the panel would otherwise be
        // painted over the buttons.
        graphics.fill(0, 0, this.width, this.height, DIM);
        graphics.fill(left, top, right, bottom, PANEL_BG);
        graphics.fill(left, top, right, top + 1, PANEL_BORDER);
        graphics.fill(left, bottom - 1, right, bottom, PANEL_BORDER);
        graphics.fill(left, top, left + 1, bottom, PANEL_BORDER);
        graphics.fill(right - 1, top, right, bottom, PANEL_BORDER);

        for (int i = 0; i < visibleRows(); i++) {
            if ((this.scroll + i) % 2 == 1) {
                int y = top + HEADER + i * ROW_HEIGHT;
                graphics.fill(left + 1, y, right - 1, y + ROW_HEIGHT, ROW_STRIPE);
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        Font font = this.font;
        graphics.drawCenteredString(font, this.title, left + PANEL_WIDTH / 2, top + 3, TEXT);

        // Column headers make the otherwise cryptic rows self-explanatory.
        if (!this.roots.isEmpty()) {
            int hy = top + 13;
            graphics.drawString(font, Component.translatable("screen.craftplanner.list.header.item"),
                    left + 6, hy, MUTED, false);
            graphics.drawCenteredString(font,
                    Component.translatable("screen.craftplanner.list.header.recipe"),
                    right + RECIPE_X + RECIPE_W / 2, hy, MUTED);
            graphics.drawCenteredString(font,
                    Component.translatable("screen.craftplanner.list.header.amount"),
                    right + (AMOUNT_LEFT + AMOUNT_RIGHT) / 2, hy, MUTED);
            graphics.drawCenteredString(font,
                    Component.translatable("screen.craftplanner.list.header.remove"),
                    right + REMOVE_X + REMOVE_W / 2, hy, MUTED);
        }

        if (this.roots.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("screen.craftplanner.list.empty"),
                    left + PANEL_WIDTH / 2, footerTop() + FOOTER_TEXT_Y, MUTED);
            graphics.drawCenteredString(font,
                    trimToWidth(font, Component.translatable("screen.craftplanner.list.hint").getString(),
                            PANEL_WIDTH - 16),
                    left + PANEL_WIDTH / 2, footerTop() + FOOTER_HINT_Y, MUTED);
        }

        for (int i = 0; i < visibleRows(); i++) {
            RecipeNode node = this.roots.get(this.scroll + i);
            int y = top + HEADER + i * ROW_HEIGHT;
            graphics.renderItem(node.displayStack(), left + 6, y + 1);
            String name = trimToWidth(font, node.item().name().getString(), right + RECIPE_X - 6 - (left + 26));
            graphics.drawString(font, name, left + 26, y + 5, TEXT, false);
            String amount = Long.toString(node.required());
            graphics.drawString(font, amount, right + AMOUNT_RIGHT - font.width(amount), y + 5, TEXT, false);
        }

        int footerLine = footerTop() + FOOTER_TEXT_Y;
        Map<ItemKey, Availability> availability = PlannerState.availability();
        long missing = ResourceCheck.totalMissing(availability);
        Component deficit = missing > 0
                ? Component.translatable("screen.craftplanner.list.missing",
                        missing, ResourceCheck.deficitTypes(availability))
                : null;

        // The summary and the deficit share one row: the deficit is pinned to
        // the right edge, so the summary gives way instead of running underneath
        // it (with long numbers the two would otherwise overlap).
        int deficitWidth = deficit == null ? 0 : font.width(deficit);
        int summaryRoom = PANEL_WIDTH - 12 - (deficitWidth == 0 ? 0 : deficitWidth + 12);
        Component summary = Component.translatable("screen.craftplanner.list.summary",
                rawTypes(), rawTotal());
        graphics.drawString(font, trimToWidth(font, summary.getString(), summaryRoom),
                left + 6, footerLine, MUTED, false);

        if (deficit != null) {
            graphics.drawString(font, deficit,
                    right - 6 - deficitWidth, footerLine, MISSING, false);
        }

        if (!this.roots.isEmpty()) {
            graphics.drawCenteredString(font,
                    trimToWidth(font, Component.translatable("screen.craftplanner.list.hint").getString(),
                            PANEL_WIDTH - 16),
                    left + PANEL_WIDTH / 2, footerTop() + FOOTER_HINT_Y, MUTED);
        }

        if (maxScroll() > 0) {
            int trackTop = top + HEADER;
            int trackHeight = visibleRows() * ROW_HEIGHT;
            int barHeight = Math.max(8, trackHeight * visibleRows() / this.roots.size());
            int barY = trackTop + (trackHeight - barHeight) * this.scroll / maxScroll();
            graphics.fill(right - 4, barY, right - 1, barY + barHeight, 0xFF909090);
        }
    }

    /** Cuts the item name with an ellipsis so it fits the given pixel width. */
    private static String trimToWidth(Font font, String text, int maxWidth) {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) {
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
        int max = maxScroll();
        if (max <= 0) {
            return false;
        }
        int next = this.scroll + (scrollY > 0 ? -1 : 1);
        this.scroll = Math.max(0, Math.min(max, next));
        refresh();
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private long rawTypes() {
        return requirements().size();
    }

    private long rawTotal() {
        long total = 0;
        for (Long amount : requirements().values()) {
            total += amount;
        }
        return total;
    }

    private Map<ItemKey, Long> requirements() {
        return PlannerState.graph().aggregateRequirements();
    }

    /**
     * Rows that fit at once: the configured cap, the number of targets, and the
     * window height. The height limit matters because the panel is centred, so
     * without it a tall list would grow past the screen edges at a high GUI
     * scale.
     */
    private int visibleRows() {
        int byHeight = Math.max(1, (this.height - HEADER - FOOTER) / ROW_HEIGHT);
        return Math.min(Math.min(MAX_VISIBLE_ROWS, this.roots.size()), byHeight);
    }

    private int maxScroll() {
        return Math.max(0, this.roots.size() - visibleRows());
    }

    private int panelHeight() {
        return HEADER + visibleRows() * ROW_HEIGHT + FOOTER;
    }

    /**
     * Top of the footer block, i.e. the first pixel row below the last list
     * row. Every footer element is placed relative to this, never relative to
     * the panel's bottom edge, so the block keeps its internal spacing whatever
     * the row count is (including an empty list, where the hint and the buttons
     * would otherwise collide).
     */
    private int footerTop() {
        return panelTop() + HEADER + visibleRows() * ROW_HEIGHT;
    }

    private int panelLeft() {
        return (this.width - PANEL_WIDTH) / 2;
    }

    private int panelTop() {
        return (this.height - panelHeight()) / 2;
    }
}
