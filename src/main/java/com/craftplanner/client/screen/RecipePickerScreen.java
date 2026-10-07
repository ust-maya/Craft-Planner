package com.craftplanner.client.screen;

import com.craftplanner.client.MachineNames;
import com.craftplanner.client.PlannerState;
import com.craftplanner.client.RecipeLabel;
import com.craftplanner.client.UiPalette;
import com.craftplanner.client.UiShapes;
import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.RecipeNode;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
 * <p>Rows are drawn by this screen rather than by widgets, and through a scaled
 * pose. Minecraft's font is a 9 px bitmap with no smaller variant, so scaling is
 * the only way to bring the text down — the same mechanism the HUD panel uses to
 * keep a machine pack's tree on one screen. Because the rows are no longer
 * widgets, their hit boxes are computed here too, in screen pixels, from the same
 * geometry the drawing uses.</p>
 *
 * <p>Everything is resolved from the graph again on every change: the engine
 * re-expands the tree in place and recreates its child nodes, so holding on to a
 * {@code RecipeNode} across a rebuild would show a stale option list. A recipe
 * switch is applied after the {@code selectRecipe} debounce, which is what
 * {@link #tick()} is for.</p>
 */
public final class RecipePickerScreen extends Screen {

    /**
     * Scale of the whole panel content: text and icons shrink together, so a row
     * stays a picture of a machine with a line of text next to it.
     */
    private static final float SCALE = 0.8f;
    /**
     * A row in the scaled pose's own units — tall enough for one 16 px icon. The
     * layout is written in these and only converted to screen pixels where fills
     * and hit boxes are drawn.
     */
    private static final int ROW_L = 17;
    private static final int ROW_H = Math.round(ROW_L * SCALE);
    /** Band above the rows that holds the title. */
    private static final int HEADER_H = 14;
    private static final int PAD = 5;
    /** Air the panel keeps from the screen edges, so nothing sits under the cursor bar. */
    private static final int MARGIN = 8;
    /**
     * Column the mark of the row in use is drawn in. Wide enough for a ✔ of the
     * vanilla bitmap, which is a full-width glyph, plus a pixel of air before the
     * icon starts.
     */
    private static final int MARK_L = 12;
    private static final int ICON_L = 16;
    private static final int GAP_L = 3;
    /** Air kept between the cost and the machine that runs it. */
    private static final int TEXT_GAP_L = 6;
    /** A machine name narrower than this says less than the icon next to it. */
    private static final int MIN_TAIL_L = 20;
    /** Rows that are always there under the options: the cut switch and close. */
    private static final int CONTROL_ROWS = 2;
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
    /** The same options as drawn rows, measured in the pose's own units. */
    private List<Option> rows = List.of();
    private int selected = -1;
    private int scroll;
    /** Whether the first layout already put the row in use on screen. */
    private boolean centered;
    private int panelWidth;
    private long seenGraphVersion;

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
        this.selected = Math.min(node.selectedRecipeIndex(), this.options.size() - 1);
        List<Option> built = new ArrayList<>(this.options.size());
        for (RecipeHolder<?> option : this.options) {
            List<Component> lines = RecipeLabel.describe(option, registries);
            built.add(new Option(costOf(lines), RecipeLabel.of(option).getString(),
                    machineIcon(option), join(lines)));
        }
        this.rows = built;
        this.panelWidth = measure();
        if (this.centered) {
            this.scroll = Math.min(this.scroll, maxScroll());
        } else {
            // The first layout opens on the row in use, not on the top of the ranking:
            // on a dozen alternatives for one plate the current recipe is the thing the
            // player came to look at. Later rebuilds keep wherever they scrolled to.
            this.centered = true;
            this.scroll = Math.max(0, Math.min(this.selected - visibleOptions() / 2, maxScroll()));
        }
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
     * Width the panel needs: the worst row, measured in the pose's units and scaled
     * down to the screen. The control rows are measured too, so the cut switch can
     * never be the row the panel is too narrow for.
     */
    private int measure() {
        int widest = this.font.width(this.title);
        for (Option row : this.rows) {
            widest = Math.max(widest, MARK_L + ICON_L + GAP_L + this.font.width(row.cost())
                    + TEXT_GAP_L + this.font.width("· " + row.machine()));
        }
        widest = Math.max(widest, MARK_L + this.font.width(cutLabel()));
        widest = Math.max(widest, MARK_L + this.font.width(tr("screen.craftplanner.list.close")));
        // Two layout pixels of slack: the width is rounded to a whole screen pixel
        // and the row reads it back by dividing, so the worst row would otherwise
        // lose its last character to the rounding.
        return Math.min(this.width - 2 * MARGIN,
                Math.max(200, Math.round((widest + 2) * SCALE) + 2 * PAD));
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
     * Option rows the screen can show at once. Everything else the panel carries is
     * subtracted first — the title band, the two rows under the list and the padding
     * below them — so a window taller than the screen never happens above a certain
     * height, and the last row never falls off the bottom.
     */
    private int visibleOptions() {
        int available = this.height - 2 * MARGIN - HEADER_H - CONTROL_ROWS * ROW_H - PAD;
        return Math.max(1, Math.min(this.options.size(), available / ROW_H));
    }

    private int maxScroll() {
        return Math.max(0, this.options.size() - visibleOptions());
    }

    private int panelHeight() {
        return HEADER_H + (visibleOptions() + CONTROL_ROWS) * ROW_H + PAD;
    }

    private int panelLeft() {
        return (this.width - this.panelWidth) / 2;
    }

    private int top() {
        return Math.max(MARGIN, (this.height - panelHeight()) / 2);
    }

    /**
     * Row under a point: the visible options first, then the cut switch and the
     * close button, or {@code -1} outside the panel's rows. Screen pixels on both
     * sides — the scaled drawing is what moves, the clicks are not. The right edge
     * is exclusive because that is where the row's highlight stops painting.
     */
    private int hitRow(int mx, int my) {
        int left = panelLeft();
        if (mx < left + PAD || mx >= left + this.panelWidth - PAD) {
            return -1;
        }
        int offset = my - (top() + HEADER_H);
        int drawn = visibleOptions() + CONTROL_ROWS;
        if (offset < 0 || offset >= drawn * ROW_H) {
            return -1;
        }
        return offset / ROW_H;
    }

    /** The tooltip of a row, or {@code null} for the close button, which says all it does. */
    private Component tooltipAt(int row) {
        int shown = visibleOptions();
        if (row < shown) {
            return this.rows.get(this.scroll + row).tooltip();
        }
        if (row == shown) {
            return Component.translatable(PlannerState.isCut(this.item)
                    ? "gui.craftplanner.picker.cut.on.tooltip"
                    : "gui.craftplanner.picker.cut.tooltip");
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        int row = hitRow((int) mouseX, (int) mouseY);
        if (row < 0) {
            return false;
        }
        int shown = visibleOptions();
        if (row < shown) {
            choose(this.options.get(this.scroll + row));
        } else if (row == shown) {
            PlannerState.toggleCut(this.item);
            this.seenGraphVersion = PlannerState.graphVersion();
        } else {
            this.onClose();
        }
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

        int hover = hitRow(mouseX, mouseY);
        Component tooltip = hover >= 0 ? tooltipAt(hover) : null;
        if (tooltip != null) {
            // Queued here and painted by vanilla once the pass is over
            // ({@code Screen.renderWithTooltip}), which puts it above every row this
            // method has just drawn. It is consumed as it is painted, so a frame with
            // no hovered row needs no clearing.
            setTooltipForNextRenderPass(tooltip);
        }

        int shown = visibleOptions();
        int y = top + HEADER_H;
        for (int row = 0; row < shown; row++) {
            int index = this.scroll + row;
            Option option = this.rows.get(index);
            boolean used = index == this.selected;
            drawRow(graphics, option.icon(), option.cost(), "· " + option.machine(),
                    used ? "✔" : "", true, used, row == hover, left, y);
            y += ROW_H;
        }
        boolean cut = PlannerState.isCut(this.item);
        // The cut switch wears the "in use" plate while it is ON: the amber
        // state must read as a decision, not as a hovered row.
        drawRow(graphics, ItemStack.EMPTY, cutLabel(), "", cut ? "✔" : "", false,
                cut, shown == hover, left, y);
        y += ROW_H;
        drawRow(graphics, ItemStack.EMPTY, tr("screen.craftplanner.list.close"), "", "", false,
                false, shown + 1 == hover, left, y);

        // A dozen machine alternatives scroll, and a wheel that finds nothing to show
        // for it reads as a broken list — so the band of option rows carries the same
        // thumb the plan panel draws.
        int hidden = maxScroll();
        if (hidden > 0) {
            int trackTop = top + HEADER_H;
            int trackH = shown * ROW_H;
            int barH = Math.max(6, trackH * shown / this.options.size());
            int barY = trackTop + (trackH - barH) * this.scroll / hidden;
            int barX = left + this.panelWidth - 4;
            graphics.fill(barX, trackTop, barX + 3, trackTop + trackH, UiPalette.SCROLL_TRACK);
            graphics.fill(barX, barY, barX + 3, barY + barH, SCROLL);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawTitle(GuiGraphics graphics, int left, int top) {
        int width = Math.round(this.font.width(this.title) * SCALE);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(left + (this.panelWidth - width) / 2f, top + 2, 0);
        pose.scale(SCALE, SCALE, 1f);
        graphics.drawString(this.font, this.title, 0, 0, TEXT, false);
        pose.popPose();
    }

    /**
     * One row: its highlight in screen pixels, then mark, icon and text through the
     * scaled pose. The mark column and the icon gutter keep their room even when
     * empty, so the text of every row starts at the same x — a machine the block
     * lookup does not know leaves the gutter blank rather than shifting its line
     * left out of alignment with the rows above it.
     */
    private void drawRow(GuiGraphics graphics, ItemStack icon, String primary, String secondary,
            String mark, boolean gutter, boolean used, boolean hovered, int left, int y) {
        int inner = this.panelWidth - 2 * PAD;
        if (hovered || used) {
            graphics.fill(left + PAD, y, left + PAD + inner, y + ROW_H,
                    used ? ROW_SELECTED : ROW_HOVER);
        }

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(left + PAD, y, 0);
        pose.scale(SCALE, SCALE, 1f);
        int textX = MARK_L + (gutter ? ICON_L + GAP_L : 0);
        int textY = (ROW_L - this.font.lineHeight) / 2;
        if (!mark.isEmpty()) {
            graphics.drawString(this.font, mark, 0, textY, TEXT, false);
        }
        if (gutter && !icon.isEmpty()) {
            graphics.renderItem(icon, MARK_L, (ROW_L - ICON_L) / 2);
        }

        // The cost has the first claim on the row: two options are compared by what
        // they consume. The machine's name is what gives way, because its icon is
        // already standing there — unless the lookup found no block, when the name
        // is the only thing saying which machine this is, so it is kept and drawn as
        // primary text. A tail too narrow to read is dropped whole rather than shown
        // as "· …", which carries no more information than the icon.
        int room = (int) (inner / SCALE) - textX;
        String head = primary;
        String tail = secondary;
        int tailRoom = room - Math.min(this.font.width(head), room) - TEXT_GAP_L;
        if (!tail.isEmpty() && this.font.width(tail) > tailRoom) {
            if (icon.isEmpty() || tailRoom >= MIN_TAIL_L) {
                tail = RecipeListLayout.trimToWidth(this.font::width, tail,
                        Math.max(0, tailRoom));
            } else {
                tail = "";
            }
        }
        int headRoom = tail.isEmpty() ? room
                : room - this.font.width(tail) - TEXT_GAP_L;
        if (this.font.width(head) > headRoom) {
            head = RecipeListLayout.trimToWidth(this.font::width, head, Math.max(0, headRoom));
        }
        graphics.drawString(this.font, head, textX, textY, TEXT, false);
        if (!tail.isEmpty()) {
            graphics.drawString(this.font, tail, textX + this.font.width(head) + TEXT_GAP_L,
                    textY, icon.isEmpty() ? TEXT : MUTED, false);
        }
        pose.popPose();
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
