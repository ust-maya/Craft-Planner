package com.craftplanner.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The sources window shows a mod always and its machines only while the mod is
 * opened.
 *
 * <p>The window is the only way a player switches a modpack's machine types on
 * and off, so folding must never hide a mod itself, must keep the mod's own row
 * first, and must leave the list alone when nothing is folded. These tests run
 * the rule over plain strings: the window's real rows carry an item icon, which
 * needs the game's registries.</p>
 */
class SourceRowsTest {

    /** A window row: a mod's own row has no machine type. */
    private record R(String mod, String type) {
    }

    private static final R A = new R("a", null);
    private static final R A1 = new R("a", "a:1");
    private static final R A2 = new R("a", "a:2");
    private static final R B = new R("b", null);
    private static final R B1 = new R("b", "b:1");

    private static final List<R> ROWS = List.of(A, A1, A2, B, B1);
    private static final List<R> NONE = List.of();

    private static List<R> visible(Set<String> opened) {
        return SourceRows.visible(ROWS, row -> row.type() == null, R::mod, opened);
    }

    @Test
    void everyModFoldedKeepsOnlyTheModRows() {
        assertEquals(List.of(A, B), visible(Set.of()));
    }

    @Test
    void anOpenedModAddsItsMachinesRightAfterIt() {
        assertEquals(List.of(A, A1, A2, B), visible(Set.of("a")));
    }

    @Test
    void everyModOpenedKeepsTheWholeList() {
        assertEquals(ROWS, visible(Set.of("a", "b")));
    }

    @Test
    void anUnknownOpenedModChangesNothing() {
        assertEquals(List.of(A, B), visible(Set.of("zzz")));
    }

    @Test
    void noRowsShowNothing() {
        assertEquals(List.of(), SourceRows.visible(NONE, row -> true, R::mod, Set.of("a")));
    }
}
