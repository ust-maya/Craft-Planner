package com.craftplanner.client.overlay;

import com.craftplanner.Config;
import com.craftplanner.Constants;
import com.craftplanner.client.Keybinds;
import com.craftplanner.client.UiLegend;
import com.craftplanner.client.OverlayState;
import com.craftplanner.client.AmountLabel;
import com.craftplanner.client.PlanRows;
import com.craftplanner.client.Scrollbar;
import com.craftplanner.client.PlannerState;
import com.craftplanner.client.TextMetrics;
import com.craftplanner.client.UiPalette;
import com.craftplanner.client.UiShapes;
import com.craftplanner.client.MachineNames;
import com.craftplanner.client.resource.Availability;
import com.craftplanner.client.tree.TreeFlatten;
import com.craftplanner.client.tree.TreeMetrics;
import com.craftplanner.client.tree.TreePanel;
import com.craftplanner.client.tree.TreeRow;
import com.craftplanner.graph.CraftGraph;
import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.RecipeNode;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Draws the craft plan over the game screen (Stage 8).
 *
 * <p>Two layouts are available, picked by {@code overlay.mode} in the client
 * config: {@code "tree"} (default) — the nested list this class is built around,
 * and {@code "graph"} — the classic node-link view, kept unchanged in
 * {@link GraphOverlayRenderer}.</p>
 *
 * <p><b>The overlay is a summary, not the tool.</b> A HUD has no cursor: while
 * no screen is open the game grabs the mouse, pins it to the centre of the
 * window and turns any movement into camera yaw. Everything this panel once
 * tried to offer as interaction — a control rail of fold chips and recipe
 * badges, tooltips on them, a draggable scrollbar — therefore depended on first
 * entering the {@code O} layout editor, and the bitmap font at a fraction of its
 * size made the chips' glyphs two pixels tall. Those controls live in
 * {@link CraftTreeScreen} now, where the player has a cursor and a keyboard.
 * What stays here is what a HUD can honestly do: show the plan at a readable
 * density, mark which branches are folded, and scroll.</p>
 *
 * <p>Readability comes first, so there is exactly one density mechanism. The
 * whole panel — title, rows, footer — is laid out in fixed layout pixels and
 * drawn through a scaled pose, and the density is {@code overlay.scale} clamped
 * to {@link TreeMetrics#MIN_READABLE_SCALE}: six pixel rows per glyph is the
 * floor, and a plan that does not fit is trimmed and clipped with a note pointing
 * at the window, rather than shrunk until nothing reads. The old
 * "shrink to fit the screen" step could reach eight percent, and the
 * node-link view falling back to this one, are both gone.</p>
 *
 * <p>Where the panel lives and how large it is remain the player's: {@code O}
 * opens {@code OverlayLayoutScreen}, a transparent screen that frees the mouse,
 * and the panel is dragged and resized there and committed to
 * {@link OverlayPlacement} and the client config. The wheel keeps scrolling the
 * plan in that mode, as it does here.</p>
 *
 * <p>The rows are not rebuilt per frame any more: {@link PlanRows} caches the
 * flattened plan against {@link PlannerState#graphVersion()} and the fold count,
 * which is also what lets the panel width be measured from the labels once per
 * rebuild instead of being recomputed sixty times a second.</p>
 *
 * <p>This class draws the tree panel and routes the two events it subscribes
 * to. The pieces that grew around it live beside it, moved out unchanged:
 * the shopping-list footer is {@link ShoppingListFooter}, the node-link mode
 * is {@link GraphOverlayRenderer}, and the fold/badge/scrollbar hit map the
 * layout editor clicks through is {@link HudHitMap}.</p>
 */
@EventBusSubscriber(modid = Constants.MODID, value = Dist.CLIENT)
public final class TreeOverlayRenderer {

    private static final int MARGIN = 8;
    private static final int TITLE_H = 12;
    /** Gap between the last tree row and the footer block. */
    private static final int FOOTER_GAP = 3;
    /** Zone at the panel's right edge for the scrollbar, in screen pixels. */
    private static final int SCROLL_ZONE = TreeMetrics.SCROLL_GUTTER;
    private static final int PANEL_BG = UiPalette.HUD_PANEL_BG;
    private static final int PANEL_BORDER = UiPalette.HUD_PANEL_BORDER;

    /** Vertical scroll of the tree layout, in rows (tree mode only). */
    private static int treeScroll;

    static int treeScroll() { return treeScroll; }

    static void setTreeScroll(int value) { treeScroll = value; }

    /**
     * The widest row of the current plan, in layout pixels, cached with the same
     * keys as {@link PlanRows#rows()}: measuring it means building every label
     * and asking the font about it, which used to happen every frame.
     */
    private static int cachedNaturalW = -1;
    private static long cachedNaturalVersion = -1;
    private static long cachedNaturalFoldVersion = -1;
    /** The font the cached measurement was taken with. */
    private static Font cachedNaturalFont;

    /**
     * Edge of the recipe button at the end of a row, in layout pixels.
     *
     * <p>A 16 px icon with a pixel of air around it, which is the smallest square
     * that still reads as a control rather than as a smudge at the label's edge —
     * and it is fixed, so the column does not resize as a count changes.</p>
     */
    private static final int BADGE = 18;

    /**
     * Scrolls the tree by {@code rows} rows; the exact clamp is applied when it is
     * drawn. Exposed for the screens that own the mouse wheel — while one is open
     * the HUD no longer receives it, and the layout editor needs the scroll to see
     * the part of the plan it is sizing.
     */
    public static void scroll(int rows) {
        treeScroll += rows;
    }

    private TreeOverlayRenderer() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        // The scrollbar only exists while it is actually drawn: a track left over
        // from a previous frame would keep answering clicks for a panel that is no
        // longer on screen.
        // Same two reasons for the tree's own markers: a fold toggle or a recipe
        // badge left over from a previous frame would keep answering a click
        // where no panel is.
        HudHitMap.clear();
        // Same reason for the drawn rectangle: a panel left over from a previous
        // frame would keep answering the wheel where no panel is.
        OverlayPlacement.reportDrawn(0, 0, 0, 0);
        if (!OverlayState.isVisible()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        CraftGraph graph = PlannerState.graph();
        if (graph.isEmpty()) {
            return;
        }
        if ("graph".equals(Config.overlayMode())) {
            GraphOverlayRenderer.renderGraph(event, mc, graph);
        } else {
            renderTree(event, mc);
        }
    }

    /**
     * Mouse wheel scrolls the left-side tree while it is on screen and the cursor
     * is over the panel, so it does not steal scrolling elsewhere (chat,
     * inventories). The exact clamp is applied when the tree is drawn.
     *
     * <p>Screens skip this entirely: the layout editor routes the wheel through
     * its own {@code mouseScrolled}, and vanilla fires this event <em>besides</em>
     * it — without the skip every notch would move the plan twice. The modals
     * (picker, list, popup) get the same skip, which also stops their dimmed
     * background from scrolling behind.</p>
     */
    @SubscribeEvent
    public static void onMouseScrolled(InputEvent.MouseScrollingEvent event) {
        if (OverlayPlacement.editing() || !OverlayState.isVisible()
                || Minecraft.getInstance().screen != null) {
            return;
        }
        if (!OverlayPlacement.overPanel((int) event.getMouseX(), (int) event.getMouseY())) {
            return;
        }
        if (!"tree".equals(Config.overlayMode())) {
            return;
        }
        treeScroll += event.getScrollDeltaY() > 0 ? -TreePanel.WHEEL_ROWS : TreePanel.WHEEL_ROWS;
    }

    // ---------------------------------------------------------------------
    // Tree (nested list) layout
    // ---------------------------------------------------------------------

    /**
     * The widest row of the plan, in layout pixels, measured once per rebuild.
     * Labels come from {@link #rowLabel}, so a folded branch counts the rows it
     * hides and the panel does not jump when it is opened. The font instance is
     * part of the key because measuring is the one thing that cannot be cached
     * across a font swap, and a bound {@code font::width} is a new object every
     * frame — comparing those would miss the cache every single time.
     */
    private static int naturalWidth(List<TreeRow<RecipeNode>> rows, Font font) {
        long version = PlannerState.graphVersion();
        long folds = TreeCollapse.version();
        if (cachedNaturalW >= 0 && cachedNaturalVersion == version && cachedNaturalFoldVersion == folds
                && cachedNaturalFont == font) {
            return cachedNaturalW;
        }
        TextMetrics metrics = font::width;
        int widest = 0;
        for (TreeRow<RecipeNode> row : rows) {
            widest = Math.max(widest, TreeMetrics.rowWidth(row.depth(), metrics.width(rowLabel(row))));
        }
        // The recipe-button column is right-aligned across every row, so it is
        // added once to the widest row rather than to each of them.
        widest += badgeLayoutW(rows);
        cachedNaturalW = widest;
        cachedNaturalVersion = version;
        cachedNaturalFoldVersion = folds;
        cachedNaturalFont = font;
        return widest;
    }

    /**
     * Width the recipe-button column needs, in layout pixels; zero when no row of
     * the plan has a recipe to open, which is what keeps the column out of the
     * panel entirely for a plan with nothing to pick.
     *
     * <p>The button is a square of fixed size rather than a measured string, so the
     * column does not jump when a row's count changes from {@code 1/2} to
     * {@code 1/12} — and it is in layout pixels because it is drawn inside the
     * same scaled pose as the label it is subtracted from.</p>
     */
    private static int badgeLayoutW(List<TreeRow<RecipeNode>> rows) {
        for (TreeRow<RecipeNode> row : rows) {
            if (row.badge() != TreeRow.Badge.NONE) {
                return BADGE + TreeMetrics.LABEL_GAP;
            }
        }
        return 0;
    }

    /** The label of a row: what it is, how much of it, and what a fold is hiding. */
    private static String rowLabel(TreeRow<RecipeNode> row) {
        return row.name() + " " + TreeMetrics.markedAmount(row.status(), AmountLabel.of(row.node()))
                + (row.hiddenRows() > 0 ? " (+" + row.hiddenRows() + ")" : "");
    }

    /**
     * Density of the overlay content: {@code overlay.scale} of the config, or the
     * value the layout editor produced, kept inside the readable band.
     */
    static double overlayScale() {
        return TreeMetrics.readableScale(OverlayPlacement.density());
    }

    /** Keeps a pinned panel on screen after a resolution change. */
    static int clampToScreen(int requested, int min, int max) {
        if (requested == OverlayPlacement.AUTO) {
            return min;
        }
        return max < min ? min : Math.max(min, Math.min(max, requested));
    }

    /** Cursor position in GUI-scaled pixels, the space the panel is drawn in. */
    static int mouseX(Minecraft mc) {
        return (int) (mc.mouseHandler.xpos() / mc.getWindow().getGuiScale());
    }

    static int mouseY(Minecraft mc) {
        return (int) (mc.mouseHandler.ypos() / mc.getWindow().getGuiScale());
    }

    static void renderTree(RenderGuiEvent.Post event, Minecraft mc) {
        Font font = mc.font;
        TextMetrics metrics = font::width;
        GuiGraphics graphics = event.getGuiGraphics();
        List<TreeRow<RecipeNode>> rows = PlanRows.rows();
        if (rows.isEmpty()) {
            return;
        }
        Map<ItemKey, Availability> availability = PlannerState.availability();

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        double scale = overlayScale();
        int naturalW = naturalWidth(rows, font);
        int pinX = OverlayPlacement.x();
        int pinY = OverlayPlacement.y();

        // The footer wraps into the panel's own width, which is not known before
        // the panel is planned — so plan without the footer first, measure the
        // shopping list into the room that leaves, then plan again with that
        // height. The width does not depend on the footer, and both passes run the
        // same code, so they cannot disagree — which is why the panel is positioned
        // only after its size is final.
        TreePanel.Request withoutFooter = new TreePanel.Request(
                screenW - 2 * MARGIN, screenH - 2 * MARGIN, scale,
                OverlayPlacement.w(), OverlayPlacement.h(), naturalW, TITLE_H, 0, rows.size(),
                TreePanel.HUD_PAD);
        TreePanel.Plan rough = TreePanel.plan(withoutFooter, treeScroll);

        ShoppingListFooter.Footer footer = ShoppingListFooter.cachedFooter(availability, metrics, rough, mc);
        int footerBlock = footer.height() > 0 ? footer.height() + FOOTER_GAP : 0;
        TreePanel.Plan sized = TreePanel.plan(new TreePanel.Request(
                withoutFooter.availW(), withoutFooter.availH(), withoutFooter.density(),
                withoutFooter.pinnedW(), withoutFooter.pinnedH(), withoutFooter.naturalContentW(),
                withoutFooter.headerH(), footerBlock, withoutFooter.rowCount(),
                withoutFooter.pad()), treeScroll);

        int left = clampToScreen(pinX, MARGIN, screenW - sized.panelW() - MARGIN);
        int top = clampToScreen(pinY, MARGIN, screenH - sized.panelH() - MARGIN);
        TreePanel.Plan panel = sized.at(left, top);
        treeScroll = panel.scroll();

        int pad = Math.max(1, Math.round(TreePanel.HUD_PAD * (float) scale));
        int contentLeft = panel.contentLeft();
        int contentTop = panel.contentTop();
        int contentBottom = panel.contentBottom();
        int contentW = panel.contentW();
        int footerTop = contentBottom + Math.max(1, Math.round(FOOTER_GAP * (float) scale));

        UiShapes.panel(graphics, left, top, panel.panelW(), panel.panelH(), PANEL_BG,
                UiPalette.HUD_BORDER_OUTER, PANEL_BORDER);
        // Header band under the title, closed by the shared accent underline.
        if (contentTop - top >= 5) {
            graphics.fill(left + 2, top + 2, left + panel.panelW() - 2, contentTop - 1,
                    UiPalette.HUD_HEADER_BG);
            graphics.fill(left + 2, contentTop - 1, left + panel.panelW() - 2, contentTop,
                    UiPalette.HUD_ACCENT_LINE);
        }

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(left + pad, top + Math.round(2 * scale), 0);
        pose.scale((float) scale, (float) scale, 1f);
        graphics.drawString(font, Component.translatable("overlay.craftplanner.title"), 0, 0,
                NodeColors.TEXT, false);
        drawTreeLegend(graphics, font, contentW);
        pose.popPose();

        graphics.enableScissor(left + 1, contentTop, left + panel.panelW() - 1, contentBottom);
        pose.pushPose();
        pose.translate(contentLeft, contentTop, 0);
        pose.scale((float) scale, (float) scale, 1f);
        drawTreeBody(graphics, font, metrics, rows, contentW, panel, availability);
        pose.popPose();
        graphics.disableScissor();

        if (footer.height() > 0) {
            UiShapes.divider(graphics, left + 2, left + panel.panelW() - 2,
                    contentBottom + Math.max(1, Math.round(FOOTER_GAP * (float) scale) / 2),
                    UiPalette.HUD_SECTION_DIVIDER);
            graphics.enableScissor(left, footerTop, left + panel.panelW(), top + panel.panelH() - 1);
            pose.pushPose();
            pose.translate(contentLeft, footerTop, 0);
            pose.scale((float) scale, (float) scale, 1f);
            ShoppingListFooter.drawFooter(graphics, font, footer, contentW);
            pose.popPose();
            graphics.disableScissor();
            if (!ShoppingListFooter.showTailTooltip(graphics, font, footer, mc, contentLeft, footerTop, scale)) {
                ShoppingListFooter.showMaterialTooltips(graphics, font, footer, mc, contentLeft, footerTop, scale);
            }
        }

        drawScrollbar(graphics, mc, panel, left, rows.size());

        OverlayPlacement.reportDrawn(left, top, panel.panelW(), panel.panelH());
        drawEditChrome(graphics, mc, font, left, top, panel.panelW(), panel.panelH(), screenW, screenH);
    }

    /**
     * The plan itself, in layout pixels inside the caller's scaled pose: goal bar
     * and fold marks, the guides a branch hangs off, then icons and labels.
     *
     * <p>The hierarchy is drawn as one 3 px bar per goal spanning its whole
     * subtree plus neutral guides per edge — the overlay used to colour a 1 px
     * connector per edge with six cycling hues, which at any density below full
     * size read as confetti rather than as a tree.</p>
     */
    private static void drawTreeBody(GuiGraphics graphics, Font font, TextMetrics metrics,
            List<TreeRow<RecipeNode>> rows, int contentW, TreePanel.Plan panel,
            Map<ItemKey, Availability> availability) {
        int first = panel.scroll();
        int last = Math.min(rows.size() - 1, first + panel.visibleRows() - 1);
        int badgeW = badgeLayoutW(rows);
        int labelW = contentW - badgeW;

        for (int r = first; r <= last; r++) {
            TreeRow<RecipeNode> row = rows.get(r);
            if (row.depth() != 0) {
                continue;
            }
            int end = Math.min(TreeFlatten.subtreeEnd(rows, r), last);
            int x = TreeMetrics.barX(0);
            graphics.fill(x, TreeMetrics.rowTop(r, first), x + TreeMetrics.BAR_W,
                    TreeMetrics.rowTop(end, first) + TreeMetrics.ROW_H,
                    NodeColors.branch(row.branch()));
        }
        // Every parent whose children reach into view draws its run, not only the
        // parents that are themselves on screen: a branch scrolled into its middle
        // would otherwise show elbows hanging off nothing. The run stops at the last
        // drawn row instead of running on into the empty band below it.
        int runBottom = TreeMetrics.rowTop(last + 1, first);
        for (int r = 0; r <= last; r++) {
            TreeRow<RecipeNode> parent = rows.get(r);
            if (!parent.expands(r) || parent.lastChildIndex() < first) {
                continue;
            }
            int x = TreeMetrics.spineX(parent.depth());
            int fromY = Math.max(TreeMetrics.rowTop(first, first), TreeMetrics.iconBottom(r, first));
            int toY = Math.min(runBottom, TreeMetrics.rowCenter(parent.lastChildIndex(), first));
            if (toY > fromY) {
                graphics.vLine(x, fromY, toY, UiPalette.TREE_GUIDE);
            }
        }
        for (int r = first; r <= last; r++) {
            TreeRow<RecipeNode> row = rows.get(r);
            if (row.depth() == 0) {
                continue;
            }
            graphics.hLine(TreeMetrics.spineX(row.depth() - 1),
                    TreeMetrics.iconX(row.depth()) - 1,
                    TreeMetrics.rowCenter(r, first), UiPalette.TREE_GUIDE);
        }

        for (int r = first; r <= last; r++) {
            TreeRow<RecipeNode> row = rows.get(r);
            if (!row.foldable()) {
                continue;
            }
            // A state mark in the HUD — the HUD has no cursor — and a button in the
            // layout editor, which is the one place these are reachable. Either way
            // the hit box is the whole row height, so a click does not have to
            // land on the few pixels of the mark itself.
            TreeMetrics.Rect plate = TreeMetrics.toggleRect(row.depth(), r, first);
            UiShapes.foldButton(graphics, plate.x(), plate.y(), plate.w(), plate.h(),
                    row.folded(), false);
            HudHitMap.add(new HudHitMap.Hotspot(panel.contentLeft(), panel.contentTop(), panel.scale(),
                    row.path(), null, row.depth(), r, null));
        }

        for (int r = first; r <= last; r++) {
            TreeRow<RecipeNode> row = rows.get(r);
            int depth = row.depth();
            graphics.renderItem(row.node().displayStack(), TreeMetrics.iconX(depth),
                    TreeMetrics.iconY(r, first));
            String label = metrics.trim(rowLabel(row),
                    TreeMetrics.labelRoom(labelW, depth));
            graphics.drawString(font, label, TreeMetrics.labelX(depth),
                    TreeMetrics.textY(r, first, font.lineHeight), NodeColors.forRow(row), false);
            drawBadge(graphics, font, row, r, first, labelW, badgeW, panel);
        }
    }

    /**
     * The recipe button at the right end of a row's label, and the hit box that
     * opens the picker for it.
     *
     * <p>The same button the tree window draws, which is also why it survives the
     * density: the panel is never drawn below {@link TreeMetrics#MIN_READABLE_SCALE},
     * so a 16 px icon is never smaller than about ten pixels — while the
     * {@code 1/4 ▾} it replaced was a string measured in font pixels and could
     * promise nothing of the kind.</p>
     */
    private static void drawBadge(GuiGraphics graphics, Font font, TreeRow<RecipeNode> row, int r,
            int first, int labelW, int badgeW, TreePanel.Plan panel) {
        if (row.badge() == TreeRow.Badge.NONE || badgeW == 0) {
            return;
        }
        int x = labelW + TreeMetrics.LABEL_GAP;
        int y = TreeMetrics.rowCenter(r, first) - BADGE / 2;
        boolean switchable = row.badge() == TreeRow.Badge.MULTIPLE;
        UiShapes.recipeButton(graphics, x, y, BADGE,
                switchable ? UiPalette.FOLD_BTN_BG : UiPalette.CHIP_BG,
                row.badge() == TreeRow.Badge.CUT ? UiPalette.MARK_CUT
                        : switchable ? UiPalette.FOLD_BTN_BORDER : UiPalette.CHIP_BORDER,
                MachineNames.iconOf(row.node().recipe()));
        HudHitMap.add(new HudHitMap.Hotspot(panel.contentLeft(), panel.contentTop(), panel.scale(),
                null, row.node(), 0, 0, new TreeMetrics.Rect(x, y, BADGE, BADGE)));
    }


    /** Quiet 3 px track, brighter thumb under the cursor or while the editor drags it. */
    private static void drawScrollbar(GuiGraphics graphics, Minecraft mc, TreePanel.Plan panel,
            int left, int rowCount) {
        if (panel.maxScroll() <= 0) {
            return;
        }
        int trackTop = panel.contentTop();
        int trackH = Math.max(1, panel.contentBottom() - trackTop);
        int barX = left + panel.panelW() - SCROLL_ZONE + 2;
        Scrollbar.Geometry bar = Scrollbar.of(barX, trackTop, trackH, panel.visibleRows(), rowCount,
                panel.scroll(), panel.maxScroll(), TreeMetrics.MIN_THUMB_H);
        boolean hot = HudHitMap.isGrabbed()
                || (mc.screen != null && Scrollbar.overColumn(bar, mouseX(mc), mouseY(mc)));
        Scrollbar.draw(graphics, bar, hot);
        HudHitMap.setTrack(new HudHitMap.ScrollTrack(barX - Scrollbar.HIT, trackTop,
                Scrollbar.WIDTH + 2 * Scrollbar.HIT, trackH, bar.thumbHeight(), bar.thumbTop(),
                panel.maxScroll()));
    }

    /**
     * Colour legend of the tree rows, right-aligned on the title line, drawn in
     * the title's scaled pose. Dropped entirely when it would collide with the
     * title — a missing legend is better than an unreadable one, and the window
     * carries the authoritative copy of it.
     */



    private static void drawTreeLegend(GuiGraphics graphics, Font font, int contentW) {
        int total = UiLegend.totalWidth(font);
        int titleW = font.width(Component.translatable("overlay.craftplanner.title"));
        if (titleW + UiLegend.ENTRY_GAP + total > contentW) {
            return;
        }
        UiLegend.drawRow(graphics, font, 0, UiLegend.entries(font).size(), contentW, 0, false);
    }

    /**
     * Hotkey hint for the footer: the verbose line when it fits, the short one
     * when the panel is narrow, nothing at all when even that would be cut. The
     * hint is never trimmed mid-word.
     *
     * <p>When the plan does not fit the panel, the hint swaps to the one line that
     * matters: what to press to see the rest.</p>
     */
    static Component hotkeyLine(TextMetrics metrics, int contentW, TreePanel.Plan panel) {
        if (panel.clipped()) {
            // A plan trimmed at the panel edge says what to press to see the rest;
            // silently cutting a row mid-word was what made users think the tree
            // was broken.
            Component note = keysHint("overlay.craftplanner.trimmed");
            if (metrics.width(note.getString()) <= contentW) {
                return note;
            }
        }
        Component full = keysHint("overlay.craftplanner.keys");
        if (metrics.width(full.getString()) <= contentW) {
            return full;
        }
        Component brief = keysHint("overlay.craftplanner.keys.short");
        return metrics.width(brief.getString()) <= contentW ? brief : null;
    }

    /**
     * A hotkey hint with the <em>current</em> key bindings substituted into its
     * placeholders, so the footer keeps telling the truth after any rebind in
     * Options → Controls.
     */
    static Component keysHint(String key) {
        return Component.translatable(key,
                Keybinds.ADD_RECIPE.getKey().getDisplayName(),
                Keybinds.TOGGLE_OVERLAY.getKey().getDisplayName(),
                Keybinds.OPEN_RECIPES.getKey().getDisplayName(),
                Keybinds.OPEN_TREE.getKey().getDisplayName(),
                Keybinds.EDIT_LAYOUT.getKey().getDisplayName());
    }

    // ---------------------------------------------------------------------
    // Layout editor: move, resize and rescale the panel with the mouse
    // ---------------------------------------------------------------------

    private static final int EDIT_GRIP = UiPalette.EDIT_GRIP;
    private static final int EDIT_GRIP_HOVER = UiPalette.EDIT_GRIP_HOVER;
    private static final int EDIT_TEXT = UiPalette.EDIT_TEXT;

    /**
     * Editor chrome on top of the finished panel: the outline being dragged, the
     * resize grip in its corner and the one-line instructions. The HUD keeps
     * rendering behind {@code OverlayLayoutScreen}, which is where the input of
     * that mode is handled.
     */
    static void drawEditChrome(GuiGraphics graphics, Minecraft mc, Font font, int left,
            int top, int panelW, int panelH, int screenW, int screenH) {
        if (!OverlayPlacement.editing()) {
            return;
        }
        UiShapes.ring(graphics, left, top, panelW, panelH, UiPalette.EDIT_BORDER);
        UiShapes.ring(graphics, left + 1, top + 1, panelW - 2, panelH - 2, UiPalette.EDIT_BORDER);

        int gripX = OverlayPlacement.gripLeft();
        int gripY = OverlayPlacement.gripTop();
        int gripSize = OverlayPlacement.gripSize();
        boolean hovered = OverlayPlacement.overGrip(mouseX(mc), mouseY(mc));
        int color = hovered ? EDIT_GRIP_HOVER : EDIT_GRIP;
        UiShapes.chip(graphics, gripX, gripY, gripSize, gripSize, UiPalette.CHIP_BG, color);
        graphics.fill(gripX + 2, gripY + 2, gripX + gripSize - 2, gripY + 3, color);
        graphics.fill(gripX + 2, gripY + 4, gripX + gripSize - 3, gripY + 5, color);

        // Trimmed to the screen, not to the panel: at a large GUI scale a
        // translated hint can easily be wider than the whole window.
        TextMetrics metrics = font::width;
        int maxTextW = Math.max(20, screenW - 8);
        String first = metrics.trim(Component.translatable("overlay.craftplanner.edit.hint",
                Integer.toString((int) Math.round(OverlayPlacement.liveDensity() * 100))).getString(),
                maxTextW);
        String second = metrics.trim(Component.translatable("overlay.craftplanner.edit.hint2",
                Keybinds.EDIT_LAYOUT.getKey().getDisplayName().getString()).getString(), maxTextW);
        int widest = Math.max(font.width(first), font.width(second));
        int textLeft = Math.max(2, Math.min(left + (panelW - widest) / 2, screenW - widest - 2));
        // Below the panel when there is room, above it when the panel sits at the
        // bottom edge — the instructions must never be cut by the screen.
        int textTop = top + panelH + 8 + font.lineHeight <= screenH
                ? top + panelH + 4
                : top - 4 - (2 * font.lineHeight + 3);
        UiShapes.chip(graphics, textLeft - 3, textTop - 2, widest + 6,
                2 * font.lineHeight + 5, UiPalette.CHIP_BG, UiPalette.CHIP_BORDER);
        graphics.drawString(font, first, textLeft, textTop, EDIT_TEXT, false);
        graphics.drawString(font, second, textLeft, textTop + font.lineHeight + 2, EDIT_TEXT, false);
    }
}
