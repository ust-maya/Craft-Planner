package com.craftplanner.client.screen;

import com.craftplanner.Config;
import com.craftplanner.client.MachineNames;
import com.craftplanner.client.AmountLabel;
import com.craftplanner.client.PlanRows;
import com.craftplanner.client.PlannerState;
import com.craftplanner.client.RecipeLabel;
import com.craftplanner.client.RecipePreview;
import com.craftplanner.client.Scrollbar;
import com.craftplanner.client.UiLegend;
import com.craftplanner.client.UiPalette;
import com.craftplanner.client.UiShapes;
import com.craftplanner.client.overlay.NodeColors;
import com.craftplanner.client.resource.Availability;
import com.craftplanner.client.resource.InventorySnapshot;
import com.craftplanner.client.resource.ResourceCheck;
import com.craftplanner.client.tree.FoldSteps;
import com.craftplanner.client.tree.TreeFlatten;
import com.craftplanner.client.tree.TreeMetrics;
import com.craftplanner.client.tree.TreeRow;
import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.RecipeNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
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
 * <p>The options are compared <em>by column</em>: what each one consumes, how much
 * one run gives back, and the machine that runs it, under headings that name the
 * three. Two recipes for the same item can cost the same raw materials and pull in
 * entirely different things further down — a furnace route that needs a furnace, against one
 * that needs a blast furnace and eight slabs — and a totals line says nothing about
 * that; the columns keep the difference where the eye can line it up. Which mod
 * owns a recipe is nothing the rows are compared by, so it lives in the option's
 * tooltip instead of six times down the list. Every
 * option can also be opened into its own dependency tree, drawn with the
 * plan's own geometry, so the alternatives are read as branches rather than as
 * numbers. Every option starts closed, like every tree outside the plan window:
 * the window opens on the comparison itself, and what an option drags in is one
 * click away and only worked out when it is asked for. Inside an opened tree every
 * branch folds on its own plate, and starts folded: the first look at an option is
 * its direct ingredients.</p>
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
    /** Line under the title band that names the three columns. */
    private static final int COLUMNS_H = 11;
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
    /** Air between two columns of an option row. */
    private static final int COL_GAP = 6;
    /** Floors and cap of the columns that may give way; see {@link PickerColumns}. */
    private static final int MIN_INPUTS_W = 80;
    private static final int MIN_MACHINE_W = 40;
    private static final int MAX_MACHINE_W = 140;
    /** Two button rows under the options: the fold buttons, then the cut switch and close. */
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
    /**
     * The mark of the option in use, in the column before the machine's icon. A
     * guillemet rather than the tree's ✔: that sign now says the inputs are covered,
     * and the row in use is already standing in an amber band.
     */
    private static final String IN_USE_MARK = "»";

    /** The item whose recipes are offered; also the key of the choice. */
    private final ItemKey item;
    /** Screen to go back to, so opening the picker never loses the player's place. */
    private final Screen returnTo;

    private List<RecipeHolder<?>> options = List.of();
    /** The same options as drawn rows. */
    private List<Option> rows = List.of();
    /** Everything the band shows, options and the trees opened under them. */
    private List<Line> lines = List.of();
    /** The flattened tree of every opened option, rebuilt by {@link #relayout()}. */
    private final Map<Integer, List<TreeRow<RecipeNode>>> trees = new HashMap<>();
    /**
     * Rows each option's whole tree holds — 0 when the recipe consumes only what
     * the plan treats as material, so there is nothing to open. Counted for every
     * option, closed ones too: the plate and its tooltip have to say what opening
     * would show before it is opened.
     */
    private int[] treeSizes = new int[0];
    /** Which options are opened, by recipe id so a re-sort does not close them. */
    private final Set<ResourceLocation> expanded = new LinkedHashSet<>();
    /**
     * Branches opened inside the options' trees, keyed {@code "<recipe id>|<tree path>"}:
     * two options that share an ingredient keep folds of their own. Everything not
     * here is folded, as in every tree outside the plan window.
     */
    private final Set<String> openedPaths = new HashSet<>();
    private int selected = -1;
    private int scroll;
    /** Whether the first layout already put the row in use on screen. */
    private boolean centered;
    private int panelWidth;
    /** How the last measure() split the panel between the three columns. */
    private PickerColumns columns = new PickerColumns(0, 0, 0);
    private long seenGraphVersion;
    /** Availability version the rows were last coloured for; the colours follow the inventory. */
    private long seenAvailabilityVersion = -1;
    private Button cutButton;
    private Button closeButton;
    /** The window's fold buttons, placed on the first control row every frame like the others. */
    private List<Button> foldButtons = List.of();
    /** Cut state the cut button was last labelled for; the label follows the plan, not a click. */
    private boolean cutShown;

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
     * One option as its columns: what it consumes — coloured by what the inventory
     * holds — how many one run makes, the machine that runs it and that machine's
     * icon ({@link ItemStack#EMPTY} when no block claims the recipe type), plus the
     * full description for the tooltip — the mod's name lives there, not in every
     * row.
     */
    private record Option(String inputs, int inputsColor, String yield, String machine,
            ItemStack icon, Component tooltip) {
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
        // One look at the inventory per layout; tick() lays the window out again when
        // the plan's availability moves, which is when the inventory changed.
        InventorySnapshot snapshot = this.minecraft.player == null ? null
                : InventorySnapshot.of(this.minecraft.player);
        this.seenAvailabilityVersion = PlannerState.availabilityVersion();
        for (RecipeHolder<?> option : this.options) {
            String needs = RecipeLabel.needsText(option);
            Availability.Status status = statusOf(option, snapshot);
            String inputs = needs.isEmpty() ? tr("gui.craftplanner.recipe.none") : needs;
            built.add(new Option(status == null ? inputs : markOf(status) + " " + inputs, colorOf(status),
                    yieldOf(option, registries), RecipeLabel.machine(option), machineIcon(option),
                    join(RecipeLabel.describe(option, registries))));
        }
        this.rows = built;
        this.treeSizes = new int[this.options.size()];
        for (int i = 0; i < this.options.size(); i++) {
            this.treeSizes[i] = TreeFlatten.flatten(treeRootsOf(i), PlanRows.planSource(), Set.of()).size();
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
        // init() also runs from tick() and render() whenever the plan changes, so the
        // buttons of the previous layout are dropped first.
        clearWidgets();
        this.cutShown = PlannerState.isCut(this.item);
        this.cutButton = addRenderableWidget(FitButton.create(0, 0, cutButtonW(), RecipeListLayout.BUTTON_H,
                Component.literal(cutLabel(this.cutShown)), button -> {
                    PlannerState.toggleCut(this.item);
                    this.seenGraphVersion = PlannerState.graphVersion();
                }, cutTooltip(this.cutShown)));
        this.closeButton = addRenderableWidget(FitButton.create(0, 0, closeButtonW(),
                RecipeListLayout.BUTTON_H, Component.literal(tr("screen.craftplanner.list.close")),
                button -> onClose(), null));
        this.foldButtons = FoldButtons.create(this.font, RecipeListLayout.BUTTON_H, new FoldButtons.Actions() {
            @Override
            public void collapseAll() {
                RecipePickerScreen.this.expanded.clear();
                RecipePickerScreen.this.openedPaths.clear();
                applyBand(FoldSteps.Change.NONE);
            }

            @Override
            public void expandAll() {
                applyBand(new FoldSteps.Change(Set.of(), FoldSteps.allPaths(wholeBand())));
            }

            @Override
            public void collapseLevel() {
                applyBand(FoldSteps.collapseLevel(wholeBand(), RecipePickerScreen.this::bandFolded));
            }

            @Override
            public void expandLevel() {
                applyBand(FoldSteps.expandLevel(wholeBand(), RecipePickerScreen.this::bandFolded));
            }
        });
        this.foldButtons.forEach(this::addRenderableWidget);
    }

    private static Set<ResourceLocation> idsOf(List<RecipeHolder<?>> options) {
        Set<ResourceLocation> ids = new LinkedHashSet<>(options.size());
        for (RecipeHolder<?> option : options) {
            ids.add(option.id());
        }
        return ids;
    }

    /** The key a branch of an option's tree is remembered by in {@link #openedPaths}. */
    private static String keyOf(RecipeHolder<?> option, String path) {
        return option.id() + "|" + path;
    }

    /** Rebuilds the band: every option, and under each opened one its dependency tree. */
    private void relayout() {
        this.trees.clear();
        List<Line> built = new ArrayList<>(this.rows.size());
        for (int i = 0; i < this.rows.size(); i++) {
            built.add(new OptionLine(i));
            if (!hasTree(i) || !this.expanded.contains(this.options.get(i).id())) {
                continue;
            }
            RecipeHolder<?> recipe = this.options.get(i);
            List<TreeRow<RecipeNode>> tree = TreeFlatten.flatten(treeRootsOf(i), PlanRows.planSource(),
                    path -> !this.openedPaths.contains(keyOf(recipe, path)));
            this.trees.put(i, tree);
            for (int r = 0; r < tree.size(); r++) {
                built.add(new TreeLine(i, r));
            }
        }
        this.lines = built;
    }

    /**
     * The whole band as the fold steps see it: each option at depth 0 — keyed by its
     * recipe id, with no "|" in it — and its full tree below it, keyed like
     * {@link #openedPaths}. An option with nothing to open is a leaf.
     */
    private List<FoldSteps.Entry> wholeBand() {
        List<FoldSteps.Entry> entries = new ArrayList<>();
        for (int i = 0; i < this.options.size(); i++) {
            RecipeHolder<?> recipe = this.options.get(i);
            if (!hasTree(i)) {
                entries.add(new FoldSteps.Entry(null, 0));
                continue;
            }
            entries.add(new FoldSteps.Entry(recipe.id().toString(), 0));
            for (TreeRow<RecipeNode> row : TreeFlatten.flatten(treeRootsOf(i), PlanRows.planSource(), Set.of())) {
                entries.add(new FoldSteps.Entry(row.path() == null ? null : keyOf(recipe, row.path()),
                        row.depth() + 1));
            }
        }
        return entries;
    }

    /** Whether a band key is folded: an option while it is closed, a branch while it is not opened. */
    private boolean bandFolded(String key) {
        return key.indexOf('|') < 0
                ? !this.expanded.contains(ResourceLocation.tryParse(key))
                : !this.openedPaths.contains(key);
    }

    /** Applies a fold step to the options and their trees; the scroll stays, as on a single click. */
    private void applyBand(FoldSteps.Change change) {
        for (String key : change.fold()) {
            if (key.indexOf('|') < 0) {
                this.expanded.remove(ResourceLocation.tryParse(key));
            } else {
                this.openedPaths.remove(key);
            }
        }
        for (String key : change.unfold()) {
            if (key.indexOf('|') < 0) {
                this.expanded.add(ResourceLocation.tryParse(key));
            } else {
                this.openedPaths.add(key);
            }
        }
        relayout();
        this.panelWidth = measure();
        this.scroll = Math.min(this.scroll, maxScroll());
    }

    /**
     * Where an option's tree starts: the ingredients of its preview root. The root
     * itself is the item the option row already names, so its children hang off
     * the option instead of off a name that is not on screen.
     */
    private List<RecipeNode> treeRootsOf(int option) {
        RecipeNode root = RecipePreview.rootOf(this.item, this.options.get(option));
        return root == null ? List.of() : PlanRows.planSource().childrenOf(root);
    }

    /** Whether the option has anything below it to open. */
    private boolean hasTree(int option) {
        return option >= 0 && option < this.treeSizes.length && this.treeSizes[option] > 0;
    }

    /**
     * The option's dependency tree as laid out by the last {@link #relayout()},
     * or {@code null} while it is closed.
     */
    private List<TreeRow<RecipeNode>> previewOf(int option) {
        return this.trees.get(option);
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
        if (PlannerState.graphVersion() != this.seenGraphVersion
                || PlannerState.availabilityVersion() != this.seenAvailabilityVersion) {
            init();
        }
    }

    /**
     * {@code "×2"} for how many one run makes of the item this window is about,
     * empty otherwise. Two recipes can ask for the same
     * thing and differ only in how much they give back, and the row is where the
     * options are compared.
     */
    private String yieldOf(RecipeHolder<?> option, HolderLookup.Provider registries) {
        ItemStack result = option.value().getResultItem(registries);
        // The yield is shown even when it is one: a row without it read as a row
        // with something missing next to rows that had it.
        if (result.isEmpty() || !ItemKey.of(result).equals(this.item)) {
            return "";
        }
        return AmountLabel.times(result.getCount());
    }

    /**
     * Whether the player could run this recipe from what they carry: each input
     * against the inventory, folded into one verdict. Null without an inventory to
     * look at — the row then says nothing rather than guess.
     */
    private static Availability.Status statusOf(RecipeHolder<?> option, InventorySnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        List<Availability.Status> each = new ArrayList<>();
        RecipeLabel.inputs(option).forEach((key, count) -> each.add(
                new Availability(count, snapshot.available(key)).status(Config.partialThresholdPercent())));
        return ResourceCheck.combined(each);
    }

    /** The sign the tree puts in front of a material, for the same three verdicts. */
    private static String markOf(Availability.Status status) {
        return switch (status) {
            case OK -> TreeRow.Status.COVERED.mark();
            case PARTIAL -> TreeRow.Status.PARTIAL.mark();
            case MISSING -> TreeRow.Status.MISSING.mark();
        };
    }

    private static int colorOf(Availability.Status status) {
        if (status == null) {
            return TEXT;
        }
        return switch (status) {
            case OK -> NodeColors.HAVE;
            case PARTIAL -> NodeColors.PARTIAL;
            case MISSING -> NodeColors.MISSING;
        };
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
     *
     * <p>The same pass fits the three option columns to the panel actually granted
     * and leaves the result in {@link #columns}, so no draw call asks {@link PickerColumns}
     * again.</p>
     */
    private int measure() {
        // Each column wants its widest entry or its heading, whichever is wider; the
        // machine's name is capped because its icon already stands next to it.
        int inputsWant = this.font.width(tr("gui.craftplanner.picker.col.inputs"));
        int yieldW = this.font.width(tr("gui.craftplanner.picker.col.yield"));
        int machineWant = this.font.width(tr("gui.craftplanner.picker.col.machine"));
        for (Option row : this.rows) {
            inputsWant = Math.max(inputsWant, this.font.width(row.inputs()));
            yieldW = Math.max(yieldW, this.font.width(row.yield()));
            machineWant = Math.max(machineWant, this.font.width(row.machine()));
        }
        machineWant = Math.min(machineWant, MAX_MACHINE_W);
        int widest = Math.max(this.font.width(this.title),
                optionGutterW() + inputsWant + COL_GAP + yieldW + COL_GAP + machineWant);
        for (int i = 0; i < this.rows.size(); i++) {
            List<TreeRow<RecipeNode>> preview = previewOf(i);
            if (preview == null) {
                continue;
            }
            for (TreeRow<RecipeNode> row : preview) {
                String amount = AmountLabel.marked(row)
                        + (row.hiddenRows() > 0 ? " (+" + row.hiddenRows() + ")" : "");
                widest = Math.max(widest, optionGutterW() + TreeMetrics.labelX(depthOf(row))
                    + this.font.width(row.name()) + TEXT_GAP
                    + this.font.width(amount));
            }
        }
        widest = Math.max(widest, cutButtonW() + GAP + closeButtonW());
        widest = Math.max(widest, FoldButtons.width(this.font));
        int width = Math.min(this.width - 2 * MARGIN, Math.max(200, widest + 2 + 2 * PAD));
        // The columns are fitted to the panel actually granted, which on a small
        // window is narrower than the rows asked for.
        this.columns = PickerColumns.fit(width - 2 * PAD - optionGutterW(), inputsWant, yieldW,
                machineWant, COL_GAP, MIN_MACHINE_W, MIN_INPUTS_W);
        return width;
    }

    /** The columns every option row spends before its text starts. */
    private static int optionGutterW() {
        return TOGGLE_W + TOGGLE_GAP + MARK_W + ICON_W + GAP;
    }

    /**
     * How deep a tree row is drawn: the tree starts from the recipe's ingredients,
     * so a row's own depth is its column.
     */
    private static int depthOf(TreeRow<RecipeNode> row) {
        return row.depth();
    }

    private static String cutLabel(boolean cut) {
        return "✂ " + tr(cut ? "gui.craftplanner.picker.uncut" : "gui.craftplanner.picker.cut");
    }

    /** Wide enough for either label, so the button does not jump when it is pressed. */
    private int cutButtonW() {
        return Math.max(this.font.width(cutLabel(false)), this.font.width(cutLabel(true))) + 2 * PAD;
    }

    private int closeButtonW() {
        return Math.max(48, this.font.width(tr("screen.craftplanner.list.close")) + 2 * PAD);
    }

    private static Component cutTooltip(boolean cut) {
        return Component.translatable(cut ? "gui.craftplanner.picker.cut.on.tooltip"
                : "gui.craftplanner.picker.cut.tooltip");
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
     * subtracted first — the title band, the button row under the list, the colour
     * key under it and the padding below that — so a window taller than the screen
     * never happens above a certain height, and the last line never falls off the
     * bottom.
     */
    private int visibleLines() {
        int available = this.height - 2 * MARGIN - HEADER_H - COLUMNS_H - CONTROL_ROWS * ROW_H
                - legendH() - PAD;
        return Math.max(1, Math.min(this.lines.size(), available / ROW_H));
    }

    /**
     * Room the colour key takes under the buttons: three pixels for its divider and
     * one or two lines. The rows here are coloured like the plan's own tree — the
     * inputs by what the inventory holds, an opened tree by what each row is — and
     * the key that names those colours was only ever in the tree window.
     */
    private int legendH() {
        return 3 + UiLegend.lineCount(this.font, this.panelWidth - 2 * PAD) * (this.font.lineHeight + 2);
    }

    private int maxScroll() {
        return Math.max(0, this.lines.size() - visibleLines());
    }

    private int panelHeight() {
        return HEADER_H + COLUMNS_H + visibleLines() * ROW_H + CONTROL_ROWS * ROW_H + legendH() + PAD;
    }

    private int panelLeft() {
        return (this.width - this.panelWidth) / 2;
    }

    private int top() {
        return Math.max(MARGIN, (this.height - panelHeight()) / 2);
    }

    /** Top of the scrollable band. */
    private int bandTop() {
        return top() + HEADER_H + COLUMNS_H;
    }

    /** Screen y of a line of the band. */
    private int lineTop(int index) {
        return bandTop() + (index - this.scroll) * ROW_H;
    }

    /**
     * Line under a point, or {@code -1} outside the band of lines. Screen pixels on
     * both sides — the drawing is what moves, the clicks are not. The right edge is
     * exclusive because that is where the row's highlight stops painting.
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

    /** Whether a point is on an option's fold plate, which opens its tree. */
    private boolean overToggle(int mx, int my) {
        int index = hitLine(mx, my);
        if (index < 0 || !(this.lines.get(index) instanceof OptionLine)) {
            return false;
        }
        return mx < panelLeft() + PAD + TOGGLE_W + TOGGLE_GAP / 2;
    }

    /** Whether a point is on the fold plate of a tree line — where the tree draws it, by depth. */
    private boolean overTreeToggle(int mx, TreeLine line) {
        List<TreeRow<RecipeNode>> tree = previewOf(line.option());
        if (tree == null || line.row() >= tree.size() || !tree.get(line.row()).foldable()) {
            return false;
        }
        int x = panelLeft() + PAD + optionGutterW()
                + TreeMetrics.toggleX(depthOf(tree.get(line.row())));
        return mx >= x && mx < x + Math.max(TreeMetrics.TOGGLE_W, TreeMetrics.MIN_TOGGLE_HIT_W);
    }

    /** The tooltip of a line, or {@code null} for a row that has none. */
    private Component tooltipAt(int index, int mx) {
        if (index < 0 || index >= this.lines.size()) {
            return null;
        }
        Line line = this.lines.get(index);
        if (line instanceof OptionLine row) {
            Component base = this.rows.get(row.option()).tooltip();
            return base.copy().append("\n").append(toggleHint(row.option()));
        }
        TreeLine tree = (TreeLine) line;
        if (overTreeToggle(mx, tree)) {
            TreeRow<RecipeNode> row = previewOf(tree.option()).get(tree.row());
            return Component.translatable(row.folded()
                    ? "overlay.craftplanner.fold.expand" : "overlay.craftplanner.fold.collapse");
        }
        return Component.translatable("gui.craftplanner.picker.tree.readonly");
    }

    private Component toggleHint(int option) {
        if (!hasTree(option)) {
            return Component.translatable("gui.craftplanner.picker.tree.empty");
        }
        boolean open = this.expanded.contains(this.options.get(option).id());
        return Component.translatable(open ? "gui.craftplanner.picker.collapse"
                : "gui.craftplanner.picker.expand", this.treeSizes[option]);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Widgets first: the cut switch and the close button sit in the row this
        // method used to hit-test by hand.
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        int mx = (int) mouseX;
        int my = (int) mouseY;
        int index = hitLine(mx, my);
        if (index >= 0) {
            Line line = this.lines.get(index);
            if (line instanceof TreeLine tree) {
                // A dependency row is not a choice: opening the tree must not be a way to
                // switch recipes by accident. Its plate folds its branch, as in every tree.
                if (overTreeToggle(mx, tree)) {
                    toggleBranch(tree.option(), previewOf(tree.option()).get(tree.row()).path());
                }
                return true;
            }
            OptionLine row = (OptionLine) line;
            if (overToggle(mx, my) && hasTree(row.option())) {
                toggle(row.option());
                return true;
            }
            choose(this.options.get(row.option()));
            return true;
        }
        return false;
    }

    /** Opens or closes an option's dependency tree and re-measures the panel around it. */
    private void toggle(int option) {
        ResourceLocation id = this.options.get(option).id();
        if (!this.expanded.remove(id)) {
            this.expanded.add(id);
        }
        // Opening or closing a tree only changes the lines below the option, so the
        // scroll is left alone and the option stays under the cursor — as in every
        // other tree. Scrolling to it moved the clicked line to the top of the band.
        relayout();
        this.panelWidth = measure();
        this.scroll = Math.min(this.scroll, maxScroll());
    }

    /** Folds or unfolds one branch of an option's tree; the scroll stays, as in {@link #toggle}. */
    private void toggleBranch(int option, String path) {
        String key = keyOf(this.options.get(option), path);
        if (!this.openedPaths.remove(key)) {
            this.openedPaths.add(key);
        }
        relayout();
        this.panelWidth = measure();
        this.scroll = Math.min(this.scroll, maxScroll());
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
        // The plan can be rebuilt between this screen's tick and this frame: the
        // debounced rebuild runs in the client tick after Screen.tick(). The lines
        // were laid out from the previews of the old plan, and a preview of the new
        // one can be shorter (a cut item has no tree at all), so the band is laid out
        // again before anything indexes into a preview.
        if (PlannerState.graphVersion() != this.seenGraphVersion) {
            init();
            if (this.minecraft.screen != this) {
                return; // init() closed the picker: the item left the plan
            }
        }
        int left = panelLeft();
        int top = top();
        graphics.fill(0, 0, this.width, this.height, DIM);
        UiShapes.panel(graphics, left, top, this.panelWidth, panelHeight(), PANEL_BG,
                UiPalette.MODAL_BORDER_OUTER, PANEL_BORDER);
        UiShapes.headerBand(graphics, left, top, this.panelWidth, HEADER_H - 3,
                UiPalette.MODAL_HEADER_BG, UiPalette.ACCENT_LINE, PAD);
        drawTitle(graphics, left, top);
        drawColumnHeadings(graphics, left, top);

        int hover = hitLine(mouseX, mouseY);
        Component tooltip = hover >= 0 ? tooltipAt(hover, mouseX) : null;
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
        // draw over the button row underneath.
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
                drawTreeLine(graphics, tree, y, index == hover && overTreeToggle(mouseX, tree));
            }
        }
        graphics.disableScissor();

        // Two rows of buttons under the options: the fold buttons act on the trees,
        // the cut switch and close on the item and the window.
        int foldY = bandTop() + shown * ROW_H + (ROW_H - RecipeListLayout.BUTTON_H) / 2;
        int buttonY = foldY + ROW_H;
        FoldButtons.place(this.foldButtons, left + PAD, foldY);
        boolean cut = PlannerState.isCut(this.item);
        if (cut != this.cutShown) {
            this.cutShown = cut;
            this.cutButton.setMessage(Component.literal(cutLabel(cut)));
            this.cutButton.setTooltip(Tooltip.create(cutTooltip(cut)));
        }
        this.cutButton.setX(left + PAD);
        this.cutButton.setY(buttonY);
        this.closeButton.setX(left + this.panelWidth - PAD - this.closeButton.getWidth());
        this.closeButton.setY(buttonY);

        int legendTop = bandTop() + shown * ROW_H + CONTROL_ROWS * ROW_H;
        UiShapes.divider(graphics, left + 2, left + this.panelWidth - 2, legendTop,
                UiPalette.SECTION_DIVIDER);
        UiLegend.draw(graphics, this.font, left + PAD, legendTop + 3, this.panelWidth - 2 * PAD);

        // A dozen machine alternatives scroll, and a wheel that finds nothing to show
        // for it reads as a broken list — so the band of lines carries the same
        // thumb the plan panel draws.
        if (maxScroll() > 0) {
            Scrollbar.draw(graphics, Scrollbar.of(left + this.panelWidth - 4, bandTop(),
                    shown * ROW_H, shown, this.lines.size(), this.scroll, maxScroll(), 6));
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
        if (preview == null || line.row() >= preview.size()) {
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

    /** The column headings under the title band, aligned with the option rows. */
    private void drawColumnHeadings(GuiGraphics graphics, int left, int top) {
        int y = top + HEADER_H + 1;
        int x = left + PAD + optionGutterW();
        graphics.drawString(this.font, RecipeListLayout.trimToWidth(this.font::width,
                tr("gui.craftplanner.picker.col.inputs"), this.columns.inputsW()), x, y, MUTED, false);
        x += this.columns.inputsW() + COL_GAP;
        String yield = tr("gui.craftplanner.picker.col.yield");
        graphics.drawString(this.font, yield, x + this.columns.yieldW() - this.font.width(yield), y,
                MUTED, false);
        x += this.columns.yieldW() + COL_GAP;
        if (this.columns.machineW() > 0) {
            graphics.drawString(this.font, RecipeListLayout.trimToWidth(this.font::width,
                    tr("gui.craftplanner.picker.col.machine"), this.columns.machineW()), x, y, MUTED, false);
        }
    }

    private void drawTitle(GuiGraphics graphics, int left, int top) {
        graphics.drawString(this.font, this.title,
                left + (this.panelWidth - this.font.width(this.title)) / 2, top + 2, TEXT, false);
    }

    /** An option: its highlight, the in-use mark, the machine icon, then the three columns. */
    private void drawOptionLine(GuiGraphics graphics, int option, boolean hovered, int left, int y) {
        Option row = this.rows.get(option);
        boolean used = option == this.selected;
        if (hovered || used) {
            graphics.fill(left + PAD, y, left + this.panelWidth - PAD, y + ROW_H,
                    used ? ROW_SELECTED : ROW_HOVER);
        }
        int bx = left + PAD + TOGGLE_W + TOGGLE_GAP;
        int textY = y + (ROW_H - this.font.lineHeight) / 2;
        if (used) {
            graphics.drawString(this.font, IN_USE_MARK, bx, textY, TEXT, false);
        }
        if (!row.icon().isEmpty()) {
            graphics.renderItem(row.icon(), bx + MARK_W, y + (ROW_H - ICON_W) / 2);
        }
        int x = left + PAD + optionGutterW();
        graphics.drawString(this.font, RecipeListLayout.trimToWidth(this.font::width, row.inputs(),
                this.columns.inputsW()), x, textY, row.inputsColor(), false);
        x += this.columns.inputsW() + COL_GAP;
        graphics.drawString(this.font, row.yield(),
                x + this.columns.yieldW() - this.font.width(row.yield()), textY, TEXT, false);
        x += this.columns.yieldW() + COL_GAP;
        if (this.columns.machineW() > 0) {
            graphics.drawString(this.font, RecipeListLayout.trimToWidth(this.font::width, row.machine(),
                    this.columns.machineW()), x, textY, MUTED, false);
        }
        drawToggle(graphics, option, hovered, left, y);
    }

    /**
     * The fold plate of an option: the tree's own fold button, so a recipe and a
     * branch open the same way — "+" while closed, "−" while open, and no plate at
     * all when the recipe has nothing below it, like a leaf of the tree.
     *
     * <p>It sits before the mark rather than replacing it: the mark says which
     * recipe is in use, the plate says there is more to read, and neither answers
     * the other.</p>
     */
    private void drawToggle(GuiGraphics graphics, int option, boolean hovered, int left, int y) {
        if (!hasTree(option)) {
            return;
        }
        boolean open = this.expanded.contains(this.options.get(option).id());
        int height = ROW_H - 4;
        UiShapes.foldButton(graphics, left + PAD, y + (ROW_H - height) / 2, TOGGLE_W, height,
                !open, hovered);
    }

    /** One row of an opened tree: its own fold plate, guide, icon, name and amount. */
    private void drawTreeLine(GuiGraphics graphics, TreeLine line, int y, boolean aimed) {
        List<TreeRow<RecipeNode>> preview = previewOf(line.option());
        if (preview == null || line.row() >= preview.size()) {
            return;
        }
        TreeRow<RecipeNode> row = preview.get(line.row());
        int originX = panelLeft() + PAD + optionGutterW();
        int depth = depthOf(row);
        int iconX = originX + TreeMetrics.iconX(depth);
        if (row.foldable()) {
            int height = ROW_H - 4;
            UiShapes.foldButton(graphics, originX + TreeMetrics.toggleX(depth), y + (ROW_H - height) / 2,
                    TreeMetrics.TOGGLE_W, height, row.folded(), aimed);
        }
        graphics.renderItem(row.node().displayStack(), iconX, y + (ROW_H - ICON_W) / 2);

        int textY = y + (ROW_H - this.font.lineHeight) / 2;
        int room = this.panelWidth - 2 * PAD - optionGutterW() - TreeMetrics.labelX(depth);
        String amount = AmountLabel.marked(row) + (row.hiddenRows() > 0 ? " (+" + row.hiddenRows() + ")" : "");
        String name = RecipeListLayout.trimToWidth(this.font::width, row.name(),
                Math.max(0, room - this.font.width(amount) - TEXT_GAP));
        int nameX = originX + TreeMetrics.labelX(depth);
        graphics.drawString(this.font, name, nameX, textY,
                NodeColors.forRow(row), false);
        graphics.drawString(this.font, amount,
                nameX + room - this.font.width(amount), textY, NodeColors.forRow(row), false);
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