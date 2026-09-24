package com.craftplanner.client.resource;

import com.craftplanner.graph.ItemKey;
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
        if (requirements.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<ItemKey, Availability> result = new LinkedHashMap<>();
        requirements.forEach((key, required) ->
                result.put(key, new Availability(required, snapshot.available(key))));
        return result;
    }

    /** Sum of everything that is still missing. */
    public static long totalMissing(Map<ItemKey, Availability> availability) {
        long total = 0;
        for (Availability value : availability.values()) {
            total += value.missing();
        }
        return total;
    }

    /** How many distinct materials are not fully covered. */
    public static long deficitTypes(Map<ItemKey, Availability> availability) {
        return availability.values().stream().filter(value -> !value.satisfied()).count();
    }
}
