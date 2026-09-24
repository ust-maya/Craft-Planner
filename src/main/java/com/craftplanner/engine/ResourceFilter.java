package com.craftplanner.engine;

import com.craftplanner.graph.ItemKey;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Decides which items the craft tree leaves out.
 *
 * <p>Ore blocks are mined, not crafted: no recipe in the planner produces them,
 * so they always end up as leaves and turn the shopping list into
 * "4 deepslate redstone ore" instead of "4 redstone". Hiding them makes the
 * branch stop one step earlier, so the item that needed the ore becomes the raw
 * material itself — which is what the player actually has to gather. Quantities
 * stay correct: a hidden ingredient simply does not add a node, and its parent
 * keeps the full required amount.</p>
 *
 * <p>Membership comes from the conventional tags first, so modded ores are
 * covered without hardcoding names:</p>
 * <ul>
 *   <li>ore blocks — {@code c:ores} (NeoForge) and {@code forge:ores} (packs
 *       that keep the legacy namespace);</li>
 *   <li>raw ore items — {@code c:raw_materials} and {@code forge:raw_materials}
 *       ({@code raw_iron}, {@code raw_copper}, {@code raw_gold}).</li>
 * </ul>
 *
 * <p>The {@code *_ore} name suffix and {@code ancient_debris} stay as a
 * fallback for packs that do not use tags at all, so the filter still works
 * even if tag binding is unavailable. A bare {@code raw_} prefix is
 * deliberately <em>not</em> used: it would also swallow {@code raw_fish},
 * {@code raw_beef} and the rest of the raw food items.</p>
 */
public final class ResourceFilter {

    /** Keeps everything — used when the filter is switched off. */
    public static final ResourceFilter NONE = new ResourceFilter(false);

    /** Hides ore blocks and raw ore materials. */
    public static final ResourceFilter ORE_LIKE = new ResourceFilter(true);

    private static final List<TagKey<Item>> ORE_TAGS = List.of(
            tag("c", "ores"),
            tag("forge", "ores"));

    private static final List<TagKey<Item>> RAW_MATERIAL_TAGS = List.of(
            tag("c", "raw_materials"),
            tag("forge", "raw_materials"));

    /** Ore blocks whose registry name does not end in {@code _ore}. */
    private static final List<String> EXTRA_ORE_NAMES = List.of("ancient_debris");

    private static final String ORE_SUFFIX = "_ore";

    private final boolean enabled;

    private ResourceFilter(boolean enabled) {
        this.enabled = enabled;
    }

    /** The filter for the given switch state; never {@code null}. */
    public static ResourceFilter of(boolean hideOreLike) {
        return hideOreLike ? ORE_LIKE : NONE;
    }

    /** True when the item must not become a node of the tree. */
    public boolean hides(ItemKey key) {
        return enabled && key != null && isOreLike(key.item());
    }

    /**
     * Tag/naming test behind {@link #hides}. Static because it only depends on
     * the item's tags and registry name, which makes it usable from the UI and
     * from tests without building a whole filter.
     */
    public static boolean isOreLike(Item item) {
        if (item == null) {
            return false;
        }
        if (inAnyTag(item, ORE_TAGS) || inAnyTag(item, RAW_MATERIAL_TAGS)) {
            return true;
        }
        String path = pathOf(item);
        return path.endsWith(ORE_SUFFIX) || EXTRA_ORE_NAMES.contains(path);
    }

    private static boolean inAnyTag(Item item, List<TagKey<Item>> tags) {
        // Through a stack: Item#builtInRegistryHolder is deprecated in 1.21.1.
        ItemStack probe = item.getDefaultInstance();
        for (TagKey<Item> tag : tags) {
            if (probe.is(tag)) {
                return true;
            }
        }
        return false;
    }

    /** Registry path of the item, e.g. {@code deepslate_redstone_ore}. */
    private static String pathOf(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "" : id.getPath();
    }

    private static TagKey<Item> tag(String namespace, String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }
}
