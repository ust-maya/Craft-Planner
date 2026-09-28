package com.craftplanner.client.screen;

import com.craftplanner.client.PlannerState;
import com.craftplanner.client.overlay.TreeCollapse;
import com.craftplanner.client.overlay.TreeOverlayRenderer;
import com.craftplanner.graph.RecipeNode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.lwjgl.glfw.GLFW;

/**
 * Window with all targets the player added (Stage 7, hotkey {@code K}).
 *
 * <p>Each row shows the item, its requested amount and four controls: the
 * recipe switcher of Stage 6, {@code -}, {@code +} (hold Shift for steps of
 * 10) and {@code x} to drop the target. Every change is applied immediately
 * and re-expands the trees, so the overlay and the shopping list stay in
 * sync.</p>
 *
 * <p>The footer deliberately shows no raw-material summary and no deficit line:
 * whatever the list holds, the bottom of the window carries only the hint and
 * the buttons. The inventory comparison of Stage 9 itself is untouched — it
 * still feeds the overlay's shopping list and node colours.</p>
 *
 * <p>Stage 11: the footer also carries the ore switch — a button that flips
 * {@code filters.hideOres} at runtime, so ore blocks and raw ore materials can
 * be kept out of the tree without editing the config file and restarting.</p>
 *
 * <p>Layout (2026-09-25): the geometry lives in {@link RecipeListLayout} and is
 * measured from the font instead of hard-coded, so a long translation cannot
 * push text out of the panel: columns grow with their headers, the panel grows
 * with the columns, the footer hint wraps onto several lines and whatever is
 * still too wide is cut with an ellipsis inside its own box. This class only
 * draws the result and places the widgets from it.</p>
 */
public class RecipeListScreen extends Screen {

    // --- translation keys --------------------------------------------------
    private static final String KEY_TITLE = "screen.craftplanner.list.title";
    private static final String KEY_CLEAR = "screen.craftplanner.list.clear";
    private static final String KEY_CLOSE = "screen.craftplanner.list.close";
    private static final String KEY_EMPTY = "screen.craftplanner.list.empty";
    private static final String KEY_HINT = "screen.craftplanner.list.hint";
    private static final String KEY_HEADER_ITEM = "screen.craftplanner.list.header.item";
    private static final String KEY_HEADER_RECIPE = "screen.craftplanner.list.header.recipe";
    private static final String KEY_HEADER_AMOUNT = "screen.craftplanner.list.header.amount";
    private static final String KEY_HEADER_REMOVE = "screen.craftplanner.list.header.remove";
    private static final String KEY_ORES_HIDDEN = "screen.craftplanner.list.ores.hidden";
    private static final String KEY_ORES_SHOWN = "screen.craftplanner.list.ores.shown";

    private static final int TEXT = 0xFFE0E0E0;
    private static final int MUTED = 0xFFA0A0A0;
    private static final int DIM = 0x80000000;
    private static final int PANEL_BG = 0xF0101010;
    private static final int PANEL_BORDER = 0xFF404040;
    private static final int ROW_STRIPE = 0x20FFFFFF;

    private final List<RecipeNode> roots = new ArrayList<>();
    private int scroll;
    /** Measured geometry of the current list; rebuilt by {@link #computeLayout()}. */
    private RecipeListLayout layout;
    /** {@code font::width}, refreshed in {@link #init()} — the layout's only input. */
    private TextMetrics metrics;
    /** Graph generation the widgets were last built from (see {@link #tick()}). */
    private long seenGraphVersion;

    public RecipeListScreen() {
        super(Component.translatable(KEY_TITLE));
    }

    @Override
    protected void init() {
        this.metrics = this.font::width;
        this.roots.clear();
        this.roots.addAll(PlannerState.graph().roots());
        this.seenGraphVersion = PlannerState.graphVersion();
        computeLayout();
        this.scroll = Math.min(this.scroll, maxScroll());
        buildWidgets();
    }

    /**
     * A recipe switch is applied after the {@code selectRecipe} debounce, so
     * the window cannot refresh from the click alone: when the tree was
     * rebuilt while this screen was open, re-read the roots and widgets.
     */
    @Override
    public void tick() {
        if (PlannerState.graphVersion() != this.seenGraphVersion) {
            refresh();
        }
    }

    /**
     * Measures the panel for the current list. Everything else — widget bounds,
     * drawing, the panel height — reads the result, so they cannot drift apart.
     */
    private void computeLayout() {
        List<String> names = new ArrayList<>(this.roots.size());
        long maxAmount = 1L;
        int maxOptions = 1;
        for (RecipeNode node : this.roots) {
            names.add(node.item().name().getString());
            maxAmount = Math.max(maxAmount, node.required());
            maxOptions = Math.max(maxOptions, node.options().size());
        }
        // The hint teaches the add-a-recipe gesture once; after the player has
        // opened and closed this window at least once it only adds noise.
        this.layout = new RecipeListLayout(this.metrics, this.width, names, maxAmount, maxOptions,
                tr(KEY_HEADER_RECIPE), tr(KEY_HEADER_AMOUNT), tr(KEY_HEADER_REMOVE),
                tr(KEY_CLEAR), tr(KEY_CLOSE), oresLabel().getString(),
                PlannerState.listHintSeen() ? "" : tr(KEY_HINT));
    }

    @Override
    public void onClose() {
        PlannerState.markListHintSeen();
        super.onClose();
    }

    private void buildWidgets() {
        clearWidgets();
        RecipeListLayout l = this.layout;
        int left = panelLeft();
        int top = panelTop();
        int right = left + l.panelWidth;

        for (int i = 0; i < visibleRows(); i++) {
            RecipeNode node = this.roots.get(this.scroll + i);
            int y = top + RecipeListLayout.HEADER + i * RecipeListLayout.ROW_HEIGHT + 2;

            addRenderableWidget(recipeButton(node, right + l.recipeX, y));
            addRenderableWidget(FitButton.create(right + l.minusX, y, l.minusW, RecipeListLayout.BUTTON_H,
                    Component.literal("-"), button -> change(node, -1),
                    Component.translatable("screen.craftplanner.list.decrease")));
            addRenderableWidget(FitButton.create(right + l.plusX, y, l.plusW, RecipeListLayout.BUTTON_H,
                    Component.literal("+"), button -> change(node, 1),
                    Component.translatable("screen.craftplanner.list.increase")));
            addRenderableWidget(FitButton.create(right + l.removeX, y, l.removeW, RecipeListLayout.BUTTON_H,
                    Component.literal("x"), button -> remove(node),
                    Component.translatable("screen.craftplanner.list.remove.tooltip")));
        }

        int footerY = footerTop() + l.buttonsY;
        if (!this.roots.isEmpty()) {
            addRenderableWidget(FitButton.create(left + l.clearX, footerY, l.clearW, RecipeListLayout.BUTTON_H,
                    Component.translatable(KEY_CLEAR),
                    button -> {
                        PlannerState.clear();
                        refresh();
                    },
                    Component.translatable("screen.craftplanner.list.clear.tooltip")));
        }
        addRenderableWidget(FitButton.create(left + l.closeX, footerY, l.closeW, RecipeListLayout.BUTTON_H,
                Component.translatable(KEY_CLOSE),
                button -> onClose(), null));

        // Ore filter switch: sits between the two buttons and stays available
        // even with an empty list, because it changes the tree, not the list.
        addRenderableWidget(FitButton.create(left + l.oresX, footerY, l.oresW, RecipeListLayout.BUTTON_H,
                oresLabel(),
                button -> {
                    PlannerState.toggleHideOres();
                    refresh();
                },
                Component.translatable("screen.craftplanner.list.ores.tooltip")));
    }

    /**
     * Label of the ore switch. The leading mark is the state indicator the
     * plain text lacked: ✓ when ores are shown in the tree, ✗ when the filter
     * keeps them out.
     */
    private static Component oresLabel() {
        boolean hidden = PlannerState.hideOres();
        return Component.literal(hidden ? "✗ " : "✓ ")
                .append(Component.translatable(hidden ? KEY_ORES_HIDDEN : KEY_ORES_SHOWN));
    }

    /**
     * "2/3 ▾" button (Stage 6): the row's item is produced by {@code 3} recipes
     * and {@code 2} of them is in use. Inactive when there is only one way, and
     * the chevron says the rest. Clicking opens {@link RecipePickerScreen}, which
     * names every option — cycling them blindly left the player choosing an index
     * without ever seeing what the index was.
     */
    private Button recipeButton(RecipeNode node, int x, int y) {
        List<RecipeHolder<?>> options = node.options();
        int total = options.size();
        int index = total == 0 ? 0 : Math.min(node.selectedRecipeIndex(), total - 1);
        String counter = total > 0 ? (index + 1) + "/" + total : "-";

        Button button = FitButton.create(x, y, this.layout.recipeW, RecipeListLayout.BUTTON_H,
                Component.literal(total > 1 ? counter + " ▾" : counter),
                pressed -> this.minecraft.setScreen(new RecipePickerScreen(node.item(), this)),
                Component.translatable("screen.craftplanner.list.recipe.tooltip",
                        index + 1, total));
        button.active = total > 1;
        return button;
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
        if (this.layout == null) {
            // Defensive: init() always measures first, but render must never
            // depend on that order.
            if (this.metrics == null) {
                this.metrics = this.font::width;
            }
            computeLayout();
        }
        RecipeListLayout l = this.layout;
        TextMetrics m = this.metrics;
        int left = panelLeft();
        int top = panelTop();
        int right = left + l.panelWidth;
        int bottom = top + panelHeight();
        int innerW = l.panelWidth - 2 * RecipeListLayout.PAD;

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
                int y = top + RecipeListLayout.HEADER + i * RecipeListLayout.ROW_HEIGHT;
                graphics.fill(left + 1, y, right - 1, y + RecipeListLayout.ROW_HEIGHT, ROW_STRIPE);
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        Font font = this.font;
        drawCentered(font, m, graphics, this.title.getString(), left + l.panelWidth / 2, top + 3, innerW, TEXT);

        // Column headers make the otherwise cryptic rows self-explanatory. Each
        // one is trimmed to its own column, so they can neither overlap each
        // other nor leave the panel.
        if (!this.roots.isEmpty()) {
            int hy = top + 13;
            drawTrimmed(font, m, graphics, tr(KEY_HEADER_ITEM), left + RecipeListLayout.PAD, hy,
                    (right + l.recipeX) - RecipeListLayout.GAP - (left + RecipeListLayout.PAD), MUTED);
            drawCentered(font, m, graphics, tr(KEY_HEADER_RECIPE),
                    right + l.recipeX + l.recipeW / 2, hy, l.recipeW - 4, MUTED);
            drawCentered(font, m, graphics, tr(KEY_HEADER_AMOUNT),
                    right + (l.amountLeft + l.amountRight) / 2, hy, l.amountW - 4, MUTED);
            drawCentered(font, m, graphics, tr(KEY_HEADER_REMOVE),
                    right + l.removeX + l.removeW / 2, hy, l.removeW - 4, MUTED);
        }

        for (int i = 0; i < visibleRows(); i++) {
            RecipeNode node = this.roots.get(this.scroll + i);
            int y = top + RecipeListLayout.HEADER + i * RecipeListLayout.ROW_HEIGHT;
            graphics.renderItem(node.displayStack(), left + RecipeListLayout.PAD, y + 1);
            graphics.drawString(font, RecipeListLayout.trimToWidth(m, node.item().name().getString(), l.nameW),
                    left + RecipeListLayout.NAME_LEFT, y + 5, TEXT, false);
            String amount = RecipeListLayout.trimToWidth(m, Long.toString(node.required()), l.amountW);
            graphics.drawString(font, amount, right + l.amountRight - m.width(amount), y + 5, TEXT, false);
        }

        renderFooter(graphics, font, m, left);

        if (maxScroll() > 0) {
            int trackTop = top + RecipeListLayout.HEADER;
            int trackHeight = visibleRows() * RecipeListLayout.ROW_HEIGHT;
            int barHeight = Math.max(8, trackHeight * visibleRows() / this.roots.size());
            int barY = trackTop + (trackHeight - barHeight) * this.scroll / maxScroll();
            graphics.fill(right - 4, barY, right - 1, barY + barHeight, 0xFF909090);
        }
    }

    /**
     * Footer: the empty-list message (only when there is nothing to plan) and
     * the wrapped hint. The button row is made of widgets and is drawn by
     * {@code super.render}.
     *
     * <p>No raw-material summary and no deficit line are drawn here — by design,
     * in every state of the list. {@link RecipeListLayout} therefore reserves no
     * row for them, so the footer is exactly as tall as the hint needs.</p>
     */
    private void renderFooter(GuiGraphics graphics, Font font, TextMetrics m, int left) {
        RecipeListLayout l = this.layout;
        int innerW = l.panelWidth - 2 * RecipeListLayout.PAD;

        if (this.roots.isEmpty()) {
            drawCentered(font, m, graphics, tr(KEY_EMPTY), left + l.panelWidth / 2, footerTop() + 3, innerW, MUTED);
        }

        int hintY = footerTop() + l.hintY;
        for (int i = 0; i < l.hintLines.size(); i++) {
            drawCentered(font, m, graphics, l.hintLines.get(i), left + l.panelWidth / 2,
                    hintY + i * RecipeListLayout.LINE_H, innerW, MUTED);
        }
    }

    /** Draws the text left-aligned, cut to {@code maxWidth}. */
    private static void drawTrimmed(Font font, TextMetrics m, GuiGraphics graphics, String text,
            int x, int y, int maxWidth, int color) {
        graphics.drawString(font, RecipeListLayout.trimToWidth(m, text, maxWidth), x, y, color, false);
    }

    /** Draws the text centred on {@code centerX}, cut to {@code maxWidth}. */
    private static void drawCentered(Font font, TextMetrics m, GuiGraphics graphics, String text,
            int centerX, int y, int maxWidth, int color) {
        graphics.drawCenteredString(font, RecipeListLayout.trimToWidth(m, text, maxWidth), centerX, y, color);
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Intentionally empty: the vanilla blurred background would be drawn on
        // top of our panel. render() paints its own dim instead.
    }

    /**
     * The tree panel behind this window stays usable: its − / + markers fold a
     * branch and its badges open the recipe picker, because this is one of the
     * two screens that own the mouse. Clicks inside this window's own panel are
     * left to its widgets.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.layout != null
                && !overPanel((int) mouseX, (int) mouseY)) {
            int mx = (int) mouseX;
            int my = (int) mouseY;
            RecipeNode choice = TreeOverlayRenderer.hitChoice(mx, my);
            if (choice != null) {
                this.minecraft.setScreen(new RecipePickerScreen(choice.item(), this));
                return true;
            }
            String fold = TreeOverlayRenderer.hitFold(mx, my);
            if (fold != null) {
                TreeCollapse.toggle(fold);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean overPanel(int mx, int my) {
        int left = panelLeft();
        int top = panelTop();
        return mx >= left && mx <= left + this.layout.panelWidth
                && my >= top && my <= top + panelHeight();
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

    /**
     * Rows that fit at once: the configured cap, the number of targets, and the
     * window height. The height limit matters because the panel is centred, so
     * without it a tall list would grow past the screen edges at a high GUI
     * scale.
     */
    private int visibleRows() {
        return RecipeListLayout.visibleRows(this.height, this.layout.footerH, this.roots.size());
    }

    private int maxScroll() {
        return Math.max(0, this.roots.size() - visibleRows());
    }

    private int panelHeight() {
        return RecipeListLayout.panelHeight(visibleRows(), this.layout.footerH);
    }

    /**
     * Top of the footer block, i.e. the first pixel row below the last list
     * row. Every footer element is placed relative to this, never relative to
     * the panel's bottom edge, so the block keeps its internal spacing whatever
     * the row count is (including an empty list, where the hint and the buttons
     * would otherwise collide).
     */
    private int footerTop() {
        return panelTop() + RecipeListLayout.HEADER + visibleRows() * RecipeListLayout.ROW_HEIGHT;
    }

    private int panelLeft() {
        return (this.width - this.layout.panelWidth) / 2;
    }

    private int panelTop() {
        return (this.height - panelHeight()) / 2;
    }
}
