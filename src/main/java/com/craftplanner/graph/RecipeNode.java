package com.craftplanner.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * A node of the craft tree: one item with the amount required to satisfy its
 * parent, plus the recipe chosen to produce it and the resulting children.
 *
 * <p>Nodes are mutable while the engine expands the tree (Stage 3) and are
 * treated as read-only afterwards by the overlay and the shopping list.</p>
 */
public final class RecipeNode {
    private final ItemKey item;
    private final int depth;
    private final List<RecipeEdge> children = new ArrayList<>();
    private final List<RecipeHolder<?>> alternatives = new ArrayList<>();
    private final List<ItemKey> candidates = new ArrayList<>();

    private long required;
    private long producedPerCraft = 1;
    private NodeKind kind;
    private RecipeHolder<?> recipe;
    private int selectedRecipeIndex;
    private int selectedCandidate;

    public RecipeNode(ItemKey item, long required, int depth, NodeKind kind) {
        this.item = item;
        this.required = Math.max(1, required);
        this.depth = depth;
        this.kind = kind;
    }

    public static RecipeNode root(ItemKey item, long required) {
        return new RecipeNode(item, required, 0, NodeKind.ROOT);
    }

    public ItemKey item() {
        return item;
    }

    public int depth() {
        return depth;
    }

    /** Total amount of this item that has to be produced or gathered. */
    public long required() {
        return required;
    }

    public void setRequired(long required) {
        this.required = Math.max(1, required);
    }

    /** Number of craft operations needed: {@code ceil(required / perCraft)}. */
    public long crafts() {
        return CraftMath.craftsFor(required, producedPerCraft);
    }

    public RecipeHolder<?> recipe() {
        return recipe;
    }

    public void setRecipe(RecipeHolder<?> recipe, int outputCount) {
        this.recipe = recipe;
        this.producedPerCraft = Math.max(1, outputCount);
        if (kind == NodeKind.RAW) {
            kind = depth == 0 ? NodeKind.ROOT : NodeKind.INTERMEDIATE;
        }
    }

    /**
     * Forgets the recipe picked by an earlier expansion.
     *
     * <p>The graph keeps its nodes across rebuilds and the engine re-expands
     * them in place, so a recipe that no longer applies has to be dropped
     * explicitly — otherwise the list screen keeps offering a choice for a
     * branch that is not there any more.</p>
     */
    public void clearRecipe() {
        this.recipe = null;
        this.producedPerCraft = 1;
    }

    public NodeKind kind() {
        return kind;
    }

    public void setKind(NodeKind kind) {
        this.kind = kind;
    }

    public boolean isLeaf() {
        return children.isEmpty();
    }

    public List<RecipeEdge> children() {
        return Collections.unmodifiableList(children);
    }

    public RecipeEdge addChild(RecipeNode child) {
        RecipeEdge edge = new RecipeEdge(child);
        children.add(edge);
        return edge;
    }

    public void clearChildren() {
        children.clear();
    }

    /**
     * Other recipes producing the same item — filled by the engine when the
     * output is ambiguous (crafting / smelting / blasting, ...).
     */
    public List<RecipeHolder<?>> alternatives() {
        return Collections.unmodifiableList(alternatives);
    }

    public void addAlternative(RecipeHolder<?> holder) {
        alternatives.add(holder);
    }

    /**
     * Forgets the alternatives of an earlier expansion. Same reason as
     * {@link #clearRecipe()}: without it every rebuild appends the same
     * recipes again and the "N/M" button counts duplicates.
     */
    public void clearAlternatives() {
        alternatives.clear();
    }

    /**
     * Every recipe producing this item, in the order the engine prefers them;
     * {@link #recipe()} sits at {@link #selectedRecipeIndex()}. Rebuilt from
     * {@link #alternatives()} because the engine stores the chosen recipe
     * separately from the ones it rejected.
     */
    public List<RecipeHolder<?>> options() {
        if (recipe == null) {
            return Collections.emptyList();
        }
        List<RecipeHolder<?>> all = new ArrayList<>(alternatives);
        all.add(Math.min(selectedRecipeIndex, all.size()), recipe);
        return Collections.unmodifiableList(all);
    }

    /** Position of the chosen recipe inside {@link #options()}. */
    public int selectedRecipeIndex() {
        return selectedRecipeIndex;
    }

    public void setSelectedRecipeIndex(int index) {
        selectedRecipeIndex = Math.max(0, index);
    }

    /**
     * Candidate items for a tag-based ingredient ({@code #forge:ingots/iron}).
     * {@code selectedCandidate} points at the one currently used.
     */
    public List<ItemKey> candidates() {
        return Collections.unmodifiableList(candidates);
    }

    public void addCandidate(ItemKey key) {
        candidates.add(key);
    }

    public int selectedCandidate() {
        return selectedCandidate;
    }

    public void selectCandidate(int index) {
        if (!candidates.isEmpty()) {
            selectedCandidate = Math.max(0, Math.min(index, candidates.size() - 1));
        }
    }

    /** Display stack, clamped to the item's max stack size. */
    public ItemStack displayStack() {
        int max = item.toStack().getMaxStackSize();
        return item.toStack((int) Math.min(required, Math.max(1, max)));
    }
}
