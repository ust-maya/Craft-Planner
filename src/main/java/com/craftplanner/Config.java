package com.craftplanner;

import com.craftplanner.client.OverlayState;
import com.craftplanner.client.PlannerState;
import com.craftplanner.engine.CraftTreeBuilder;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client configuration of the planner (Stage 10).
 *
 * <p>Everything here is client side: the tree, the overlay and the inventory
 * comparison live on the client only, so the spec is registered as
 * {@code ModConfig.Type.CLIENT} and lands in
 * {@code config/craftplanner-client.toml}.</p>
 *
 * <p>Key bindings are <em>not</em> part of this file: {@code KeyMapping}s are
 * registered in {@code Keybinds} and are remapped by the game itself through
 * <em>Options → Controls → Craft Planner</em>, which also resolves conflicts
 * with other mods.</p>
 */
@EventBusSubscriber(modid = Constants.MODID, value = Dist.CLIENT)
public final class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.IntValue MAX_DEPTH = BUILDER
            .comment("Maximum craft-tree depth. Deeper branches are cut off and their items are treated as raw materials.")
            .defineInRange("limits.maxDepth", CraftTreeBuilder.MAX_DEPTH, 1, 64);

    private static final ModConfigSpec.IntValue MAX_NODES = BUILDER
            .comment("Hard cap on the number of expanded nodes, so one broken datapack cannot freeze the client.")
            .defineInRange("limits.maxNodes", CraftTreeBuilder.MAX_NODES, 100, 50_000);

    private static final ModConfigSpec.BooleanValue OVERLAY_ENABLED = BUILDER
            .comment("Show the craft-tree overlay. Applied on start and whenever the config is reloaded; the V key still toggles it in game.")
            .define("overlay.enabled", true);

    private static final ModConfigSpec.ConfigValue<String> OVERLAY_MODE = BUILDER
            .comment("Overlay layout: 'tree' shows the plan as a nested list on the left (each item keeps its icon,",
                    "with ├──/└──/│ connector lines). 'graph' shows the classic node-link craft tree.")
            .define("overlay.mode", "tree");

    private static final ModConfigSpec.DoubleValue OVERLAY_SCALE = BUILDER
            .comment("Density of the overlay: 1.0 keeps the full-size item icons and text, 0.23 is the",
                    "compact default (a third of the old 0.7). Icons, text (including the title and",
                    "the footer) and connector lines are scaled together, so the tree keeps its",
                    "proportions and simply gets smaller. Lower values fit more rows on screen but",
                    "make the glyphs and item icons softer, because both are bitmaps. A panel that is",
                    "too wide for the screen is scaled down further instead of being clipped.")
            .defineInRange("overlay.scale", 0.23, 0.1, 1.0);

    private static final ModConfigSpec.IntValue MAX_AMOUNT = BUILDER
            .comment("Maximum amount a single planned recipe can request. The quantity popup",
                    "clamps input to this value and flashes the field when the limit is hit.",
                    "Large automation packs may need more than the default 1 000 000.")
            .defineInRange("quantity.maxAmount", 1_000_000, 1, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue PARTIAL_THRESHOLD = BUILDER
            .comment("How much of a required item must be in the inventory for it to count as 'partly there' (yellow).",
                    "Below this percentage the item is painted red. 0 keeps the old rule: anything above zero is yellow.")
            .defineInRange("resources.partialThresholdPercent", 0, 0, 100);

    private static final ModConfigSpec.BooleanValue HIDE_ORES = BUILDER
            .comment("Keep ore blocks and raw ore materials out of the tree and the shopping list.",
                    "Ores are mined, not crafted: hiding them ends the branch one step earlier, so the item that",
                    "needed the ore becomes the raw material itself ('4 redstone' instead of",
                    "'4 deepslate redstone ore'). Membership: the c:ores / c:raw_materials tags (plus their",
                    "legacy forge: counterparts) and the *_ore naming, so modded ores are covered too.",
                    "The K window has a button that flips this switch in game without editing the file.")
            .define("filters.hideOres", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    public static int maxDepth() {
        return MAX_DEPTH.get();
    }

    public static int maxNodes() {
        return MAX_NODES.get();
    }

    public static boolean overlayEnabled() {
        return OVERLAY_ENABLED.get();
    }

    /** Layout of the overlay: {@code "tree"} (nested list) or {@code "graph"} (node-link). */
    public static String overlayMode() {
        return OVERLAY_MODE.get();
    }

    /**
     * Density of the overlay content. {@code 1.0} = full-size item icons and
     * text; lower values scale icons, text and lines down together.
     */
    public static double overlayScale() {
        return OVERLAY_SCALE.get();
    }

    /** Upper bound for a single planned quantity (quantity popup, steppers). */
    public static long maxAmount() {
        return MAX_AMOUNT.get().longValue();
    }

    /** Percent of the required amount that still counts as "partly available". */
    public static int partialThresholdPercent() {
        return PARTIAL_THRESHOLD.get();
    }

    /** Whether ore-like resources are kept out of the tree by default. */
    public static boolean hideOres() {
        return HIDE_ORES.get();
    }

    /** Fires on load and on every in-game edit of the file. */
    @SubscribeEvent
    public static void onConfigChanged(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        OverlayState.setVisible(overlayEnabled());
        // An edited file wins over whatever the player toggled in the K window.
        PlannerState.resetHideOresOverride();
        // Depth/node limits changed: the existing tree may have been cut off.
        PlannerState.rebuild();
    }
}
