package com.craftplanner.client.screen;

import com.craftplanner.client.AmountLabel;
import com.craftplanner.client.Keybinds;
import com.craftplanner.client.MaterialTooltip;
import com.craftplanner.client.PlanRows;
import com.craftplanner.client.PlannerState;
import com.craftplanner.client.RecipeLabel;
import com.craftplanner.client.TextMetrics;
import com.craftplanner.client.UiPalette;
import com.craftplanner.client.UiLegend;
import com.craftplanner.client.UiShapes;
import com.craftplanner.client.overlay.NodeColors;
import com.craftplanner.client.overlay.TreeCollapse;
import com.craftplanner.client.resource.Availability;
import com.craftplanner.client.resource.ResourceCheck;
import com.craftplanner.client.tree.TreeFlatten;
import com.craftplanner.client.tree.TreeMetrics;
import com.craftplanner.client.tree.TreePanel;
import com.craftplanner.client.tree.TreeRow;
import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.RecipeNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.lwjgl.glfw.GLFW;

/**
 * The craft tree as a window (hotkey {@code G}).
 *
 * <p>This is the surface the tree was meant to be read on. The overlay it grew
 * out of is a HUD: the game holds the mouse, so there is no cursor to aim with,
 * no tooltip the player can wait for and no keyboard — which forced every
 * control the panel gained (fold marks, recipe badges, a scrollbar) to be
 * reached by first entering another mode, and every label to survive being drawn
 * at a fraction of the font's size. Here the text is at {@code 1:1}, the cursor
 * is the player's, the footer buttons are real widgets, and the plan is walkable
 * without the mouse.</p>
 *
 * <p>A row is {@code goal bar · fold toggle · icon · name · ×amount · recipe
 * chip}, the first three columns stepping in with depth. Nesting is carried by
 * geometry the eye can follow — a 3 px bar in the goal's own colour running the
 * whole length of its subtree, and neutral 1 px guides from a parent's icon
 * centre into each child — while the goal row also carries a tinted band, so a
 * plan reads as a list of goals with their assemblies under them rather than one
 * grey column of identical squares. Colour is not asked to do double duty: the
 * label's ink is what the inventory covers, the bar's hue is which goal the row
 * belongs to, and the legend in the footer names the inks.</p>
 *
 * <p>The arithmetic is not here: {@link TreeMetrics} places the columns and
 * {@link TreePanel} sizes the panel and maps the scroll, both shared with the HUD
 * and both covered by tests, so the two surfaces cannot drift into two different
 * trees again.</p>
 */
public final class CraftTreeScreen extends Screen {

    private static final String KEY_TITLE = "screen.craftplanner.tree.title";
    private static final String KEY_EMPTY = "screen.craftplanner.tree.empty";
    private static final String KEY_SUMMARY = "screen.craftplanner.tree.summary";
    private static final String KEY_COLLAPSE = "screen.craftplanner.tree.collapse.all";
    private static final String KEY_EXPAND = "screen.craftplanner.tree.expand.all";
    private static final String KEY_LIST = "screen.craftplanner.tree.list";
    private static final String KEY_RECIPE = "screen.craftplanner.tree.recipe";
    private static final String KEY_CLOSE = "screen.craftplanner.tree.close";
    private static final String KEY_HINT = "screen.craftplanner.tree.hint";
    private static final String KEY_HIDDEN = "screen.craftplanner.tree.hidden.rows";

    private static final int PAD = 6;
    private static final int HEADER_H = 20;
    private static final int COL_GAP = 6;
    private static final int BUTTON_H = RecipeListLayout.BUTTON_H;
    private static final int LINE_H = RecipeListLayout.LINE_H;
    private static final int MIN_NAME_W = 70;
    private static final int MAX_NAME_W = 320;
    /** Edge of the square recipe button: a 16 px icon with a pixel of air around it. */
    private static final int RECIPE_BTN = 18;
    /** Air between two footer buttons — the same gap {@link #buildWidgets()} lays out with. */
    private static final int FOOTER_GAP = 4;

    private List<TreeRow<RecipeNode>> rows = List.of();
    /** Panel-relative geometry; the panel is centred after it is known. */
    private TreePanel.Plan plan = new TreePanel.Plan(1.0, TreeMetrics.ROW_H, 120, 40, PAD,
            PAD + HEADER_H, PAD + HEADER_H + TreeMetrics.ROW_H, 100, false, 1, 0, 0);
    private int panelLeft;
    private int panelTop;

    private int nameW = MIN_NAME_W;
    private int amountW;
    private int recipeW;
    private int maxDepth;

    private int scroll;
    /**
     * The row the keyboard is on, by its tree path rather than by index: a rebuild
     * after a fold moves rows up and down the list, and an index would then light
     * up whatever row happened to sit there. The path is the row the player chose.
     */
    private String focusPath;
    /** {@link #focusPath} resolved against the current rows; -1 when it is not among them. */
    private int focus = -1;
    /** The explicit "choose this row's recipe" button, kept so its state can follow the focus. */
    private Button recipeButton;
    private boolean thumbDrag;
    /** What the recipe button's tooltip currently describes; see {@link #syncRecipeButton()}. */
    private String recipeButtonState;
    private long seenGraphVersion = -1;
    private long seenAvailabilityVersion = -1;
    private long seenFoldVersion = -1;

    public CraftTreeScreen() {
        super(Component.translatable(KEY_TITLE));
    }

    @Override
    protected void init() {
        rebuild();
    }

    @Override
    public void tick() {
        // A recipe choice lands after the selectRecipe debounce and an item can be
        // added from another screen, so the tree follows the plan rather than the
        // click that opened it.
        if (PlannerState.graphVersion() != this.seenGraphVersion
                || TreeCollapse.version() != this.seenFoldVersion) {
            rebuild();
        } else if (PlannerState.availabilityVersion() != this.seenAvailabilityVersion) {
            this.seenAvailabilityVersion = PlannerState.availabilityVersion();
            this.rows = PlanRows.rows();
        }
    }

    /** Re-reads the plan, measures the columns, sizes the panel and re-places widgets. */
    private void rebuild() {
        this.seenGraphVersion = PlannerState.graphVersion();
        this.seenAvailabilityVersion = PlannerState.availabilityVersion();
        this.seenFoldVersion = TreeCollapse.version();
        this.rows = PlanRows.rows();
        measure(this.font::width);
        // The plan comes back panel-relative, so centring cannot disagree with the
        // sizing that produced it.
        this.plan = TreePanel.plan(new TreePanel.Request(
                this.width - 16, this.height - 16, 1.0, TreePanel.AUTO, TreePanel.AUTO,
                naturalContentW(), HEADER_H, footerH(), this.rows.size(), PAD), this.scroll);
        this.panelLeft = (this.width - this.plan.panelW()) / 2;
        this.panelTop = (this.height - this.plan.panelH()) / 2;
        this.scroll = this.plan.scroll();
        this.focus = resolveFocus();
        buildWidgets();
    }

    /**
     * Measures the data columns from what the plan actually holds, the way
     * {@code RecipeListLayout} does: a column is as wide as the widest thing it
     * must show and the name column takes the rest, so only names are ever
     * trimmed, and only when the window is narrower than they need.
     */
    private void measure(TextMetrics metrics) {
        int widestName = 0;
        long maxAmount = 1;
        boolean anyChoice = false;
        this.maxDepth = 0;
        for (TreeRow<RecipeNode> row : this.rows) {
            widestName = Math.max(widestName, metrics.width(row.name()));
            maxAmount = Math.max(maxAmount, row.amount());
            this.maxDepth = Math.max(this.maxDepth, row.depth());
            anyChoice |= row.badge() != TreeRow.Badge.NONE;
        }
        this.nameW = Math.min(MAX_NAME_W, Math.max(MIN_NAME_W, widestName + 2));
        // The column is measured from the string a row actually draws, sign included:
        // an availability mark that does not fit is a number pushed out of the panel,
        // which is the one thing the mark must never do.
        this.amountW = Math.max(metrics.width(AmountLabel.millibuckets(maxAmount)) + 8,
                metrics.width(TreeMetrics.markedAmount(TreeRow.Status.MISSING,
                        AmountLabel.times(Math.max(maxAmount, 9_999)))));
        // The recipe column is a square button, so it is measured rather than
        // fitted to text — and it is reserved whenever any row has a recipe at all,
        // not only when some row offers a choice, so the column a player learns to
        // look in is the column that is there.
        this.recipeW = anyChoice ? RECIPE_BTN + 2 * 3 : 0;
    }

    private int naturalContentW() {
        // The rows decide how wide the tree wants to be; the footer decides how
        // wide the window must be. A panel sized only by its rows puts the button
        // row outside itself — most visibly on an empty plan, where the buttons are
        // the whole content and their labels are as long as the language makes them.
        return Math.max(TreeMetrics.labelX(this.maxDepth) + this.nameW
                + (this.amountW > 0 ? COL_GAP + this.amountW : 0)
                + (this.recipeW > 0 ? COL_GAP + this.recipeW : 0),
                Math.max(footerButtonsWidth(), emptyMessageWidth()));
    }

    /**
     * The footer buttons as {@link #buildWidgets()} places them: five labels, each
     * at least its minimum width, with the gaps between them.
     */
    private int footerButtonsWidth() {
        return buttonWidth(text(KEY_COLLAPSE), 60) + buttonWidth(text(KEY_EXPAND), 60)
                + buttonWidth(text(KEY_RECIPE), 60) + buttonWidth(text(KEY_LIST), 60)
                + buttonWidth(text(KEY_CLOSE), 48) + 4 * FOOTER_GAP;
    }

    /** One footer button: its label plus the padding it is measured with, over its floor. */
    private int buttonWidth(String label, int minWidth) {
        return Math.max(minWidth, this.font.width(label) + PAD);
    }

    /** The "nothing planned yet" line: it is centred inside the panel, so the panel must hold it. */
    private int emptyMessageWidth() {
        return this.rows.isEmpty() ? this.font.width(emptyMessage()) + 2 * PAD : 0;
    }

    /** The empty-plan line, naming the binding the player actually has. */
    private String emptyMessage() {
        return text(KEY_EMPTY, Keybinds.ADD_RECIPE.getKey().getDisplayName());
    }

    /**
     * A translation as plain text: the footer is measured in pixels, and a width
     * can only be taken from a string.
     */
    private static String text(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    /** Legend, two hint lines and the button row. */
    private int footerH() {
        return 3 + 2 * LINE_H + 2 + LINE_H + 2 * LINE_H + 4 + BUTTON_H + 4;
    }

    private void buildWidgets() {
        clearWidgets();
        int rowLeft = this.panelLeft + PAD;
        int buttonsY = contentBottom() + footerH() - BUTTON_H - 4;
        int gap = FOOTER_GAP;
        int collapseW = buttonWidth(text(KEY_COLLAPSE), 60);
        int expandW = buttonWidth(text(KEY_EXPAND), 60);
        int recipeW = buttonWidth(text(KEY_RECIPE), 60);
        int listW = buttonWidth(text(KEY_LIST), 60);
        int closeW = buttonWidth(text(KEY_CLOSE), 48);
        int right = this.panelLeft + this.plan.panelW() - PAD;

        if (!this.rows.isEmpty()) {
            addRenderableWidget(FitButton.create(rowLeft, buttonsY, collapseW, BUTTON_H,
                    tr(KEY_COLLAPSE), button -> foldAll(true),
                    Component.translatable("screen.craftplanner.tree.collapse.tooltip")));
            addRenderableWidget(FitButton.create(rowLeft + collapseW + gap, buttonsY, expandW, BUTTON_H,
                    tr(KEY_EXPAND), button -> foldAll(false),
                    Component.translatable("screen.craftplanner.tree.expand.tooltip")));
        }
        // "There is no button for choosing a recipe" is what a per-row badge costs
        // when the column only appears once a row happens to offer a choice: the
        // affordance is then invisible on every plan that is already decided, and
        // absent entirely on one where no item has a recipe to change. A button
        // that acts on the focused row is always there, greys out with nothing to
        // pick, and names the item it will act on.
        this.recipeButton = FitButton.create(right - closeW - gap - listW - gap - recipeW,
                buttonsY, recipeW, BUTTON_H, tr(KEY_RECIPE), button -> openPicker(focusedRow()),
                Component.translatable("screen.craftplanner.tree.recipe.none"));
        addRenderableWidget(this.recipeButton);
        // The two navigation buttons stay pinned to the right edge, so they are
        // always in the same place however few goals the plan holds.
        addRenderableWidget(FitButton.create(right - closeW - gap - listW, buttonsY, listW, BUTTON_H,
                tr(KEY_LIST), button -> this.minecraft.setScreen(new RecipeListScreen()),
                Component.translatable("screen.craftplanner.tree.list.tooltip")));
        addRenderableWidget(FitButton.create(right - closeW, buttonsY, closeW, BUTTON_H,
                tr(KEY_CLOSE), button -> onClose(), null));
    }

    /** Remembers {@code r} as the focused row, by the path that identifies it. */
    /**
     * Moves the window over the plan without re-measuring it.
     *
     * <p>Scrolling decides which rows are drawn and nothing else: the panel is the
     * same size, its columns hold the same text, and the footer widgets sit where
     * {@link #buildWidgets()} put them. Rebuilding for a wheel notch threw that
     * whole layout away sixty times a minute, along with the button the cursor was
     * resting on — so a drag of the thumb stuttered, and a scroll ended with a
     * re-created panel rather than a moved one.</p>
     */
    private void scrollTo(int target) {
        this.scroll = TreePanel.clampScroll(target, this.rows.size(), this.plan.visibleRows());
    }

    private void setFocusRow(int r) {
        this.focus = r;
        this.focusPath = r >= 0 && r < this.rows.size() ? this.rows.get(r).path() : null;
    }

    /**
     * The row {@link #focusPath} names in the current rows, or -1 when the plan no
     * longer shows it — folded away, which is when the path is kept and the row
     * comes back with the branch.
     */
    private int resolveFocus() {
        if (this.focusPath == null) {
            return -1;
        }
        for (int r = 0; r < this.rows.size(); r++) {
            if (this.focusPath.equals(this.rows.get(r).path())) {
                return r;
            }
        }
        return -1;
    }

    /** The row the keyboard is on, or {@code null} when the plan is empty or nothing is picked. */
    private TreeRow<RecipeNode> focusedRow() {
        return this.focus >= 0 && this.focus < this.rows.size() ? this.rows.get(this.focus) : null;
    }

    private void foldAll(boolean collapse) {
        if (collapse) {
            List<String> paths = new ArrayList<>();
            for (TreeRow<RecipeNode> row : this.rows) {
                if (row.path() != null) {
                    paths.add(row.path());
                }
            }
            TreeCollapse.foldAll(paths);
        } else {
            TreeCollapse.unfoldAll();
        }
        rebuild();
    }

    // --- drawing ------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Empty on purpose, like the list and the pickers: render() paints its own
        // dim, and vanilla's blurred background would land on top of the rows.
    }

    /** The world keeps running: the plan is read while it is being gathered. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, UiPalette.MODAL_SCRIM);
        // The recipe button acts on whatever the focus is on, and the focus moves
        // on a click, on a key and on a rebuild — asking once per frame is cheaper
        // than remembering to update it in all three.
        syncRecipeButton();
        UiShapes.panel(graphics, this.panelLeft, this.panelTop, this.plan.panelW(), this.plan.panelH(),
                UiPalette.MODAL_PANEL_BG, UiPalette.MODAL_BORDER_OUTER, UiPalette.MODAL_PANEL_BORDER);
        UiShapes.headerBand(graphics, this.panelLeft, this.panelTop, this.plan.panelW(),
                HEADER_H - 3, UiPalette.MODAL_HEADER_BG, UiPalette.ACCENT_LINE, PAD);

        drawHeader(graphics);
        drawRows(graphics, mouseX, mouseY);
        drawScrollbar(graphics, mouseX, mouseY);

        super.render(graphics, mouseX, mouseY, partialTick);

        drawFooter(graphics);
        renderRowTooltip(graphics, mouseX, mouseY);
    }

    private void drawHeader(GuiGraphics graphics) {
        graphics.drawString(this.font, this.title, this.panelLeft + PAD, this.panelTop + 4,
                UiPalette.TEXT, false);
        if (this.rows.isEmpty()) {
            return;
        }
        Component summary = Component.translatable(KEY_SUMMARY,
                PlannerState.graph().roots().size(), this.rows.size());
        int room = this.plan.panelW() - 2 * PAD - this.font.width(this.title) - COL_GAP;
        graphics.drawString(this.font,
                RecipeListLayout.trimToWidth(this.font::width, summary.getString(), room),
                this.panelLeft + PAD + this.font.width(this.title) + COL_GAP, this.panelTop + 4,
                UiPalette.MUTED, false);
    }

    private void drawRows(GuiGraphics graphics, int mouseX, int mouseY) {
        if (this.rows.isEmpty()) {
            graphics.drawCenteredString(this.font, emptyMessage(),
                    this.panelLeft + this.plan.panelW() / 2, contentTop() + 6, UiPalette.MUTED);
            return;
        }
        int first = this.scroll;
        int last = Math.min(this.rows.size() - 1, this.scroll + this.plan.visibleRows() - 1);
        graphics.enableScissor(this.panelLeft + 2, contentTop(), labelRightEdge() + 2, contentBottom());
        drawGoalBars(graphics, first, last);
        drawGuides(graphics, first, last);
        for (int r = first; r <= last; r++) {
            drawRow(graphics, r, mouseX, mouseY);
        }
        graphics.disableScissor();
    }

    /**
     * The plan is normally longer than the window, and until now the only sign of
     * that was the rows shifting under the wheel — so the track and the thumb are
     * drawn here as well, the same three quiet pixels the overlay and the list
     * use. The thumb says both that there is more of the plan and how much of it
     * is off screen; it brightens where the cursor is over its column, which is
     * also the column the mouse can grab and drag.
     */
    private void drawScrollbar(GuiGraphics graphics, int mouseX, int mouseY) {
        if (this.plan.maxScroll() <= 0) {
            return;
        }
        TreeMetrics.Rect thumb = thumbRect();
        int x = thumb.x();
        int trackTop = contentTop();
        int trackHeight = trackHeight();
        graphics.fill(x, trackTop, x + TreeMetrics.SCROLL_BAR_W, trackTop + trackHeight,
                UiPalette.SCROLL_TRACK);
        boolean hot = this.thumbDrag
                || (mouseX >= x - 3 && mouseX < x + TreeMetrics.SCROLL_BAR_W + 3
                        && mouseY >= trackTop && mouseY < trackTop + trackHeight);
        graphics.fill(x, thumb.y(), x + TreeMetrics.SCROLL_BAR_W, thumb.y() + thumb.h(),
                hot ? UiPalette.SCROLL_BAR_HOT : UiPalette.SCROLL_BAR);
    }

    /**
     * One 3 px bar per planned goal, spanning the full height of its subtree.
     *
     * <p>The overlay coloured a 1 px connector per edge instead, cycling six
     * hues: at a small density that is not a hierarchy but confetti, and a player
     * could not say which sub-assembly belonged to which goal. One solid bar per
     * goal, which keeps running past the top of the panel as the plan scrolls, is
     * the same information in a shape that survives a quick look.</p>
     */
    private void drawGoalBars(GuiGraphics graphics, int first, int last) {
        int x = contentLeft() + TreeMetrics.barX(0);
        for (int r = first; r <= last; r++) {
            TreeRow<RecipeNode> row = this.rows.get(r);
            if (row.depth() != 0) {
                continue;
            }
            int end = Math.min(TreeFlatten.subtreeEnd(this.rows, r), last);
            graphics.fill(x, rowTop(r), x + TreeMetrics.BAR_W,
                    rowTop(end) + this.plan.pitch(), NodeColors.branch(row.branch()));
        }
    }

    private void drawGuides(GuiGraphics graphics, int first, int last) {
        int left = contentLeft();
        for (int r = first; r <= last; r++) {
            TreeRow<RecipeNode> parent = this.rows.get(r);
            if (!parent.expands(r)) {
                continue;
            }
            int x = left + TreeMetrics.spineX(parent.depth());
            graphics.vLine(x, contentTop() + TreeMetrics.iconBottom(r, this.scroll),
                    rowCenter(parent.lastChildIndex()), UiPalette.TREE_GUIDE);
        }
        for (int r = first; r <= last; r++) {
            TreeRow<RecipeNode> row = this.rows.get(r);
            if (row.depth() == 0) {
                continue;
            }
            graphics.hLine(left + TreeMetrics.spineX(row.depth() - 1),
                    left + TreeMetrics.iconX(row.depth()) - 1, rowCenter(r), UiPalette.TREE_GUIDE);
        }
    }

    private void drawRow(GuiGraphics graphics, int r, int mouseX, int mouseY) {
        TreeRow<RecipeNode> row = this.rows.get(r);
        int yTop = rowTop(r);
        int left = contentLeft();
        int bandRight = labelRightEdge();
        boolean hovered = r == hoveredRow(mouseY) && overPanelColumn(mouseX);
        boolean focused = r == this.focus;

        if (row.depth() == 0) {
            graphics.fill(left - PAD, yTop, bandRight, yTop + this.plan.pitch(),
                    UiPalette.TREE_ROOT_BAND);
        }
        if (focused) {
            graphics.fill(left - PAD, yTop, bandRight, yTop + this.plan.pitch(),
                    UiPalette.TREE_FOCUS_BG);
            UiShapes.ring(graphics, left - PAD, yTop, bandRight - left + PAD, this.plan.pitch(),
                    UiPalette.TREE_FOCUS_BORDER);
        } else if (hovered) {
            graphics.fill(left - PAD, yTop, bandRight, yTop + this.plan.pitch(),
                    UiPalette.MODAL_ROW_HOVER);
        }

        if (row.foldable()) {
            drawFoldButton(graphics, row, yTop, left, hovered && TreeMetrics.toggleHit(
                    row.depth(), r, this.scroll, 1.0, left, contentTop()).contains(mouseX, mouseY));
        }

        TreeMetrics.Rect icon = TreeMetrics.toScreen(
                TreeMetrics.iconRect(row.depth(), r, this.scroll), 1.0, left, contentTop());
        graphics.renderItem(row.node().displayStack(), icon.x(), icon.y());

        int ink = NodeColors.forRow(row);
        int nameX = left + TreeMetrics.labelX(row.depth());
        String name = RecipeListLayout.trimToWidth(this.font::width, row.name(),
                nameRightEdge() - nameX);
        graphics.drawString(this.font, name, nameX, textY(r), ink, false);

        if (this.amountW > 0) {
                String amount = TreeMetrics.markedAmount(row.status(), AmountLabel.of(row.node()))
                    + (row.hiddenRows() > 0 ? " (+" + row.hiddenRows() + ")" : "");
            String shown = RecipeListLayout.trimToWidth(this.font::width, amount, this.amountW);
            graphics.drawString(this.font, shown, nameRightEdge() - this.font.width(shown),
                    textY(r), ink, false);
        }
        drawRecipeButton(graphics, row, r,
                recipeRect(r).contains(mouseX, mouseY));
    }


    /**
 * What the recipe button on a row means, said where the row can be looked up.
 *
 * <p>The button itself is an icon now: it has to answer "what does clicking this
 * do?" at a glance, and it cannot also answer "which one is in use?" without
 * becoming a sentence. The count is the second question, so it went to the
 * tooltip.</p>
 */
    private static Component recipeHint(TreeRow<RecipeNode> row) {
        return row.badge() == TreeRow.Badge.CUT
                ? Component.translatable("screen.craftplanner.tree.recipe.cut")
                : row.options() > 1
                        ? Component.translatable("screen.craftplanner.tree.recipe.chosen",
                                row.selected(), row.options())
                        : Component.translatable("screen.craftplanner.tree.recipe.only");
    }

/**
     * The fold control of a branch: the shared tree button, placed where the row's
     * depth puts it and sized to fill the row.
     *
     * <p>A folded branch keeps the amber plate: a plan with collapsed parts has to be
     * obvious at a glance, since what it hides is still being planned.</p>
     */
    private void drawFoldButton(GuiGraphics graphics, TreeRow<RecipeNode> row, int yTop,
            int left, boolean aimed) {
        int height = this.plan.pitch() - 4;
        UiShapes.foldButton(graphics, left + TreeMetrics.toggleX(row.depth()),
                yTop + (this.plan.pitch() - height) / 2,
                TreeMetrics.TOGGLE_W, height, row.folded(), aimed);
    }

    /**
     * The recipe choice of a row: a crafting-table button, amber-ringed when the
     * row really can be switched and the cut mark's red when it is stopped.
     */
    private void drawRecipeButton(GuiGraphics graphics, TreeRow<RecipeNode> row, int r,
            boolean aimed) {
        if (row.badge() == TreeRow.Badge.NONE) {
            return;
        }
        TreeMetrics.Rect box = recipeRect(r);
        boolean switchable = row.badge() == TreeRow.Badge.MULTIPLE;
        UiShapes.recipeButton(graphics, box.x(), box.y(), RECIPE_BTN,
                switchable ? UiPalette.FOLD_BTN_BG : UiPalette.CHIP_BG,
                row.badge() == TreeRow.Badge.CUT ? UiPalette.MARK_CUT
                        : switchable ? UiPalette.FOLD_BTN_BORDER
                                : aimed ? UiPalette.FOLD_BTN_HOVER_BORDER : UiPalette.CHIP_BORDER);
    }

    private TreeMetrics.Rect recipeRect(int r) {
        return new TreeMetrics.Rect(nameRightEdge() + COL_GAP + this.amountW + COL_GAP,
                rowCenter(r) - RECIPE_BTN / 2, RECIPE_BTN, RECIPE_BTN);
    }

    private int rowTop(int r) {
        return contentTop() + (r - this.scroll) * this.plan.pitch();
    }

    private int rowCenter(int r) {
        return rowTop(r) + this.plan.pitch() / 2;
    }

    private int textY(int r) {
        return rowTop(r) + (this.plan.pitch() - LINE_H) / 2;
    }

    /** Right edge of the name column: whatever sits to its right is a fixed column. */
    private int nameRightEdge() {
        return chipLeft() - (this.recipeW > 0 ? COL_GAP : 0)
                - (this.amountW > 0 ? COL_GAP + this.amountW : 0);
    }

    private int chipLeft() {
        return labelRightEdge() - (this.recipeW > 0 ? this.recipeW : 0);
    }

    /**
     * Right edge of the content area, in screen pixels: the very line
     * {@link TreePanel} measured {@code contentW} against, so a chip can never
     * reach into the padding the scrollbar sits in, however narrow the window is.
     */
    private int labelRightEdge() {
        return contentLeft() + this.plan.contentW();
    }

    private void drawFooter(GuiGraphics graphics) {
        int left = this.panelLeft;
        int y = contentBottom() + 2;
        UiShapes.divider(graphics, left + 2, left + this.plan.panelW() - 2, y - 2,
                UiPalette.SECTION_DIVIDER);
        drawLegend(graphics, left + PAD, y + 3);
        drawMaterialSummary(graphics, left + PAD, y + 3 + 2 * LINE_H + 2);
        int hintY = y + 3 + 3 * LINE_H + 4;
        List<String> hint = RecipeListLayout.wrapText(this.font::width, hint(),
                this.plan.panelW() - 2 * PAD, 2);
        for (int i = 0; i < hint.size(); i++) {
            graphics.drawString(this.font, hint.get(i), left + PAD, hintY + i * LINE_H,
                    UiPalette.MUTED, false);
        }
    }

    /**
     * The key line names the bindings the player actually has, so a rebind in
     * Options → Controls cannot leave the window lying about itself.
     */
    private String hint() {
        return Component.translatable(KEY_HINT,
                Keybinds.ADD_RECIPE.getKey().getDisplayName(),
                Keybinds.OPEN_RECIPES.getKey().getDisplayName(),
                Keybinds.TOGGLE_OVERLAY.getKey().getDisplayName()).getString();
    }

    /** The material and plan colours, each named: the encoding the rows use. */
    private void drawLegend(GuiGraphics graphics, int x, int y) {
        UiLegend.draw(graphics, this.font, x, y, this.plan.panelW() - 2 * PAD, LINE_H);
    }

    private void drawMaterialSummary(GuiGraphics graphics, int x, int y) {
        Map<ItemKey, Availability> availability = PlannerState.availability();
        long missingTypes = ResourceCheck.deficitTypes(availability);
        boolean fluidNeedsUntracked = !PlannerState.fluidRequirements().isEmpty();
        String key;
        int color;
        if (!PlannerState.inventoryScanned()) {
            key = fluidNeedsUntracked
                    ? "screen.craftplanner.tree.materials.unknown.fluid_unknown"
                    : "screen.craftplanner.tree.materials.unknown";
            color = NodeColors.PARTIAL;
        } else if (missingTypes == 0) {
            key = fluidNeedsUntracked
                    ? "screen.craftplanner.tree.materials.ready.fluid_unknown"
                    : "screen.craftplanner.tree.materials.ready";
            color = fluidNeedsUntracked ? NodeColors.PARTIAL : NodeColors.HAVE;
        } else {
            key = fluidNeedsUntracked
                    ? "screen.craftplanner.tree.materials.missing.fluid_unknown"
                    : "screen.craftplanner.tree.materials.missing";
            color = NodeColors.MISSING;
        }
        Component summary = !PlannerState.inventoryScanned() || missingTypes == 0
                ? Component.translatable(key)
                : Component.translatable(key, missingTypes, ResourceCheck.totalMissing(availability));
        String text = RecipeListLayout.trimToWidth(this.font::width, summary.getString(),
                this.plan.panelW() - 2 * PAD);
        graphics.drawString(this.font, text, x, y, color, false);
    }

    // --- coordinates --------------------------------------------------------

    private int contentLeft() {
        return this.panelLeft + this.plan.contentLeft();
    }

    private int contentTop() {
        return this.panelTop + this.plan.contentTop();
    }

    private int contentBottom() {
        return this.panelTop + this.plan.contentBottom();
    }

    // --- input --------------------------------------------------------------

    /**
     * Whether the cursor is over the panel's own columns, rather than beside the
     * window.
     *
     * <p>The rows are laid out inside the panel, but the hit test only knew about
     * the vertical band, so a cursor sitting to the right of the window — over the
     * game, or over the HUD — still highlighted a row and raised its tooltip. The
     * scrim makes the panel edge visible, and the pointer belongs to whatever is
     * under it.</p>
     */
    private boolean overPanelColumn(int mx) {
        return mx >= this.panelLeft && mx < this.panelLeft + this.plan.panelW();
    }

    private int hoveredRow(int mouseY) {
        int r = TreeMetrics.rowAt(contentTop(), contentBottom(), this.scroll, 1.0, mouseY);
        if (r < this.scroll || r >= this.rows.size()) {
            return -1;
        }
        return r;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !overPanel((int) mouseX, (int) mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (grabThumb((int) mouseX, (int) mouseY)) {
            return true;
        }
        int jumped = trackJump((int) mouseX, (int) mouseY);
        if (jumped >= 0) {
            scrollTo(jumped);
            return true;
        }
        int r = hoveredRow((int) mouseY);
        if (r < 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        setFocusRow(r);
        TreeRow<RecipeNode> row = this.rows.get(r);
        TreeMetrics.Rect toggle = TreeMetrics.toggleHit(row.depth(), r, this.scroll, 1.0,
                contentLeft(), contentTop());
        if (row.foldable() && toggle.contains((int) mouseX, (int) mouseY)) {
            TreeCollapse.toggle(row.path());
            rebuild();
            return true;
        }
        if (this.recipeW > 0 && recipeRect(r).contains((int) mouseX, (int) mouseY)) {
            openPicker(row);
            return true;
        }
        if (row.badge() != TreeRow.Badge.NONE && mouseX >= contentLeft()
                + TreeMetrics.labelX(row.depth()) && mouseX < nameRightEdge()) {
            // The name is the row's label, so it is also where the player looks
            // for the way to change what the row is made of.
            openPicker(row);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean overPanel(int mx, int my) {
        return mx >= this.panelLeft && mx <= this.panelLeft + this.plan.panelW()
                && my >= this.panelTop && my <= this.panelTop + this.plan.panelH();
    }

    private void openPicker(TreeRow<RecipeNode> row) {
        if (row == null || row.badge() == TreeRow.Badge.NONE) {
            return;
        }
        this.minecraft.setScreen(new RecipePickerScreen(row.node().item(), this));
    }

    /**
     * The footer button follows the focused row: the label stays "Рецепт" so the
     * button never changes width under the cursor, but its tooltip names the item
     * it will act on, and the button greys out whenever that row has no recipe to
     * change.
     */
    private void syncRecipeButton() {
        if (this.recipeButton == null) {
            return;
        }
        TreeRow<RecipeNode> row = focusedRow();
        boolean pickable = row != null && row.badge() != TreeRow.Badge.NONE;
        // The tooltip names the item under the focus, which is what the button acts
        // on, so the row is its key: a frame that focuses the same row as the last
        // has nothing to say, and building the component again every frame is work
        // the screen does once per click.
        String key = pickable ? row.path() + '/' + row.node().item() : null;
        if (Objects.equals(key, this.recipeButtonState) && this.recipeButton.active == pickable) {
            return;
        }
        this.recipeButtonState = key;
        this.recipeButton.active = pickable;
        this.recipeButton.setTooltip(Tooltip.create(pickable
                ? Component.translatable("screen.craftplanner.tree.recipe.tooltip", row.node().displayName())
                : Component.translatable("screen.craftplanner.tree.recipe.none")));
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.thumbDrag) {
            scrollTo(TreePanel.scrollAt(contentTop(), trackHeight(), thumbHeight(),
                    this.plan.maxScroll(), (int) mouseY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.thumbDrag = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean grabThumb(int mx, int my) {
        if (this.plan.maxScroll() <= 0 || !thumbRect().contains(mx, my)) {
            return false;
        }
        this.thumbDrag = true;
        // The thumb stays under the cursor instead of jumping its own height.
        scrollTo(TreePanel.scrollAt(contentTop(), trackHeight(), thumbHeight(),
                this.plan.maxScroll(), my));
        return true;
    }

    /** A click on the bare track pages the plan to that point. */
    private int trackJump(int mx, int my) {
        if (this.plan.maxScroll() <= 0) {
            return -1;
        }
        int x = thumbRect().x();
        if (mx < x - 3 || mx > x + TreeMetrics.SCROLL_BAR_W + 6
                || my < contentTop() || my >= contentBottom()) {
            return -1;
        }
        return TreePanel.scrollAt(contentTop(), trackHeight(), thumbHeight(),
                this.plan.maxScroll(), my);
    }

    private TreeMetrics.Rect thumbRect() {
        int x = labelRightEdge() + 2;
        return new TreeMetrics.Rect(x,
                TreePanel.thumbTop(contentTop(), trackHeight(), thumbHeight(),
                        this.scroll, this.plan.maxScroll()),
                TreeMetrics.SCROLL_BAR_W + 4, thumbHeight());
    }

    private int trackHeight() {
        return Math.max(1, contentBottom() - contentTop());
    }

    private int thumbHeight() {
        return TreePanel.thumbHeight(trackHeight(), this.plan.visibleRows(), this.rows.size());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.plan.maxScroll() <= 0) {
            return false;
        }
        scrollTo(this.scroll + (scrollY > 0 ? -TreePanel.WHEEL_ROWS : TreePanel.WHEEL_ROWS));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.rows.isEmpty()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_DOWN:
                moveFocus(1);
                return true;
            case GLFW.GLFW_KEY_UP:
                moveFocus(-1);
                return true;
            case GLFW.GLFW_KEY_LEFT:
                foldFocused(true);
                return true;
            case GLFW.GLFW_KEY_RIGHT:
                foldFocused(false);
                return true;
            case GLFW.GLFW_KEY_ENTER:
            case GLFW.GLFW_KEY_KP_ENTER:
                // A button that has the focus is what Enter belongs to: the player
                // tabbed onto it, and the key must press that button rather than
                // open the row underneath. Only with nothing focused does Enter
                // mean "choose the recipe of the row the arrows stopped on".
                if (getFocused() instanceof Button) {
                    return super.keyPressed(keyCode, scanCode, modifiers);
                }
                openPicker(focusedRow());
                return true;
            case GLFW.GLFW_KEY_PAGE_DOWN:
                page(1);
                return true;
            case GLFW.GLFW_KEY_PAGE_UP:
                page(-1);
                return true;
            case GLFW.GLFW_KEY_HOME:
                scrollTo(0);
                return true;
            case GLFW.GLFW_KEY_END:
                scrollTo(this.plan.maxScroll());
                return true;
            default:
                break;
        }
        if (Keybinds.OPEN_RECIPES.matches(keyCode, scanCode)) {
            this.minecraft.setScreen(new RecipeListScreen());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void moveFocus(int delta) {
        int target = this.focus < 0 ? 0
                : Math.max(0, Math.min(this.rows.size() - 1, this.focus + delta));
        setFocusRow(target);
        if (target < this.scroll) {
            scrollTo(target);
        } else if (target >= this.scroll + this.plan.visibleRows()) {
            scrollTo(target - this.plan.visibleRows() + 1);
        }
    }

    private void page(int direction) {
        scrollTo(this.scroll + direction * this.plan.visibleRows());
        if (this.focus >= 0) {
            setFocusRow(Math.max(this.scroll,
                    Math.min(this.scroll + this.plan.visibleRows() - 1, this.focus)));
        }
    }

    private void foldFocused(boolean collapse) {
        if (this.focus < 0 || this.focus >= this.rows.size()) {
            return;
        }
        TreeRow<RecipeNode> row = this.rows.get(this.focus);
        if (row.path() == null || row.folded() == collapse) {
            return;
        }
        TreeCollapse.toggle(row.path());
        rebuild();
    }

    // --- tooltips -----------------------------------------------------------

    private void renderRowTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        // The row band spans the whole panel width, so a cursor that has left the
        // window would still be told about the row it crossed vertically.
        if (!overPanelColumn(mouseX)) {
            return;
        }
        int r = hoveredRow(mouseY);
        if (r < 0) {
            return;
        }
        TreeRow<RecipeNode> row = this.rows.get(r);
        TreeMetrics.Rect toggle = TreeMetrics.toggleHit(row.depth(), r, this.scroll, 1.0,
                contentLeft(), contentTop());
        if (row.foldable() && toggle.contains(mouseX, mouseY)) {
            this.setTooltipForNextRenderPass(Component.translatable(row.folded()
                    ? "overlay.craftplanner.fold.expand" : "overlay.craftplanner.fold.collapse"));
            return;
        }
        // The recipe button is an icon now, so the count that used to be printed on
        // it has to be said somewhere: aiming at the button answers what the button
        // does, which is the question the row behind it does not.
        if (this.recipeW > 0 && recipeRect(r).contains(mouseX, mouseY)) {
            this.setTooltipForNextRenderPass(recipeHint(row));
            return;
        }
        List<Component> lines = rowTooltip(row);
        MutableComponent joined = lines.get(0).copy();
        for (int i = 1; i < lines.size(); i++) {
            joined.append(Component.literal("\n")).append(lines.get(i).copy());
        }
        this.setTooltipForNextRenderPass(joined);
    }

    /**
     * What a row says about itself: the item, then what the plan asks of it and
     * what the inventory already covers, then the recipe in use and what clicking
     * would change. The bare red {@code ×12} in the old panel left the player to
     * guess which of its three meanings applied.
     */
    private List<Component> rowTooltip(TreeRow<RecipeNode> row) {
        Availability state = row.node().isFluidRequirement()
            ? null : PlannerState.availability().get(row.node().item());
        // The material's own lines come from the same place the HUD's shopping list
        // takes them, so the two surfaces cannot disagree about what "have" means.
        List<Component> lines = new ArrayList<>(MaterialTooltip.lines(
                row.node().displayName(), TreeMetrics.markedAmount(row.status(),
                        AmountLabel.of(row.node())), state));
        if (row.hiddenRows() > 0) {
            lines.add(Component.translatable(KEY_HIDDEN, row.hiddenRows()));
        }
        RecipeNode node = row.node();
        if (node.recipe() == null || this.minecraft.level == null) {
            return lines;
        }
        int total = node.options().size();
        Component head;
        if (row.badge() == TreeRow.Badge.CUT) {
            head = Component.translatable("overlay.craftplanner.cut.tooltip");
        } else if (total > 1) {
            head = Component.translatable("overlay.craftplanner.choice.tooltip",
                    Integer.toString(node.selectedRecipeIndex() + 1), Integer.toString(total));
        } else {
            head = Component.translatable("overlay.craftplanner.single.tooltip");
        }
        lines.addAll(RecipeLabel.describe(node.recipe(), this.minecraft.level.registryAccess()));
        lines.add(0, head);
        return lines;
    }

    private static Component tr(String key) {
        return Component.translatable(key);
    }
}
