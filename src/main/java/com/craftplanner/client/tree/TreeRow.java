package com.craftplanner.client.tree;

/**
 * One row of the craft tree, as the layout needs to see it.
 *
 * <p>The row carries <em>meaning</em> (what kind of node this is, how the
 * inventory covers it, whether a recipe can be switched) and never a colour:
 * mapping these enums to pixels is the renderer's job, which keeps the geometry
 * of the tree free of anything that needs a running game. {@code N} is the
 * node type of whoever builds the tree — {@code RecipeNode} in the mod, a plain
 * {@code String} in a test.</p>
 */
public final class TreeRow<N> {

    /** Role of the node in the plan. */
    public enum Kind {
        /** A target the player added. */
        ROOT,
        /** A sub-assembly produced by a recipe. */
        ASSEMBLY,
        /** A leaf that has to be gathered. */
        MATERIAL,
        /** A node that would recurse into itself. */
        CYCLE
    }

    /** How far the inventory covers a material. */
    public enum Status {
        COVERED,
        PARTIAL,
        MISSING,
        /** Not a material, or no inventory comparison available. */
        UNKNOWN
    }

    /** What the recipe column of this row can do. */
    public enum Badge {
        /** Nothing to choose: the item has no recipe. */
        NONE,
        /** Exactly one way — the picker still opens, for the tree-cut switch. */
        SINGLE,
        /** Several ways, {@code selected}/{@code options} says which. */
        MULTIPLE,
        /** The player cut the branch here; the item counts as material. */
        CUT
    }

    private final N node;
    private final int depth;
    /** Index of the planned goal this row belongs to — colours its branch. */
    private final int branch;
    /** Fold key, {@code null} when the row has no children. */
    private final String path;
    private final Kind kind;
    private final Status status;
    private final Badge badge;
    private final int options;
    /** 1-based position of the chosen recipe among {@link #options}. */
    private final int selected;
    private final String name;
    private final long amount;
    private final boolean foldable;

    private int lastChildIndex;
    private int hiddenRows;

    public TreeRow(N node, int depth, int branch, String path, Kind kind, Status status,
            Badge badge, int options, int selected, String name, long amount, boolean foldable) {
        this.node = node;
        this.depth = depth;
        this.branch = branch;
        this.path = path;
        this.kind = kind;
        this.status = status;
        this.badge = badge;
        this.options = options;
        this.selected = selected;
        this.name = name;
        this.amount = amount;
        this.foldable = foldable;
        this.lastChildIndex = -1;
    }

    public N node() {
        return node;
    }

    public int depth() {
        return depth;
    }

    public int branch() {
        return branch;
    }

    public String path() {
        return path;
    }

    public Kind kind() {
        return kind;
    }

    public Status status() {
        return status;
    }

    public Badge badge() {
        return badge;
    }

    public int options() {
        return options;
    }

    public int selected() {
        return selected;
    }

    public String name() {
        return name;
    }

    public long amount() {
        return amount;
    }

    /** True when the row owns a branch that can be folded away. */
    public boolean foldable() {
        return foldable;
    }

    /** True while the row's own children are folded up. */
    public boolean folded() {
        return hiddenRows > 0;
    }

    /** Rows the folded branch would draw, children of the hidden rows included. */
    public int hiddenRows() {
        return hiddenRows;
    }

    public int lastChildIndex() {
        return lastChildIndex;
    }

    /** Row index of the last drawn child, and the count of the hidden ones. */
    void setSpan(int lastChildIndex, int hiddenRows) {
        this.lastChildIndex = lastChildIndex;
        this.hiddenRows = hiddenRows;
    }

    /** True while this row's children are drawn below it. */
    public boolean expands(int index) {
        return lastChildIndex > index;
    }
}
