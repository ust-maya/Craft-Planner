package com.craftplanner.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Helpers around vanilla {@link Ingredient}: expansion to concrete stacks,
 * tag detection and representative-item selection.
 *
 * <p>Ingredients can describe several items at once (tag or explicit list), and
 * two different items are picked from that list on purpose: the engine expands an
 * ingredient to the <em>cheapest</em> item it accepts, because that is the plan the
 * player wants, while {@link #representative} answers with the <em>first</em> one,
 * which is what a readable label or a cycle check needs — a label must describe the
 * recipe as written, not the plan it happens to feed. {@link #keys} keeps the
 * alternatives the player can switch to.</p>
 */
public final class IngredientUtil {

    private IngredientUtil() {
    }

    public static boolean isEmpty(Ingredient ingredient) {
        return ingredient == null || ingredient.isEmpty();
    }

    /** Every stack the ingredient accepts, in declaration order. */
    public static List<ItemStack> stacks(Ingredient ingredient) {
        List<ItemStack> stacks = new ArrayList<>();
        if (isEmpty(ingredient)) {
            return stacks;
        }
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) {
                stacks.add(stack.copy());
            }
        }
        return stacks;
    }

    /** Same as {@link #stacks} but as graph keys (count dropped). */
    public static List<ItemKey> keys(Ingredient ingredient) {
        List<ItemKey> keys = new ArrayList<>();
        for (ItemStack stack : stacks(ingredient)) {
            keys.add(ItemKey.of(stack));
        }
        return keys;
    }

    /**
     * First accepted stack, or {@code null} when the ingredient matches
     * nothing (missing mod / datapack entry).
     */
    public static ItemStack representative(Ingredient ingredient) {
        List<ItemStack> stacks = stacks(ingredient);
        return stacks.isEmpty() ? null : stacks.get(0);
    }

    /** First accepted item as a key, or {@code null} if nothing matches. */
    public static ItemKey representativeKey(Ingredient ingredient) {
        ItemStack stack = representative(ingredient);
        return stack == null ? null : ItemKey.of(stack);
    }

    /** Tag this ingredient is built from, if any. */
    public static Optional<TagKey<Item>> tagOf(Ingredient ingredient) {
        if (isEmpty(ingredient)) {
            return Optional.empty();
        }
        for (Ingredient.Value value : ingredient.getValues()) {
            if (value instanceof Ingredient.TagValue tagValue) {
                return Optional.of(tagValue.tag());
            }
        }
        return Optional.empty();
    }

    public static boolean isTag(Ingredient ingredient) {
        return tagOf(ingredient).isPresent();
    }

    /** All items currently bound to the given item tag. */
    public static List<ItemKey> expandTag(TagKey<Item> tag) {
        List<ItemKey> keys = new ArrayList<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
            keys.add(ItemKey.of(holder.value()));
        }
        return keys;
    }

    /** Short human-readable description, used in tooltips and logs. */
    public static String describe(Ingredient ingredient) {
        Optional<TagKey<Item>> tag = tagOf(ingredient);
        if (tag.isPresent()) {
            return "#" + tag.get().location();
        }
        ItemKey key = representativeKey(ingredient);
        return key == null ? "<empty>" : key.id().toString();
    }
}
