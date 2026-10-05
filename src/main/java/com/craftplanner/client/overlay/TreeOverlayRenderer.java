package com.craftplanner.client.overlay;

import com.craftplanner.Config;
import com.craftplanner.Constants;
import com.craftplanner.client.Keybinds;
import com.craftplanner.client.OverlayState;
import com.craftplanner.client.UiPalette;
import com.craftplanner.client.PlannerState;
import com.craftplanner.client.RecipeLabel;
import com.craftplanner.client.UiShapes;
import com.craftplanner.client.layout.PlacedNode;
import com.craftplanner.client.layout.TreeLayout;
import com.craftplanner.client.resource.Availability;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Draws the craft plan over the game screen (Stage 8).
 *
 * <p>Two layouts are available, picked by {@code overlay.mode} in the client
 * config (Stage 10):</p>
 * <ul>
 *   <li>{@code "tree"} (default) — a compact nested list pinned to the left,
 *       each element drawn with its own item icon and connector lines showing
 *       the hierarchy. A row is exactly as tall as its icon, and a node's
 *       vertical spine leaves the <em>bottom centre</em> of its icon, so the
 *       branches look like they grow out of the items instead of running down
 *       beside them. Roots are the items the player added; every intermediate
 *       craft becomes a subassembly group listing the materials it needs.
 *       Connector lines belong to nested ingredients only: a root has no
 *       elbow of its own, because there is no parent line to hook onto.</li>
 *   <li>{@code "graph"} — the classic node-link craft tree of Stage 8.</li>
 * </ul>
 *
 * <p>The bottom of both panels carries the shopping list: one icon per raw
 * material with its <em>total</em> demand across all recipes, coloured by
 * availability (green / amber / red) and wrapped into up to three lines. There
 * are no labels — the icon is the label — so the block stays narrow and no
 * text has to be cut. When the materials do not fit, the last line ends with
 * {@code +N}.</p>
 *
 * <p>Both render on {@link RenderGuiEvent.Post}, on top of the vanilla HUD, and
 * only when the overlay is enabled and the planner is not empty. The tree is
 * rebuilt from {@link PlannerState#graph()} every frame, so it reflects adds
 * and removes immediately without any extra bookkeeping.</p>
 *
 * <p>Density is controlled by {@code overlay.scale} (see
 * {@link #overlayScale()}): the whole panel content — title, rows, footer — is
 * laid out in fixed layout pixels and then drawn through scaled poses, so
 * icons, text and connector lines shrink together and the tree keeps its
 * proportions. That is the only way to make the text smaller — Minecraft's
 * font is a 9 px bitmap and cannot be rendered below its native size without
 * scaling. An auto-sized panel is additionally scaled down to fit the screen
 * instead of being clipped, so a wide tree never loses its right-hand text.</p>
 *
 * <p>Where the panel lives, how large it is and how dense its text is are the
 * player's, not the mod's: {@code O} ({@link Keybinds#EDIT_LAYOUT}) opens a
 * layout editor — {@code OverlayLayoutScreen}, a transparent screen that frees
 * the mouse from the game — in which the panel is dragged, its bottom-right
 * corner resized, its text grown or shrunk with the {@code +} and {@code -}
 * keys, and the result is committed to {@link OverlayPlacement} and the client
 * config. The wheel keeps scrolling the plan in that mode, as it does in the
 * HUD: a panel is arranged against the rows the player can actually see. A
 * pinned panel then keeps the text size it was given and clips its content,
 * because the player has already answered "it does not fit" by making the window
 * larger.</p>
 *
 * <p>A machine pack's tree can be a hundred rows deep, so every row that has
 * children carries a − / + chip that folds its branch away
 * ({@link TreeCollapse}). Both the chips and the "this item can be crafted
 * differently" badges live in a fixed-size rail on the panel's right edge —
 * never in the scaled tree, where a 9 px glyph would shrink to two pixels —
 * and they are clicked through a screen ({@code K} or {@code O}), because only
 * there does the player have a cursor; the scrollbar is grabbed the same way.
 * Folding is a view decision: the hidden rows still count in the shopping list
 * and the totals. Connector lines take one colour per planned item
 * ({@link NodeColors#branch}) so a nested sub-assembly can be followed back to
 * the goal it belongs to.</p>
 */
@EventBusSubscriber(modid = Constants.MODID, value = Dist.CLIENT)
public final class TreeOverlayRenderer {

    private static final int MARGIN = 8;
    private static final int PADDING = 5;
    private static final int TITLE_H = 12;
    private static final int ROW_H = 16;
    private static final int ICON = 16;
    private static final int INDENT = 14;
    /** Left offset of the first icon; the rest is depth × {@link #INDENT}. */
    private static final int ICON_PAD = 2;
    /**
     * Gap between a row's icon and its label. The fold and badge controls live
     * in the screen-space rail now, so the label only needs breathing room —
     * every pixel not spent on controls belongs to the text.
     */
    private static final int TEXT_GAP = 4;
    /** Gap between the last tree row and the footer block. */
    private static final int FOOTER_GAP = 3;
    /** Gap between two shopping-list entries. */
    private static final int FOOTER_ENTRY_GAP = 6;
    /**
     * Slack kept by the wrap. The content width is exact in layout pixels but
     * rounded in screen pixels, so two pixels are left free rather than risk
     * the last icon of a line being cut by the panel edge.
     */
    private static final int FOOTER_SAFE = 2;
    /** Height of one text line of the footer (9 px glyphs + 2 px leading). */
    private static final int FOOTER_TEXT_H = 11;
    /** Height of one shopping-list line (icon + 2 px leading). */
    private static final int FOOTER_LINE_H = ICON + 2;
    /**
     * Cap on shopping-list lines. Three lines of 16 px icons already cost more
     * vertical space than the tree usually can spare; anything beyond that is
     * reported as {@code +N} and stays reachable through the K window.
     */
    private static final int MAX_FOOTER_LINES = 3;
    /**
     * Floor for the automatic "shrink to fit the screen" scale. It has to stay
     * below the smallest configured density ({@code overlay.scale} allows 0.1),
     * otherwise the shrink-to-fit would scale a deliberately tiny panel back
     * up; below this a very narrow window clips instead.
     */
    private static final float MIN_SCALE = 0.08f;
    /**
     * Floor for the automatic "shrink to fit" of the graph layout. Below this
     * the node-link view is unreadably small, and the overlay falls back to
     * the scrollable list (tree) layout instead of squeezing the text further.
     */
    private static final float MIN_READABLE_FIT = 0.6f;
    /** Folded chips are painted amber so a plan with collapsed parts is obvious. */
    private static final int MARKER_FOLDED_BG = UiPalette.MARKER_FOLDED_BG;
    private static final int MARKER_FOLDED_TEXT = UiPalette.MARKER_FOLDED_TEXT;
    /**
     * Control rail geometry, in <em>screen</em> pixels: chips keep this size
     * whatever the panel density, which is the whole point — the inline badges
     * they replace were two pixels tall at the default scale.
     */
    private static final int CHIP_H = 11;
    private static final int FOLD_CHIP_W = 11;
    private static final int RAIL_GAP = 3;
    private static final int CHIP_TEXT_PAD = 2;
    /**
     * Row pitch from which the rail can carry its text again: one plate per row
     * without touching the row above, so every row shows its own {@code -} or
     * {@code 2/3 ▾}. Below it only the row under the cursor gets a plate.
     */
    private static final int ROOMY_PITCH = CHIP_H + 1;
    /** Width of a rail column that holds nothing but a mark. */
    private static final int MARK_COL_W = 7;
    /**
     * Size of a rest mark: it is squeezed into the row's own pitch, which is what
     * keeps marks from ever landing on one another however tight the rows are.
     */
    private static final int MARK_MIN = 2;
    private static final int MARK_MAX = 5;
    /** Zone kept at the panel's right edge for the scrollbar track. */
    private static final int SCROLL_ZONE = 5;
    private static final int PANEL_BG = UiPalette.HUD_PANEL_BG;
    private static final int PANEL_BORDER = UiPalette.HUD_PANEL_BORDER;
    private static final int MUTED = UiPalette.HUD_MUTED;
    private static final int MARK_FOLDED = UiPalette.MARK_FOLDED;
    private static final int MARK_NEUTRAL = UiPalette.MARK_NEUTRAL;
    private static final int MARK_CUT = UiPalette.MARK_CUT;
    /** Scrollbar: quiet until the cursor is over it or it is being dragged. */
    private static final int SCROLL_BAR = UiPalette.SCROLL_BAR;
    private static final int SCROLL_BAR_HOT = UiPalette.SCROLL_BAR_HOT;
    /**
     * Extra pixels around the 2 px bar that still count as a hit. A bar that
     * thin cannot be aimed at with a game cursor, while the grab itself must not
     * reach into the rows, or it would steal clicks from the fold markers.
     */
    private static final int SCROLL_HIT = 3;

    /** Vertical scroll of the tree layout, in rows (tree mode only). */
    private static int treeScroll;
    /**
     * Cached node-link layout (graph mode). Recomputing it every frame is
     * pure waste: the coordinates only change when the tree is rebuilt, so
     * the cache is keyed by {@link PlannerState#graphVersion()}.
     */
    private static TreeLayout cachedLayout;
    private static long cachedLayoutVersion = -1;
    /**
     * Screen rectangles of the fold markers drawn last frame, in the same
     * GUI-scaled pixels the panels are positioned in. The markers live in the
     * HUD, which has no cursor of its own, so the screens that do — the layout
     * editor and the recipe list — ask this list what was clicked.
     */
    private static final List<FoldHit> foldHits = new ArrayList<>();
    /**
     * Screen rectangles of the "this item has several recipes" badges, same
     * mechanism as {@link #foldHits}: the click is delivered by whichever screen
     * currently owns the cursor.
     */
    private static final List<ChoiceHit> choiceHits = new ArrayList<>();

    /** A clickable badge: the item whose recipe is chosen, and its row center. */
    private record ChoiceHit(RecipeNode node, int x, int w, int centerY) {
    }

    /** A clickable fold control: the branch it folds, and its row center. */
    private record FoldHit(String path, int x, int w, int centerY) {
    }

    /**
     * Branch key of the fold control at {@code (mx, my)}, or {@code null}.
     * At small row pitches a row is a couple of pixels tall, so the column is
     * aimed by the <em>nearest</em> row center rather than by a plate rectangle —
     * a rectangle test there would let one click resolve to whichever neighbour
     * happened to be drawn last.
     */
    public static String hitFold(int mx, int my) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (FoldHit hit : foldHits) {
            if (mx < hit.x || mx > hit.x + hit.w) {
                continue;
            }
            int dist = Math.abs(my - hit.centerY);
            if (dist < bestDist) {
                bestDist = dist;
                best = hit.path;
            }
        }
        return bestDist <= CHIP_H ? best : null;
    }

    /**
     * The node whose badge is at {@code (mx, my)}, or {@code null}. Same
     * nearest-center rule as {@link #hitFold}: the picker must open for the row
     * the cursor is closest to, even where two marks touch.
     */
    public static RecipeNode hitChoice(int mx, int my) {
        RecipeNode best = null;
        int bestDist = Integer.MAX_VALUE;
        for (ChoiceHit hit : choiceHits) {
            if (mx < hit.x || mx > hit.x + hit.w) {
                continue;
            }
            int dist = Math.abs(my - hit.centerY);
            if (dist < bestDist) {
                bestDist = dist;
                best = hit.node;
            }
        }
        return bestDist <= CHIP_H ? best : null;
    }

    /** Where the last drawn frame put the scrollbar, {@code null} when the plan fits. */
    private static ScrollTrack scrollTrack;
    /** True while one of the screens holds that scrollbar. */
    private static boolean scrollGrab;

    /**
     * The scrollbar in screen pixels, with the mapping from a cursor row back to a
     * scroll offset: the thumb is as tall as the visible part of the plan, so the
     * free track height is the distance its whole range is spread over.
     */
    private record ScrollTrack(int x, int y, int w, int h, int barH, int maxScroll) {
        boolean contains(int mx, int my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }

        /** Rows to scroll so the middle of the thumb lands under {@code my}. */
        int scrollAt(int my) {
            int travel = Math.max(1, h - barH);
            int offset = Math.round((my - y - barH / 2f) * maxScroll / travel);
            return Math.max(0, Math.min(maxScroll, offset));
        }
    }

    /**
     * Takes a click on the scrollbar: the thumb jumps under the cursor and stays
     * there until the drag is released, the way a browser track behaves.
     *
     * @return true when the point was the scrollbar and the click is consumed
     */
    public static boolean grabScroll(int mx, int my) {
        if (scrollTrack == null || !scrollTrack.contains(mx, my)) {
            return false;
        }
        scrollGrab = true;
        treeScroll = scrollTrack.scrollAt(my);
        return true;
    }

    /** Follows the cursor with the thumb while a grab is live. */
    public static void dragScroll(int my) {
        if (scrollTrack != null) {
            treeScroll = scrollTrack.scrollAt(my);
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
     * Scrolls the tree by {@code rows} rows; the exact clamp is applied when it
     * is drawn. Exposed for the screens that own the mouse wheel — while one is
     * open the HUD no longer receives it, and the layout editor needs the
     * scroll to see the part of the plan it is sizing.
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
        // longer on screen. renderTree re-arms it when the plan does not fit.
        scrollTrack = null;
        // Same reason for the drawn rectangle: a panel left over from a previous
        // frame would keep answering the wheel and the hits where no panel is.
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
            renderTree(event, mc, graph);
        }
    }

    /**
     * Mouse wheel scrolls the left-side tree while it is on screen and the
     * cursor is over the panel, so it does not steal scrolling elsewhere
     * (chat, inventories). The exact clamp is applied when the tree is drawn.
     *
     * <p>Screens skip this entirely: the layout editor routes the wheel
     * through its own {@code mouseScrolled}, and vanilla fires this event
     * <em>besides</em> it — without the skip every notch would move the plan
     * twice. The modals (picker, list, popup) get the same skip, which also
     * stops their dimmed background from scrolling behind.</p>
     */
    @SubscribeEvent
    public static void onMouseScrolled(InputEvent.MouseScrollingEvent event) {
        if (OverlayPlacement.editing() || !OverlayState.isVisible()
                || Minecraft.getInstance().screen != null) {
            return;
        }
        double mx = event.getMouseX();
        double my = event.getMouseY();
        if (!OverlayPlacement.overPanel((int) mx, (int) my)) {
            return;
        }
        if (!"tree".equals(Config.overlayMode())) {
            return;
        }
        treeScroll += event.getScrollDeltaY() > 0 ? -1 : 1;
    }

    // ---------------------------------------------------------------------
    // Tree (nested list) layout
    // ---------------------------------------------------------------------

    /** One visible row of the tree. */
    private static final class Row {
        final RecipeNode node;
        final int depth;
        /** Fold key of this row, {@code null} when the node has no children. */
        final String path;
        /** Index of the planned item this row belongs to — colours its lines. */
        final int branch;
        /** Recipes that produce this item; more than one makes the badge appear. */
        final int options;
        /** 1-based position of the chosen recipe among {@link #options}. */
        final int selected;
        /** Row index of the last *direct* child (for the vertical spine). */
        int lastChildIndex;
        /** Rows hidden because this branch is folded; 0 when expanded. */
        int hiddenRows;

        Row(RecipeNode node, int depth, String path, int branch) {
            this.node = node;
            this.depth = depth;
            this.path = path;
            this.branch = branch;
            // Counted from the parts rather than through node.options(), which
            // copies the whole list: this runs for every row of every frame.
            this.options = node.recipe() == null ? 0 : node.alternatives().size() + 1;
            this.selected = node.selectedRecipeIndex() + 1;
            this.lastChildIndex = -1;
        }

        /** True when the row's own children are drawn below it. */
        boolean expands(int index) {
            return lastChildIndex > index;
        }
    }

    /**
     * Flattens the tree into rows, skipping the children of a folded branch.
     *
     * @return the row index the node was drawn at, so the parent can find the
     *         last row of its children for the spine
     */
    private static int buildRows(RecipeNode node, int depth, String parentPath,
            int branch, List<Row> out) {
        int myIndex = out.size();
        List<RecipeEdge> children = node.children();
        // Only a node with children can be folded, so the allocating path key is
        // built for exactly the rows that need one.
        String path = children.isEmpty() ? null : pathOf(parentPath, node);
        Row row = new Row(node, depth, path, branch);
        out.add(row);
        if (path != null && TreeCollapse.isFolded(path)) {
            row.hiddenRows = countRows(children);
            row.lastChildIndex = myIndex;
            return myIndex;
        }
        for (int i = 0; i < children.size(); i++) {
            int childIndex = buildRows(children.get(i).child(), depth + 1, path, branch, out);
            if (i == children.size() - 1) {
                row.lastChildIndex = childIndex;
            }
        }
        if (row.lastChildIndex < 0) {
            row.lastChildIndex = myIndex; // no children: spine not drawn
        }
        return myIndex;
    }

    /**
     * Key of a branch: the chain of items leading to it. The engine merges equal
     * ingredients into one child, so items are unique among siblings and the
     * chain keeps pointing at the same branch across rebuilds — see
     * {@link TreeCollapse}.
     */
    private static String pathOf(String parentPath, RecipeNode node) {
        return parentPath == null ? node.item().toString() : parentPath + "/" + node.item();
    }

    /** Rows a folded branch would draw, children included. */
    private static int countRows(List<RecipeEdge> children) {
        int total = 0;
        for (RecipeEdge edge : children) {
            total += 1 + countRows(edge.child().children());
        }
        return total;
    }

    /** Left edge of the icon of a node at {@code depth}. */
    private static int iconX(int depth) {
        return ICON_PAD + depth * INDENT;
    }

    /**
     * X of the vertical spine that carries a node's children. It sits on the
     * <em>centre</em> of the node's icon, so the line reads as leaving the
     * bottom of the icon rather than running down beside it.
     */
    private static int spineX(int depth) {
        return iconX(depth) + ICON / 2;
    }

    private static int rowTop(int index, int scroll, int contentTop) {
        return contentTop + (index - scroll) * ROW_H;
    }

    private static int rowCenterY(int index, int scroll, int contentTop) {
        return rowTop(index, scroll, contentTop) + ROW_H / 2;
    }

    /** Bottom edge of the icon of a row, i.e. where its spine starts. */
    private static int iconBottomY(int index, int scroll, int contentTop) {
        return rowTop(index, scroll, contentTop) + (ROW_H - ICON) / 2 + ICON;
    }

    /**
     * Density of the overlay content: {@code overlay.scale} of the config, or
     * the value the mouse wheel produced in the layout editor. Clamped to the
     * range the config allows, so a hand-edited file cannot scale the UI away.
     */
    private static float overlayScale() {
        return (float) Math.max(0.1, Math.min(1.0, OverlayPlacement.density()));
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

    private static void renderTree(RenderGuiEvent.Post event, Minecraft mc, CraftGraph graph) {
        Font font = mc.font;
        GuiGraphics graphics = event.getGuiGraphics();

        List<Row> rows = new ArrayList<>();
        List<RecipeNode> roots = graph.roots();
        foldHits.clear();
        choiceHits.clear();
        for (int i = 0; i < roots.size(); i++) {
            buildRows(roots.get(i), 0, null, i, rows);
        }
        if (rows.isEmpty()) {
            return;
        }

        Map<ItemKey, Availability> availability = PlannerState.availability();

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int availW = screenW - 2 * MARGIN;
        int availH = screenH - 2 * MARGIN;
        int pinX = OverlayPlacement.x();
        int pinY = OverlayPlacement.y();
        int pinW = OverlayPlacement.w();
        int pinH = OverlayPlacement.h();

        // Widest row decides the panel width (in layout pixels). The control
        // rail is measured here too, but in screen pixels and from ALL rows —
        // sizing it from the visible ones would make the panel jitter while
        // the player scrolls.
        int maxRowW = 0;
        boolean hasFold = false;
        int badgeColW = 0;
        for (Row r : rows) {
            maxRowW = Math.max(maxRowW,
                    iconX(r.depth) + ICON + TEXT_GAP + font.width(rowLabel(r)));
            if (r.path != null) {
                hasFold = true;
            }
            String badge = badgeText(r);
            if (badge != null) {
                badgeColW = Math.max(badgeColW, font.width(badge));
            }
        }
        // The rail's shape depends on the row pitch, and the pitch depends on the
        // panel width, so the reserve is taken from the <em>requested</em> density:
        // neither sizing path can end below it (a pinned panel keeps it, an
        // auto-sized one only shrinks), so the worst the estimate can do is leave
        // the panel a few pixels wider than a dense rail needs.
        float density = overlayScale();
        int railW = railWidth(hasFold, badgeColW, rowPitch(density) >= ROOMY_PITCH);
        // The footer sets a floor for the width: the shopping list wraps, but
        // the hotkey line is a single line and has to fit — nothing is ever
        // cut or pushed outside the panel.
        int footerMinW = Math.max(widestEntry(availability, font),
                font.width(keysHint("overlay.craftplanner.keys.short")));

        // Density comes first: an auto-sized panel shrinks to stay on screen,
        // while a panel the player sized with the mouse keeps its text size and
        // simply clips — resizing is the player's answer to "it does not fit".
        // The rail counts against the fit budget too, so a full-width panel
        // never loses its chips to the screen edge.
        int autoContentW = Math.max(maxRowW, footerMinW);
        float scale;
        int contentW;
        int panelW;
        if (pinW == OverlayPlacement.AUTO) {
            scale = Math.max(MIN_SCALE,
                    Math.min(density, (float) availW / (autoContentW + 2 * PADDING + railW)));
            contentW = autoContentW;
            panelW = Math.min(availW,
                    Math.max(1, Math.round((contentW + 2 * PADDING) * scale)) + railW);
        } else {
            scale = density;
            panelW = Math.min(availW, pinW);
            contentW = Math.max(24, Math.round((panelW - railW) / scale) - 2 * PADDING);
        }

        Footer footer = buildFooter(availability, font, contentW,
                hotkeyLine(font, contentW));
        int footerH = footer.height();

        int layoutH = TITLE_H + rows.size() * ROW_H + 2 * PADDING
                + (footerH > 0 ? FOOTER_GAP + footerH : 0);
        int panelH = pinH == OverlayPlacement.AUTO
                ? Math.min(availH, Math.max(1, Math.round(layoutH * scale)))
                : Math.min(availH, pinH);

        int left = clampToScreen(pinX, 0, screenW - panelW);
        int top = clampToScreen(pinY, 0, screenH - panelH);
        int pad = Math.max(1, Math.round(PADDING * scale));
        int rowPx = rowPitch(scale);
        int footerPx = Math.round(footerH * scale);
        int gapPx = footerH > 0 ? Math.round(FOOTER_GAP * scale) : 0;

        int contentLeft = left + pad;
        int contentTop = top + pad + Math.round(TITLE_H * scale);
        int footerTop = top + panelH - pad - footerPx;
        int contentBottom = Math.max(contentTop + rowPx, footerTop - gapPx);
        int visibleRows = Math.max(1, (contentBottom - contentTop) / rowPx);
        int maxScroll = Math.max(0, rows.size() - visibleRows);
        treeScroll = Math.max(0, Math.min(treeScroll, maxScroll));

        UiShapes.panel(graphics, left, top, panelW, panelH, PANEL_BG,
                UiPalette.HUD_BORDER_OUTER, PANEL_BORDER);
        // Header band under the title line, closed by the shared accent
        // underline. At small densities the whole header is a few pixels —
        // below five there is nothing to band, and painting it would eat the
        // first tree row.
        if (contentTop - top >= 5) {
            graphics.fill(left + 2, top + 2, left + panelW - 2, contentTop - 1,
                    UiPalette.HUD_HEADER_BG);
            graphics.fill(left + 2, contentTop - 1, left + panelW - 2, contentTop,
                    UiPalette.HUD_ACCENT_LINE);
        }

        PoseStack pose = graphics.pose();

        // Title: same scaled font as the rest of the panel, so it cannot look
        // (or be) larger than the tree it heads.
        pose.pushPose();
        pose.translate(left + pad, top + Math.round(2 * scale), 0);
        pose.scale(scale, scale, 1f);
        graphics.drawString(font, Component.translatable("overlay.craftplanner.title"),
                0, 0, NodeColors.TEXT, false);
        drawTreeLegend(graphics, font, contentW);
        pose.popPose();

        int firstVisible = treeScroll;
        int lastVisible = Math.min(rows.size() - 1, treeScroll + visibleRows - 1);

        // Clipped to the panel interior, not to the nominal content width: the
        // two differ by a rounding pixel, and a label must never be cut by it.
        graphics.enableScissor(left + 1, contentTop, left + panelW - 1, contentBottom);

        // Inside the pose the origin is the top-left of the content area, so all
        // layout helpers below work in layout pixels starting at zero.
        pose.pushPose();
        pose.translate(contentLeft, contentTop, 0);
        pose.scale(scale, scale, 1f);

        final int originY = 0;

        // Connector lines first (so icons paint on top). Every planned item gets
        // its own colour: nested machine recipes cross the panel, and one grey
        // web makes it impossible to say which sub-assembly belongs to which
        // goal.

        // A parent's spine is one continuous segment from below its icon to the
        // last child's row center, so it is drawn over <em>all</em> rows and
        // clipped to the visible window: the previous per-visible-row pass made
        // every ancestor line vanish the moment its owner scrolled above the
        // panel. The scissor already hides the off-screen ends; the clip only
        // keeps the int range small and skips non-intersecting segments.
        int viewBottom = (int) Math.ceil((contentBottom - contentTop) / (float) scale);
        for (int p = 0; p < rows.size(); p++) {
            Row parent = rows.get(p);
            if (!parent.expands(p)) {
                continue;
            }
            int start = Math.max(iconBottomY(p, treeScroll, originY), originY);
            int end = Math.min(rowCenterY(parent.lastChildIndex, treeScroll, originY), viewBottom);
            if (end - start >= 1) {
                graphics.vLine(spineX(parent.depth), start, end,
                        NodeColors.branch(parent.branch));
            }
        }

        for (int r = firstVisible; r <= lastVisible; r++) {
            Row row = rows.get(r);
            // Elbow running from the parent's spine into this row's icon.
            // Roots have no parent, hence no elbow and no stub to their left.
            if (row.depth > 0) {
                graphics.hLine(spineX(row.depth - 1), iconX(row.depth) - 1,
                        rowCenterY(r, treeScroll, originY), NodeColors.branch(row.branch));
            }
        }

        // Icons + labels. The fold and badge controls are NOT drawn here — they
        // live in the screen-space rail below, where density cannot shrink them
        // to two pixels. The panel is sized from the widest row, so the room
        // left of a label is exactly its own width — no row is ever trimmed.
        int labelRoom = contentW - 4;
        for (int r = firstVisible; r <= lastVisible; r++) {
            Row row = rows.get(r);
            int yTop = rowTop(r, treeScroll, originY);
            int ix = iconX(row.depth);
            int textX = ix + ICON + TEXT_GAP;

            graphics.renderItem(row.node.displayStack(), ix, yTop + (ROW_H - ICON) / 2);

            String text = trimToWidth(font, rowLabel(row), labelRoom - textX);
            int color = NodeColors.forNode(row.node, availability);
            graphics.drawString(font, text, textX, yTop + (ROW_H - font.lineHeight) / 2,
                    color, false);
        }

        pose.popPose();
        graphics.disableScissor();

        drawControlRail(graphics, mc, font, rows, left, panelW, contentLeft, contentTop,
                contentBottom, firstVisible, lastVisible, rowPx, hasFold, badgeColW);

        showChoiceTooltip(graphics, mc, font);
        showFoldTooltip(graphics, mc, font);

        // Footer: own scaled pose, clipped to the panel, so neither the icons
        // nor the numbers can ever spill into the game view.
        if (footerH > 0) {
            UiShapes.divider(graphics, left + 2, left + panelW - 2,
                    footerTop - Math.max(1, gapPx / 2), UiPalette.HUD_SECTION_DIVIDER);
            graphics.enableScissor(left, footerTop, left + panelW, top + panelH - 1);
            pose.pushPose();
            pose.translate(left + pad, footerTop, 0);
            pose.scale(scale, scale, 1f);
            drawFooter(graphics, font, footer, contentW);
            pose.popPose();
            graphics.disableScissor();
            if (!showTailTooltip(graphics, font, footer, mc, left + pad, footerTop, scale)) {
                showMaterialTooltips(graphics, font, footer, mc, left + pad, footerTop, scale);
            }
        }

        if (maxScroll > 0) {
            int trackTop = contentTop;
            int trackH = contentBottom - contentTop;
            int barH = Math.max(8, trackH * visibleRows / rows.size());
            int barY = trackTop + (trackH - barH) * treeScroll / maxScroll;
            int barX = left + panelW - 4;
            // A quiet 3 px track makes the bar's existence obvious; the thumb
            // on top brightens under the cursor.
            graphics.fill(barX, trackTop, barX + 3, trackTop + trackH, UiPalette.SCROLL_TRACK);
            // Recorded in screen pixels for the screens that receive clicks.
            scrollTrack = new ScrollTrack(barX - SCROLL_HIT, trackTop,
                    3 + 2 * SCROLL_HIT, trackH, barH, maxScroll);
            boolean hot = scrollGrab || scrollTrack.contains(mouseX(mc), mouseY(mc));
            graphics.fill(barX, barY, barX + 3, barY + barH, hot ? SCROLL_BAR_HOT : SCROLL_BAR);
        }

        OverlayPlacement.reportDrawn(left, top, panelW, panelH);
        drawEditChrome(graphics, mc, font, left, top, panelW, panelH, screenW, screenH);
    }

    /**
     * Colour legend of the tree rows, right-aligned on the title line, drawn in
     * the title's scaled pose. Same entries as the graph view, so switching
     * {@code overlay.mode} never leaves the player with a colour nobody named.
     * Dropped entirely when it would collide with the title — a missing legend
     * is better than an unreadable one, and the panel width is the player's.
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
        int[] itemW = new int[labels.length];
        int total = 0;
        for (int i = 0; i < labels.length; i++) {
            itemW[i] = G_LEGEND_SQUARE + 2 + font.width(labels[i]);
            total += itemW[i] + gap;
        }
        int titleW = font.width(Component.translatable("overlay.craftplanner.title"));
        if (titleW + gap + total > contentW) {
            return;
        }
        int x = contentW - total;
        for (int i = 0; i < labels.length; i++) {
            graphics.fill(x, 2, x + G_LEGEND_SQUARE, 2 + G_LEGEND_SQUARE, colors[i]);
            graphics.drawString(font, labels[i], x + G_LEGEND_SQUARE + 2, 0, NodeColors.TEXT, false);
            x += itemW[i] + gap;
        }
    }

    private static String label(RecipeNode node) {
        return node.item().name().getString() + " ×" + node.required();
    }

    /** The row label, with the number of rows a folded branch is hiding. */
    private static String rowLabel(Row row) {
        return row.hiddenRows > 0
                ? label(row.node) + " (+" + row.hiddenRows + ")"
                : label(row.node);
    }

    /**
     * The badge of a row — its way of saying "this item can be made differently,
     * or not made at all" and showing which way is picked — or {@code null} when
     * the item has no recipe or the badges are switched off.
     *
     * <p>{@code used / total ▾} marks an item with several ways, {@code ✂} one the
     * player cut the tree at, and a bare {@code ▾} an item with a single way: the
     * picker that any of them opens also carries the cut switch, so the one-recipe
     * leather of a book plan is reachable and not only the multi-recipe paper.</p>
     */
    private static String badgeText(Row row) {
        if (!Config.badgesEnabled()) {
            return null;
        }
        if (PlannerState.isCut(row.node.item())) {
            return "✂";
        }
        if (row.options > 1) {
            return row.selected + "/" + row.options + " ▾";
        }
        return row.options == 1 ? "▾" : null;
    }

    /** Row pitch in screen pixels at the given panel density. */
    private static int rowPitch(float scale) {
        return Math.max(1, Math.round(ROW_H * scale));
    }

    /**
     * Width of the control rail in screen pixels, including the scrollbar zone.
     * A rail that shows its text needs a column per text; one that shows only
     * marks needs the two narrow columns.
     */
    private static int railWidth(boolean hasFold, int badgeColW, boolean roomy) {
        int width = SCROLL_ZONE + 1;
        if (hasFold) {
            width += roomy ? FOLD_CHIP_W : MARK_COL_W;
        }
        if (badgeColW > 0) {
            width += (hasFold ? RAIL_GAP : 0)
                    + (roomy ? badgeColW + 2 * CHIP_TEXT_PAD : MARK_COL_W);
        }
        return width;
    }

    /**
     * The control rail: the fold and recipe controls of the visible rows, pinned
     * to the panel's right edge in <em>screen</em> pixels.
     *
     * <p>One rule decides the whole design: a rest state never uses the bitmap
     * font, because that font is nine pixels tall whatever the panel density, so
     * at the default two-pixel row pitch the {@code -} of one row landed on the
     * {@code 2/3} of its neighbour and neither read as anything. At rest the rail
     * is geometry sized to the row — a caret for a branch (amber pointing right
     * while it is closed, grey pointing down while it is open) and a square for a
     * recipe (filled in its branch colour where several recipes exist, hollow
     * where there is only one, red where the player cut the branch). A mark that
     * cannot be taller than its own row cannot overlap another mark.</p>
     *
     * <p>The words come back in two places: once the pitch fits a whole plate per
     * row, every row carries its {@code -}/{@code +} and {@code 2/3 ▾}; below
     * that, the single row under the cursor does, with its whole row lit — which
     * is the answer to "which label is this control on", and why one plate per
     * frame can never collide with another. The badge tooltip names the recipe
     * behind a mark, so nothing is asked of a two-pixel figure.</p>
     *
     * <p>Backgrounds paint before glyphs so no glyph can end up under a
     * neighbouring plate; the rows whose marks fall inside the focused plate are
     * left unmarked for that frame for the same reason.</p>
     */
    private static void drawControlRail(GuiGraphics graphics, Minecraft mc, Font font,
            List<Row> rows, int left, int panelW, int contentLeft, int contentTop,
            int contentBottom, int firstVisible, int lastVisible, int rowPx,
            boolean hasFold, int badgeColW) {
        foldHits.clear();
        choiceHits.clear();
        if (contentBottom - contentTop < CHIP_H) {
            return; // too little panel to hold a whole plate
        }
        boolean roomy = rowPx >= ROOMY_PITCH;
        int foldW = roomy ? FOLD_CHIP_W : MARK_COL_W;
        int badgeW = roomy ? badgeColW + 2 * CHIP_TEXT_PAD : MARK_COL_W;
        int railRight = left + panelW - SCROLL_ZONE;
        int badgeX = railRight - badgeW;
        int foldX = badgeColW > 0 ? badgeX - RAIL_GAP - foldW : railRight - foldW;
        int railLeft = hasFold ? foldX : (badgeColW > 0 ? badgeX : railRight);
        int stripRight = railLeft - 2;
        // A plate is always the same readable size, even when the column that
        // hosts it at rest is narrower: it is centred on that column.
        int badgePlateW = badgeColW + 2 * CHIP_TEXT_PAD;
        int badgePlateX = railRight - badgePlateW;
        int foldPlateX = foldX - (FOLD_CHIP_W - foldW) / 2;
        // Which of the two columns the cursor is in; with no badges there is one.
        int splitX = badgeColW > 0 ? badgeX : railRight;
        int mark = Math.max(MARK_MIN, Math.min(MARK_MAX, rowPx - 1));
        // Only a screen owns a real cursor; in the bare HUD the pointer is
        // pinned to the centre and hover would light a random row.
        boolean cursor = mc.screen != null;
        int mx = mouseX(mc);
        int my = mouseY(mc);
        boolean inRail = cursor && mx >= railLeft - 2 && mx < railRight
                && my >= contentTop && my < contentBottom;
        // The focused row is found by its <em>band</em>, not by a rectangle: at a
        // two-pixel pitch a plate covers five rows, and the one the cursor is
        // nearest is the one it aims at.
        int hot = inRail ? Math.min(lastVisible, firstVisible + (my - contentTop) / rowPx) : -1;
        int hotYc = hot < 0 ? Integer.MIN_VALUE
                : contentTop + Math.round((hot - treeScroll + 0.5f) * rowPx);

        for (int pass = 0; pass < 2; pass++) {
            for (int r = firstVisible; r <= lastVisible; r++) {
                Row row = rows.get(r);
                String badge = badgeText(row);
                boolean fold = row.path != null;
                if (!fold && badge == null) {
                    continue;
                }
                int yc = contentTop + Math.round((r - treeScroll + 0.5f) * rowPx);
                int cy = Math.max(contentTop, Math.min(contentBottom - CHIP_H, yc - CHIP_H / 2));
                boolean folded = fold && row.hiddenRows > 0;
                boolean hotFold = fold && r == hot && mx < splitX;
                boolean hotBadge = badge != null && r == hot && !hotFold;
                boolean plateFold = fold && (roomy || hotFold);
                boolean plateBadge = badge != null && (roomy || hotBadge);
                boolean eclipsed = !roomy && hot >= 0 && Math.abs(yc - hotYc) <= CHIP_H / 2 + mark;
                boolean cut = badge != null && PlannerState.isCut(row.node.item());

                if (pass == 0) {
                    if (hotFold || hotBadge) {
                        graphics.fill(contentLeft, yc - rowPx / 2, stripRight,
                                yc + rowPx / 2 + 1, UiPalette.MODAL_ROW_HOVER);
                    }
                    if (plateFold) {
                        UiShapes.chip(graphics, foldPlateX, cy, FOLD_CHIP_W, CHIP_H,
                                folded ? MARKER_FOLDED_BG
                                        : (hotFold ? UiPalette.CHIP_HOVER_BG : UiPalette.CHIP_BG),
                                hotFold ? UiPalette.CHIP_HOVER_BORDER : UiPalette.CHIP_BORDER);
                    }
                    if (plateBadge) {
                        UiShapes.chip(graphics, badgePlateX, cy, badgePlateW, CHIP_H,
                                hotBadge ? UiPalette.CHIP_HOVER_BG : UiPalette.CHIP_BG,
                                hotBadge ? UiPalette.CHIP_HOVER_BORDER : UiPalette.CHIP_BORDER);
                    }
                    if (fold && !plateFold && !eclipsed) {
                        UiShapes.caret(graphics, foldX + (foldW - mark) / 2, yc - mark / 2, mark,
                                folded, folded ? MARK_FOLDED : MARK_NEUTRAL);
                    }
                    if (badge != null && !plateBadge && !eclipsed) {
                        int x = badgeX + (badgeW - mark) / 2;
                        if (cut || row.options > 1) {
                            graphics.fill(x, yc - mark / 2, x + mark, yc - mark / 2 + mark,
                                    cut ? MARK_CUT : NodeColors.branch(row.branch));
                        } else {
                            UiShapes.ring(graphics, x, yc - mark / 2, mark, mark, MARK_NEUTRAL);
                        }
                    }
                    if (fold) {
                        foldHits.add(new FoldHit(row.path, foldX, foldW, yc));
                    }
                    if (badge != null) {
                        choiceHits.add(new ChoiceHit(row.node, badgeX, badgeW, yc));
                    }
                } else {
                    int gy = Math.max(contentTop,
                            Math.min(contentBottom - font.lineHeight, yc - font.lineHeight / 2));
                    if (plateFold) {
                        String glyph = folded ? "+" : "-";
                        graphics.drawString(font, glyph,
                                foldPlateX + (FOLD_CHIP_W - font.width(glyph)) / 2, gy,
                                folded ? MARKER_FOLDED_TEXT : (hotFold
                                        ? NodeColors.TEXT : MARK_NEUTRAL), false);
                    }
                    if (plateBadge) {
                        graphics.drawString(font, badge, badgePlateX + CHIP_TEXT_PAD, gy,
                                hotBadge ? NodeColors.TEXT : NodeColors.branch(row.branch), false);
                    }
                }
            }
        }
    }

    /**
     * Hotkey hint for the footer: the verbose line when it fits, the short
     * {@code N — … · V — … · K — …} when the panel is narrow, nothing at all when
     * even that would be cut. The hint is never trimmed mid-word.
     */
    private static Component hotkeyLine(Font font, int contentW) {
        Component full = keysHint("overlay.craftplanner.keys");
        if (font.width(full) <= contentW) {
            return full;
        }
        Component brief = keysHint("overlay.craftplanner.keys.short");
        return font.width(brief) <= contentW ? brief : null;
    }

    /**
     * A hotkey hint with the <em>current</em> key bindings substituted into its
     * %1$s/%2$s/%3$s/%4$s placeholders, so the footer keeps telling the truth
     * after any rebind in Options → Controls.
     */
    private static Component keysHint(String key) {
        return Component.translatable(key,
                Keybinds.ADD_RECIPE.getKey().getDisplayName(),
                Keybinds.TOGGLE_OVERLAY.getKey().getDisplayName(),
                Keybinds.OPEN_RECIPES.getKey().getDisplayName(),
                Keybinds.EDIT_LAYOUT.getKey().getDisplayName());
    }

    // ---------------------------------------------------------------------
    // Footer: shopping list + totals
    // ---------------------------------------------------------------------

    /** Prepared footer block: wrapped shopping list plus the hotkey line. */
    private static final class Footer {
        final List<List<Map.Entry<ItemKey, Availability>>> lines = new ArrayList<>();
        /** Materials dropped because {@link #MAX_FOOTER_LINES} was reached. */
        int hidden;
        /** The dropped materials, as the tooltip content of the {@code +N} marker. */
        List<Map.Entry<ItemKey, Availability>> hiddenEntries = List.of();
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
                h += FOOTER_TEXT_H;
            }
            return h;
        }
    }

    /** One drawn shopping-list icon: what it stands for and where it was put. */
    private record MaterialIcon(ItemKey key, Availability value, int x, int y) {
    }

    /**
     * Builds the footer for a content width given in layout pixels. The
     * shopping list is sorted by shortage first, so what the player still has
     * to gather is what they see.
     *
     * <p>{@code keys} may be null — the hotkey line is dropped rather than cut
     * when the panel is narrower than even the short hint, which the layout
     * editor can produce by pinning the width or raising the text density. The
     * width check has to test for that first, or {@code Font.width(null)}
     * throws and takes the game down with it.</p>
     */
    private static Footer buildFooter(Map<ItemKey, Availability> availability, Font font,
            int contentW, Component keys) {
        Footer footer = new Footer(keys != null && font.width(keys) <= contentW ? keys : null);
        List<Map.Entry<ItemKey, Availability>> entries = shoppingList(availability);
        wrapShoppingList(footer, entries, font, contentW, 0);
        if (footer.hidden > 0) {
            // Something was dropped: redo the wrap with room for the "+N" tail.
            // The reserve is sized for the largest possible number, so the
            // marker can never be pushed out by its own growth.
            Footer retry = new Footer(footer.keys);
            wrapShoppingList(retry, entries, font, contentW,
                    font.width("+" + entries.size()) + 3);
            footer = retry;
        }
        return footer;
    }

    /** Raw materials, worst shortage first, then largest demand. */
    private static List<Map.Entry<ItemKey, Availability>> shoppingList(
            Map<ItemKey, Availability> availability) {
        List<Map.Entry<ItemKey, Availability>> entries = new ArrayList<>(availability.entrySet());
        entries.sort((a, b) -> {
            int byShortage = Long.compare(b.getValue().missing(), a.getValue().missing());
            return byShortage != 0
                    ? byShortage
                    : Long.compare(b.getValue().required(), a.getValue().required());
        });
        return entries;
    }

    private static int entryWidth(Font font, long amount) {
        return ICON + 1 + font.width(Long.toString(amount)) + FOOTER_ENTRY_GAP;
    }

    /** Narrowest panel that still shows at least one material per line. */
    private static int widestEntry(Map<ItemKey, Availability> availability, Font font) {
        int width = 0;
        for (Availability value : availability.values()) {
            width = Math.max(width, entryWidth(font, value.required()));
        }
        return width;
    }

    /**
     * Greedy wrap of the shopping list into at most {@link #MAX_FOOTER_LINES}
     * lines. {@code reserveLast} is the room kept free at the end of the last
     * line for the {@code +N} marker (0 on the first pass).
     */
    private static void wrapShoppingList(Footer footer,
            List<Map.Entry<ItemKey, Availability>> entries, Font font, int contentW,
            int reserveLast) {
        List<Map.Entry<ItemKey, Availability>> current = new ArrayList<>();
        int used = 0;
        int index = 0;
        while (index < entries.size()) {
            Map.Entry<ItemKey, Availability> entry = entries.get(index);
            int width = entryWidth(font, entry.getValue().required());
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
                footer.tailY = y + (ICON - font.lineHeight) / 2 + 1;
                footer.tailW = font.width("+" + hidden);
                footer.tailH = font.lineHeight;
            }
            y += FOOTER_LINE_H;
        }
        if (footer.keys != null) {
            graphics.drawCenteredString(font, footer.keys, contentW / 2, y + 1, MUTED);
        }
    }

    /**
     * One line of the shopping list: icon, total amount, colour by
     * availability — no names, so nothing has to be trimmed.
     *
     * @return the layout-x where the {@code +N} marker was drawn, {@code -1}
     *         when the line has no marker (used to hover-test the tail tooltip)
     */
    private static int drawShoppingLine(GuiGraphics graphics, Font font, Footer footer,
            List<Map.Entry<ItemKey, Availability>> line, int y, int hidden) {
        int textY = y + (ICON - font.lineHeight) / 2 + 1;
        int x = 0;
        for (Map.Entry<ItemKey, Availability> entry : line) {
            graphics.renderItem(entry.getKey().toStack(), x, y);
            footer.icons.add(new MaterialIcon(entry.getKey(), entry.getValue(), x, y));
            String amount = Long.toString(entry.getValue().required());
            graphics.drawString(font, amount, x + ICON + 1, textY,
                    NodeColors.forAvailability(entry.getValue()), false);
            x += entryWidth(font, entry.getValue().required());
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
            Minecraft mc, int originX, int originY, float scale) {
        if (footer.hidden <= 0 || footer.tailX < 0 || footer.hiddenEntries.isEmpty()) {
            return false;
        }
        double guiScale = mc.getWindow().getGuiScale();
        int mx = (int) (mc.mouseHandler.xpos() / guiScale);
        int my = (int) (mc.mouseHandler.ypos() / guiScale);
        final int pad = 2;
        int x1 = originX + (int) ((footer.tailX - pad) * scale);
        int y1 = originY + (int) ((footer.tailY - pad) * scale);
        int x2 = originX + (int) Math.ceil((footer.tailX + footer.tailW + pad) * scale);
        int y2 = originY + (int) Math.ceil((footer.tailY + footer.tailH + pad) * scale);
        if (mx < x1 || mx > x2 || my < y1 || my > y2) {
            return false;
        }
        List<Component> lines = new ArrayList<>();
        for (Map.Entry<ItemKey, Availability> entry : footer.hiddenEntries) {
            lines.add(entry.getKey().name().copy()
                    .append(Component.literal(" ×" + entry.getValue().required())));
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
     * @param originX screen x of the footer pose the icons were drawn from
     * @param originY screen y of that pose
     * @param scale   scale factor of that pose
     */
    private static void showMaterialTooltips(GuiGraphics graphics, Font font, Footer footer,
            Minecraft mc, int originX, int originY, float scale) {
        if (footer.icons.isEmpty()) {
            return;
        }
        double guiScale = mc.getWindow().getGuiScale();
        int mx = (int) (mc.mouseHandler.xpos() / guiScale);
        int my = (int) (mc.mouseHandler.ypos() / guiScale);
        final int pad = 1;
        for (MaterialIcon icon : footer.icons) {
            int x1 = originX + (int) ((icon.x() - pad) * scale);
            int y1 = originY + (int) ((icon.y() - pad) * scale);
            int x2 = originX + (int) Math.ceil((icon.x() + ICON + pad) * scale);
            int y2 = originY + (int) Math.ceil((icon.y() + ICON + pad) * scale);
            if (mx < x1 || mx > x2 || my < y1 || my > y2) {
                continue;
            }
            showTooltip(graphics, font, mc, materialLines(icon.key(), icon.value()));
            return;
        }
    }

    /**
     * A material named, then what the plan asks of it: the amount the footer
     * prints, plus the part the inventory already covers. Only a shortfall gets
     * its own line — a material already in stock has nothing left to report.
     */
    private static List<Component> materialLines(ItemKey key, Availability value) {
        List<Component> lines = new ArrayList<>();
        lines.add(key.name().copy());
        lines.add(Component.translatable("overlay.craftplanner.material.required",
                Long.toString(value.required())));
        lines.add(Component.translatable("overlay.craftplanner.material.have",
                Long.toString(value.available())));
        if (value.missing() > 0) {
            lines.add(Component.translatable("overlay.craftplanner.material.missing",
                    Long.toString(value.missing())));
        }
        return lines;
    }

    /**
     * Hover feedback for a badge: the number alone does not say which recipe is in
     * use or what the others would ask for, so the tooltip names the machine, the
     * mod and the ingredients, and leads with what clicking does — choose between
     * several ways, open a branch the player cut, or end it.
     *
     * <p>Offered only while one of the planner's screens is open: with no screen
     * the game grabs the cursor and pins it to the centre of the window, and the
     * tooltip would follow a pointer the player cannot see or move.</p>
     */
    private static void showChoiceTooltip(GuiGraphics graphics, Minecraft mc, Font font) {
        if (mc.screen == null || choiceHits.isEmpty() || mc.level == null) {
            return;
        }
        int mx = mouseX(mc);
        int my = mouseY(mc);
        RecipeNode node = hitChoice(mx, my);
        if (node == null || node.recipe() == null) {
            return;
        }
        int total = node.options().size();
        Component head;
        if (PlannerState.isCut(node.item())) {
            head = Component.translatable("overlay.craftplanner.cut.tooltip");
        } else if (total > 1) {
            head = Component.translatable("overlay.craftplanner.choice.tooltip",
                    Integer.toString(node.selectedRecipeIndex() + 1), Integer.toString(total));
        } else {
            head = Component.translatable("overlay.craftplanner.single.tooltip");
        }
        List<Component> lines = new ArrayList<>(
                RecipeLabel.describe(node.recipe(), mc.level.registryAccess()));
        lines.add(0, head);
        showTooltip(graphics, font, mc, lines);
    }

    /**
     * Hover feedback for the fold marks: the caret reads as a state but not as
     * something to click, so the tooltip says what the click does. Same screen
     * requirement as the badge's, and it steps aside when a badge answers the
     * cursor first — one tooltip per frame, the richer one wins.
     */
    private static void showFoldTooltip(GuiGraphics graphics, Minecraft mc, Font font) {
        if (mc.screen == null || mc.level == null) {
            return;
        }
        int mx = mouseX(mc);
        int my = mouseY(mc);
        if (hitChoice(mx, my) != null) {
            return;
        }
        String path = hitFold(mx, my);
        if (path == null) {
            return;
        }
        showTooltip(graphics, font, mc, List.of(Component.translatable(
                TreeCollapse.isFolded(path)
                        ? "overlay.craftplanner.fold.expand"
                        : "overlay.craftplanner.fold.collapse")));
    }

    /**
     * Shows tooltip lines for the point the cursor is at. While a screen is open
     * they are handed to it instead of being drawn here: Minecraft paints the HUD
     * — and so every tooltip the overlay could draw — <em>before</em> the screen,
     * and the planner's screens dim the whole view on top of it, which left the
     * player with a dark box and unreadable text. A screen draws what it was
     * handed as the last thing of its own render pass, above everything it
     * painted, and wraps it to the vanilla tooltip width.
     */
    private static void showTooltip(GuiGraphics graphics, Font font, Minecraft mc,
            List<Component> lines) {
        if (lines.isEmpty()) {
            return;
        }
        if (mc.screen == null) {
            graphics.renderTooltip(font, lines, Optional.empty(), mouseX(mc), mouseY(mc));
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

    private static final int EDIT_BORDER = UiPalette.EDIT_BORDER;
    private static final int EDIT_GRIP = UiPalette.EDIT_GRIP;
    private static final int EDIT_GRIP_HOVER = UiPalette.EDIT_GRIP_HOVER;
    private static final int EDIT_TEXT = UiPalette.EDIT_TEXT;

    /**
     * Editor chrome on top of the finished panel: the outline being dragged, the
     * resize grip in its corner and the one-line instructions. The HUD keeps
     * rendering behind {@code OverlayLayoutScreen}, which is where the input of
     * that mode is handled.
     */
    private static void drawEditChrome(GuiGraphics graphics, Minecraft mc, Font font,
            int left, int top, int panelW, int panelH, int screenW, int screenH) {
        if (!OverlayPlacement.editing()) {
            return;
        }
        UiShapes.ring(graphics, left, top, panelW, panelH, EDIT_BORDER);
        UiShapes.ring(graphics, left + 1, top + 1, panelW - 2, panelH - 2, EDIT_BORDER);

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
        int maxTextW = Math.max(20, screenW - 8);
        String first = trimToWidth(font, Component.translatable("overlay.craftplanner.edit.hint",
                Integer.toString((int) Math.round(OverlayPlacement.liveDensity() * 100))).getString(), maxTextW);
        String second = trimToWidth(font, Component.translatable("overlay.craftplanner.edit.hint2",
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

    /**
     * Cuts text with an ellipsis so it fits the given pixel width — and gives up
     * entirely when not even the ellipsis fits, rather than returning a string
     * that still overflows (same contract as the list layout's trim).
     */
    private static String trimToWidth(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        if (maxWidth < font.width("…")) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() > 1 && font.width(sb + "…") > maxWidth) {
            sb.setLength(sb.length() - 1);
        }
        return sb + "…";
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
     * The layout for the current tree: reused while
     * {@link PlannerState#graphVersion()} does not change, recomputed once
     * after every rebuild.
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
        // The node-link view has no fold markers; leave no clickable boxes of a
        // previous tree frame behind.
        foldHits.clear();
        choiceHits.clear();
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
        float density = overlayScale();
        int panelWidth = pinW == OverlayPlacement.AUTO
                ? Math.min(maxPanelWidth,
                        Math.max(1, Math.round(contentWidth * density)) + 2 * G_PADDING)
                : Math.min(maxPanelWidth, pinW);

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = mc.font;

        // The footer needs the panel width before the height is known: the
        // shopping list wraps into whatever horizontal room it is given.
        Map<ItemKey, Availability> availability = PlannerState.availability();
        int footerLayoutW = Math.max(1, (int) ((panelWidth - 2 * G_PADDING) / density));
        Footer footer = buildFooter(availability, font, footerLayoutW,
                hotkeyLine(font, footerLayoutW));
        int footerSpace = Math.max(G_FOOTER_SPACE, Math.round(footer.height() * density) + 2);

        int panelHeight = pinH == OverlayPlacement.AUTO
                ? Math.min(maxPanelHeight,
                        Math.max(1, Math.round(contentHeight * density)) + 2 * G_PADDING
                                + G_TITLE_SPACE + footerSpace)
                : Math.min(maxPanelHeight, pinH);
        int left = clampToScreen(pinX, 0, screenWidth - panelWidth);
        int top = clampToScreen(pinY, 0, screenHeight - panelHeight);
        float availableWidth = Math.max(1f, panelWidth - 2f * G_PADDING);
        float availableHeight = Math.max(1f, panelHeight - 2f * G_PADDING - G_TITLE_SPACE - footerSpace);
        // Density decides how big a small graph is drawn; a large one is still
        // shrunk to fit the panel, so the two limits simply combine.
        float fit = Math.min(availableWidth / contentWidth, availableHeight / contentHeight);
        if (fit < MIN_READABLE_FIT) {
            if (!OverlayPlacement.pinned()) {
                // Fitting the node-link view would mean squeezing the text below
                // readability: fall back to the scrollable list layout instead.
                renderTree(event, mc, graph);
                return;
            }
            // A panel the player sized on purpose keeps its text size and lets
            // the graph clip — resizing was the point of pinning it.
            fit = MIN_READABLE_FIT;
        }
        float scale = Math.max(0.05f, Math.min(density, fit));

        UiShapes.panel(graphics, left, top, panelWidth, panelHeight, PANEL_BG,
                UiPalette.HUD_BORDER_OUTER, PANEL_BORDER);
        int graphHeaderBottom = top + G_PADDING + G_TITLE_SPACE - 1;
        graphics.fill(left + 2, top + 2, left + panelWidth - 2, graphHeaderBottom,
                UiPalette.HUD_HEADER_BG);
        graphics.fill(left + 2, graphHeaderBottom, left + panelWidth - 2, graphHeaderBottom + 1,
                UiPalette.HUD_ACCENT_LINE);

        graphics.drawString(font,
                Component.translatable("overlay.craftplanner.title"),
                left + G_PADDING, top + 3, NodeColors.TEXT, false);
        drawLegend(graphics, font, left, top, panelWidth);

        graphics.enableScissor(left, top, left + panelWidth, top + panelHeight);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(left + G_PADDING, top + G_PADDING + G_TITLE_SPACE, 0);
        pose.scale(scale, scale, 1f);

        drawEdges(graphics, layout);
        drawNodes(graphics, layout);

        pose.popPose();
        graphics.disableScissor();

        int footerTop = top + panelHeight - footerSpace - 2;
        UiShapes.divider(graphics, left + 2, left + panelWidth - 2,
                footerTop - 2, UiPalette.HUD_SECTION_DIVIDER);
        graphics.enableScissor(left, footerTop, left + panelWidth, top + panelHeight - 1);
        pose.pushPose();
        pose.translate(left + G_PADDING, footerTop, 0);
        pose.scale(density, density, 1f);
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
     * Colour legend, right-aligned next to the title. One line while it fits
     * beside the title; otherwise two lines (the second one may use the full
     * panel width); dropped entirely only when even two lines do not fit —
     * the old fixed 140 px slack silently swallowed it before.
     */
    private static void drawLegend(GuiGraphics graphics, Font font, int left, int top, int panelWidth) {
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

    private static void drawLegendRow(GuiGraphics graphics, Font font, int[] colors, Component[] labels,
            int[] itemW, int from, int to, int left, int y, int panelWidth, int rowTotal) {
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
            graphics.drawString(font, Long.toString(node.required()),
                    x, y + TreeLayout.ICON_SIZE + 1, color, false);
        }
    }
}
