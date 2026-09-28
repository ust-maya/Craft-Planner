package com.craftplanner;

import com.craftplanner.client.OverlayState;
import com.craftplanner.client.PlannerState;
import com.craftplanner.client.overlay.OverlayPlacement;
import com.craftplanner.engine.CraftTreeBuilder;
import com.craftplanner.engine.RecipeIndex;
import java.util.Arrays;
import java.util.List;
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

    /** Density the layout editor resets to, and the range it may wheel through. */
    private static final double OVERLAY_SCALE_DEFAULT = 0.23;
    private static final double OVERLAY_SCALE_MIN = 0.1;
    private static final double OVERLAY_SCALE_MAX = 1.0;
    /** Unset marker of the geometry keys; must match {@code OverlayPlacement.AUTO}. */
    private static final int GEOMETRY_AUTO = -1;

    /**
     * The ranks {@code recipes.moddedPriority} accepts; must match RecipeIndex's switch.
     * A mutable list on purpose: while correcting an old config file that lacks the
     * key, NeoForge calls {@code allowedValues.contains(null)}, and {@code List.of}
     * throws NPE on that instead of returning false, crashing the whole mod load.
     */
    private static final String MODDED_PRIORITY_DEFAULT = "after_vanilla";
    private static final List<String> MODDED_PRIORITIES =
            Arrays.asList("after_vanilla", "before_smelting", "first");

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
                    "too wide for the screen is scaled down further instead of being clipped.",
                    "This is also what the mouse wheel changes while the overlay is being moved.")
            .defineInRange("overlay.scale", OVERLAY_SCALE_DEFAULT, OVERLAY_SCALE_MIN, OVERLAY_SCALE_MAX);

    private static final ModConfigSpec.IntValue OVERLAY_X = BUILDER
            .comment("Overlay panel position and size, in GUI-scaled pixels — the same space the HUD",
                    "uses, so the panel keeps its place whatever the resolution is.",
                    "-1 means automatic: the panel is pinned to the top-left margin and sized from",
                    "its content. The in-game layout editor writes these values; resetting the panel",
                    "there, or deleting the four keys, gives the automatic layout back.",
                    "A pinned panel keeps the configured text density and clips what does not fit",
                    "instead of shrinking it further.")
            .defineInRange("overlay.positionX", GEOMETRY_AUTO, GEOMETRY_AUTO, 32_000);

    private static final ModConfigSpec.IntValue OVERLAY_Y = BUILDER
            .defineInRange("overlay.positionY", GEOMETRY_AUTO, GEOMETRY_AUTO, 32_000);

    private static final ModConfigSpec.IntValue OVERLAY_WIDTH = BUILDER
            .defineInRange("overlay.width", GEOMETRY_AUTO, GEOMETRY_AUTO, 32_000);

    private static final ModConfigSpec.IntValue OVERLAY_HEIGHT = BUILDER
            .defineInRange("overlay.height", GEOMETRY_AUTO, GEOMETRY_AUTO, 32_000);

    private static final ModConfigSpec.BooleanValue OVERLAY_BADGES = BUILDER
            .comment("Mark the rows of the tree that can be crafted in more than one way with a",
                    "'used / total' badge, and let the player click it (in the layout editor or the",
                    "K window) to choose. Switching this off leaves the tree as it was: the engine",
                    "still picks the preferred recipe, there is just no affordance to change it.")
            .define("overlay.badges", true);

    private static final ModConfigSpec.BooleanValue INDEX_MODDED = BUILDER
            .comment("Plan recipes of modded machines, not only the vanilla crafting table,",
                    "furnace, stonecutter and smithing table. A modded recipe enters the plan when",
                    "it has an item output and readable item inputs; types that keep their inputs",
                    "in private fields produce nothing and stay out, so their items remain raw",
                    "materials. Only item inputs are counted: energy, fluids and processing time of",
                    "a machine are not part of the plan.")
            .define("recipes.indexModded", true);

    private static final ModConfigSpec.ConfigValue<String> MODDED_PRIORITY = BUILDER
            .comment("Where a machine recipe ranks against the vanilla ones for the same item:",
                    "'after_vanilla' (default) keeps the hand crafting table first and shows the",
                    "machine as an alternative; 'before_smelting' puts machines ahead of the",
                    "furnace group; 'first' lets a machine recipe outrank everything. Changing this",
                    "rebuilds the recipe index, so the tree re-expands on the next tick.")
            .defineInList("recipes.moddedPriority", MODDED_PRIORITY_DEFAULT, MODDED_PRIORITIES);

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

    /** Whether the tree marks items that have more than one recipe. */
    public static boolean badgesEnabled() {
        return OVERLAY_BADGES.get();
    }

    /** Whether recipes of modded machine types are planned at all. */
    public static boolean indexModdedRecipes() {
        return INDEX_MODDED.get();
    }

    /** Rank of a machine recipe against the vanilla ones: see {@code MODDED_PRIORITIES}. */
    public static String moddedPriority() {
        return MODDED_PRIORITY.get();
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

    /** Pinned overlay panel left/top in GUI-scaled pixels, or {@code -1} for automatic. */
    public static int overlayX() {
        return OVERLAY_X.get();
    }

    public static int overlayY() {
        return OVERLAY_Y.get();
    }

    /** Pinned overlay panel width/height in GUI-scaled pixels, or {@code -1} to fit content. */
    public static int overlayW() {
        return OVERLAY_WIDTH.get();
    }

    public static int overlayH() {
        return OVERLAY_HEIGHT.get();
    }

    /**
     * Writes the geometry the layout editor produced and flushes it to disk,
     * so a moved panel is still there after the game restarts.
     */
    public static void saveOverlayGeometry(int x, int y, int width, int height, double scale) {
        OVERLAY_X.set(x);
        OVERLAY_Y.set(y);
        OVERLAY_WIDTH.set(width);
        OVERLAY_HEIGHT.set(height);
        OVERLAY_SCALE.set(scale);
        SPEC.save();
    }

    /** Back to the automatic layout: margin, content-sized panel, default density. */
    public static void resetOverlayGeometry() {
        OVERLAY_X.set(GEOMETRY_AUTO);
        OVERLAY_Y.set(GEOMETRY_AUTO);
        OVERLAY_WIDTH.set(GEOMETRY_AUTO);
        OVERLAY_HEIGHT.set(GEOMETRY_AUTO);
        OVERLAY_SCALE.set(OVERLAY_SCALE_DEFAULT);
        SPEC.save();
    }

    /** Fires on load and on every in-game edit of the file. */
    @SubscribeEvent
    public static void onConfigChanged(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        OverlayState.setVisible(overlayEnabled());
        // The file carries the panel geometry: re-read it so an external edit
        // wins over what the player dragged in the layout editor.
        OverlayPlacement.reloadFromConfig();
        // Which recipe types are indexable, and in what order, is decided while
        // the index is built — the cached one has to go, or the change would
        // only apply on the next world load.
        RecipeIndex.invalidateCache();
        // An edited file wins over whatever the player toggled in the K window.
        PlannerState.resetHideOresOverride();
        // Depth/node limits changed: the existing tree may have been cut off.
        PlannerState.rebuild();
    }
}
