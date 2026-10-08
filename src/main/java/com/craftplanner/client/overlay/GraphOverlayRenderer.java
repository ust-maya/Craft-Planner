package com.craftplanner.client.overlay;

import com.craftplanner.client.AmountLabel;
import com.craftplanner.client.PlannerState;
import com.craftplanner.client.TextMetrics;
import com.craftplanner.client.UiLegend;
import com.craftplanner.client.UiPalette;
import com.craftplanner.client.UiShapes;
import com.craftplanner.client.layout.PlacedNode;
import com.craftplanner.client.layout.TreeLayout;
import com.craftplanner.client.resource.Availability;
import com.craftplanner.graph.CraftGraph;
import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.RecipeEdge;
import com.craftplanner.graph.RecipeNode;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * The node-link ("graph") view of the craft plan overlay, moved out of
 * {@link TreeOverlayRenderer} without touching its logic.
 *
 * <p>Selected by {@code overlay.mode = "graph"} in the client config. The tree
 * and the panel chrome belong to {@link TreeOverlayRenderer}; the shopping-list
 * footer is shared through {@link ShoppingListFooter}. When fitting the graph
 * into an unpinned panel would squeeze the text below readability, the whole
 * event is handed back to the scrollable list layout.</p>
 */
final class GraphOverlayRenderer {

    private static final int G_MARGIN = 8;
    private static final int G_PADDING = 4;
    private static final int G_TITLE_SPACE = 11;
    private static final int G_FOOTER_SPACE = 24;
    /**
     * Floor for the automatic "shrink to fit" of the graph layout. Below this the
     * node-link view is unreadably small, and the overlay falls back to the
     * scrollable list (tree) layout instead of squeezing the text further.
     */
    private static final float MIN_READABLE_FIT = 0.6f;
    private static final int PANEL_BG = UiPalette.HUD_PANEL_BG;
    private static final int PANEL_BORDER = UiPalette.HUD_PANEL_BORDER;

    /**
     * Cached node-link layout (graph mode). Recomputing it every frame is pure
     * waste: the coordinates only change when the tree is rebuilt.
     */
    private static TreeLayout cachedLayout;
    private static long cachedLayoutVersion = -1;

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

    static void renderGraph(RenderGuiEvent.Post event, Minecraft mc, CraftGraph graph) {
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
        double density = TreeOverlayRenderer.overlayScale();
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
        ShoppingListFooter.Footer footer = ShoppingListFooter.buildFooter(availability, PlannerState.fluidRequirements(), metrics, footerLayoutW,
                graphKeysHint(metrics, footerLayoutW));
        int footerSpace = Math.max(G_FOOTER_SPACE, (int) Math.round(footer.height() * density) + 2);

        int panelHeight = pinH == OverlayPlacement.AUTO
                ? Math.min(maxPanelHeight,
                        Math.max(1, (int) Math.round(contentHeight * density)) + 2 * G_PADDING
                                + G_TITLE_SPACE + footerSpace)
                : Math.min(maxPanelHeight, pinH);
        int left = TreeOverlayRenderer.clampToScreen(pinX, 0, screenWidth - panelWidth);
        int top = TreeOverlayRenderer.clampToScreen(pinY, 0, screenHeight - panelHeight);
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
                TreeOverlayRenderer.renderTree(event, mc);
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
        ShoppingListFooter.drawFooter(graphics, font, footer, footerLayoutW);
        pose.popPose();
        graphics.disableScissor();
        if (!ShoppingListFooter.showTailTooltip(graphics, font, footer, mc, left + G_PADDING, footerTop, density)) {
            ShoppingListFooter.showMaterialTooltips(graphics, font, footer, mc, left + G_PADDING, footerTop, density);
        }
        OverlayPlacement.reportDrawn(left, top, panelWidth, panelHeight);
        TreeOverlayRenderer.drawEditChrome(graphics, mc, font, left, top, panelWidth, panelHeight,
                screenWidth, screenHeight);
    }

    /**
     * The graph view has no row trimming to note, so it keeps the plain hint and
     * does not take the tree's list of rows.
     */
    private static Component graphKeysHint(TextMetrics metrics, int contentW) {
        Component full = TreeOverlayRenderer.keysHint("overlay.craftplanner.keys");
        if (metrics.width(full.getString()) <= contentW) {
            return full;
        }
        Component brief = TreeOverlayRenderer.keysHint("overlay.craftplanner.keys.short");
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
        int entries = UiLegend.entries(font).size();
        int total = UiLegend.totalWidth(font);
        int titleW = font.width(Component.translatable("overlay.craftplanner.title"));
        int firstRowRoom = panelWidth - 2 * G_PADDING - titleW - UiLegend.ENTRY_GAP;
        int right = left + panelWidth - G_PADDING;
        if (total <= firstRowRoom) {
            UiLegend.drawRow(graphics, font, 0, entries, right, top + 2, true);
            return;
        }

        // Greedy split: as many entries as fit in the title row, the rest below.
        int split = UiLegend.split(font, firstRowRoom);
        int restTotal = UiLegend.rowWidth(font, split, entries);
        if (split == 0 || restTotal > panelWidth - 2 * G_PADDING) {
            return;
        }
        UiLegend.drawRow(graphics, font, 0, split, right, top + 2, true);
        UiLegend.drawRow(graphics, font, split, entries, right, top + 2 + font.lineHeight, true);
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
                graphics.drawString(font, AmountLabel.of(node),
                    x, y + TreeLayout.ICON_SIZE + 1, color, false);
        }
    }

    private GraphOverlayRenderer() {
    }
}
