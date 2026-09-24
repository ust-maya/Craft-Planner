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
 * Resources the tree ends at instead of expanding them into their own
 * ingredients.
 *
 * <p><b>Planks</b> are the first entry. A recipe almost always asks for the
 * {@code minecraft:planks} tag, so the branch would otherwise continue with an
 * arbitrary wood species — whichever item the tag happens to list first — and
 * read as {@code crafting table -> 4 oak planks -> 1 oak log -> ...}. The log
 * is also the wrong unit to plan with: taking a log apart into planks and
 * putting four planks back together is a mechanical conversion done once at
 * the start of a world, not a step of the plan. Ending the branch at the planks
 * keeps the species choice where it belongs (the node still carries every plank
 * type as a candidate) and counts the planks as raw material, so the shopping
 * list says "8 oak planks" instead of "2 oak logs".</p>
 *
 * <p>The idea is the same as {@link ResourceFilter} has for ores, the mechanism
 * is not: ores are dropped from the tree, so the item that needed the ore
 * becomes raw, while planks stay visible and simply end the branch.</p>
 *
 * <p>Membership comes from the vanilla {@code minecraft:planks} tag plus the
 * common {@code c:planks} / {@code forge:planks} aliases, with the
 * {@code _planks} name suffix as a fallback for packs that do not use tags at
 * all.</p>
 */
public final class BaseMaterials {

    private static final List<TagKey<Item>> PLANKS_TAGS = List.of(
            tag("minecraft", "planks"),
            tag("c", "planks"),
            tag("forge", "planks"));

    private static final String PLANKS_SUFFIX = "_planks";

    private BaseMaterials() {
    }

    /**
     * True when the branch must stop at this item: no recipe is looked up for
     * it and it is treated as raw material, exactly like an item that has no
     * recipe at all.
     */
    public static boolean isBaseMaterial(ItemKey key) {
        return key != null && isPlanks(key.item());
    }

    /** Planks of any wood species, tagged or named. */
    public static boolean isPlanks(Item item) {
        if (item == null) {
            return false;
        }
        if (inAnyTag(item, PLANKS_TAGS)) {
            return true;
        }
        return pathOf(item).endsWith(PLANKS_SUFFIX);
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

    /** Registry path of the item, e.g. {@code spruce_planks}. */
    private static String pathOf(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "" : id.getPath();
    }

    private static TagKey<Item> tag(String namespace, String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }
}
