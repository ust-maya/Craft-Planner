package com.craftplanner.client.resource;

import com.craftplanner.graph.CraftMath;
import com.craftplanner.graph.ItemKey;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Matches the shopping list against the player's inventory (Stage 9).
 */
public final class ResourceCheck {

    private ResourceCheck() {
    }

    /** Availability for every requested raw material, in requirement order. */
    public static Map<ItemKey, Availability> evaluate(
            Map<ItemKey, Long> requirements, InventorySnapshot snapshot) {
        return evaluate(requirements, snapshot, Map.of());
    }

    /**
     * Availability for every requested material against what the inventory still
     * holds once {@code reserved} — what the tree's assemblies took — is set aside.
     */
    public static Map<ItemKey, Availability> evaluate(Map<ItemKey, Long> requirements,
            InventorySnapshot snapshot, Map<ItemKey, Long> reserved) {
        if (requirements.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<ItemKey, Availability> result = new LinkedHashMap<>();
        requirements.forEach((key, required) -> result.put(key, new Availability(required,
                Math.max(0, snapshot.available(key) - reserved.getOrDefault(key, 0L)))));
        return result;
    }

    /**
     * One verdict for several materials: covered only when every one is, missing
     * only when none of them is even partly there, partial otherwise. A recipe that
     * consumes nothing is covered.
     */
    public static Availability.Status combined(Collection<Availability.Status> statuses) {
        boolean anyShort = false;
        boolean anyThere = false;
        for (Availability.Status status : statuses) {
            anyShort |= status != Availability.Status.OK;
            anyThere |= status != Availability.Status.MISSING;
        }
        if (!anyShort) {
            return Availability.Status.OK;
        }
        return anyThere ? Availability.Status.PARTIAL : Availability.Status.MISSING;
    }

    /** Sum of everything that is still missing. */
    public static long totalMissing(Map<ItemKey, Availability> availability) {
        return totalMissing(availability.values());
    }

    static long totalMissing(Iterable<Availability> availability) {
        long total = 0;
        for (Availability value : availability) {
            total = CraftMath.saturatingAdd(total, value.missing());
        }
        return total;
    }

    /** How many distinct materials are not fully covered. */
    public static long deficitTypes(Map<ItemKey, Availability> availability) {
        return availability.values().stream().filter(value -> !value.satisfied()).count();
    }
}
