package com.craftplanner.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The inventory pool the tree draws from while it expands.
 *
 * <p>The rules that matter are the ones a plan depends on: a covered item must
 * not be crafted again, two branches must not count the same stack twice, and a
 * request of nothing must not disturb the pool. Negative counts are defended
 * against too — a snapshot is built from item stacks, and the tree would rather
 * see an empty shelf than a borrow it cannot repay.</p>
 */
class StockTest {

    private static Stock<String> booksAndBad() {
        return new Stock<>(Map.of("book", 10L, "bad", -5L)::get);
    }

    @Test
    void takingLessThanThereIsLeavesTheRest() {
        Stock<String> stock = booksAndBad();
        assertEquals(3, stock.take("book", 3));
        assertEquals(7, stock.left("book"));
    }

    @Test
    void twoBranchesShareOnePool() {
        Stock<String> stock = booksAndBad();
        assertEquals(3, stock.take("book", 3));
        // The second branch asks for nine but only seven are still there.
        assertEquals(7, stock.take("book", 9));
        assertEquals(0, stock.left("book"));
    }

    @Test
    void anItemThePlayerDoesNotCarryGivesNothing() {
        assertEquals(0, booksAndBad().take("leather", 2));
    }

    @Test
    void askingForNothingTakesNothing() {
        Stock<String> stock = booksAndBad();
        assertEquals(0, stock.take("book", 0));
        assertEquals(10, stock.left("book"));
    }

    @Test
    void anEmptyStockIsAnEmptyBag() {
        assertEquals(0, Stock.<String>empty().take("book", 5));
    }

    @Test
    void aNegativeCountIsReadAsZero() {
        Stock<String> stock = booksAndBad();
        assertEquals(0, stock.take("bad", 1));
        assertEquals(0, stock.left("bad"));
    }
}
