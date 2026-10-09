package com.craftplanner.client.overlay;

import com.craftplanner.client.AmountLabel;
import com.craftplanner.client.MaterialTooltip;
import com.craftplanner.client.PlannerState;
import com.craftplanner.client.TextMetrics;
import com.craftplanner.client.UiPalette;
import com.craftplanner.client.resource.Availability;
import com.craftplanner.client.tree.TreeMetrics;
import com.craftplanner.client.tree.TreePanel;
import com.craftplanner.graph.ItemKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;

/**
 * The shopping-list footer of the craft-plan HUD, moved out of
 * {@link TreeOverlayRenderer} without touching its logic.
 *
 * <p>One footer block holds the wrapped material icons (worst shortage first),
 * the {@code +N} marker for what did not fit, and the hotkey line. Every
 * coordinate it produces is in layout pixels: the caller owns the scaled pose,
 * and the tooltip entry points take the origin and scale of the pose the footer
 * was drawn in so a hover test can map the cursor back onto the icons.</p>
 *
 * <p>The block is rebuilt at most once per change: {@link #cachedFooter} keys
 * the cache on the identity of the availability and fluid maps, the room the
 * panel gives the footer, the density, whether the plan is clipped, and the
 * language — the six things its numbers and labels are made of.</p>
 */
final class ShoppingListFooter {

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
    private static final int MUTED = UiPalette.HUD_MUTED;

    /** Prepared footer block: wrapped shopping list plus the hotkey line. */
    static final class Footer {
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

    /**
     * One shopping-list entry.
     *
     * @param amount what the entry's number says: the shortfall of an item — the
     *               footer answers "what do I still have to gather" — or the volume
     *               of a fluid
     */
    record FooterMaterial(ItemKey item, Fluid fluid, long amount, Availability availability) {
        static FooterMaterial item(ItemKey key, Availability value) {
            return new FooterMaterial(key, null, value.missing(), value);
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
            return isFluid() ? AmountLabel.millibuckets(amount) : AmountLabel.bare(amount);
        }

        int color() {
            return availability == null ? NodeColors.RAW : NodeColors.forAvailability(availability);
        }
    }

    record MaterialIcon(FooterMaterial material, int x, int y) {
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
    static Footer buildFooter(Map<ItemKey, Availability> availability, Map<Fluid, Long> fluids,
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

    /**
     * The footer block, or the cached one when nothing it is made of changed.
     *
     * <p>Building it sorts the whole shopping list, wraps it into lines twice over
     * when the {@code +N} marker has to be squeezed in, and measures the hotkey
     * hint — on a HUD that runs every frame while the numbers behind it move once
     * a second at best. The keys are exactly what those numbers come from: the
     * availability map and the fluid map (both replaced wholesale whenever they
     * change, so identity is enough), the room the panel gives the footer, the
     * density it is drawn at, whether the plan is trimmed, and the language the
     * labels are read in.</p>
     */
    static Footer cachedFooter(Map<ItemKey, Availability> availability, TextMetrics metrics,
            TreePanel.Plan rough, Minecraft mc) {
        String language = mc.getLanguageManager().getSelected();
        Map<Fluid, Long> fluids = PlannerState.fluidRequirements();
        if (footerCache != null && footerAvailability == availability && footerFluids == fluids
                && footerWidth == rough.contentW() && footerScale == rough.scale()
                && footerClipped == rough.clipped() && language.equals(footerLanguage)) {
            return footerCache;
        }
        footerCache = buildFooter(availability, fluids, metrics, rough.contentW(),
                TreeOverlayRenderer.hotkeyLine(metrics, rough.contentW(), rough));
        footerAvailability = availability;
        footerFluids = fluids;
        footerWidth = rough.contentW();
        footerScale = rough.scale();
        footerClipped = rough.clipped();
        footerLanguage = language;
        return footerCache;
    }

    private static Map<ItemKey, Availability> footerAvailability;
    private static Map<Fluid, Long> footerFluids;
    private static int footerWidth = -1;
    private static double footerScale = -1;
    private static boolean footerClipped;
    private static String footerLanguage = "";
    private static Footer footerCache;

    /** Raw materials, worst shortage first, then largest demand. */
    private static List<FooterMaterial> shoppingList(Map<ItemKey, Availability> availability,
            Map<Fluid, Long> fluids) {
        List<FooterMaterial> entries = new ArrayList<>();
        // Only what is still missing: a covered material has nothing left to gather,
        // and with it gone the footer's numbers add up to the tree window's total.
        availability.forEach((key, value) -> {
            if (!value.satisfied()) {
                entries.add(FooterMaterial.item(key, value));
            }
        });
        fluids.forEach((fluid, amount) -> entries.add(FooterMaterial.fluid(fluid, amount)));
        entries.sort((a, b) -> {
            long missingA = a.availability() == null ? 0 : a.availability().missing();
            long missingB = b.availability() == null ? 0 : b.availability().missing();
            int byShortage = Long.compare(missingB, missingA);
            return byShortage != 0
                    ? byShortage
                    : Long.compare(b.amount(), a.amount());
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
    static void drawFooter(GuiGraphics graphics, Font font, Footer footer, int contentW) {
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
     * One line of the shopping list: icon, the amount still missing (a fluid: its
     * volume), colour by availability — no names, so nothing has to be trimmed.
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
    static boolean showTailTooltip(GuiGraphics graphics, Font font, Footer footer,
            Minecraft mc, int originX, int originY, double scale) {
        if (footer.hidden <= 0 || footer.tailX < 0 || footer.hiddenEntries.isEmpty()
                || !OverlayPlacement.editing()) {
            return false;
        }
        int mx = TreeOverlayRenderer.mouseX(mc);
        int my = TreeOverlayRenderer.mouseY(mc);
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
                    + (entry.isFluid() ? entry.amountLabel() : AmountLabel.times(entry.amount())))));
        }
        showTooltip(graphics, font, mc, lines);
        return true;
    }

    /**
     * Hover feedback for a shopping-list icon: the footer shows one number per
     * material — how much is still missing — which says nothing about
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
    static void showMaterialTooltips(GuiGraphics graphics, Font font, Footer footer,
            Minecraft mc, int originX, int originY, double scale) {
        if (footer.icons.isEmpty() || !OverlayPlacement.editing()) {
            return;
        }
        int mx = TreeOverlayRenderer.mouseX(mc);
        int my = TreeOverlayRenderer.mouseY(mc);
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

    /** The material's own tooltip lines, said the way every surface says them. */
    private static List<Component> materialLines(FooterMaterial material) {
        // The footer's number is the shortfall; the tooltip still states the whole
        // demand, then what is on hand and what is missing.
        String demand = material.isFluid() ? material.amountLabel()
                : AmountLabel.bare(material.availability().required());
        return MaterialTooltip.lines(material.name(), demand, material.availability());
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

    private ShoppingListFooter() {
    }
}
