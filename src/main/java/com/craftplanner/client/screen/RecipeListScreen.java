package com.craftplanner.client.screen;

import com.craftplanner.client.Keybinds;
import com.craftplanner.client.PlannerState;
import com.craftplanner.client.TextMetrics;
import com.craftplanner.client.UiPalette;
import com.craftplanner.client.UiShapes;
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
    private static final String KEY_AMBIGUOUS = "screen.craftplanner.list.ambiguous";
    /** Tooltip of a row with several recipes, and of a row with exactly one. */
    private static final String KEY_RECIPE_TOOLTIP = "screen.craftplanner.list.recipe.tooltip";
    private static final String KEY_RECIPE_ONE = "screen.craftplanner.list.recipe.tooltip.one";
    /** Tooltip of a row whose branch the player cut — the words the tree badge shows. */
    private static final String KEY_CUT = "overlay.craftplanner.cut.tooltip";

    private static final int TEXT = UiPalette.TEXT;
    private static final int MUTED = UiPalette.MUTED;
    private static final int DIM = UiPalette.MODAL_SCRIM;
    private static final int PANEL_BG = UiPalette.MODAL_PANEL_BG;
    private static final int PANEL_BORDER = UiPalette.MODAL_PANEL_BORDER;
    private static final int ROW_STRIPE = UiPalette.MODAL_ROW_STRIPE;

    private final List<RecipeNode> roots = new ArrayList<>();
    private int scroll;
    /**
     * The four controls of each visible row, kept so a scroll can point them at
     * whatever root moved into that slot. They live at a fixed y — the slot does
     * not move, the list does — so only their labels and their target change.
     */
    private final List<RowControls> rowControls = new ArrayList<>();
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
        boolean anyCut = false;
        for (RecipeNode node : this.roots) {
            names.add(node.item().name().getString());
            maxAmount = Math.max(maxAmount, node.required());
            maxOptions = Math.max(maxOptions, node.options().size());
            anyCut |= PlannerState.isCut(node.item());
        }
        // The hint teaches the add-a-recipe gesture once; after the player has
        // opened and closed this window at least once it only adds noise.
        int ambiguous = BatchPickerScreen.ambiguousNodes().size();
        String ambiguousLabel = ambiguous == 0
                ? ""
                : Component.translatable(KEY_AMBIGUOUS, ambiguous).getString();
        this.layout = new RecipeListLayout(this.metrics, this.width, names, maxAmount, maxOptions,
                tr(KEY_HEADER_RECIPE), tr(KEY_HEADER_AMOUNT), tr(KEY_HEADER_REMOVE),
                tr(KEY_CLEAR), tr(KEY_CLOSE), oresLabel().getString(), ambiguousLabel,
                PlannerState.listHintSeen() ? "" : hint(), anyCut);
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

        this.rowControls.clear();
        for (int slot = 0; slot < visibleRows(); slot++) {
            final int row = slot;
            int y = top + RecipeListLayout.HEADER + slot * RecipeListLayout.ROW_HEIGHT + 2;
            // Every handler reads the root through the slot, at the moment it is
            // clicked: the row under a button changes when the list scrolls, and a
            // button that remembered the root it was built with would change the
            // amount of an item the player never aimed at.
            Button recipe = FitButton.create(right + l.recipeX, y, l.recipeW,
                    RecipeListLayout.BUTTON_H, Component.literal("-"),
                    pressed -> openPickerAt(row), null);
            Button minus = FitButton.create(right + l.minusX, y, l.minusW, RecipeListLayout.BUTTON_H,
                    Component.literal("-"), pressed -> change(rowRoot(row), -1),
                    Component.translatable("screen.craftplanner.list.decrease"));
            Button plus = FitButton.create(right + l.plusX, y, l.plusW, RecipeListLayout.BUTTON_H,
                    Component.literal("+"), pressed -> change(rowRoot(row), 1),
                    Component.translatable("screen.craftplanner.list.increase"));
            Button remove = FitButton.create(right + l.removeX, y, l.removeW, RecipeListLayout.BUTTON_H,
                    Component.literal("x"), pressed -> remove(rowRoot(row)),
                    Component.translatable("screen.craftplanner.list.remove.tooltip"));
            addRenderableWidget(recipe);
            addRenderableWidget(minus);
            addRenderableWidget(plus);
            addRenderableWidget(remove);
            this.rowControls.add(new RowControls(recipe, minus, plus, remove));
        }
        syncRowControls();

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

        // Batch recipe choice: only offered while the tree actually holds items
        // with more than one recipe — the engine's own pick is otherwise final.
        if (l.ambiguousW > 0) {
            addRenderableWidget(FitButton.create(left + l.ambiguousX, footerY, l.ambiguousW,
                    RecipeListLayout.BUTTON_H,
                    Component.literal(Component.translatable(KEY_AMBIGUOUS,
                            BatchPickerScreen.ambiguousNodes().size()).getString()),
                    button -> this.minecraft.setScreen(new BatchPickerScreen(this)),
                    Component.translatable("screen.craftplanner.list.ambiguous.tooltip")));
        }
    }

    /**
     * The first-run hint, with the bindings the player actually has: the sentence
     * used to print the letters N, G and O itself, so after any rebind in
     * Options → Controls it taught the wrong keys — the one thing a hint cannot
     * afford to be.
     */
    private String hint() {
        return Component.translatable(KEY_HINT,
                Keybinds.ADD_RECIPE.getTranslatedKeyMessage(),
                Keybinds.OPEN_TREE.getTranslatedKeyMessage(),
                Keybinds.EDIT_LAYOUT.getTranslatedKeyMessage()).getString();
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

    /** The controls of one visible row; see {@link #rowControls}. */
    private record RowControls(Button recipe, Button minus, Button plus, Button remove) {
    }

    /** The root currently drawn in row slot {@code slot}, or {@code null} past the end. */
    private RecipeNode rowRoot(int slot) {
        int index = this.scroll + slot;
        return index >= 0 && index < this.roots.size() ? this.roots.get(index) : null;
    }

    /** Opens the picker for whatever root the clicked slot shows. */
    private void openPickerAt(int slot) {
        RecipeNode node = rowRoot(slot);
        if (node != null) {
            this.minecraft.setScreen(new RecipePickerScreen(node.item(), this));
        }
    }

    /**
     * Re-labels the row controls from the roots that are under them now. Called
     * after a build and after every scroll, which is the whole cost of scrolling:
     * one recipe counter per row, not a rebuilt window.
     */
    private void syncRowControls() {
        for (int slot = 0; slot < this.rowControls.size(); slot++) {
            RowControls row = this.rowControls.get(slot);
            labelRecipeButton(row.recipe(), rowRoot(slot));
        }
    }

    /**
     * The "2/3 ▾" of a row: the row's item is produced by {@code 3} recipes and
     * {@code 2} of them is in use. Clicking the button opens
     * {@link RecipePickerScreen}, which names every option — cycling them blindly
     * left the player choosing an index without ever seeing what the index was.
     *
     * <p>A row with a single way is clickable too: the picker that opens carries
     * the "cut the tree here" switch, which is asked for exactly one way as much
     * as for several ("stop planning rabbit hide"). Only an item with no recipe at
     * all — plain raw material — has nothing to open, and a row whose branch is cut
     * carries {@code ✂}.</p>
     */
    private void labelRecipeButton(Button button, RecipeNode node) {
        if (node == null) {
            button.setMessage(Component.literal("-"));
            button.setTooltip(null);
            button.active = false;
            return;
        }
        List<RecipeHolder<?>> options = node.options();
        int total = options.size();
        int index = total == 0 ? 0 : Math.min(node.selectedRecipeIndex(), total - 1);
        boolean cut = PlannerState.isCut(node.item());
        button.setMessage(Component.literal((cut ? "✂ " : "") + (total > 0
                ? (index + 1) + "/" + total + " ▾" : "-")));
        button.setTooltip(total == 0 ? null
                : cut ? Tooltip.create(Component.translatable(KEY_CUT))
                : Tooltip.create(Component.translatable(total > 1 ? KEY_RECIPE_TOOLTIP : KEY_RECIPE_ONE,
                        index + 1, total)));
        button.active = total > 0;
    }

    private void change(RecipeNode node, int direction) {
        if (node == null) {
            return;
        }
        long step = hasShiftDown() ? 10L : 1L;
        long amount = Math.max(1, node.required() + direction * step);
        PlannerState.setAmount(node.item(), amount);
        refresh();
    }

    private void remove(RecipeNode node) {
        if (node == null) {
            return;
        }
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
        UiShapes.panel(graphics, left, top, l.panelWidth, panelHeight(), PANEL_BG,
                UiPalette.MODAL_BORDER_OUTER, PANEL_BORDER);
        UiShapes.headerBand(graphics, left, top, l.panelWidth, RecipeListLayout.HEADER - 3,
                UiPalette.MODAL_HEADER_BG, UiPalette.ACCENT_LINE, RecipeListLayout.PAD);

        for (int i = 0; i < visibleRows(); i++) {
            if ((this.scroll + i) % 2 == 1) {
                int y = top + RecipeListLayout.HEADER + i * RecipeListLayout.ROW_HEIGHT;
                graphics.fill(left + 2, y, right - 2, y + RecipeListLayout.ROW_HEIGHT, ROW_STRIPE);
            }
        }

        // The amber bar names the row under the cursor; the row itself has no
        // hover fill because its buttons are widgets that paint later.
        int hoverRow = (mouseY - (top + RecipeListLayout.HEADER)) / RecipeListLayout.ROW_HEIGHT;
        if (hoverRow >= 0 && hoverRow < visibleRows()
                && mouseX >= left && mouseX <= right
                && mouseY >= top + RecipeListLayout.HEADER && mouseY < footerTop()) {
            graphics.fill(left + 2, top + RecipeListLayout.HEADER + hoverRow * RecipeListLayout.ROW_HEIGHT,
                    left + 4, top + RecipeListLayout.HEADER + (hoverRow + 1) * RecipeListLayout.ROW_HEIGHT,
                    UiPalette.SELECT_BAR);
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
            graphics.fill(right - 4, trackTop, right - 1, trackTop + trackHeight, UiPalette.SCROLL_TRACK);
            graphics.fill(right - 4, barY, right - 1, barY + barHeight, UiPalette.SCROLL_BAR);
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
     * A click on this window's own panel pages its list; everything else is left
     * to its widgets. The overlay behind it is no longer clickable from here: a
     * HUD row cannot be aimed at reliably, which is why the tree's controls moved
     * to {@link CraftTreeScreen}.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.layout != null
                && overPanel((int) mouseX, (int) mouseY)
                && scrollTrackClick((int) mouseX, (int) mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * A click on the thin track at the panel's right edge jumps the list to
     * that position. The HUD bar is draggable; here one click is the whole
     * gesture — the same browser-style jump the HUD track answers with.
     */
    private boolean scrollTrackClick(int mx, int my) {
        if (maxScroll() <= 0) {
            return false;
        }
        RecipeListLayout l = this.layout;
        int right = panelLeft() + l.panelWidth;
        int trackTop = panelTop() + RecipeListLayout.HEADER;
        int trackHeight = visibleRows() * RecipeListLayout.ROW_HEIGHT;
        if (mx < right - 6 || mx > right || my < trackTop || my >= trackTop + trackHeight) {
            return false;
        }
        this.scroll = Math.max(0, Math.min(maxScroll(),
                (int) ((long) (my - trackTop) * maxScroll() / trackHeight)));
        syncRowControls();
        return true;
    }

    private boolean overPanel(int mx, int my) {
        int left = panelLeft();
        int top = panelTop();
        return mx >= left && mx <= left + this.layout.panelWidth                && my >= top && my <= top + panelHeight();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = maxScroll();
        if (max <= 0) {
            return false;
        }
        int next = this.scroll + (scrollY > 0 ? -1 : 1);
        // Which rows are drawn is the only thing a notch of the wheel decides: the
        // panel is the same size and its widgets — the header, the footer buttons —
        // sit outside the list. refresh() rebuilt those widgets, so scrolling cost a
        // layout pass and dropped the button the cursor was resting on.
        this.scroll = Math.max(0, Math.min(max, next));
        syncRowControls();
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
