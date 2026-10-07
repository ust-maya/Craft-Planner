package com.craftplanner.client.screen;

import com.craftplanner.client.MachineNames;
import com.craftplanner.client.PlannerState;
import com.craftplanner.client.RecipeLabel;
import com.craftplanner.client.RecipePreview;
import com.craftplanner.client.UiPalette;
import com.craftplanner.client.UiShapes;
import com.craftplanner.client.overlay.NodeColors;
import com.craftplanner.client.tree.TreeMetrics;
import com.craftplanner.client.tree.TreeRow;
import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.RecipeNode;
import com.craftplanner.client.resource.Availability;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import org.lwjgl.glfw.GLFW;

/**
 * The screen that answers "this item can be made several ways" (badge {@code 2/3}
 * in the tree, recipe button in the {@code K} window).
 *
 * <p>It lists every recipe that produces one item, names each by what it consumes
 * and by the machine that runs it, marks the one in use and lets the player pick
 * another or stop the tree at this item. The list screen's cycling button could
 * only ever say "1/3, 2/3, 3/3" — which is no help when the options differ by more
 * than an index — so the choice is made here, where both the cost and the machine
 * of each alternative are on screen. A picked recipe stays picked: it is un-pinned
 * only by taking the item out of the plan.</p>
 *
 * <p>The one-line cost is also where comparing alternatives stops working. Two
 * recipes for the same item can cost the same raw materials and pull in entirely
 * different things further down — a furnace route that needs a furnace, against one
 * that needs a blast furnace and eight slabs — and the totals say nothing about
 * that. So every option can be opened into its own dependency tree, drawn with the
 * plan's own geometry, and the alternatives are read as branches rather than as
 * numbers. The option in use starts open, because that is the one the player came
 * to look at; the others are one click away, and what they drag in is only worked
 * out when they are actually asked for.</p>
 *
 * <p>Rows are drawn by this screen rather than by widgets — no vanilla widget
 * offers the composition a row needs (fold plate, mark, machine icon, two-colour
 * text, and for a tree row a guide and an amount) — so their hit boxes are computed
 * here too, from the same geometry the drawing uses.</p>
 *
 * <p>Everything is resolved from the graph again on every change: the engine
 * re-expands the tree in place and recreates its child nodes, so holding on to a
 * {@code RecipeNode} across a rebuild would show a stale option list. A recipe
 * switch is applied after the {@code selectRecipe} debounce, which is what
 * {@link #tick()} is for. An expanded option is remembered by recipe id rather than
 * by position, so a re-sort or a rebuild does not close what the player opened.</p>
 */
public final class RecipePickerScreen extends Screen {

    /** A row: one 16 px icon plus a pixel of air above and below. */
    private static final int ROW_H = 17;
    /** Band above the rows that holds the title. */
    private static final int HEADER_H = 14;
    private static final int PAD = 5;
    /** Air the panel keeps from the screen edges, so nothing sits under the cursor bar. */
    private static final int MARGIN = 8;
    /**
     * Column of the mark of the row in use is drawn in. Wide enough for a ✔ of the
     * vanilla bitmap, which is a full-width glyph, plus a pixel of air before the
     * icon starts.
     */
    private static final int MARK_W = 12;
    private static final int ICON_W = 16;
    private static final int GAP = 3;
    /** Air kept between the cost and the machine that runs it. */
    private static final int TEXT_GAP = 6;
    /** A machine name narrower than this says less than the icon next to it. */
    private static final int MIN_TAIL = 20;
    /** Rows that are always there under the options: the cut switch and close. */
    private static final int CONTROL_ROWS = 2;
    /** The fold plate, borrowed from the plan's own tree so both open the same way. */
    private static final int TOGGLE_W = TreeMetrics.TOGGLE_W;
    /** Air between the fold plate and the mark it sits next to. */
    private static final int TOGGLE_GAP = 2;
    private static final int PANEL_BG = UiPalette.MODAL_PANEL_BG;
    private static final int PANEL_BORDER = UiPalette.MODAL_PANEL_BORDER;
    /** Same dim as the {@code K} window: the world behind stays readable. */
    private static final int DIM = UiPalette.MODAL_SCRIM;
    private static final int TEXT = UiPalette.TEXT;
    private static final int MUTED = UiPalette.MUTED;
    /** The amber of the tree's own markers, so "in use" reads the same everywhere. */
    private static final int ROW_SELECTED = UiPalette.MODAL_ROW_SELECTED;
    private static final int ROW_HOVER = UiPalette.MODAL_ROW_HOVER;
    /** Thumb of the option band: the quiet grey of the plan panel's own scrollbar. */
    private static final int SCROLL = UiPalette.SCROLL_BAR;

    /** The item whose recipes are offered; also the key of the choice. */
    private final ItemKey item;
    /** Screen to go back to, so opening the picker never loses the player's place. */
    private final Screen returnTo;

    private List<RecipeHolder<?>> options = List.of();
    /** The same options as drawn rows. */
    private List<Option> rows = List.of();
    /** Everything the band shows, options and the trees opened under them. */
    private List<Line> lines = List.of();
    /** Which options are opened, by recipe id so a re-sort does not close them. */
    private final Set<ResourceLocation> expanded = new LinkedHashSet<>();
    private int selected = -1;
    private int scroll;
    /** Whether the first layout already put the row in use on screen. */
    private boolean centered;
    private int panelWidth;
    private long seenGraphVersion;

    /**
     * One line of the band.
     *
     * <p>A flat list rather than nested lists, because the band scrolls as one
     * thing and a row under a click has to be resolvable to a single index.</p>
     */
    private sealed interface Line {
    }

    /** An option. Clicking its body picks the recipe; its plate opens the tree. */
    private record OptionLine(int option) implements Line {
    }

    /** One row of an opened option's dependency tree. Read-only: it says, not chooses. */
    private record TreeLine(int option, int row) implements Line {
    }

    /**
     * One option: what it costs, the machine that runs it, that machine's icon
     * ({@link ItemStack#EMPTY} when no block claims the recipe type), and the full
     * description kept for the tooltip — the yield is the second thing a player
     * compares, after the cost, so it does not crowd the row.
     */
    private record Option(String cost, String machine, ItemStack icon, Component tooltip) {
    }

    public RecipePickerScreen(ItemKey item, Screen returnTo) {
        super(Component.translatable("gui.craftplanner.picker.title", item.name()));
        this.item = item;
        this.returnTo = returnTo;
    }

    @Override
    protected void init() {
        this.seenGraphVersion = PlannerState.graphVersion();
        RecipeNode node = PlannerState.graph().nodeFor(this.item);
        if (node == null || node.recipe() == null || this.minecraft.level == null) {
            // The item left the plan, its last recipe turned out unreadable, or the
            // world went away: either way there is nothing to choose between, so
            // close instead of offering an empty box.
            this.minecraft.setScreen(this.returnTo);
            return;
        }
        HolderLookup.Provider registries = this.minecraft.level.registryAccess();
        this.options = node.options();
        if (this.options.isEmpty()) {
            // The node resolved a recipe but the index offers nothing for it — the
            // same dead end as no recipe at all, rather than an empty box to stare at.
            this.minecraft.setScreen(this.returnTo);
            return;
        }
        this.selected = Math.min(node.selectedRecipeIndex(), this.options.size() - 1);
        List<Option> built = new ArrayList<>(this.options.size());
        for (RecipeHolder<?> option : this.options) {
            List<Component> lines = RecipeLabel.describe(option, registries);
            built.add(new Option(costOf(lines), RecipeLabel.of(option).getString(),
                    machineIcon(option), join(lines)));
        }
        this.rows = built;
        // The recipe in use opens on its own: its tree is what the player is here to
        // check, and the cost row above it is only a summary of it.
        if (!this.expanded.contains(this.options.get(this.selected).id())) {
            this.expanded.add(this.options.get(this.selected).id());
        }
        this.expanded.retainAll(idsOf(this.options));
        relayout();
        this.panelWidth = measure();
        if (this.centered) {
            this.scroll = Math.min(this.scroll, maxScroll());
        } else {
            // The first layout opens on the row in use, not on the top of the ranking:
            // on a dozen alternatives for one plate the current recipe is the thing the
            // player came to look at. Later rebuilds keep wherever they scrolled to.
            this.centered = true;
            this.scroll = Math.max(0, Math.min(lineOf(this.selected), maxScroll()));
        }
    }

    private static Set<ResourceLocation> idsOf(List<RecipeHolder<?>> options) {
        Set<ResourceLocation> ids = new LinkedHashSet<>(options.size());
        for (RecipeHolder<?> option : options) {
            ids.add(option.id());
        }
        return ids;
    }

    /** Rebuilds the band: every option, and under each opened one its dependency tree. */
    private void relayout() {
        List<Line> built = new ArrayList<>(this.rows.size());
        for (int i = 0; i < this.rows.size(); i++) {
            built.add(new OptionLine(i));
            List<TreeRow<RecipeNode>> preview = previewOf(i);
            if (preview != null) {
                for (int r = 0; r < preview.size(); r++) {
                    built.add(new TreeLine(i, r));
                }
            }
        }
        this.lines = built;
    }

    /** The option's dependency tree, or {@code null} while it is closed. */
    private List<TreeRow<RecipeNode>> previewOf(int option) {
        if (option < 0 || option >= this.options.size()) {
            return null;
        }
        return this.expanded.contains(this.options.get(option).id())
                ? RecipePreview.of(this.item, this.options.get(option))
                : null;
    }

    /** Where an option's own line sits in {@link #lines}, so its tree can be found. */
    private int lineOf(int option) {
        int seen = 0;
        for (Line line : this.lines) {
            if (line instanceof OptionLine row && row.option() == option) {
                return seen;
            }
            seen++;
        }
        return 0;
    }

    @Override
    public void tick() {
        if (PlannerState.graphVersion() != this.seenGraphVersion) {
            init();
        }
    }

    /**
     * The row text leads with the cost: what a recipe consumes is what decides
     * between a dozen machine options, while the machine itself is recognizable by
     * its icon and so follows, in a secondary colour.
     */
    private static String costOf(List<Component> lines) {
        return lines.isEmpty() ? "" : lines.get(lines.size() - 1).getString();
    }

    /** The block that runs the recipe, so rows differ by more than their text. */
    private static ItemStack machineIcon(RecipeHolder<?> option) {
        Block machine = MachineNames.blockOf(
                BuiltInRegistries.RECIPE_TYPE.getKey(option.value().getType()));
        return machine == null ? ItemStack.EMPTY : new ItemStack(machine.asItem());
    }

    /**
     * Vanilla draws the lines of a single {@code Component} separated by newlines,
     * so a description needs no list overload here.
     */
    private static Component join(List<Component> lines) {
        if (lines.isEmpty()) {
            return Component.empty();
        }
        MutableComponent joined = lines.get(0).copy();
        for (int i = 1; i < lines.size(); i++) {
            joined.append(Component.literal("\n")).append(lines.get(i).copy());
        }
        return joined;
    }

    /**
     * Width the panel needs: the worst row. The control rows are measured too, so
     * the cut switch can never be the row the panel is too narrow for, and an
     * opened tree counts as much as its deepest, longest name — otherwise opening
     * a branch would push text past the edge instead of shortening it.
     */
    private int measure() {
        int widest = this.font.width(this.title);
        for (Option row : this.rows) {
            widest = Math.max(widest, optionGutterW() + this.font.width(row.cost())
                    + TEXT_GAP + this.font.width("· " + row.machine()));
        }
        for (int i = 0; i < this.rows.size(); i++) {
            List<TreeRow<RecipeNode>> preview = previewOf(i);
            if (preview == null) {
                continue;
            }
            for (TreeRow<RecipeNode> row : preview) {
                widest = Math.max(widest, optionGutterW() + TreeMetrics.labelX(depthOf(row))
                        + this.font.width(row.name()) + TEXT_GAP + this.font.width("×" + row.amount()));
            }
        }
        widest = Math.max(widest, optionGutterW() + this.font.width(cutLabel()));
        widest = Math.max(widest, optionGutterW() + this.font.width(tr("screen.craftplanner.list.close")));
        return Math.min(this.width - 2 * MARGIN, Math.max(200, widest + 2 + 2 * PAD));
    }

    /** The columns every option row spends before its text starts. */
    private static int optionGutterW() {
        return TOGGLE_W + TOGGLE_GAP + MARK_W + ICON_W + GAP;
    }

    /**
     * How deep a preview row is drawn.
     *
     * <p>The root of a preview is the item the option row already shows, and it is
     * dropped, so its children keep their meaning but start one step further in:
     * they hang off the option, not off a name that is not on screen.</p>
     */
    private static int depthOf(TreeRow<RecipeNode> row) {
        return Math.max(0, row.depth() - 1);
    }

    private static String cutLabel() {
        return "✂ " + tr("gui.craftplanner.picker.cut");
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private void choose(RecipeHolder<?> recipe) {
        PlannerState.selectRecipe(this.item, recipe);
        // The rebuild is debounced; tick() repaints the rows once it has landed.
        this.seenGraphVersion = PlannerState.graphVersion();
    }

    /**
     * Lines the screen can show at once. Everything else the panel carries is
     * subtracted first — the title band, the two rows under the list and the padding
     * below them — so a window taller than the screen never happens above a certain
     * height, and the last line never falls off the bottom.
     */
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

    /** Top of the scrollable band. */
    private int bandTop() {
        return top() + HEADER_H;
    }

    /** Screen y of a line of the band. */
    private int lineTop(int index) {
        return bandTop() + (index - this.scroll) * ROW_H;
    }

    /**
     * Line under a point: the band first, then the cut switch and the close button,
     * or {@code -1} outside the panel's rows. Screen pixels on both sides — the
     * drawing is what moves, the clicks are not. The right edge is exclusive because
     * that is where the row's highlight stops painting.
     */
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

    /** The control row under a point, or {@code -1}: {@code 0} is the cut switch. */
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

    /** Whether a point is on an option's fold plate, which opens its tree. */
    private boolean overToggle(int mx, int my) {
        int index = hitLine(mx, my);
        if (index < 0 || !(this.lines.get(index) instanceof OptionLine)) {
            return false;
        }
        return mx < panelLeft() + PAD + TOGGLE_W + TOGGLE_GAP / 2;
    }

    /** The tooltip of a line, or {@code null} for the close row, which says all it does. */
    private Component tooltipAt(int index) {
        if (index < 0 || index >= this.lines.size()) {
            return null;
        }
        Line line = this.lines.get(index);
        if (line instanceof OptionLine row) {
            Component base = this.rows.get(row.option()).tooltip();
            return base.copy().append("\n").append(toggleHint(row.option()));
        }
        return Component.translatable("gui.craftplanner.picker.tree.readonly");
    }

    private Component toggleHint(int option) {
        boolean open = this.expanded.contains(this.options.get(option).id());
        List<TreeRow<RecipeNode>> preview = previewOf(option);
        return preview == null || preview.isEmpty()
                ? Component.translatable("gui.craftplanner.picker.tree.empty")
                : Component.translatable(open ? "gui.craftplanner.picker.collapse"
                        : "gui.craftplanner.picker.expand", preview.size());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        int mx = (int) mouseX;
        int my = (int) mouseY;
        int index = hitLine(mx, my);
        if (index >= 0) {
            Line line = this.lines.get(index);
            if (line instanceof TreeLine) {
                // A dependency row is not a choice: opening the tree must not be a
                // way to switch recipes by accident.
                return true;
            }
            OptionLine row = (OptionLine) line;
            if (overToggle(mx, my)) {
                toggle(row.option());
                return true;
            }
            choose(this.options.get(row.option()));
            return true;
        }
        int control = hitControl(mx, my);
        if (control < 0) {
            return false;
        }
        if (control == 0) {
            PlannerState.toggleCut(this.item);
            this.seenGraphVersion = PlannerState.graphVersion();
        } else {
            this.onClose();
        }
        return true;
    }

    /** Opens or closes an option's dependency tree and re-measures the panel around it. */
    private void toggle(int option) {
        ResourceLocation id = this.options.get(option).id();
        if (!this.expanded.remove(id)) {
            this.expanded.add(id);
        }
        // The line under the cursor should stay under the cursor: opening a tree
        // pushes everything below it down, so the option's own line is the anchor.
        int anchor = lineOf(option);
        relayout();
        this.panelWidth = measure();
        this.scroll = Math.max(0, Math.min(anchor, maxScroll()));
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
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Empty on purpose: render() paints its own dim, and the vanilla blurred
        // background would sit on top of the panel it is supposed to be behind.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = panelLeft();
        int top = top();
        graphics.fill(0, 0, this.width, this.height, DIM);
        UiShapes.panel(graphics, left, top, this.panelWidth, panelHeight(), PANEL_BG,
                UiPalette.MODAL_BORDER_OUTER, PANEL_BORDER);
        UiShapes.headerBand(graphics, left, top, this.panelWidth, HEADER_H - 3,
                UiPalette.MODAL_HEADER_BG, UiPalette.ACCENT_LINE, PAD);
        drawTitle(graphics, left, top);

        int hover = hitLine(mouseX, mouseY);
        int hoverControl = hover >= 0 ? -1 : hitControl(mouseX, mouseY);
        Component tooltip = hover >= 0 ? tooltipAt(hover)
                : hoverControl == 0 ? Component.translatable(PlannerState.isCut(this.item)
                        ? "gui.craftplanner.picker.cut.on.tooltip"
                        : "gui.craftplanner.picker.cut.tooltip")
                : null;
        if (tooltip != null) {
            // Queued here and painted by vanilla once the pass is over
            // ({@code Screen.renderWithTooltip}), which puts it above every row this
            // method has just drawn. It is consumed as it is painted, so a frame with
            // no hovered row needs no clearing.
            setTooltipForNextRenderPass(tooltip);
        }

        int shown = visibleLines();
        // A tree's guides are drawn from its own rows, so one whose parent sits above
        // the band runs past its top edge and one scrolled off the bottom runs past
        // that. Clipped to the band, they stop where the rows stop; without it they
        // draw over the cut switch and the close row underneath.
        graphics.enableScissor(left + 1, bandTop(), left + this.panelWidth - 1,
                bandTop() + shown * ROW_H);
        drawGuides(graphics, left);

        for (int row = 0; row < shown; row++) {
            int index = this.scroll + row;
            if (index >= this.lines.size()) {
                break;
            }
            Line line = this.lines.get(index);
            int y = lineTop(index);
            if (line instanceof OptionLine option) {
                drawOptionLine(graphics, option.option(), index == hover, left, y);
            } else if (line instanceof TreeLine tree) {
                drawTreeLine(graphics, tree, y);
            }
        }
        graphics.disableScissor();

        int y = bandTop() + shown * ROW_H;
        boolean cut = PlannerState.isCut(this.item);
        // The cut switch wears the "in use" plate while it is ON: the amber
        // state must read as a decision, not as a hovered row.
        drawRow(graphics, ItemStack.EMPTY, cutLabel(), "", cut ? "✔" : "", false,
                cut, hoverControl == 0, left, y);
        y += ROW_H;
        drawRow(graphics, ItemStack.EMPTY, tr("screen.craftplanner.list.close"), "", "", false,
                false, hoverControl == 1, left, y);

        // A dozen machine alternatives scroll, and a wheel that finds nothing to show
        // for it reads as a broken list — so the band of lines carries the same
        // thumb the plan panel draws.
        int hidden = maxScroll();
        if (hidden > 0) {
            int trackTop = bandTop();
            int trackH = shown * ROW_H;
            int barH = Math.max(6, trackH * shown / this.lines.size());
            int barY = trackTop + (trackH - barH) * this.scroll / hidden;
            int barX = left + this.panelWidth - 4;
            graphics.fill(barX, trackTop, barX + 3, trackTop + trackH, UiPalette.SCROLL_TRACK);
            graphics.fill(barX, barY, barX + 3, barY + barH, SCROLL);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * The branches of every opened tree: one vertical run per visible parent down
     * to its last child, and one elbow per visible child.
     *
     * <p>Scrolled into the middle of a tree, the option row that owns it is off the
     * top of the band — so the owning option is found by walking back up, not by
     * assuming the first visible line is one. Without that, scrolling down a branch
     * would quietly drop the lines that say what the branch belongs to.</p>
     */
    private void drawGuides(GuiGraphics graphics, int left) {
        int originX = left + PAD + optionGutterW();
        int shown = visibleLines();
        int first = this.scroll;
        int last = Math.min(this.lines.size() - 1, first + shown - 1);
        int anchor = anchorOf(first);
        if (anchor < 0) {
            return;
        }
        for (int i = first; i <= last; i++) {
            if (this.lines.get(i) instanceof OptionLine row) {
                anchor = i;
            } else if (this.lines.get(i) instanceof TreeLine tree) {
                drawGuideFor(graphics, tree, originX, anchor, first, last);
            }
        }
    }

    /** Band line of the option owning {@code index}, or {@code -1} if it has none. */
    private int anchorOf(int index) {
        for (int i = index; i >= 0; i--) {
            if (this.lines.get(i) instanceof OptionLine) {
                return i;
            }
        }
        return -1;
    }

    private void drawGuideFor(GuiGraphics graphics, TreeLine line, int originX, int anchor,
            int first, int last) {
        List<TreeRow<RecipeNode>> preview = previewOf(line.option());
        if (preview == null) {
            return;
        }
        TreeRow<RecipeNode> row = preview.get(line.row());
        int depth = depthOf(row);
        int centerY = lineTop(anchor + 1 + line.row()) + ROW_H / 2;
        if (depth > 0) {
            graphics.hLine(originX + TreeMetrics.spineX(depth - 1),
                    originX + TreeMetrics.iconX(depth) - 1, centerY, UiPalette.TREE_GUIDE);
        }
        if (!row.expands(line.row())) {
            return;
        }
        int childLast = anchor + 1 + row.lastChildIndex();
        if (childLast < first || anchor + 1 + line.row() > last) {
            return;
        }
        graphics.vLine(originX + TreeMetrics.spineX(depth),
                lineTop(anchor + 1 + line.row()) + ROW_H / 2,
                lineTop(childLast) + ROW_H / 2, UiPalette.TREE_GUIDE);
    }

    private void drawTitle(GuiGraphics graphics, int left, int top) {
        graphics.drawString(this.font, this.title,
                left + (this.panelWidth - this.font.width(this.title)) / 2, top + 2, TEXT, false);
    }

    /** An option: its row as before, plus the plate that opens what the option pulls in. */
    private void drawOptionLine(GuiGraphics graphics, int option, boolean hovered, int left, int y) {
        Option row = this.rows.get(option);
        boolean used = option == this.selected;
        drawRow(graphics, row.icon(), row.cost(), "· " + row.machine(),
                used ? "✔" : "", true, used, hovered, left, y);
        drawToggle(graphics, option, hovered, left, y);
    }

    /**
     * The fold plate of an option: the tree's own fold button, so a recipe and a
     * branch open the same way.
     *
     * <p>It sits before the mark rather than replacing it: the mark says which
     * recipe is in use, the plate says there is more to read, and neither answers
     * the other.</p>
     */
    private void drawToggle(GuiGraphics graphics, int option, boolean hovered, int left, int y) {
        List<TreeRow<RecipeNode>> preview = previewOf(option);
        boolean empty = preview == null || preview.isEmpty();
        boolean open = !empty && this.expanded.contains(this.options.get(option).id());
        int height = ROW_H - 4;
        UiShapes.foldButton(graphics, left + PAD, y + (ROW_H - height) / 2, TOGGLE_W, height,
                open, hovered);
    }

    /** One row of an opened tree: guide, icon, name and how much of it is needed. */
    private void drawTreeLine(GuiGraphics graphics, TreeLine line, int y) {
        List<TreeRow<RecipeNode>> preview = previewOf(line.option());
        TreeRow<RecipeNode> row = preview.get(line.row());
        int originX = panelLeft() + PAD + optionGutterW();
        int depth = depthOf(row);
        int iconX = originX + TreeMetrics.iconX(depth);
        graphics.renderItem(row.node().displayStack(), iconX, y + (ROW_H - ICON_W) / 2);

        int textY = y + (ROW_H - this.font.lineHeight) / 2;
        int room = this.panelWidth - 2 * PAD - optionGutterW() - TreeMetrics.labelX(depth);
        String amount = "×" + row.amount();
        String name = RecipeListLayout.trimToWidth(this.font::width, row.name(),
                Math.max(0, room - this.font.width(amount) - TEXT_GAP));
        int nameX = originX + TreeMetrics.labelX(depth);
        graphics.drawString(this.font, name, nameX, textY,
                lineInk(row, PlannerState.availability()), false);
        graphics.drawString(this.font, amount,
                nameX + room - this.font.width(amount), textY, MUTED, false);
    }

    /** The same availability colouring the plan's own tree uses. */
    private static int lineInk(TreeRow<RecipeNode> row, Map<ItemKey, Availability> availability) {
        if (row.kind() == TreeRow.Kind.CYCLE) {
            return NodeColors.CYCLE;
        }
        return switch (row.status()) {
            case COVERED -> NodeColors.HAVE;
            case PARTIAL -> NodeColors.PARTIAL;
            case MISSING -> NodeColors.MISSING;
            case UNKNOWN -> switch (row.kind()) {
                case ROOT -> NodeColors.ROOT;
                case ASSEMBLY -> NodeColors.INTERMEDIATE;
                case MATERIAL -> NodeColors.RAW;
                case CYCLE -> NodeColors.CYCLE;
            };
        };
    }

    /**
     * One row: its highlight, then mark, icon and text. The mark column and the
     * icon gutter keep their room even when empty, so the text of every row
     * starts at the same x — a machine the block lookup does not know leaves the
     * gutter blank rather than shifting its line left out of alignment with the
     * rows above it.
     */
    private void drawRow(GuiGraphics graphics, ItemStack icon, String primary, String secondary,
            String mark, boolean gutter, boolean used, boolean hovered, int left, int y) {
        int inner = this.panelWidth - 2 * PAD;
        if (hovered || used) {
            graphics.fill(left + PAD, y, left + PAD + inner, y + ROW_H,
                    used ? ROW_SELECTED : ROW_HOVER);
        }

        int bx = left + PAD + TOGGLE_W + TOGGLE_GAP;
        int gutterW = MARK_W + (gutter ? ICON_W + GAP : 0);
        int textX = bx + gutterW;
        int textY = y + (ROW_H - this.font.lineHeight) / 2;
        if (!mark.isEmpty()) {
            graphics.drawString(this.font, mark, bx, textY, TEXT, false);
        }
        if (gutter && !icon.isEmpty()) {
            graphics.renderItem(icon, bx + MARK_W, y + (ROW_H - ICON_W) / 2);
        }

        // The cost has the first claim on the row: two options are compared by what
        // they consume. The machine's name is what gives way, because its icon is
        // already standing there — unless the lookup found no block, when the name
        // is the only thing saying which machine this is, so it is kept and drawn as
        // primary text. A tail too narrow to read is dropped whole rather than shown
        // as "· …", which carries no more information than the icon.
        int room = inner - gutterW - TOGGLE_W - TOGGLE_GAP;
        String head = primary;
        String tail = secondary;
        int tailRoom = room - Math.min(this.font.width(head), room) - TEXT_GAP;
        if (!tail.isEmpty() && this.font.width(tail) > tailRoom) {
            if (icon.isEmpty() || tailRoom >= MIN_TAIL) {
                tail = RecipeListLayout.trimToWidth(this.font::width, tail,
                        Math.max(0, tailRoom));
            } else {
                tail = "";
            }
        }
        int headRoom = tail.isEmpty() ? room
                : room - this.font.width(tail) - TEXT_GAP;
        if (this.font.width(head) > headRoom) {
            head = RecipeListLayout.trimToWidth(this.font::width, head, Math.max(0, headRoom));
        }
        graphics.drawString(this.font, head, textX, textY, TEXT, false);
        if (!tail.isEmpty()) {
            graphics.drawString(this.font, tail, textX + this.font.width(head) + TEXT_GAP,
                    textY, icon.isEmpty() ? TEXT : MUTED, false);
        }
    }

    /** Closes to the screen the picker was opened from — never to the world. */
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