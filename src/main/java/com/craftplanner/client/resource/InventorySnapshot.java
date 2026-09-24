package com.craftplanner.client.resource;

import com.craftplanner.graph.CraftMath;
import com.craftplanner.graph.ItemKey;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What the player currently carries (Stage 9).
 *
 * <p>Two counts are kept: exact, per {@link ItemKey} (item plus its data
 * components) and coarse, per {@link Item} — so an enchanted pickaxe still
 * counts towards a plain pickaxe requirement, but never the other way round.</p>
 *
 * <p>Only the main inventory is scanned: armour and offhand are not craft
 * materials in practice. AE2 / RS networks can be added later by extending
 * {@link #counts(Player)} with their storage APIs.</p>
 */
public final class InventorySnapshot {

    private final Map<ItemKey, Long> exact = new HashMap<>();
    private final Map<Item, Long> byItem = new HashMap<>();

    private InventorySnapshot() {
    }

    public static InventorySnapshot of(Player player) {
        InventorySnapshot snapshot = new InventorySnapshot();
        if (player == null) {
            return snapshot;
        }
        for (ItemStack stack : counts(player)) {
            if (stack.isEmpty()) {
                continue;
            }
            ItemKey key = ItemKey.of(stack);
            snapshot.exact.merge(key, (long) stack.getCount(), CraftMath::saturatingAdd);
            snapshot.byItem.merge(stack.getItem(), (long) stack.getCount(), CraftMath::saturatingAdd);
        }
        return snapshot;
    }

    private static NonNullList<ItemStack> counts(Player player) {
        return player.getInventory().items;
    }

    /**
     * Amount carried that can satisfy the requirement.
     *
     * <p>Stacks matching the key exactly (item + components) are counted
     * first. Only when the requirement is a plain item and nothing plain is
     * carried does the coarse per-{@link Item} count kick in — a damaged or
     * enchanted pickaxe still satisfies "an iron pickaxe". A requirement that
     * carries components is never satisfied by plain stacks.</p>
     */
    public long available(ItemKey key) {
        long exactAmount = exact.getOrDefault(key, 0L);
        if (exactAmount > 0 || key.hasComponents()) {
            return exactAmount;
        }
        return byItem.getOrDefault(key.item(), 0L);
    }

    public boolean isEmpty() {
        return exact.isEmpty();
    }

    public int distinctItems() {
        return exact.size();
    }
}
