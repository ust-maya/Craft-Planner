package com.craftplanner.client.overlay;

import com.craftplanner.Config;
import com.craftplanner.Constants;
import com.craftplanner.client.OverlayState;
import com.craftplanner.client.PlannerState;
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
 * scaling. The panel is additionally scaled down to fit the screen instead of
 * being clipped, so a wide tree never loses its right-hand text.</p>
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
    private static final int CONNECTOR = NodeColors.EDGE;
    private static final int PANEL_BG = 0xC0101010;
    private static final int PANEL_BORDER = 0x90606060;
    private static final int MUTED = 0x80C8C8C8;

    /** Vertical scroll of the tree layout, in rows (tree mode only). */
    private static int treeScroll;
    /** Last drawn panel rectangle, used to hit-test the mouse wheel. */
    private static int lastPanelLeft;
    private static int lastPanelTop;
    private static int lastPanelW;
    private static int lastPanelH;

    private TreeOverlayRenderer() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
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
     */
    @SubscribeEvent
    public static void onMouseScrolled(InputEvent.MouseScrollingEvent event) {
        if (!OverlayState.isVisible() || !"tree".equals(Config.overlayMode())) {
            return;
        }
        double mx = event.getMouseX();
        double my = event.getMouseY();
        if (mx < lastPanelLeft || mx > lastPanelLeft + lastPanelW
                || my < lastPanelTop || my > lastPanelTop + lastPanelH) {
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
        final boolean last;
        /** Row index of the last *direct* child (for the vertical spine). */
        int lastChildIndex;

        Row(RecipeNode node, int depth, boolean last) {
            this.node = node;
            this.depth = depth;
            this.last = last;
            this.lastChildIndex = -1;
        }
    }

    private static int buildRows(RecipeNode node, int depth, boolean last, List<Row> out) {
        int myIndex = out.size();
        Row row = new Row(node, depth, last);
        out.add(row);
        List<RecipeEdge> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            int childIndex = buildRows(children.get(i).child(), depth + 1,
                    i == children.size() - 1, out);
            if (i == children.size() - 1) {
                row.lastChildIndex = childIndex;
            }
        }
        if (row.lastChildIndex < 0) {
            row.lastChildIndex = myIndex; // no children: spine not drawn
        }
        return myIndex;
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
     * Density of the overlay content ({@code overlay.scale}), clamped to the
     * range the config allows so a hand-edited file cannot scale the UI away.
     */
    private static float overlayScale() {
        return (float) Math.max(0.1, Math.min(1.0, Config.overlayScale()));
    }

    private static void renderTree(RenderGuiEvent.Post event, Minecraft mc, CraftGraph graph) {
        Font font = mc.font;
        GuiGraphics graphics = event.getGuiGraphics();

        List<Row> rows = new ArrayList<>();
        List<RecipeNode> roots = graph.roots();
        for (int i = 0; i < roots.size(); i++) {
            buildRows(roots.get(i), 0, i == roots.size() - 1, rows);
        }
        if (rows.isEmpty()) {
            return;
        }

        Map<ItemKey, Availability> availability = PlannerState.availability();

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int availW = screenW - 2 * MARGIN;
        int availH = screenH - 2 * MARGIN;

        // Widest row decides the panel width (in layout pixels).
        int maxRowW = 0;
        for (Row r : rows) {
            int w = iconX(r.depth) + ICON + 3 + font.width(label(r.node)) + 4;
            maxRowW = Math.max(maxRowW, w);
        }
        // The footer sets a floor for the width: the shopping list wraps, but
        // the hotkey line is a single line and has to fit — nothing is ever
        // cut or pushed outside the panel.
        int footerMinW = Math.max(widestEntry(availability, font),
                font.width(Component.translatable("overlay.craftplanner.keys.short")));

        int layoutW = Math.max(maxRowW, footerMinW) + 2 * PADDING;
        float scale = Math.max(MIN_SCALE, Math.min(overlayScale(), (float) availW / layoutW));
        int panelW = Math.min(availW, Math.max(1, Math.round(layoutW * scale)));

        int contentW = layoutW - 2 * PADDING;
        Footer footer = buildFooter(availability, font, contentW,
                hotkeyLine(font, contentW));
        int footerH = footer.height();

        int layoutH = TITLE_H + rows.size() * ROW_H + 2 * PADDING
                + (footerH > 0 ? FOOTER_GAP + footerH : 0);
        int panelH = Math.min(availH, Math.max(1, Math.round(layoutH * scale)));

        int left = MARGIN;
        int top = MARGIN;
        int pad = Math.max(1, Math.round(PADDING * scale));
        int rowPx = Math.max(1, Math.round(ROW_H * scale));
        int footerPx = Math.round(footerH * scale);
        int gapPx = footerH > 0 ? Math.round(FOOTER_GAP * scale) : 0;

        int contentLeft = left + pad;
        int contentTop = top + pad + Math.round(TITLE_H * scale);
        int footerTop = top + panelH - pad - footerPx;
        int contentBottom = Math.max(contentTop + rowPx, footerTop - gapPx);
        int visibleRows = Math.max(1, (contentBottom - contentTop) / rowPx);
        int maxScroll = Math.max(0, rows.size() - visibleRows);
        treeScroll = Math.max(0, Math.min(treeScroll, maxScroll));

        graphics.fill(left, top, left + panelW, top + panelH, PANEL_BG);
        drawBorder(graphics, left, top, panelW, panelH);

        PoseStack pose = graphics.pose();

        // Title: same scaled font as the rest of the panel, so it cannot look
        // (or be) larger than the tree it heads.
        pose.pushPose();
        pose.translate(left + pad, top + Math.round(2 * scale), 0);
        pose.scale(scale, scale, 1f);
        graphics.drawString(font, Component.translatable("overlay.craftplanner.title"),
                0, 0, NodeColors.TEXT, false);
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

        // Connector lines first (so icons paint on top).
        for (int r = firstVisible; r <= lastVisible; r++) {
            Row row = rows.get(r);
            int yc = rowCenterY(r, treeScroll, originY);

            // Elbow running from the parent's spine into this row's icon.
            // Roots have no parent, hence no elbow and no stub to their left.
            if (row.depth > 0) {
                graphics.hLine(spineX(row.depth - 1), iconX(row.depth) - 1, yc, CONNECTOR);
            }

            // This row's own spine: it starts at the bottom edge of its icon,
            // so the branch looks like it grows out of the icon.
            if (!row.node.children().isEmpty()) {
                graphics.vLine(spineX(row.depth),
                        iconBottomY(r, treeScroll, originY),
                        rowCenterY(row.lastChildIndex, treeScroll, originY), CONNECTOR);
            }
        }
        // Keep the parent line flowing from the top edge when scrolled.
        if (treeScroll > 0) {
            Row r0 = rows.get(firstVisible);
            if (r0.depth > 0) {
                graphics.vLine(spineX(r0.depth - 1), originY,
                        rowCenterY(firstVisible, treeScroll, originY), CONNECTOR);
            }
        }

        // Icons + labels. The panel is sized from the widest row, so the room
        // left of a label is exactly its own width — no row is ever trimmed.
        int labelRoom = contentW - 4;
        for (int r = firstVisible; r <= lastVisible; r++) {
            Row row = rows.get(r);
            int yTop = rowTop(r, treeScroll, originY);
            int ix = iconX(row.depth);
            int textX = ix + ICON + 3;

            graphics.renderItem(row.node.displayStack(), ix, yTop + (ROW_H - ICON) / 2);

            String text = label(row.node);
            int room = labelRoom - textX;
            if (room > 0 && font.width(text) > room) {
                text = trimToWidth(font, text, room);
            }
            int color = NodeColors.forNode(row.node, availability);
            graphics.drawString(font, text, textX, yTop + (ROW_H - font.lineHeight) / 2, color, false);
        }

        pose.popPose();
        graphics.disableScissor();

        // Footer: own scaled pose, clipped to the panel, so neither the icons
        // nor the numbers can ever spill into the game view.
        if (footerH > 0) {
            graphics.enableScissor(left, footerTop, left + panelW, top + panelH - 1);
            pose.pushPose();
            pose.translate(left + pad, footerTop, 0);
            pose.scale(scale, scale, 1f);
            drawFooter(graphics, font, footer, contentW);
            pose.popPose();
            graphics.disableScissor();
        }

        if (maxScroll > 0) {
            int trackTop = contentTop;
            int trackH = contentBottom - contentTop;
            int barH = Math.max(8, trackH * visibleRows / rows.size());
            int barY = trackTop + (trackH - barH) * treeScroll / maxScroll;
            int barX = left + panelW - 3;
            graphics.fill(barX, barY, barX + 2, barY + barH, 0x90A0A0A0);
        }

        lastPanelLeft = left;
        lastPanelTop = top;
        lastPanelW = panelW;
        lastPanelH = panelH;
    }

    private static String label(RecipeNode node) {
        return node.item().name().getString() + " ×" + node.required();
    }

    /**
     * Hotkey hint for the footer: the verbose line when it fits, the short
     * {@code V — … · K — …} when the panel is narrow, nothing at all when
     * even that would be cut. The hint is never trimmed mid-word.
     */
    private static Component hotkeyLine(Font font, int contentW) {
        Component full = Component.translatable("overlay.craftplanner.keys");
        if (font.width(full) <= contentW) {
            return full;
        }
        Component brief = Component.translatable("overlay.craftplanner.keys.short");
        return font.width(brief) <= contentW ? brief : null;
    }

    // ---------------------------------------------------------------------
    // Footer: shopping list + totals
    // ---------------------------------------------------------------------

    /** Prepared footer block: wrapped shopping list plus the hotkey line. */
    private static final class Footer {
        final List<List<Map.Entry<ItemKey, Availability>>> lines = new ArrayList<>();
        /** Materials dropped because {@link #MAX_FOOTER_LINES} was reached. */
        int hidden;
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

    /**
     * Builds the footer for a content width given in layout pixels. The
     * shopping list is sorted by shortage first, so what the player still has
     * to gather is what they see.
     */
    private static Footer buildFooter(Map<ItemKey, Availability> availability, Font font,
            int contentW, Component keys) {
        Footer footer = new Footer(font.width(keys) <= contentW ? keys : null);
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
        int y = 0;
        for (int i = 0; i < footer.lines.size(); i++) {
            boolean last = i == footer.lines.size() - 1;
            drawShoppingLine(graphics, font, footer.lines.get(i), y, last ? footer.hidden : 0);
            y += FOOTER_LINE_H;
        }
        if (footer.keys != null) {
            graphics.drawCenteredString(font, footer.keys, contentW / 2, y + 1, MUTED);
        }
    }

    /**
     * One line of the shopping list: icon, total amount, colour by
     * availability — no names, so nothing has to be trimmed.
     */
    private static void drawShoppingLine(GuiGraphics graphics, Font font,
            List<Map.Entry<ItemKey, Availability>> line, int y, int hidden) {
        int textY = y + (ICON - font.lineHeight) / 2 + 1;
        int x = 0;
        for (Map.Entry<ItemKey, Availability> entry : line) {
            graphics.renderItem(entry.getKey().toStack(), x, y);
            String amount = Long.toString(entry.getValue().required());
            graphics.drawString(font, amount, x + ICON + 1, textY,
                    NodeColors.forAvailability(entry.getValue()), false);
            x += entryWidth(font, entry.getValue().required());
        }
        if (hidden > 0) {
            graphics.drawString(font, "+" + hidden, x, textY, MUTED, false);
        }
    }

    private static void drawBorder(GuiGraphics graphics, int left, int top, int w, int h) {
        graphics.fill(left, top, left + w, top + 1, PANEL_BORDER);
        graphics.fill(left, top + h - 1, left + w, top + h, PANEL_BORDER);
        graphics.fill(left, top, left + 1, top + h, PANEL_BORDER);
        graphics.fill(left + w - 1, top, left + w, top + h, PANEL_BORDER);
    }

    /** Cuts text with an ellipsis so it fits the given pixel width. */
    private static String trimToWidth(Font font, String text, int maxWidth) {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() > 1 && font.width(sb.toString() + "…") > maxWidth) {
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

    private static void renderGraph(RenderGuiEvent.Post event, Minecraft mc, CraftGraph graph) {
        TreeLayout layout = TreeLayout.of(graph.roots());
        if (layout.isEmpty()) {
            return;
        }

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int maxPanelWidth = screenWidth - 2 * G_MARGIN;
        int maxPanelHeight = screenHeight - 2 * G_MARGIN;

        int contentWidth = layout.width();
        int contentHeight = layout.height();
        float density = overlayScale();
        int panelWidth = Math.min(maxPanelWidth,
                Math.max(1, Math.round(contentWidth * density)) + 2 * G_PADDING);

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = mc.font;

        // The footer needs the panel width before the height is known: the
        // shopping list wraps into whatever horizontal room it is given.
        Map<ItemKey, Availability> availability = PlannerState.availability();
        int footerLayoutW = Math.max(1, (int) ((panelWidth - 2 * G_PADDING) / density));
        Footer footer = buildFooter(availability, font, footerLayoutW,
                hotkeyLine(font, footerLayoutW));
        int footerSpace = Math.max(G_FOOTER_SPACE, Math.round(footer.height() * density) + 2);

        int panelHeight = Math.min(maxPanelHeight,
                Math.max(1, Math.round(contentHeight * density)) + 2 * G_PADDING
                        + G_TITLE_SPACE + footerSpace);
        int left = G_MARGIN;
        int top = G_MARGIN;

        graphics.fill(left, top, left + panelWidth, top + panelHeight, PANEL_BG);
        graphics.fill(left, top, left + panelWidth, top + 1, PANEL_BORDER);
        graphics.fill(left, top + panelHeight - 1, left + panelWidth, top + panelHeight, PANEL_BORDER);
        graphics.fill(left, top, left + 1, top + panelHeight, PANEL_BORDER);
        graphics.fill(left + panelWidth - 1, top, left + panelWidth, top + panelHeight, PANEL_BORDER);

        graphics.drawString(font,
                Component.translatable("overlay.craftplanner.title"),
                left + G_PADDING, top + 3, NodeColors.TEXT, false);
        drawLegend(graphics, font, left, top, panelWidth);

        float availableWidth = Math.max(1f, panelWidth - 2f * G_PADDING);
        float availableHeight = Math.max(1f, panelHeight - 2f * G_PADDING - G_TITLE_SPACE - footerSpace);
        // Density decides how big a small graph is drawn; a large one is still
        // shrunk to fit the panel, so the two limits simply combine.
        float scale = Math.max(0.05f, Math.min(density,
                Math.min(availableWidth / contentWidth, availableHeight / contentHeight)));

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
        graphics.enableScissor(left, footerTop, left + panelWidth, top + panelHeight - 1);
        pose.pushPose();
        pose.translate(left + G_PADDING, footerTop, 0);
        pose.scale(density, density, 1f);
        drawFooter(graphics, font, footer, footerLayoutW);
        pose.popPose();
        graphics.disableScissor();
    }

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
        int total = 0;
        for (int i = 0; i < labels.length; i++) {
            total += G_LEGEND_SQUARE + 2 + font.width(labels[i]) + 6;
        }
        if (total > panelWidth - 140) {
            return;
        }
        int x = left + panelWidth - G_PADDING - total;
        int y = top + 4;
        for (int i = 0; i < labels.length; i++) {
            graphics.fill(x, y, x + G_LEGEND_SQUARE, y + G_LEGEND_SQUARE, colors[i]);
            graphics.drawString(font, labels[i], x + G_LEGEND_SQUARE + 2, y, NodeColors.TEXT, false);
            x += G_LEGEND_SQUARE + 2 + font.width(labels[i]) + 6;
        }
    }

    private static void drawEdges(GuiGraphics graphics, TreeLayout layout) {
        for (PlacedNode parent : layout.nodes()) {
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

                graphics.vLine(startX, startY, midY, NodeColors.EDGE);
                graphics.hLine(Math.min(startX, endX), Math.max(startX, endX), midY, NodeColors.EDGE);
                graphics.vLine(endX, midY, endY, NodeColors.EDGE);
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
