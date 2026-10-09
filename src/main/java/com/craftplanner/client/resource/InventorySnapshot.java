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
 * What the player currently carries.
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
     * <p>A component-bearing requirement needs an exact item + component
     * match. A plain-item requirement accepts all stacks of that item, even
     * when they carry components; this keeps the count monotonic as compatible
     * variants are added. Recipe-specific ingredient predicates are not
     * available at this aggregation stage.</p>
     */
    public long available(ItemKey key) {
        return available(key.hasComponents(), exact.getOrDefault(key, 0L),
                byItem.getOrDefault(key.item(), 0L));
    }

    static long available(boolean componentSpecific, long exactAmount, long itemAmount) {
        return componentSpecific ? exactAmount : itemAmount;
    }

    /**
     * Whether {@code other} holds exactly the same stacks. The tree is expanded
     * against the inventory, so it is expanded again only when this says no —
     * a once-a-second rescan that finds the same bag costs a map comparison, not
     * a rebuild.
     */
    public boolean sameCounts(InventorySnapshot other) {
        return other != null && exact.equals(other.exact);
    }
}
