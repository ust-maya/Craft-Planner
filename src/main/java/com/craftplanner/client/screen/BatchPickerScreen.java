package com.craftplanner.client.screen;

import com.craftplanner.client.AmountLabel;
import com.craftplanner.client.PlanRows;
import com.craftplanner.client.PlannerState;
import com.craftplanner.client.Scrollbar;
import com.craftplanner.client.UiPalette;
import com.craftplanner.client.UiShapes;
import com.craftplanner.client.overlay.NodeColors;
import com.craftplanner.client.overlay.TreeCollapse;
import com.craftplanner.client.tree.TreeFlatten;
import com.craftplanner.client.tree.TreeMetrics;
import com.craftplanner.client.tree.TreeRow;
import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.RecipeNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * One place for every decision the engine could not make alone.
 *
 * <p>The tree marks rows whose item has more than one recipe (a {@code 2/3}
 * badge), but finding them one by one in a long plan is chores — this screen
 * lists all of them at once and opens the per-item {@link RecipePickerScreen}
 * from a single click. The choices are the same ones the badges make, keyed by
 * item and saved through {@code RecipeChoices}, so the overlay, the K window
 * and this list always agree.</p>
 *
 * <p>It used to be a flat list of names, which answers "what can I decide?" and
 * nothing else. The question the player actually has is "which of these is worth
 * deciding, and what does it cost me?" — and neither is answerable from a list,
 * because both are properties of the branch the decision sits in. Copper ingots
 * appearing four times and a copper block appearing once are rows of the same
 * width; the difference is that one of them drags eight slabs behind it.</p>
 *
 * <p>So each decision is shown as the branch it heads, taken from the plan that
 * already exists rather than from a fresh expansion: the item's own row, then
 * everything the plan currently has hanging off it, drawn with the tree's own
 * geometry. Nothing is recomputed, which is why opening this window costs
 * nothing, and the branches it shows are the ones the overlay is drawing — a fold
 * made here folds there, and the {@code (+12)} on a closed branch is the plan's
 * own hidden count.</p>
 *
 * <p>A decision nested inside another decision's branch is shown once, as part of
 * that branch; its own heading would be the same row twice. What is left is a
 * tree whose headings are exactly the things still open to a decision, and whose
 * branches are what answering them would cost.</p>
 *
 * <p>Like the picker, nothing is held across a rebuild: the engine re-expands
 * the tree in place, so every refresh re-reads the graph (see {@link #tick()}).
 * Items are listed in tree order and only once, because a choice for an item
 * applies to every row carrying it.</p>
 */
public final class BatchPickerScreen extends Screen {

    /** Height of one row; the plan's own row height, so the two read as one tree. */
    private static final int ROW_H = TreeMetrics.ROW_H;
    private static final int HEADER_H = 24;
    private static final int PAD = 6;
    /** Air the panel keeps from the screen edges. */
    private static final int MARGIN = 8;
    /** Rows that are always there under the branches: just the close row. */
    private static final int CONTROL_ROWS = 1;
    /** Air between a name and the column that follows it. */
    private static final int TEXT_GAP = 6;
    /** Edge of the square recipe button, the tree's own size. */
    private static final int RECIPE_BTN = 18;
    private static final int PANEL_BG = UiPalette.MODAL_PANEL_BG;
    private static final int PANEL_BORDER = UiPalette.MODAL_PANEL_BORDER;
    private static final int DIM = UiPalette.MODAL_SCRIM;
    private static final int TEXT = UiPalette.TEXT;

    private final Screen returnTo;
    /**
     * The branches as plan-row indices: one heading per decision, followed by the
     * rows hanging off it. Indices, not nodes, because the plan rebuilds its rows
     * on every change and anything held across one would point at a stale tree.
     */
    private List<Line> lines = List.of();
    private List<TreeRow<RecipeNode>> plan = List.of();
    /** The items still open to a decision, so any row carrying one can be clicked. */
    private Set<ItemKey> ambiguous = Set.of();
    private int panelWidth;
    private int scroll;
    /** True while the left button holds the thumb; only then does a drag move the list. */
    private boolean thumbDrag;
    /** Cursor row and scroll offset at the moment the thumb was taken, for a drag relative to them. */
    private int grabY;
    private int grabScroll;
    private long seenGraphVersion;
    private long seenFoldVersion = -1;
    private long seenAvailabilityVersion = -1;

    /**
     * One drawn line of a branch.
     *
     * @param planRow  index into the plan
     * @param head     whether this line is the decision itself rather than its branch
     * @param headLine band line of the heading this row hangs off, itself for a heading
     */
    private record Line(int planRow, boolean head, int headLine) {
    }

    public BatchPickerScreen(Screen returnTo) {
        super(Component.translatable("screen.craftplanner.ambiguous.title"));
        this.returnTo = returnTo;
    }

    /**
     * Every planned item with more than one recipe, in tree order and once per
     * item — the decisions this screen offers and the footer button counts.
     */
    public static List<RecipeNode> ambiguousNodes() {
        LinkedHashMap<ItemKey, RecipeNode> byItem = new LinkedHashMap<>();
        PlannerState.graph().forEachNode(node -> {
            if (!node.isFluidRequirement() && node.options().size() > 1) {
                byItem.putIfAbsent(node.item(), node);
            }
        });
        return new ArrayList<>(byItem.values());
    }

    @Override
    protected void init() {
        this.seenGraphVersion = PlannerState.graphVersion();
        this.seenFoldVersion = TreeCollapse.version();
        this.seenAvailabilityVersion = PlannerState.availabilityVersion();
        if (this.minecraft.level == null) {
            // The world went away: nothing to decide in, so close. An empty branch
            // list stays open on purpose — the player clicked "Alternatives" and
            // must be told there is nothing to decide, not watch the window flash shut.
            this.minecraft.setScreen(this.returnTo);
            return;
        }
        this.plan = PlanRows.rows();
        relayout();
        this.panelWidth = measure();
        this.scroll = Math.min(this.scroll, maxScroll());
    }

    @Override
    public void tick() {
        // A fold made here is a fold of the plan, so it lands through the same
        // version the overlay watches: without this the window would keep drawing
        // a branch the overlay has already closed.
        if (PlannerState.graphVersion() != this.seenGraphVersion
                || TreeCollapse.version() != this.seenFoldVersion) {
            init();
        } else if (PlannerState.availabilityVersion() != this.seenAvailabilityVersion) {
            // The player moved materials around while deciding. The rows carry the
            // inventory's verdict on what they name, so they are re-read — but the
            // panel is not laid out again: nothing about the plan changed, and a
            // relayout would slide the list under the cursor once a second.
            this.seenAvailabilityVersion = PlannerState.availabilityVersion();
            this.plan = PlanRows.rows();
        }
    }

    /**
     * Lays the decisions out as branches: the item's own plan row, then every row
     * the plan has below it, which is its subtree by construction of a depth-first
     * flattening.
     *
     * <p>A row already printed inside an earlier branch is never printed twice —
     * two decisions can sit inside one another, and the inner one is on screen as
     * part of the outer branch. But a decision is never <em>lost</em> that way:
     * if its first appearance in the plan is already inside another branch, it
     * takes the next one that is not, and if there is none it is simply already
     * visible where it is. Every row that names a switchable item stays clickable
     * either way, so an inner decision is one click from where it is drawn.</p>
     */
    private void relayout() {
        List<Line> built = new ArrayList<>();
        Set<Integer> shown = new HashSet<>();
        this.ambiguous = new HashSet<>();
        for (RecipeNode node : ambiguousNodes()) {
            this.ambiguous.add(node.item());
            int at = firstUncovered(node.item(), shown);
            if (at < 0) {
                continue;
            }
            int headLine = built.size();
            built.add(new Line(at, true, headLine));
            int end = TreeFlatten.subtreeEnd(this.plan, at);
            for (int r = at + 1; r <= end; r++) {
                shown.add(r);
                built.add(new Line(r, false, headLine));
            }
        }
        this.lines = built;
    }

    /**
     * The plan's first row for an item that no earlier branch has printed yet, or
 * {@code -1} when the plan does not carry it or every row of it is already shown.
     */
    private int firstUncovered(ItemKey item, Set<Integer> shown) {
        for (int r = 0; r < this.plan.size(); r++) {
            if (!this.plan.get(r).node().isFluidRequirement()
                    && this.plan.get(r).node().item().equals(item) && !shown.contains(r)) {
                return r;
            }
        }
        return -1;
    }

    /**
     * How deep a line is drawn.
     *
     * <p>The heading is the column the plan gave it and everything below hangs off
     * that, one step in: the depth the plan assigned is measured from the goal at
     * the top, and the goal is not part of this window, so keeping the plan's own
     * numbers would leave the first branch indented by however deep it happens to
     * sit in the plan.</p>
     */
    private int depthOf(Line line) {
        if (line.head()) {
            return 0;
        }
        int headDepth = this.plan.get(this.lines.get(line.headLine()).planRow()).depth();
        return Math.max(1, this.plan.get(line.planRow()).depth() - headDepth);
    }

    /**
     * Width the panel needs: the widest heading, the widest branch row under one,
     * and the control rows, so nothing that has to be read can be the row the
     * panel is too narrow for.
     */
    private int measure() {
        int widest = this.font.width(this.title);
        int amountW = this.font.width("×9999 (+9999)");
        int counterW = RECIPE_BTN + TEXT_GAP + this.font.width("9/9");
        for (Line line : this.lines) {
            TreeRow<RecipeNode> row = this.plan.get(line.planRow());
            widest = Math.max(widest, TreeMetrics.labelX(depthOf(line))
                    + this.font.width(row.name())
                    + TEXT_GAP + amountW
                    + (line.head() ? TEXT_GAP + counterW : 0));
        }
        widest = Math.max(widest, TreeMetrics.TOGGLE_W
                + this.font.width(Component.translatable("screen.craftplanner.list.close").getString()));
        return Math.min(this.width - 2 * MARGIN, Math.max(240, widest + 2 + 2 * PAD));
    }

    private int visibleLines() {
        int available = this.height - 2 * MARGIN - HEADER_H - CONTROL_ROWS * ROW_H - PAD;
        return Math.max(1, Math.min(this.lines.size(), available / ROW_H));
    }

    private int maxScroll() {
        return Math.max(0, this.lines.size() - visibleLines());
    }

    private int panelHeight() {
        return HEADER_H + visibleLines() * ROW_H + CONTROL_ROWS * ROW_H + PAD;
    }

    private int panelLeft() {
        return (this.width - this.panelWidth) / 2;
    }

    private int top() {
        return Math.max(MARGIN, (this.height - panelHeight()) / 2);
    }

    private int bandTop() {
        return top() + HEADER_H;
    }

    private int lineTop(int index) {
        return bandTop() + (index - this.scroll) * ROW_H;
    }

    private int hitLine(int mx, int my) {
        int left = panelLeft();
        if (mx < left + PAD || mx >= left + this.panelWidth - PAD) {
            return -1;
        }
        int offset = my - bandTop();
        int drawn = visibleLines();
        if (offset < 0 || offset >= drawn * ROW_H) {
            return -1;
        }
        return this.scroll + offset / ROW_H;
    }

    /** The control row under a point, or {@code -1}. */
    private int hitControl(int mx, int my) {
        int left = panelLeft();
        if (mx < left + PAD || mx >= left + this.panelWidth - PAD) {
            return -1;
        }
        int offset = my - (bandTop() + visibleLines() * ROW_H);
        if (offset < 0 || offset >= CONTROL_ROWS * ROW_H) {
            return -1;
        }
        return offset / ROW_H;
    }

    /**
     * Whether a point is on a line's fold button.
     *
     * <p>The button is drawn where the tree draws it — the plate the row's own
     * depth puts it at — rather than in one column of its own, because a branch
     * three levels down has its control three indents to the right, and a hit box
     * that ignored that would fold the wrong row.</p>
     */
    private boolean overToggle(int mx, int my, int index) {
        int x = panelLeft() + PAD + TreeMetrics.toggleX(depthOf(this.lines.get(index)));
        int y = lineTop(index);
        return mx >= x && mx < x + TreeMetrics.TOGGLE_W && my >= y && my < y + ROW_H;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        int mx = (int) mouseX;
        int my = (int) mouseY;
        // The bar is checked before the rows: its grab zone reaches a few pixels into
        // the band, and a click meant for the thumb must not open a recipe picker.
        if (grabThumb(mx, my)) {
            return true;
        }
        int jumped = trackJump(mx, my);
        if (jumped >= 0) {
            this.scroll = jumped;
            // The bare track brings the thumb under the cursor; holding the button
            // then drags it from there, as on any other scrollbar.
            startThumbDrag(my);
            return true;
        }
        int index = hitLine(mx, my);
        if (index >= 0 && index < this.lines.size()) {
            Line line = this.lines.get(index);
            TreeRow<RecipeNode> row = this.plan.get(line.planRow());
            if (row.foldable() && overToggle(mx, my, index)) {
                TreeCollapse.toggle(row.path());
                refreshKeeping(index);
                return true;
            }
            // Any row naming a switchable item opens its picker, not only the
            // headings: a decision can end up inside another decision's branch, and
            // it is one click from there rather than one scroll away from a heading.
            if (this.ambiguous.contains(row.node().item())) {
                this.minecraft.setScreen(new RecipePickerScreen(row.node().item(), this));
            }
            return true;
        }
        if (hitControl(mx, my) == 0) {
            this.onClose();
            return true;
        }
        return false;
    }

    /**
     * Re-reads the plan after a fold, keeping the folded line where it was.
     *
     * <p>Closing a branch removes the rows under it and pulls everything below up
     * by however many there were, so leaving the scroll alone would slide the whole
     * list under the cursor for a click that only meant to close one branch.</p>
     */
    private void refreshKeeping(int anchorLine) {
        this.seenGraphVersion = PlannerState.graphVersion();
        this.seenFoldVersion = TreeCollapse.version();
        this.seenAvailabilityVersion = PlannerState.availabilityVersion();
        this.plan = PlanRows.rows();
        relayout();
        this.panelWidth = measure();
        this.scroll = Math.max(0, Math.min(anchorLine, maxScroll()));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() <= 0) {
            return false;
        }
        this.scroll = Math.max(0, Math.min(maxScroll(), this.scroll + (scrollY > 0 ? -1 : 1)));
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.thumbDrag) {
            this.scroll = Scrollbar.scrollDragged(bar(panelLeft(), visibleLines()), this.grabScroll,
                    (int) mouseY - this.grabY, maxScroll());
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.thumbDrag = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * Takes hold of the thumb under the cursor. Taking hold does not move it; only
     * the drag that follows does, measured from here — the same grab the K window has.
     */
    private boolean grabThumb(int mx, int my) {
        if (maxScroll() <= 0 || !Scrollbar.overThumb(bar(panelLeft(), visibleLines()), mx, my)) {
            return false;
        }
        startThumbDrag(my);
        return true;
    }

    private void startThumbDrag(int my) {
        this.thumbDrag = true;
        this.grabY = my;
        this.grabScroll = this.scroll;
    }

    /** A click on the bare track pages the list to that point; {@code -1} off the track. */
    private int trackJump(int mx, int my) {
        if (maxScroll() <= 0) {
            return -1;
        }
        Scrollbar.Geometry bar = bar(panelLeft(), visibleLines());
        return Scrollbar.overColumn(bar, mx, my) ? Scrollbar.scrollAt(bar, my, maxScroll()) : -1;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Empty on purpose, like the picker: render() paints its own dim.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = panelLeft();
        int top = top();
        graphics.fill(0, 0, this.width, this.height, DIM);
        UiShapes.panel(graphics, left, top, this.panelWidth, panelHeight(), PANEL_BG,
                UiPalette.MODAL_BORDER_OUTER, PANEL_BORDER);
        UiShapes.headerBand(graphics, left, top, this.panelWidth, HEADER_H - 4,
                UiPalette.MODAL_HEADER_BG, UiPalette.ACCENT_LINE, PAD);
        drawHeader(graphics, left, top);

        int shown = visibleLines();
        if (this.lines.isEmpty()) {
            drawEmpty(graphics, left);
        } else {
            drawBranches(graphics, left, mouseX, mouseY, shown);
            Component tooltip = tooltipAt(hitLine(mouseX, mouseY), mouseX, mouseY);
            if (tooltip != null) {
                setTooltipForNextRenderPass(tooltip);
            }
        }

        drawControl(graphics, left, bandTop() + shown * ROW_H, hitControl(mouseX, mouseY) == 0);
        drawScrollbar(graphics, left, shown);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawHeader(GuiGraphics graphics, int left, int top) {
        // The header names the count, but a long translation must not leave the
        // band — it is trimmed to the panel like every other string here.
        String header = RecipeListLayout.trimToWidth(this.font::width,
                Component.translatable("screen.craftplanner.ambiguous.header", decisions())
                        .getString(),
                this.panelWidth - 2 * PAD);
        graphics.drawCenteredString(this.font, header, left + this.panelWidth / 2, top + 6, TEXT);
    }

    private int decisions() {
        int heads = 0;
        for (Line line : this.lines) {
            if (line.head()) {
                heads++;
            }
        }
        return heads;
    }

    private void drawEmpty(GuiGraphics graphics, int left) {
        String message = RecipeListLayout.trimToWidth(this.font::width,
                Component.translatable("screen.craftplanner.ambiguous.empty").getString(),
                this.panelWidth - 2 * PAD - TreeMetrics.TOGGLE_W);
        graphics.drawString(this.font, message, left + PAD + TreeMetrics.TOGGLE_W,
                bandTop() + (ROW_H - this.font.lineHeight) / 2, UiPalette.MUTED, false);
    }

    /**
     * Every visible line: the heading bands, then the guides that tie a branch
     * together, then the rows. A branch keeps its shape even when the window is
     * scrolled into the middle of it, because every row carries the heading it
     * hangs off rather than looking at its neighbours.
     */
    private void drawBranches(GuiGraphics graphics, int left, int mouseX, int mouseY, int shown) {
        int contentLeft = left + PAD;
        int contentRight = left + this.panelWidth - PAD;
        int first = this.scroll;
        int last = Math.min(this.lines.size() - 1, first + shown - 1);
        int hover = hitLine(mouseX, mouseY);

        // Clipped to the band: a branch whose heading sits above the window runs
        // past the top edge, and one scrolled off the bottom runs past that.
        graphics.enableScissor(left + 1, bandTop(), left + this.panelWidth - 1,
                bandTop() + shown * ROW_H);

        for (int i = first; i <= last; i++) {
            Line line = this.lines.get(i);
            int depth = depthOf(line);
            if (line.head()) {
                graphics.fill(contentLeft, lineTop(i), contentRight, lineTop(i) + ROW_H,
                        UiPalette.MODAL_ROW_SELECTED);
            } else if (i == hover) {
                graphics.fill(contentLeft, lineTop(i), contentRight, lineTop(i) + ROW_H,
                        UiPalette.MODAL_ROW_HOVER);
            }
            if (depth > 0) {
                graphics.hLine(contentLeft + TreeMetrics.spineX(depth - 1),
                        contentLeft + TreeMetrics.iconX(depth) - 1,
                        lineTop(i) + ROW_H / 2, UiPalette.TREE_GUIDE);
            }
        }

        for (int i = first; i <= last; i++) {
            Line line = this.lines.get(i);
            drawBranchLine(graphics, line, i, i == hover,
                    overToggle(mouseX, mouseY, i) && this.plan.get(line.planRow()).foldable(),
                    contentLeft, contentRight);
        }
        graphics.disableScissor();
    }

    /** Draws one line of a branch: fold button, icon, name, amount and counter. */
    private void drawBranchLine(GuiGraphics graphics, Line line, int index, boolean hovered,
            boolean aimed, int contentLeft, int contentRight) {
        TreeRow<RecipeNode> row = this.plan.get(line.planRow());
        RecipeNode node = row.node();
        int depth = depthOf(line);
        int y = lineTop(index);
        int textY = y + (ROW_H - this.font.lineHeight) / 2;

        if (row.foldable()) {
            int height = ROW_H - 4;
            UiShapes.foldButton(graphics, contentLeft + TreeMetrics.toggleX(depth),
                    y + (ROW_H - height) / 2, TreeMetrics.TOGGLE_W, height,
                    row.folded(), aimed);
        }
        graphics.renderItem(node.displayStack(), contentLeft + TreeMetrics.iconX(depth),
                y + (ROW_H - TreeMetrics.ICON) / 2);

        // A heading reserves room for the recipe button that ends it; the count that used
        // to be printed there lives in the tooltip now.
        int counterW = line.head() ? RECIPE_BTN : 0;
        String amount = AmountLabel.of(node) + (row.hiddenRows() > 0
                ? " (+" + row.hiddenRows() + ")" : "");
        int nameX = contentLeft + TreeMetrics.labelX(depth);
        int nameRoom = contentRight - nameX - TEXT_GAP
                - this.font.width(amount) - (counterW > 0 ? TEXT_GAP + counterW : 0);

        graphics.drawString(this.font,
                RecipeListLayout.trimToWidth(this.font::width, row.name(),
                        Math.max(0, nameRoom)),
                nameX, textY, line.head() ? TEXT : NodeColors.forRow(row), false);
        graphics.drawString(this.font, amount,
                contentRight - counterW - (counterW > 0 ? TEXT_GAP : 0) - this.font.width(amount),
                textY, UiPalette.MUTED, false);
        if (line.head()) {
            // The heading ends in the same button the tree rows end in, so "this is
            // the row you choose on" looks the same in both windows.
            UiShapes.recipeButton(graphics, contentRight - RECIPE_BTN, y + (ROW_H - RECIPE_BTN) / 2,
                    RECIPE_BTN, UiPalette.FOLD_BTN_BG, UiPalette.FOLD_BTN_BORDER);
        }
    }

    /** The heading's counter: which of the engine's alternatives is in use. */
    private static String counterOf(RecipeNode node) {
        int total = node.options().size();
        int index = Math.min(node.selectedRecipeIndex(), total - 1);
        return (index + 1) + "/" + total;
    }


    /** What a click would do, said before it is done. */
    private Component tooltipAt(int index, int mx, int my) {
        if (index < 0 || index >= this.lines.size()) {
            return null;
        }
        Line line = this.lines.get(index);
        TreeRow<RecipeNode> row = this.plan.get(line.planRow());
        if (row.foldable() && overToggle(mx, my, index)) {
            return Component.translatable(row.folded()
                    ? "screen.craftplanner.tree.expand.tooltip"
                    : "screen.craftplanner.tree.collapse.tooltip");
        }
        if (this.ambiguous.contains(row.node().item())) {
            // Said with the words of the key that already exists for it: the counter
            // and the item are facts about the row, but what the click is for belongs
            // to the language files, where it used to sit unused.
            return Component.translatable("screen.craftplanner.ambiguous.row.tooltip",
                    counterOf(row.node()), row.node().displayName());
        }
        return null;
    }

    private void drawControl(GuiGraphics graphics, int left, int y, boolean hovered) {
        int width = this.panelWidth - 2 * PAD;
        if (hovered) {
            graphics.fill(left + PAD, y, left + PAD + width, y + ROW_H, UiPalette.MODAL_ROW_HOVER);
        }
        graphics.drawString(this.font,
                Component.translatable("screen.craftplanner.list.close").getString(),
                left + PAD + TreeMetrics.TOGGLE_W,
                y + (ROW_H - this.font.lineHeight) / 2, TEXT, false);
    }

    private void drawScrollbar(GuiGraphics graphics, int left, int shown) {
        // A dozen alternatives scroll, so the list carries the same thumb the K
        // window draws — a wheel with no visible position reads as a broken list.
        if (maxScroll() > 0) {
            Scrollbar.draw(graphics, bar(left, shown));
        }
    }

    /** The bar of this list: the same thumb the K window draws, in the same gutter. */
    private Scrollbar.Geometry bar(int left, int shown) {
        return Scrollbar.of(left + this.panelWidth - 4, bandTop(), shown * ROW_H, shown,
                this.lines.size(), this.scroll, maxScroll(), 8);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.returnTo);
    }

    /** The world keeps running: this is a choice, not a pause. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}