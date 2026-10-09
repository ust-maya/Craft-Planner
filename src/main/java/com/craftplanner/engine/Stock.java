package com.craftplanner.engine;

import com.craftplanner.graph.CraftMath;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * What the player carries, as one rebuild of the tree spends it.
 *
 * <p>The tree takes from the inventory before it plans anything to be made:
 * three books in the bag are three books nobody has to craft. The stock is
 * shared by the whole rebuild and only goes down, so two branches that both ask
 * for books never count the same ten twice — the first one met takes them
 * first.</p>
 *
 * <p>Generic over the key so it is tested without a game: the mod uses
 * {@code ItemKey}, a test uses strings.</p>
 */
public final class Stock<K> {

    private final Function<K, Long> initial;
    private final Map<K, Long> taken = new HashMap<>();

    /** @param initial how much of an item there was before anything was taken; null means none */
    public Stock(Function<K, Long> initial) {
        this.initial = initial;
    }

    /** A stock with nothing in it: the tree is then planned exactly as before. */
    public static <K> Stock<K> empty() {
        return new Stock<>(key -> 0L);
    }

    /** Takes up to {@code want} of {@code key}; returns how much was actually taken. */
    public long take(K key, long want) {
        if (want <= 0) {
            return 0;
        }
        long got = Math.min(want, left(key));
        if (got > 0) {
            taken.merge(key, got, CraftMath::saturatingAdd);
        }
        return got;
    }

    /** How much of {@code key} is still there. */
    public long left(K key) {
        Long start = initial.apply(key);
        long have = start == null ? 0 : Math.max(0, start);
        return Math.max(0, have - taken.getOrDefault(key, 0L));
    }
}
