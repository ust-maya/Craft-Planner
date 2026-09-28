package com.craftplanner.client.screen;

import com.craftplanner.client.PlannerState;
import com.craftplanner.client.RecipeLabel;
import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.RecipeNode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * The screen that answers "this item can be made several ways" (badge {@code 2/3}
 * in the tree, recipe button in the {@code K} window).
 *
 * <p>It lists every recipe that produces one item, names each by machine and mod
 * and by what it consumes, marks the one in use and lets the player pick another
 * or hand the choice back to the engine. The list screen's cycling button could
 * only ever say "1/3, 2/3, 3/3" — which is no help when the options differ by
 * more than an index — so the choice is made here, where both the label and the
 * cost of each alternative are on screen.</p>
 *
 * <p>Everything is resolved from the graph again on every change: the engine
 * re-expands the tree in place and recreates its child nodes, so holding on to a
 * {@code RecipeNode} across a rebuild would show a stale option list. A recipe
 * switch is applied after the {@code selectRecipe} debounce, which is what
 * {@link #tick()} is for.</p>
 */
public final class RecipePickerScreen extends Screen {

    /** Height of one option row; the same rhythm as the list window. */
    private static final int ROW_H = 18;
    private static final int HEADER_H = 22;
    private static final int PAD = 6;
    private static final int PANEL_BG = 0xF0101010;
    private static final int PANEL_BORDER = 0xFF404040;
    private static final int DIM = 0xB0000000;
    private static final int TEXT = 0xFFE0E0E0;

    /** The item whose recipes are offered; also the key of the choice. */
    private final ItemKey item;
    /** Screen to go back to, so opening the picker never loses the player's place. */
    private final Screen returnTo;

    /** Registry access the option descriptions are resolved against. */
    private HolderLookup.Provider registries;

    private List<RecipeHolder<?>> options = List.of();
    /** One row's text, measured by {@link #measure()} and shown on its button. */
    private List<String> labels = List.of();
    /** The same options in full — name, yield, cost — as row tooltips. */
    private List<List<Component>> descriptions = List.of();
    private int selected = -1;
    private int scroll;
    private int panelWidth;
    private long seenGraphVersion;

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
        this.registries = this.minecraft.level.registryAccess();
        this.options = node.options();
        this.selected = Math.min(node.selectedRecipeIndex(), this.options.size() - 1);
        this.descriptions = new ArrayList<>(this.options.size());
        this.labels = new ArrayList<>(this.options.size());
        for (RecipeHolder<?> holder : this.options) {
            List<Component> lines = RecipeLabel.describe(holder, this.registries);
            this.descriptions.add(lines);
            this.labels.add(rowLabel(lines));
        }
        this.panelWidth = measure();
        this.scroll = Math.min(this.scroll, maxScroll());
        buildWidgets();
    }

    @Override
    public void tick() {
        if (PlannerState.graphVersion() != this.seenGraphVersion) {
            init();
        }
    }

    /**
     * The row text: the name of the way and what it consumes — {@code "Compressor
     * · Modern Industrialization — Needs: Iron Dust ×2"}. The full description,
     * including what the recipe yields, goes into the row's tooltip: yield matters
     * for the choice (two recipes for one plate can produce four at a time), but
     * it is the second thing a player compares, after the cost.
     */
    private static String rowLabel(List<Component> lines) {
        String name = lines.isEmpty() ? "" : lines.get(0).getString();
        String cost = lines.size() < 2 ? "" : lines.get(lines.size() - 1).getString();
        return cost.isEmpty() ? name : name + " — " + cost;
    }

    private int measure() {
        int widest = this.font.width(this.title);
        for (String label : this.labels) {
            widest = Math.max(widest, this.font.width(label) + 16);
        }
        widest = Math.max(widest, this.font.width(Component.translatable("gui.craftplanner.picker.auto")) + 16);
        return Math.min(this.width - 16, Math.max(220, widest + 2 * PAD));
    }

    private void buildWidgets() {
        clearWidgets();
        int left = (this.width - this.panelWidth) / 2;
        int inner = this.panelWidth - 2 * PAD;
        int y = top() + HEADER_H;

        for (int i = 0; i < visibleOptions(); i++) {
            int index = this.scroll + i;
            String label = (index == this.selected ? "✔ " : "   ") + this.labels.get(index);
            addRenderableWidget(FitButton.create(left + PAD, y, inner, ROW_H - 2,
                    Component.literal(label),
                    button -> choose(this.options.get(index)),
                    tooltip(index)));
            y += ROW_H;
        }

        if (PlannerState.preferences().containsKey(this.item)) {
            // Only shown while the player has actually overridden the engine, so
            // the row below never promises a reset to something that is not set.
            addRenderableWidget(FitButton.create(left + PAD, y, inner, ROW_H - 2,
                    Component.translatable("gui.craftplanner.picker.auto"),
                    button -> choose(null),
                    Component.translatable("gui.craftplanner.picker.auto.tooltip")));
            y += ROW_H;
        }
        addRenderableWidget(FitButton.create(left + PAD, y, inner, ROW_H - 2,
                // Same word the list window uses for the same action — no third
                // translation of "Close" to keep in sync.
                Component.translatable("screen.craftplanner.list.close"),
                button -> this.onClose(), null));
    }

    /**
     * The full description of one option as its tooltip: vanilla draws the lines
     * of a single {@code Component} separated by newlines, so no list overload is
     * needed.
     */
    private Component tooltip(int index) {
        List<Component> lines = this.descriptions.get(index);
        MutableComponent joined = lines.get(0).copy();
        for (int i = 1; i < lines.size(); i++) {
            joined.append(Component.literal("\n")).append(lines.get(i).copy());
        }
        return joined;
    }

    private void choose(RecipeHolder<?> recipe) {
        PlannerState.selectRecipe(this.item, recipe);
        // The rebuild is debounced; tick() repaints the rows once it has landed.
        this.seenGraphVersion = PlannerState.graphVersion();
    }

    /** The two rows that are always there: the reset and the close button. */
    private int fixedRows() {
        return PlannerState.preferences().containsKey(this.item) ? 2 : 1;
    }

    /**
     * Option rows that fit on the screen. The panel is centred, so with more
     * alternatives than fit — machine packs give a steel plate a dozen — the list
     * scrolls and the fixed rows stay under the visible ones.
     */
    private int visibleOptions() {
        int available = this.height - 16 - HEADER_H - fixedRows() * ROW_H;
        return Math.max(1, Math.min(this.options.size(), available / ROW_H));
    }

    private int maxScroll() {
        return Math.max(0, this.options.size() - visibleOptions());
    }

    private int panelHeight() {
        return HEADER_H + (visibleOptions() + fixedRows()) * ROW_H + PAD;
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
        buildWidgets();
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Empty on purpose: render() paints its own dim, and the vanilla blurred
        // background would sit on top of the panel it is supposed to be behind.
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

        graphics.drawCenteredString(this.font, this.title, this.width / 2, top + 6, TEXT);
        super.render(graphics, mouseX, mouseY, partialTick);
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
