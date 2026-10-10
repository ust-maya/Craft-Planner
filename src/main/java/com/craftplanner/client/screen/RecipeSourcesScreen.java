package com.craftplanner.client.screen;

import com.craftplanner.client.MachineNames;
import com.craftplanner.client.PlannerState;
import com.craftplanner.client.Scrollbar;
import com.craftplanner.client.UiPalette;
import com.craftplanner.client.UiShapes;
import com.craftplanner.client.overlay.NodeColors;
import com.craftplanner.client.tree.TreeMetrics;
import com.craftplanner.engine.RecipeIndex;
import com.craftplanner.engine.SourceFilter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import org.lwjgl.glfw.GLFW;

/**
 * Which mods and machines the plan may use, opened from the recipe list.
 * The choice is global, not per item, and the list opens even when every
 * machine is switched off, so the way back is always at hand.
 *
 * <p>One row per mod, and under it one row per machine the mod adds — its recipe
 * types, with the block that runs them and how many indexed recipes they hold.
 * A tick says the row is in use; clicking the row switches it. A mod switched
 * off takes its machines and every recipe it wrote with it (see
 * {@link SourceFilter}), so its machine rows stay listed, dimmed: switching the
 * mod back on gives back exactly what was there.</p>
 *
 * <p>A switch reaches the plan after the same debounce as a recipe choice: a
 * machine switched off is no longer picked by the tree nor offered in the recipe
 * window, and an item whose every recipe is switched off becomes material to
 * gather.</p>
 *
 * <p>Mods open folded: only their own rows show, and a {@code +} plate next to a
 * mod's name opens its machines underneath. Which mods the player opened is kept
 * for the game session, so coming back to the window leaves the mod they were
 * working in exactly as they left it.</p>
 */
public final class RecipeSourcesScreen extends Screen {

    /** A row: a 16 px icon plus a pixel of air above and below. */
    private static final int ROW_H = 18;
    private static final int HEADER_H = 26;
    private static final int PAD = 6;
    private static final int MARGIN = 8;
    /** Edge of the tick box. */
    private static final int BOX = 10;
    private static final int ICON = 16;
    private static final int GAP = 4;
    /** How far a machine steps in under its mod. */
    private static final int INDENT = 14;
    /** Air left for the scrollbar at the right edge. */
    private static final int BAR_ROOM = 6;
    /** The fold plate of a mod row, the tree's own size so both open the same way. */
    private static final int TOGGLE_W = TreeMetrics.TOGGLE_W;
    /** Column every row keeps for the plate, so the tick boxes line up under one another. */
    private static final int FOLD_W = TOGGLE_W + GAP;

    /**
     * Mods whose machines the player opened, kept for the game session: the window
     * is rebuilt every time it is opened, and a mod the player was working in should
     * still be open when they come back from the list. Empty at start, so every mod
     * opens folded — a modpack's hundreds of machines are one click away, not one
     * long scroll.
     */
    private static final Set<String> OPENED_MODS = new HashSet<>();

    private final Screen returnTo;
    private List<Row> rows = List.of();
    private List<Row> shown = List.of();
    private int panelWidth;
    private int scroll;
    private Button resetButton;
    private Button closeButton;

    /** A mod ({@code type == null}) or one of its machines, with what the row shows. */
    private record Row(String mod, String type, String label, ItemStack icon, int count,
            List<String> machines) {

        boolean isMod() {
            return type == null;
        }
    }

    public RecipeSourcesScreen(Screen returnTo) {
        super(Component.translatable("screen.craftplanner.sources.title"));
        this.returnTo = returnTo;
    }

    @Override
    protected void init() {
        this.rows = collectRows();
        this.panelWidth = measure();
        refold();
        clearWidgets();
        Component reset = Component.translatable("screen.craftplanner.sources.reset");
        this.resetButton = addRenderableWidget(FitButton.create(0, 0, buttonW(reset),
                RecipeListLayout.BUTTON_H, reset, button -> PlannerState.setSources(SourceFilter.NONE),
                Component.translatable("screen.craftplanner.sources.reset.tooltip")));
        Component close = Component.translatable("screen.craftplanner.list.close");
        this.closeButton = addRenderableWidget(FitButton.create(0, 0, buttonW(close),
                RecipeListLayout.BUTTON_H, close, button -> onClose(), null));
    }

    /**
     * Every mod that owns a machine or wrote a recipe, each followed by its
     * machines, both in name order. A mod's count is what switching it off would
     * take away: the recipes it wrote plus the ones its machines run.
     */
    private List<Row> collectRows() {
        if (this.minecraft.level == null) {
            return List.of();
        }
        RecipeIndex index = RecipeIndex.cachedFor(this.minecraft.level.getRecipeManager(),
                this.minecraft.level.registryAccess());
        Map<String, Integer> byMod = new HashMap<>();
        Map<ResourceLocation, Integer> byType = new HashMap<>();
        for (RecipeHolder<?> holder : index.recipes()) {
            String writer = holder.id().getNamespace();
            byMod.merge(writer, 1, Integer::sum);
            ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
            if (type != null) {
                byType.merge(type, 1, Integer::sum);
                if (!type.getNamespace().equals(writer)) {
                    byMod.merge(type.getNamespace(), 1, Integer::sum);
                }
            }
        }
        List<String> mods = new ArrayList<>(byMod.keySet());
        mods.sort(Comparator.comparing(RecipeSourcesScreen::modName, String.CASE_INSENSITIVE_ORDER));
        List<Row> built = new ArrayList<>();
        for (String mod : mods) {
            List<ResourceLocation> types = new ArrayList<>();
            for (ResourceLocation type : byType.keySet()) {
                if (type.getNamespace().equals(mod)) {
                    types.add(type);
                }
            }
            types.sort(Comparator.comparing(RecipeSourcesScreen::typeName, String.CASE_INSENSITIVE_ORDER));
            List<String> machineIds = new ArrayList<>(types.size());
            for (ResourceLocation type : types) {
                machineIds.add(type.toString());
            }
            built.add(new Row(mod, null, modName(mod), ItemStack.EMPTY, byMod.get(mod),
                    List.copyOf(machineIds)));
            for (ResourceLocation type : types) {
                Block block = MachineNames.blockOf(type);
                built.add(new Row(mod, type.toString(), typeName(type),
                        block == null ? ItemStack.EMPTY : new ItemStack(block.asItem()), byType.get(type),
                        List.of()));
            }
        }
        return built;
    }

    /** The mod's own name, as its metadata gives it; the namespace when no mod claims it. */
    private static String modName(String mod) {
        return ModList.get().getModContainerById(mod)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(mod);
    }

    /** The machine's name in the player's language, or the type's path when no block names it. */
    private static String typeName(ResourceLocation type) {
        String name = MachineNames.of(type);
        return name != null ? name : type.getPath();
    }

    private int buttonW(Component label) {
        return Math.max(48, this.font.width(label) + 2 * PAD);
    }

    private String countOf(Row row) {
        return Component.translatable("screen.craftplanner.sources.count", row.count()).getString();
    }

    private static int indentOf(Row row) {
        return row.isMod() ? FOLD_W : FOLD_W + INDENT;
    }

    private int measure() {
        int widest = Math.max(this.font.width(this.title),
                this.font.width(Component.translatable("screen.craftplanner.sources.header")));
        for (Row row : this.rows) {
            widest = Math.max(widest, indentOf(row) + BOX + GAP + ICON + GAP
                    + this.font.width(row.label()) + 2 * GAP + this.font.width(countOf(row)) + BAR_ROOM);
        }
        widest = Math.max(widest, buttonW(Component.translatable("screen.craftplanner.sources.reset"))
                + GAP + buttonW(Component.translatable("screen.craftplanner.list.close")));
        return Math.min(this.width - 2 * MARGIN, Math.max(220, widest + 2 * PAD));
    }

    /** Recomputes the rows on screen after a fold, keeping the scroll inside the shorter list. */
    private void refold() {
        this.shown = SourceRows.visible(this.rows, Row::isMod, Row::mod, OPENED_MODS);
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));
    }

    private int visibleLines() {
        return linesFor(this.shown.size());
    }

    /** Lines a list of {@code count} rows gets on this screen. */
    private int linesFor(int count) {
        int available = this.height - 2 * MARGIN - HEADER_H - ROW_H - PAD;
        return Math.max(1, Math.min(Math.max(1, count), available / ROW_H));
    }

    private int maxScroll() {
        return Math.max(0, this.shown.size() - visibleLines());
    }

    private int panelHeight() {
        return HEADER_H + visibleLines() * ROW_H + ROW_H + PAD;
    }

    private int panelLeft() {
        return (this.width - this.panelWidth) / 2;
    }

    private int top() {
        // Placed for the list with every mod opened, so opening a mod grows the panel
        // downward and the row just clicked stays under the cursor.
        int full = HEADER_H + linesFor(this.rows.size()) * ROW_H + ROW_H + PAD;
        return Math.max(MARGIN, (this.height - full) / 2);
    }

    private int bandTop() {
        return top() + HEADER_H;
    }

    private int lineTop(int index) {
        return bandTop() + (index - this.scroll) * ROW_H;
    }

    /** Row under a point, or {@code -1} outside the band. */
    private int hitLine(int mx, int my) {
        int left = panelLeft();
        if (mx < left + PAD || mx >= left + this.panelWidth - PAD) {
            return -1;
        }
        int offset = my - bandTop();
        if (offset < 0 || offset >= visibleLines() * ROW_H) {
            return -1;
        }
        int index = this.scroll + offset / ROW_H;
        return index < this.shown.size() ? index : -1;
    }

    /** Whether a point is on the fold plate of a mod row; a mod without machines has none. */
    private boolean overPlate(int mx, Row row) {
        return row.isMod() && !row.machines().isEmpty()
                && mx >= panelLeft() + PAD && mx < panelLeft() + PAD + TOGGLE_W + GAP / 2;
    }

    private void toggleFold(String mod) {
        if (!OPENED_MODS.remove(mod)) {
            OPENED_MODS.add(mod);
        }
        refold();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        int index = hitLine((int) mouseX, (int) mouseY);
        if (index < 0) {
            return false;
        }
        Row row = this.shown.get(index);
        // The plate folds the mod; the rest of the row still switches it, as before.
        if (overPlate((int) mouseX, row)) {
            toggleFold(row.mod());
            return true;
        }
        SourceFilter filter = PlannerState.sources();
        PlannerState.setSources(row.isMod()
                ? filter.withMod(row.mod(), !filter.modHidden(row.mod()))
                : filter.withType(row.type(), !filter.typeHidden(row.type())));
        return true;
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
        // Empty on purpose, like the pickers: render() paints its own dim.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = panelLeft();
        int top = top();
        graphics.fill(0, 0, this.width, this.height, UiPalette.MODAL_SCRIM);
        UiShapes.panel(graphics, left, top, this.panelWidth, panelHeight(), UiPalette.MODAL_PANEL_BG,
                UiPalette.MODAL_BORDER_OUTER, UiPalette.MODAL_PANEL_BORDER);
        UiShapes.headerBand(graphics, left, top, this.panelWidth, 12, UiPalette.MODAL_HEADER_BG,
                UiPalette.ACCENT_LINE, PAD);
        graphics.drawCenteredString(this.font, this.title, left + this.panelWidth / 2, top + 3, UiPalette.TEXT);
        graphics.drawString(this.font, RecipeListLayout.trimToWidth(this.font::width,
                Component.translatable("screen.craftplanner.sources.header").getString(),
                this.panelWidth - 2 * PAD), left + PAD, top + 15, UiPalette.MUTED, false);

        int lines = visibleLines();
        int hover = hitLine(mouseX, mouseY);
        if (this.rows.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("screen.craftplanner.sources.empty"),
                    left + PAD, bandTop() + (ROW_H - this.font.lineHeight) / 2, UiPalette.MUTED, false);
        }
        SourceFilter filter = PlannerState.sources();
        for (int i = 0; i < lines; i++) {
            int index = this.scroll + i;
            if (index >= this.shown.size()) {
                break;
            }
            Row row = this.shown.get(index);
            drawRow(graphics, row, lineTop(index), index == hover, left, filter,
                    index == hover && overPlate(mouseX, row));
        }

        int controlY = bandTop() + lines * ROW_H + (ROW_H - RecipeListLayout.BUTTON_H) / 2;
        this.resetButton.setX(left + PAD);
        this.resetButton.setY(controlY);
        this.closeButton.setX(left + this.panelWidth - PAD - this.closeButton.getWidth());
        this.closeButton.setY(controlY);

        if (maxScroll() > 0) {
            Scrollbar.draw(graphics, Scrollbar.of(left + this.panelWidth - 4, bandTop(), lines * ROW_H,
                    lines, this.shown.size(), this.scroll, maxScroll(), 8));
        }
        if (hover >= 0) {
            Row row = this.shown.get(hover);
            if (overPlate(mouseX, row)) {
                setTooltipForNextRenderPass(OPENED_MODS.contains(row.mod())
                        ? Component.translatable("screen.craftplanner.sources.fold.collapse")
                        : Component.translatable("screen.craftplanner.sources.fold.expand",
                                row.machines().size()));
            } else {
                setTooltipForNextRenderPass(row.isMod()
                        ? Component.translatable("screen.craftplanner.sources.mod.tooltip", row.label())
                        : Component.translatable("screen.craftplanner.sources.type.tooltip", row.label()));
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** One row: tick box, icon, name and count; a mod adds the plate that folds its machines. */
    private void drawRow(GuiGraphics graphics, Row row, int y, boolean hovered, int left,
            SourceFilter filter, boolean plateAimed) {
        boolean on = row.isMod() ? !filter.modHidden(row.mod()) : !filter.typeHidden(row.type());
        boolean dimmed = !row.isMod() && filter.modHidden(row.mod());
        int right = left + this.panelWidth - PAD;
        if (row.isMod()) {
            graphics.fill(left + PAD, y, right, y + ROW_H, UiPalette.TREE_ROOT_BAND);
        }
        if (hovered) {
            graphics.fill(left + PAD, y, right, y + ROW_H, UiPalette.MODAL_ROW_HOVER);
        }
        if (row.isMod() && !row.machines().isEmpty()) {
            int plateH = ROW_H - 4;
            UiShapes.foldButton(graphics, left + PAD, y + (ROW_H - plateH) / 2, TOGGLE_W, plateH,
                    !OPENED_MODS.contains(row.mod()), plateAimed);
        }
        int x = left + PAD + indentOf(row);
        int boxY = y + (ROW_H - BOX) / 2;
        UiShapes.chip(graphics, x, boxY, BOX, BOX, UiPalette.CHIP_BG, UiPalette.CHIP_BORDER);
        if (on) {
            graphics.drawString(this.font, "✔", x + 2, boxY + 1, dimmed ? UiPalette.MUTED : NodeColors.HAVE, false);
        }
        x += BOX + GAP;
        if (!row.icon().isEmpty()) {
            graphics.renderItem(row.icon(), x, y + (ROW_H - ICON) / 2);
        }
        x += ICON + GAP;
        int textY = y + (ROW_H - this.font.lineHeight) / 2;
        String count = countOf(row);
        int countX = right - BAR_ROOM - this.font.width(count);
        graphics.drawString(this.font, RecipeListLayout.trimToWidth(this.font::width, row.label(),
                Math.max(0, countX - GAP - x)), x, textY, on && !dimmed ? UiPalette.TEXT : UiPalette.MUTED, false);
        graphics.drawString(this.font, count, countX, textY, UiPalette.MUTED, false);
    }

    /** Back to the recipe list it was opened from. */
    @Override
    public void onClose() {
        this.minecraft.setScreen(this.returnTo);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
