package com.craftplanner.client.overlay;

import com.craftplanner.Config;
import com.craftplanner.Constants;
import com.craftplanner.client.Keybinds;
import com.craftplanner.client.OverlayState;
import com.craftplanner.client.PlanRows;
import com.craftplanner.client.PlannerState;
import com.craftplanner.client.TextMetrics;
import com.craftplanner.client.UiPalette;
import com.craftplanner.client.UiShapes;
import com.craftplanner.client.layout.PlacedNode;
import com.craftplanner.client.layout.TreeLayout;
import com.craftplanner.client.resource.Availability;
import com.craftplanner.client.tree.TreeFlatten;
import com.craftplanner.client.tree.TreeMetrics;
import com.craftplanner.client.tree.TreePanel;
import com.craftplanner.client.tree.TreeRow;
import com.craftplanner.graph.CraftGraph;
import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.RecipeEdge;
import com.craftplanner.graph.RecipeNode;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
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
 * and {@code "graph"} — the classic node-link view, kept below unchanged.</p>
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
 */
@EventBusSubscriber(modid = Constants.MODID, value = Dist.CLIENT)
public final class TreeOverlayRenderer {

    private static final int MARGIN = 8;
    private static final int TITLE_H = 12;
    /** Gap between the last tree row and the footer block. */
    private static final int FOOTER_GAP = 3;
    /** Air between the shopping list and the hotkey line, in layout pixels. */
    private static final int FOOTER_KEYS_GAP = 2;
    /** Gap between two shopping-list entries. */
    private static final int FOOTER_ENTRY_GAP = 6;
    /**
     * Slack kept by the wrap. The content width is exact in layout pixels but
     * rounded in screen pixels, so two pixels are left free rather than risk the
     * last icon of a line being cut by the panel edge.
     */
    private static final int FOOTER_SAFE = 2;
    /** Height of one text line of the footer (9 px glyphs + 2 px leading). */
    private static final int FOOTER_TEXT_H = 11;
    /** Height of one shopping-list line (icon + 2 px leading). */
    private static final int FOOTER_LINE_H = TreeMetrics.ICON + 2;
    /**
     * Cap on shopping-list lines. Three lines already cost more vertical space
     * than the tree can usually spare; anything beyond is reported as {@code +N}
     * and stays reachable through the K window.
     */
    private static final int MAX_FOOTER_LINES = 3;
    /** Zone at the panel's right edge for the scrollbar, in screen pixels. */
    private static final int SCROLL_ZONE = TreeMetrics.SCROLL_GUTTER;
    private static final int PANEL_BG = UiPalette.HUD_PANEL_BG;
    private static final int PANEL_BORDER = UiPalette.HUD_PANEL_BORDER;
    private static final int MUTED = UiPalette.HUD_MUTED;

    /** Vertical scroll of the tree layout, in rows (tree mode only). */
    private static int treeScroll;
    /**
     * Cached node-link layout (graph mode). Recomputing it every frame is pure
     * waste: the coordinates only change when the tree is rebuilt.
     */
    private static TreeLayout cachedLayout;
    private static long cachedLayoutVersion = -1;

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

    /** Where the last drawn frame put the scrollbar, {@code null} when the plan fits. */
    private static ScrollTrack scrollTrack;
    /** True while the layout editor holds that scrollbar. */
    private static boolean scrollGrab;
    private static final int SCROLL_HIT = 3;

    /**
     * Edge of the recipe button at the end of a row, in layout pixels.
     *
     * <p>A 16 px icon with a pixel of air around it, which is the smallest square
     * that still reads as a control rather than as a smudge at the label's edge —
     * and it is fixed, so the column does not resize as a count changes.</p>
     */
    private static final int BADGE = 18;

    /**
     * The fold toggles and recipe badges of the last drawn frame, in screen
     * pixels.
     *
     * <p>A HUD cannot be clicked: while no screen is open the game grabs the
     * mouse and turns movement into camera yaw. The layout editor ({@code O}) is
     * a screen, it is transparent, and the panel stays visible behind it — so
     * that is the one place these markers are reachable. They are therefore
     * collected as they are drawn, exactly like {@link #scrollTrack}, and a
     * marker left over from a previous frame would keep answering a click for a
     * panel that is no longer on screen.</p>
     */
    private static final List<Hotspot> HOTSPOTS = new ArrayList<>();

    /**
     * One drawn control of the tree panel: which row it belongs to, where the tree
     * was drawn, and what it acts on. Exactly one of the two payloads is set.
     *
     * <p>The row is stored as an index into the plan rather than as a rectangle,
     * because the band is rebuilt against {@link #treeScroll} when it is asked
     * about, not against the scroll the frame was drawn with. Input is handled
     * before the frame is drawn, so a wheel notch and a click landing in the same
     * tick would otherwise fold the row the player was looking at before the
     * wheel, not the one under the cursor.</p>
     *
     * @param fold  branch key to toggle, {@code null} for a recipe badge
     * @param node  item whose recipes the badge would open, {@code null} for a fold
     * @param badge the badge's box in layout pixels, {@code null} for a fold
     */
    private record Hotspot(int originX, int originY, double scale, String fold, RecipeNode node,
            int depth, int row, TreeMetrics.Rect badge) {

        boolean contains(int mx, int my) {
            return this.badge != null
                    ? TreeMetrics.toScreen(this.badge, this.scale, this.originX, this.originY)
                            .contains(mx, my)
                    : TreeMetrics.toggleHit(this.depth, this.row, treeScroll, this.scale,
                            this.originX, this.originY).contains(mx, my);
        }
    }

    /**
     * The branch folded by a click at this point, or {@code null}.
     *
     * <p>Callers must only ask while the layout editor is open: the panel behind
     * it is the only place a HUD row can be aimed at, which is why the markers
     * stopped being interactive in the overlay itself.</p>
     */
    public static String hitFold(int mx, int my) {
        for (Hotspot spot : HOTSPOTS) {
            if (spot.fold() != null && spot.contains(mx, my)) {
                return spot.fold();
            }
        }
        return null;
    }

    /** The item whose recipe picker a click on a badge should open, or {@code null}. */
    public static RecipeNode hitChoice(int mx, int my) {
        for (Hotspot spot : HOTSPOTS) {
            if (spot.node() != null && spot.contains(mx, my)) {
                return spot.node();
            }
        }
        return null;
    }

    /**
     * The scrollbar in screen pixels, with the mapping from a cursor row back to a
     * scroll offset.
     */
    private record ScrollTrack(int x, int y, int w, int h, int barH, int maxScroll) {

        boolean contains(int mx, int my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }

        /** Rows to scroll so the middle of the thumb lands under {@code my}. */
        int scrollAt(int my) {
            return TreePanel.scrollAt(y, h, barH, maxScroll, my);
        }
    }

    /**
     * Takes a click on the scrollbar: the thumb jumps under the cursor and stays
     * there until the drag is released, the way a browser track behaves.
     *
     * @return true when the point was the scrollbar and the click is consumed
     */
    public static boolean grabScroll(int mx, int my) {
        ScrollTrack track = scrollTrack;
        if (track == null || !track.contains(mx, my)) {
            return false;
        }
        scrollGrab = true;
        treeScroll = track.scrollAt(my);
        return true;
    }

    /** Follows the cursor with the thumb while a grab is live. */
    public static void dragScroll(int my) {
        ScrollTrack track = scrollTrack;
        if (track != null) {
            treeScroll = track.scrollAt(my);
        }
    }

    /** Drops the scrollbar, on the release that ends the drag. */
    public static void releaseScroll() {
        scrollGrab = false;
    }

    /**
     * True while the scrollbar is held: the drag then moves the plan, not the
     * panel the editor is otherwise dragging.
     */
    public static boolean scrolling() {
        return scrollGrab;
    }

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
        scrollTrack = null;
        // Same two reasons for the tree's own markers: a fold toggle or a recipe
        // badge left over from a previous frame would keep answering a click
        // where no panel is.
        HOTSPOTS.clear();
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
            renderGraph(event, mc, graph);
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
        return row.name() + " " + row.node().amountLabel()
                + (row.hiddenRows() > 0 ? " (+" + row.hiddenRows() + ")" : "");
    }

    /**
     * Density of the overlay content: {@code overlay.scale} of the config, or the
     * value the layout editor produced, kept inside the readable band.
     */
    private static double overlayScale() {
        return TreeMetrics.readableScale(OverlayPlacement.density());
    }

    /** Keeps a pinned panel on screen after a resolution change. */
    private static int clampToScreen(int requested, int min, int max) {
        if (requested == OverlayPlacement.AUTO) {
            return min;
        }
        return max < min ? min : Math.max(min, Math.min(max, requested));
    }

    /** Cursor position in GUI-scaled pixels, the space the panel is drawn in. */
    private static int mouseX(Minecraft mc) {
        return (int) (mc.mouseHandler.xpos() / mc.getWindow().getGuiScale());
    }

    private static int mouseY(Minecraft mc) {
        return (int) (mc.mouseHandler.ypos() / mc.getWindow().getGuiScale());
    }

    private static void renderTree(RenderGuiEvent.Post event, Minecraft mc) {
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

        Footer footer = buildFooter(availability, PlannerState.fluidRequirements(), metrics, rough.contentW(),
                hotkeyLine(metrics, rough.contentW(), rough));
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
            drawFooter(graphics, font, footer, contentW);
            pose.popPose();
            graphics.disableScissor();
            if (!showTailTooltip(graphics, font, footer, mc, contentLeft, footerTop, scale)) {
                showMaterialTooltips(graphics, font, footer, mc, contentLeft, footerTop, scale);
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
        for (int r = first; r <= last; r++) {
            TreeRow<RecipeNode> parent = rows.get(r);
            if (!parent.expands(r)) {
                continue;
            }
            int x = TreeMetrics.spineX(parent.depth());
            graphics.vLine(x, TreeMetrics.iconBottom(r, first),
                    TreeMetrics.rowCenter(parent.lastChildIndex(), first), UiPalette.TREE_GUIDE);
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
            HOTSPOTS.add(new Hotspot(panel.contentLeft(), panel.contentTop(), panel.scale(),
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
                    TreeMetrics.textY(r, first, font.lineHeight), ink(row, availability), false);
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
                        : switchable ? UiPalette.FOLD_BTN_BORDER : UiPalette.CHIP_BORDER);
        HOTSPOTS.add(new Hotspot(panel.contentLeft(), panel.contentTop(), panel.scale(),
                null, row.node(), 0, 0, new TreeMetrics.Rect(x, y, BADGE, BADGE)));
    }

    /** The row's label colour, from the same availability the shopping list uses. */
    private static int ink(TreeRow<RecipeNode> row, Map<ItemKey, Availability> availability) {
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

    /** Quiet 3 px track, brighter thumb under the cursor or while the editor drags it. */
    private static void drawScrollbar(GuiGraphics graphics, Minecraft mc, TreePanel.Plan panel,
            int left, int rowCount) {
        if (panel.maxScroll() <= 0) {
            return;
        }
        int trackTop = panel.contentTop();
        int trackH = Math.max(1, panel.contentBottom() - trackTop);
        int barH = TreePanel.thumbHeight(trackH, panel.visibleRows(), rowCount);
        int barY = TreePanel.thumbTop(trackTop, trackH, barH, panel.scroll(), panel.maxScroll());
        int barX = left + panel.panelW() - SCROLL_ZONE + 2;
        graphics.fill(barX, trackTop, barX + TreeMetrics.SCROLL_BAR_W, trackTop + trackH,
                UiPalette.SCROLL_TRACK);
        boolean hot = scrollGrab
                || (mc.screen != null && mouseX(mc) >= barX - SCROLL_HIT
                        && mouseX(mc) <= barX + TreeMetrics.SCROLL_BAR_W + SCROLL_HIT
                        && mouseY(mc) >= trackTop && mouseY(mc) < trackTop + trackH);
        graphics.fill(barX, barY, barX + TreeMetrics.SCROLL_BAR_W, barY + barH,
                hot ? UiPalette.SCROLL_BAR_HOT : UiPalette.SCROLL_BAR);
        scrollTrack = new ScrollTrack(barX - SCROLL_HIT, trackTop,
                TreeMetrics.SCROLL_BAR_W + 2 * SCROLL_HIT, trackH, barH, panel.maxScroll());
    }

    /**
     * Colour legend of the tree rows, right-aligned on the title line, drawn in
     * the title's scaled pose. Dropped entirely when it would collide with the
     * title — a missing legend is better than an unreadable one, and the window
     * carries the authoritative copy of it.
     */
    private static void drawTreeLegend(GuiGraphics graphics, Font font, int contentW) {
        int[] colors = {
                NodeColors.HAVE, NodeColors.MISSING,
                NodeColors.INTERMEDIATE, NodeColors.ROOT
        };
        Component[] labels = {
                Component.translatable("overlay.craftplanner.legend.have"),
                Component.translatable("overlay.craftplanner.legend.missing"),
                Component.translatable("overlay.craftplanner.legend.intermediate"),
                Component.translatable("overlay.craftplanner.legend.root")
        };
        final int gap = 6;
        final int square = 6;
        int[] itemW = new int[labels.length];
        int total = 0;
        for (int i = 0; i < labels.length; i++) {
            itemW[i] = square + 2 + font.width(labels[i]);
            total += itemW[i] + gap;
        }
        int titleW = font.width(Component.translatable("overlay.craftplanner.title"));
        if (titleW + gap + total > contentW) {
            return;
        }
        int x = contentW - total;
        for (int i = 0; i < labels.length; i++) {
            graphics.fill(x, 2, x + square, 2 + square, colors[i]);
            graphics.drawString(font, labels[i], x + square + 2, 0, NodeColors.TEXT, false);
            x += itemW[i] + gap;
        }
    }

    /**
     * Hotkey hint for the footer: the verbose line when it fits, the short one
     * when the panel is narrow, nothing at all when even that would be cut. The
     * hint is never trimmed mid-word.
     *
     * <p>When the plan does not fit the panel, the hint swaps to the one line that
     * matters: what to press to see the rest.</p>
     */
    private static Component hotkeyLine(TextMetrics metrics, int contentW, TreePanel.Plan panel) {
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
    private static Component keysHint(String key) {
        return Component.translatable(key,
                Keybinds.ADD_RECIPE.getKey().getDisplayName(),
                Keybinds.TOGGLE_OVERLAY.getKey().getDisplayName(),
                Keybinds.OPEN_RECIPES.getKey().getDisplayName(),
                Keybinds.OPEN_TREE.getKey().getDisplayName(),
                Keybinds.EDIT_LAYOUT.getKey().getDisplayName());
    }

    // ---------------------------------------------------------------------
    // Footer: shopping list + totals
    // ---------------------------------------------------------------------

    /** Prepared footer block: wrapped shopping list plus the hotkey line. */
    private static final class Footer {
        final List<List<FooterMaterial>> lines = new ArrayList<>();
        /** Materials dropped because {@link #MAX_FOOTER_LINES} was reached. */
        int hidden;
        /** The dropped materials, as the tooltip content of the {@code +N} marker. */
        List<FooterMaterial> hiddenEntries = List.of();
        /**
         * Icons actually drawn, so hovering one can name the material behind it.
         * Filled while the footer is drawn, in layout pixels like {@link #tailX}.
         */
        final List<MaterialIcon> icons = new ArrayList<>();
        /**
         * Rectangle of the {@code +N} marker in layout pixels, set while the
         * footer is drawn; {@code tailX < 0} means nothing was drawn.
         */
        int tailX = -1;
        int tailY;
        int tailW;
        int tailH;
        /** Null when the hotkey line does not fit — it is never cut. */
        final Component keys;

        Footer(Component keys) {
            this.keys = keys;
        }

        int height() {
            int h = lines.isEmpty() ? 0 : lines.size() * FOOTER_LINE_H;
            if (keys != null) {
                h += FOOTER_KEYS_GAP + FOOTER_TEXT_H;
            }
            return h;
        }
    }

    /** One drawn shopping-list icon: what it stands for and where it was put. */
    private record FooterMaterial(ItemKey item, Fluid fluid, long required, Availability availability) {
        static FooterMaterial item(ItemKey key, Availability value) {
            return new FooterMaterial(key, null, value.required(), value);
        }

        static FooterMaterial fluid(Fluid fluid, long amount) {
            return new FooterMaterial(null, fluid, amount, null);
        }

        boolean isFluid() {
            return fluid != null;
        }

        ItemStack icon() {
            if (!isFluid()) {
                return item.toStack();
            }
            ItemStack bucket = new ItemStack(fluid.getBucket());
            return bucket.isEmpty() ? new ItemStack(Items.BUCKET) : bucket;
        }

        Component name() {
            return isFluid() ? Component.translatable(fluid.getFluidType().getDescriptionId()) : item.name();
        }

        String amountLabel() {
            return isFluid() ? required + " mB" : Long.toString(required);
        }

        int color() {
            return availability == null ? NodeColors.RAW : NodeColors.forAvailability(availability);
        }
    }

    private record MaterialIcon(FooterMaterial material, int x, int y) {
    }

    /**
     * Builds the footer for a content width given in layout pixels. The shopping
     * list is sorted by shortage first, so what the player still has to gather is
     * what they see.
     *
     * <p>{@code keys} may be null — the hotkey line is dropped rather than cut
     * when the panel is narrower than even the short hint. The width check has to
     * test for that first, or {@code Font.width(null)} throws and takes the game
     * down with it.</p>
     */
    private static Footer buildFooter(Map<ItemKey, Availability> availability, Map<Fluid, Long> fluids,
            TextMetrics metrics, int contentW, Component keys) {
        Footer footer = new Footer(
                keys != null && metrics.width(keys.getString()) <= contentW ? keys : null);
        List<FooterMaterial> entries = shoppingList(availability, fluids);
        wrapShoppingList(footer, entries, metrics, contentW, 0);
        if (footer.hidden > 0) {
            // Something was dropped: redo the wrap with room for the "+N" tail. The
            // reserve is sized for the largest possible number, so the marker can
            // never be pushed out by its own growth.
            Footer retry = new Footer(footer.keys);
            wrapShoppingList(retry, entries, metrics, contentW,
                    metrics.width("+" + entries.size()) + 3);
            footer = retry;
        }
        return footer;
    }

    /** Raw materials, worst shortage first, then largest demand. */
    private static List<FooterMaterial> shoppingList(Map<ItemKey, Availability> availability,
            Map<Fluid, Long> fluids) {
        List<FooterMaterial> entries = new ArrayList<>();
        availability.forEach((key, value) -> entries.add(FooterMaterial.item(key, value)));
        fluids.forEach((fluid, amount) -> entries.add(FooterMaterial.fluid(fluid, amount)));
        entries.sort((a, b) -> {
            long missingA = a.availability() == null ? 0 : a.availability().missing();
            long missingB = b.availability() == null ? 0 : b.availability().missing();
            int byShortage = Long.compare(missingB, missingA);
            return byShortage != 0
                    ? byShortage
                    : Long.compare(b.required(), a.required());
        });
        return entries;
    }

    private static int entryWidth(TextMetrics metrics, FooterMaterial material) {
        return TreeMetrics.ICON + 1 + metrics.width(material.amountLabel()) + FOOTER_ENTRY_GAP;
    }

    /**
     * Greedy wrap of the shopping list into at most {@link #MAX_FOOTER_LINES}
     * lines. {@code reserveLast} is the room kept free at the end of the last line
     * for the {@code +N} marker (0 on the first pass).
     */
    private static void wrapShoppingList(Footer footer, List<FooterMaterial> entries,
            TextMetrics metrics, int contentW, int reserveLast) {
        List<FooterMaterial> current = new ArrayList<>();
        int used = 0;
        int index = 0;
        while (index < entries.size()) {
            FooterMaterial entry = entries.get(index);
            int width = entryWidth(metrics, entry);
            boolean lastLine = footer.lines.size() == MAX_FOOTER_LINES - 1;
            int capacity = (lastLine ? contentW - reserveLast : contentW) - FOOTER_SAFE;
            if (!current.isEmpty() && used + width > capacity) {
                footer.lines.add(current);
                if (footer.lines.size() == MAX_FOOTER_LINES) {
                    footer.hidden = entries.size() - index;
                    footer.hiddenEntries = List.copyOf(entries.subList(index, entries.size()));
                    return;
                }
                current = new ArrayList<>();
                used = 0;
                continue;
            }
            current.add(entry);
            used += width;
            index++;
        }
        if (!current.isEmpty()) {
            footer.lines.add(current);
        }
    }

    /** Draws the footer in layout pixels; the caller owns the scaled pose. */
    private static void drawFooter(GuiGraphics graphics, Font font, Footer footer, int contentW) {
        footer.icons.clear();
        int y = 0;
        for (int i = 0; i < footer.lines.size(); i++) {
            boolean last = i == footer.lines.size() - 1;
            int hidden = last ? footer.hidden : 0;
            int tailX = drawShoppingLine(graphics, font, footer, footer.lines.get(i), y, hidden);
            if (tailX >= 0) {
                footer.tailX = tailX;
                footer.tailY = y + (TreeMetrics.ICON - font.lineHeight) / 2 + 1;
                footer.tailW = font.width("+" + hidden);
                footer.tailH = font.lineHeight;
            }
            y += FOOTER_LINE_H;
        }
        if (footer.keys != null) {
            graphics.drawCenteredString(font, footer.keys, contentW / 2,
                    y + FOOTER_KEYS_GAP, MUTED);
        }
    }

    /**
     * One line of the shopping list: icon, total amount, colour by availability —
     * no names, so nothing has to be trimmed.
     *
     * @return the layout-x where the {@code +N} marker was drawn, {@code -1} when
     *         the line has no marker (used to hover-test the tail tooltip)
     */
    private static int drawShoppingLine(GuiGraphics graphics, Font font, Footer footer,
            List<FooterMaterial> line, int y, int hidden) {
        int textY = y + (TreeMetrics.ICON - font.lineHeight) / 2 + 1;
        int x = 0;
        for (FooterMaterial entry : line) {
            graphics.renderItem(entry.icon(), x, y);
            footer.icons.add(new MaterialIcon(entry, x, y));
            String amount = entry.amountLabel();
                graphics.drawString(font, amount, x + TreeMetrics.ICON + 1, textY,
                    entry.color(), false);
            x += entryWidth(font::width, entry);
        }
        if (hidden > 0) {
            graphics.drawString(font, "+" + hidden, x, textY, MUTED, false);
            return x;
        }
        return -1;
    }

    /**
     * Hover feedback for the {@code +N} marker: the number alone does not say
     * <em>what</em> is hidden, so pointing at it lists the dropped materials.
     *
     * @param originX screen x of the footer pose the marker was drawn from
     * @param originY screen y of that pose
     * @param scale   scale factor of that pose
     * @return true when the cursor was on the marker and a tooltip was shown
     */
    private static boolean showTailTooltip(GuiGraphics graphics, Font font, Footer footer,
            Minecraft mc, int originX, int originY, double scale) {
        if (footer.hidden <= 0 || footer.tailX < 0 || footer.hiddenEntries.isEmpty()
                || !OverlayPlacement.editing()) {
            return false;
        }
        int mx = mouseX(mc);
        int my = mouseY(mc);
        final int pad = 2;
        int x1 = originX + (int) ((footer.tailX - pad) * scale);
        int y1 = originY + (int) ((footer.tailY - pad) * scale);
        int x2 = originX + (int) Math.ceil((footer.tailX + footer.tailW + pad) * scale);
        int y2 = originY + (int) Math.ceil((footer.tailY + footer.tailH + pad) * scale);
        if (mx < x1 || mx > x2 || my < y1 || my > y2) {
            return false;
        }
        List<Component> lines = new ArrayList<>();
        for (FooterMaterial entry : footer.hiddenEntries) {
                lines.add(entry.name().copy().append(Component.literal(" "
                    + (entry.isFluid() ? entry.amountLabel() : "×" + entry.amountLabel()))));
        }
        showTooltip(graphics, font, mc, lines);
        return true;
    }

    /**
     * Hover feedback for a shopping-list icon: the footer shows one number per
     * material — how much the whole plan asks for — which says nothing about
     * <em>which</em> item it is or how much of it the player already carries. So
     * pointing at an icon names it and splits the amount into needed, on hand and
     * still missing.
     *
     * <p>Offered only in the layout editor (O). Any open screen used to be enough,
     * which put these notes on top of whatever the player was actually looking at
     * — an inventory, EMI, a chest — where the HUD is behind a dim and the icons
     * under the cursor belong to that screen, not to the panel. With no screen at
     * all the game grabs the cursor and pins it to the middle of the window, so the
     * pointer cannot reach an icon in the first place.</p>
     *
     * @param originX screen x of the footer pose the icons were drawn from
     * @param originY screen y of that pose
     * @param scale   scale factor of that pose
     */
    private static void showMaterialTooltips(GuiGraphics graphics, Font font, Footer footer,
            Minecraft mc, int originX, int originY, double scale) {
        if (footer.icons.isEmpty() || !OverlayPlacement.editing()) {
            return;
        }
        int mx = mouseX(mc);
        int my = mouseY(mc);
        final int pad = 1;
        for (MaterialIcon icon : footer.icons) {
            int x1 = originX + (int) ((icon.x() - pad) * scale);
            int y1 = originY + (int) ((icon.y() - pad) * scale);
            int x2 = originX + (int) Math.ceil((icon.x() + TreeMetrics.ICON + pad) * scale);
            int y2 = originY + (int) Math.ceil((icon.y() + TreeMetrics.ICON + pad) * scale);
            if (mx < x1 || mx > x2 || my < y1 || my > y2) {
                continue;
            }
            showTooltip(graphics, font, mc, materialLines(icon.material()));
            return;
        }
    }

    /**
     * A material named, then what the plan asks of it: the amount the footer
     * prints, plus the part the inventory already covers. Only a shortfall gets its
     * own line — a material already in stock has nothing left to report.
     */
    private static List<Component> materialLines(FooterMaterial material) {
        List<Component> lines = new ArrayList<>();
        lines.add(material.name().copy());
        lines.add(Component.translatable("overlay.craftplanner.material.required",
                material.amountLabel()));
        if (material.availability() != null) {
            lines.add(Component.translatable("overlay.craftplanner.material.have",
                    Long.toString(material.availability().available())));
            if (material.availability().missing() > 0) {
                lines.add(Component.translatable("overlay.craftplanner.material.missing",
                        Long.toString(material.availability().missing())));
            }
        }
        return lines;
    }

    /**
     * Shows tooltip lines for the point the cursor is at. While a screen is open
     * they are handed to it instead of being drawn here: Minecraft paints the HUD —
     * and so every tooltip the overlay could draw — <em>before</em> the screen, and
     * the planner's screens dim the whole view on top of it, which left the player
     * with a dark box and unreadable text. A screen draws what it was handed as the
     * last thing of its own render pass, above everything it painted, and wraps it
     * to the vanilla tooltip width.
     */
    private static void showTooltip(GuiGraphics graphics, Font font, Minecraft mc,
            List<Component> lines) {
        if (lines.isEmpty()) {
            return;
        }
        MutableComponent joined = lines.get(0).copy();
        for (int i = 1; i < lines.size(); i++) {
            joined.append(Component.literal("\n")).append(lines.get(i).copy());
        }
        mc.screen.setTooltipForNextRenderPass(joined);
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
    private static void drawEditChrome(GuiGraphics graphics, Minecraft mc, Font font, int left,
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

    // ---------------------------------------------------------------------
    // Graph (node-link) layout — preserved from Stage 8
    // ---------------------------------------------------------------------

    private static final int G_MARGIN = 8;
    private static final int G_PADDING = 4;
    private static final int G_TITLE_SPACE = 11;
    private static final int G_FOOTER_SPACE = 24;
    private static final int G_LEGEND_SQUARE = 6;
    /**
     * Floor for the automatic "shrink to fit" of the graph layout. Below this the
     * node-link view is unreadably small, and the overlay falls back to the
     * scrollable list (tree) layout instead of squeezing the text further.
     */
    private static final float MIN_READABLE_FIT = 0.6f;

    /**
     * The layout for the current tree: reused while
     * {@link PlannerState#graphVersion()} does not change, recomputed once after
     * every rebuild.
     */
    private static TreeLayout layoutFor(CraftGraph graph) {
        long version = PlannerState.graphVersion();
        TreeLayout layout = cachedLayout;
        if (layout == null || cachedLayoutVersion != version) {
            layout = TreeLayout.of(graph.roots());
            cachedLayout = layout;
            cachedLayoutVersion = version;
        }
        return layout;
    }

    private static void renderGraph(RenderGuiEvent.Post event, Minecraft mc, CraftGraph graph) {
        TreeLayout layout = layoutFor(graph);
        if (layout.isEmpty()) {
            return;
        }

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int maxPanelWidth = screenWidth - 2 * G_MARGIN;
        int maxPanelHeight = screenHeight - 2 * G_MARGIN;
        int pinX = OverlayPlacement.x();
        int pinY = OverlayPlacement.y();
        int pinW = OverlayPlacement.w();
        int pinH = OverlayPlacement.h();

        int contentWidth = layout.width();
        int contentHeight = layout.height();
        double density = overlayScale();
        int panelWidth = pinW == OverlayPlacement.AUTO
                ? Math.min(maxPanelWidth,
                        Math.max(1, (int) Math.round(contentWidth * density)) + 2 * G_PADDING)
                : Math.min(maxPanelWidth, pinW);

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = mc.font;

        // The footer needs the panel width before the height is known: the
        // shopping list wraps into whatever horizontal room it is given.
        Map<ItemKey, Availability> availability = PlannerState.availability();
        int footerLayoutW = Math.max(1, (int) ((panelWidth - 2 * G_PADDING) / density));
        TextMetrics metrics = font::width;
        Footer footer = buildFooter(availability, PlannerState.fluidRequirements(), metrics, footerLayoutW,
                graphKeysHint(metrics, footerLayoutW));
        int footerSpace = Math.max(G_FOOTER_SPACE, (int) Math.round(footer.height() * density) + 2);

        int panelHeight = pinH == OverlayPlacement.AUTO
                ? Math.min(maxPanelHeight,
                        Math.max(1, (int) Math.round(contentHeight * density)) + 2 * G_PADDING
                                + G_TITLE_SPACE + footerSpace)
                : Math.min(maxPanelHeight, pinH);
        int left = clampToScreen(pinX, 0, screenWidth - panelWidth);
        int top = clampToScreen(pinY, 0, screenHeight - panelHeight);
        float availableWidth = Math.max(1f, panelWidth - 2f * G_PADDING);
        float availableHeight = Math.max(1f,
                panelHeight - 2f * G_PADDING - G_TITLE_SPACE - footerSpace);
        // Density decides how big a small graph is drawn; a large one is still
        // shrunk to fit the panel, so the two limits simply combine.
        float fit = Math.min(availableWidth / contentWidth, availableHeight / contentHeight);
        if (fit < MIN_READABLE_FIT) {
            if (!OverlayPlacement.pinned()) {
                // Fitting the node-link view would mean squeezing the text below
                // readability: fall back to the scrollable list layout instead.
                renderTree(event, mc);
                return;
            }
            // A panel the player sized on purpose keeps its text size and lets the
            // graph clip — resizing was the point of pinning it.
            fit = MIN_READABLE_FIT;
        }
        double scale = Math.max(0.05, Math.min(density, fit));

        UiShapes.panel(graphics, left, top, panelWidth, panelHeight, PANEL_BG,
                UiPalette.HUD_BORDER_OUTER, PANEL_BORDER);
        int graphHeaderBottom = top + G_PADDING + G_TITLE_SPACE - 1;
        graphics.fill(left + 2, top + 2, left + panelWidth - 2, graphHeaderBottom,
                UiPalette.HUD_HEADER_BG);
        graphics.fill(left + 2, graphHeaderBottom, left + panelWidth - 2, graphHeaderBottom + 1,
                UiPalette.HUD_ACCENT_LINE);

        graphics.drawString(font, Component.translatable("overlay.craftplanner.title"),
                left + G_PADDING, top + 3, NodeColors.TEXT, false);
        drawLegend(graphics, font, left, top, panelWidth);

        graphics.enableScissor(left, top, left + panelWidth, top + panelHeight);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(left + G_PADDING, top + G_PADDING + G_TITLE_SPACE, 0);
        pose.scale((float) scale, (float) scale, 1f);

        drawEdges(graphics, layout);
        drawNodes(graphics, layout);

        pose.popPose();
        graphics.disableScissor();

        int footerTop = top + panelHeight - footerSpace - 2;
        UiShapes.divider(graphics, left + 2, left + panelWidth - 2, footerTop - 2,
                UiPalette.HUD_SECTION_DIVIDER);
        graphics.enableScissor(left, footerTop, left + panelWidth, top + panelHeight - 1);
        pose.pushPose();
        pose.translate(left + G_PADDING, footerTop, 0);
        pose.scale((float) density, (float) density, 1f);
        drawFooter(graphics, font, footer, footerLayoutW);
        pose.popPose();
        graphics.disableScissor();
        if (!showTailTooltip(graphics, font, footer, mc, left + G_PADDING, footerTop, density)) {
            showMaterialTooltips(graphics, font, footer, mc, left + G_PADDING, footerTop, density);
        }
        OverlayPlacement.reportDrawn(left, top, panelWidth, panelHeight);
        drawEditChrome(graphics, mc, font, left, top, panelWidth, panelHeight,
                screenWidth, screenHeight);
    }

    /**
     * The graph view has no row trimming to note, so it keeps the plain hint and
     * does not take the tree's list of rows.
     */
    private static Component graphKeysHint(TextMetrics metrics, int contentW) {
        Component full = keysHint("overlay.craftplanner.keys");
        if (metrics.width(full.getString()) <= contentW) {
            return full;
        }
        Component brief = keysHint("overlay.craftplanner.keys.short");
        return metrics.width(brief.getString()) <= contentW ? brief : null;
    }

    /**
     * Colour legend, right-aligned next to the title. One line while it fits beside
     * the title; otherwise two lines (the second one may use the full panel
     * width); dropped entirely only when even two lines do not fit — the old fixed
     * 140 px slack silently swallowed it before.
     */
    private static void drawLegend(GuiGraphics graphics, Font font, int left, int top,
            int panelWidth) {
        int[] colors = {
                NodeColors.HAVE, NodeColors.MISSING,
                NodeColors.INTERMEDIATE, NodeColors.ROOT
        };
        Component[] labels = {
                Component.translatable("overlay.craftplanner.legend.have"),
                Component.translatable("overlay.craftplanner.legend.missing"),
                Component.translatable("overlay.craftplanner.legend.intermediate"),
                Component.translatable("overlay.craftplanner.legend.root")
        };
        int[] itemW = new int[labels.length];
        int total = 0;
        for (int i = 0; i < labels.length; i++) {
            itemW[i] = G_LEGEND_SQUARE + 2 + font.width(labels[i]);
            total += itemW[i] + 6;
        }

        int titleW = font.width(Component.translatable("overlay.craftplanner.title"));
        int firstRowRoom = panelWidth - 2 * G_PADDING - titleW - 6;
        int fullRoom = panelWidth - 2 * G_PADDING;
        if (total <= firstRowRoom) {
            drawLegendRow(graphics, font, colors, labels, itemW, 0, labels.length,
                    left, top + 4, panelWidth, total);
            return;
        }

        // Greedy split: as many entries as fit in the title row, the rest below.
        int split = 0;
        int firstTotal = 0;
        for (int i = 0; i < labels.length; i++) {
            int next = split == 0 ? itemW[i] : firstTotal + 6 + itemW[i];
            if (next > firstRowRoom) {
                break;
            }
            firstTotal = next;
            split++;
        }
        int restTotal = 0;
        for (int i = split; i < labels.length; i++) {
            restTotal += itemW[i] + 6;
        }
        if (split == 0 || restTotal > fullRoom) {
            return;
        }
        drawLegendRow(graphics, font, colors, labels, itemW, 0, split,
                left, top + 4, panelWidth, firstTotal);
        drawLegendRow(graphics, font, colors, labels, itemW, split, labels.length,
                left, top + 4 + font.lineHeight, panelWidth, restTotal);
    }

    private static void drawLegendRow(GuiGraphics graphics, Font font, int[] colors,
            Component[] labels, int[] itemW, int from, int to, int left, int y, int panelWidth,
            int rowTotal) {
        int x = left + panelWidth - G_PADDING - rowTotal;
        for (int i = from; i < to; i++) {
            graphics.fill(x, y, x + G_LEGEND_SQUARE, y + G_LEGEND_SQUARE, colors[i]);
            UiShapes.ring(graphics, x, y, G_LEGEND_SQUARE, G_LEGEND_SQUARE, UiPalette.CHIP_BORDER);
            graphics.drawString(font, labels[i], x + G_LEGEND_SQUARE + 2, y, NodeColors.TEXT, false);
            x += itemW[i] + 6;
        }
    }

    /** Edges of the node-link view, in the colour of the goal they belong to. */
    private static void drawEdges(GuiGraphics graphics, TreeLayout layout) {
        for (PlacedNode parent : layout.nodes()) {
            int line = NodeColors.branch(parent.branch());
            for (RecipeEdge edge : parent.node().children()) {
                PlacedNode child = layout.of(edge.child());
                if (child == null) {
                    continue;
                }
                int startX = parent.x() + TreeLayout.ICON_SIZE / 2;
                int startY = parent.y() + TreeLayout.ICON_SIZE + 2;
                int endX = child.x() + TreeLayout.ICON_SIZE / 2;
                int endY = child.y();
                int midY = startY + (endY - startY) / 2;

                graphics.vLine(startX, startY, midY, line);
                graphics.hLine(Math.min(startX, endX), Math.max(startX, endX), midY, line);
                graphics.vLine(endX, midY, endY, line);
            }
        }
    }

    private static void drawNodes(GuiGraphics graphics, TreeLayout layout) {
        Font font = Minecraft.getInstance().font;
        Map<ItemKey, Availability> availability = PlannerState.availability();

        for (PlacedNode placed : layout.nodes()) {
            RecipeNode node = placed.node();
            int color = NodeColors.forNode(node, availability);
            int x = placed.x();
            int y = placed.y();
            graphics.fill(x - 1, y - 1, x + TreeLayout.ICON_SIZE + 1, y + TreeLayout.ICON_SIZE + 1,
                    0x40000000 | (color & 0x00FFFFFF));
            graphics.renderItem(node.displayStack(), x, y);
                graphics.drawString(font, node.amountLabel(),
                    x, y + TreeLayout.ICON_SIZE + 1, color, false);
        }
    }
}
