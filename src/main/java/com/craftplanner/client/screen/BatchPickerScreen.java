package com.craftplanner.client.screen;

import com.craftplanner.client.PlannerState;
import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.RecipeNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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
 * <p>Like the picker, nothing is held across a rebuild: the engine re-expands
 * the tree in place, so every refresh re-reads the graph (see {@link #tick()}).
 * Items are listed in tree order and only once, because a choice for an item
 * applies to every row carrying it.</p>
 */
public final class BatchPickerScreen extends Screen {

    /** Height of one item row; the same rhythm as the other planner panels. */
    private static final int ROW_H = 18;
    private static final int HEADER_H = 24;
    private static final int PAD = 6;
    /** Space in front of the row text, taken by the item icon. */
    private static final int ICON_W = 20;
    private static final int PANEL_BG = 0xF0101010;
    private static final int PANEL_BORDER = 0xFF404040;
    private static final int DIM = 0xB0000000;
    private static final int TEXT = 0xFFE0E0E0;

    private final Screen returnTo;
    /** The ambiguous nodes as of the last rebuild, one per item. */
    private List<RecipeNode> rows = List.of();
    private int panelWidth;
    private int scroll;
    private long seenGraphVersion;

    public BatchPickerScreen(Screen returnTo) {
        super(Component.translatable("screen.craftplanner.ambiguous.title"));
        this.returnTo = returnTo;
    }

    /**
     * Every planned item with more than one recipe, in tree order and once per
     * item — the rows this screen offers and the footer button counts.
     */
    public static List<RecipeNode> ambiguousNodes() {
        LinkedHashMap<ItemKey, RecipeNode> byItem = new LinkedHashMap<>();
        PlannerState.graph().forEachNode(node -> {
            if (node.options().size() > 1) {
                byItem.putIfAbsent(node.item(), node);
            }
        });
        return new ArrayList<>(byItem.values());
    }

    @Override
    protected void init() {
        this.seenGraphVersion = PlannerState.graphVersion();
        this.rows = ambiguousNodes();
        if (this.rows.isEmpty() || this.minecraft.level == null) {
            // Nothing left to choose between (the plan changed, or the world
            // went away): close instead of offering an empty box.
            this.minecraft.setScreen(this.returnTo);
            return;
        }
        this.panelWidth = Math.min(this.width - 16, Math.max(260, this.width / 3));
        this.scroll = Math.min(this.scroll, maxScroll());
        clearWidgets();
        int left = (this.width - this.panelWidth) / 2;
        int rowW = this.panelWidth - 2 * PAD - ICON_W;
        int y = top() + HEADER_H;
        for (int i = 0; i < visibleRows(); i++) {
            RecipeNode node = this.rows.get(this.scroll + i);
            addRenderableWidget(FitButton.create(left + PAD + ICON_W, y, rowW, ROW_H - 2,
                    Component.literal(rowLabel(node)),
                    button -> this.minecraft.setScreen(new RecipePickerScreen(node.item(), this)),
                    Component.translatable("screen.craftplanner.ambiguous.row.tooltip")));
            y += ROW_H;
        }
        addRenderableWidget(FitButton.create(left + PAD + ICON_W, y + 3, rowW, ROW_H - 2,
                Component.translatable("screen.craftplanner.list.close"),
                button -> onClose(), null));
    }

    /** {@code "Copper Plate   2/3 ▾"} — the counter of the row button, right aligned. */
    private String rowLabel(RecipeNode node) {
        int total = node.options().size();
        int index = Math.min(node.selectedRecipeIndex(), total - 1);
        int innerW = panelWidth - 2 * PAD - ICON_W;
        String counter = (index + 1) + "/" + total + " ▾";
        String name = RecipeListLayout.trimToWidth(this.font::width,
                node.item().name().getString(), innerW - this.font.width(counter) - 8);
        return name + " " + counter;
    }

    @Override
    public void tick() {
        if (PlannerState.graphVersion() != this.seenGraphVersion) {
            init();
        }
    }

    private int visibleRows() {
        int available = this.height - 16 - HEADER_H - 2 * ROW_H;
        return Math.max(1, Math.min(this.rows.size(), available / ROW_H));
    }

    private int maxScroll() {
        return Math.max(0, this.rows.size() - visibleRows());
    }

    private int panelHeight() {
        return HEADER_H + (visibleRows() + 1) * ROW_H + PAD + 3;
    }

    private int top() {
        return Math.max(8, (this.height - panelHeight()) / 2);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() <= 0) {
            return false;
        }
        this.scroll = Math.max(0, Math.min(maxScroll(), this.scroll + (scrollY > 0 ? -1 : 1)));
        init();
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Empty on purpose, like the picker: render() paints its own dim.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = (this.width - this.panelWidth) / 2;
        int top = top();
        int bottom = top + panelHeight();
        graphics.fill(0, 0, this.width, this.height, DIM);
        graphics.fill(left, top, left + this.panelWidth, bottom, PANEL_BG);
        graphics.fill(left, top, left + this.panelWidth, top + 1, PANEL_BORDER);
        graphics.fill(left, bottom - 1, left + this.panelWidth, bottom, PANEL_BORDER);
        graphics.fill(left, top, left + 1, bottom, PANEL_BORDER);
        graphics.fill(left + this.panelWidth - 1, top, left + this.panelWidth, bottom, PANEL_BORDER);

        graphics.drawCenteredString(this.font,
                Component.translatable("screen.craftplanner.ambiguous.header", this.rows.size()).getString(),
                this.width / 2, top + 6, TEXT);
        super.render(graphics, mouseX, mouseY, partialTick);

        // Icons after the widgets: the button would paint over anything drawn
        // earlier in its row.
        for (int i = 0; i < visibleRows(); i++) {
            RecipeNode node = this.rows.get(this.scroll + i);
            graphics.renderItem(node.displayStack(), left + PAD + 2, top + HEADER_H + i * ROW_H + 2);
        }
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
