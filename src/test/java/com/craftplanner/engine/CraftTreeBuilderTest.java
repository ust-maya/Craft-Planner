package com.craftplanner.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.craftplanner.Config;
import com.craftplanner.graph.CraftGraph;
import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.NodeKind;
import com.craftplanner.graph.RecipeNode;
import com.electronwill.nightconfig.core.CommentedConfig;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Edge cases of the recursive craft-tree expansion: empty and broken recipes,
 * goals of zero or negative size, saturated quantities, recipe cycles, grouped
 * ingredient slots and inventory that covers part of the demand.
 *
 * <p>The engine reads Minecraft's registry and the mod config, so the test
 * bootstraps the game's built-in registries once and gives {@link Config} an
 * in-memory default file. Recipes are built with vanilla {@link ShapelessRecipe}
 * and a real {@link RecipeManager}, which is the same path the game takes.</p>
 */
class CraftTreeBuilderTest {

    private static final String NAMESPACE = "craftplanner_edge_tests";
    private static final Map<ItemKey, ResourceLocation> NO_PREFERENCES = Map.of();

    @BeforeAll
    static void bootstrapGame() throws Exception {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }
        Bootstrap.bootStrap();
        loadDefaultConfig();
    }

    /**
     * The engine asks {@link Config} for its depth and node limits. A plain
     * unit test has no mod-loading config file, so give the spec an in-memory
     * one with every value at its default. The loaded-config implementation is
     * package-private in NeoForge, hence the reflection; no test value differs
     * from production's default.
     */
    private static void loadDefaultConfig() throws Exception {
        if (Config.SPEC.isLoaded()) {
            return;
        }
        CommentedConfig config = CommentedConfig.inMemory();
        Config.SPEC.correct(config);
        Constructor<?> constructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
                .getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
        constructor.setAccessible(true);
        IConfigSpec.ILoadedConfig loaded = (IConfigSpec.ILoadedConfig) constructor.newInstance(config, null, null);
        Config.SPEC.acceptConfig(loaded);
    }

    private static RecipeHolder<?> recipe(String id, ItemStack output, Ingredient... ingredients) {
        NonNullList<Ingredient> slots = NonNullList.create();
        Collections.addAll(slots, ingredients);
        ShapelessRecipe recipe = new ShapelessRecipe("", CraftingBookCategory.MISC, output, slots);
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, id), recipe);
    }

    private static RecipeIndex index(RecipeHolder<?>... recipes) {
        RecipeManager manager = new RecipeManager(RegistryAccess.EMPTY);
        manager.replaceRecipes(Arrays.asList(recipes));
        return RecipeIndex.build(manager, RegistryAccess.EMPTY);
    }

    private static CraftGraph rebuild(RecipeIndex index, long amount, Item item) {
        return rebuild(index, amount, item, Stock.empty());
    }

    private static CraftGraph rebuild(RecipeIndex index, long amount, Item item, Stock<ItemKey> stock) {
        CraftGraph graph = new CraftGraph();
        graph.addRoot(ItemKey.of(item), amount);
        new CraftTreeBuilder(index, NO_PREFERENCES, ResourceFilter.NONE, Set.of(), stock, SourceFilter.NONE)
                .rebuild(graph);
        return graph;
    }

    @Test
    void aGoalNothingProducesStaysRaw() {
        CraftGraph graph = rebuild(index(), 5, Items.DIAMOND);
        RecipeNode goal = graph.roots().get(0);

        assertEquals(NodeKind.ROOT, goal.kind());
        assertNull(goal.recipe());
        assertTrue(goal.isLeaf());
        assertEquals(5, goal.required());
        assertEquals(Map.of(ItemKey.of(Items.DIAMOND), 5L), graph.aggregateRequirements());
    }

    @Test
    void aMissingRecipeBelowAGoalEndsAsRaw() {
        CraftGraph graph = rebuild(
                index(recipe("log_from_stick", new ItemStack(Items.OAK_LOG), Ingredient.of(Items.STICK))),
                1, Items.OAK_LOG);
        RecipeNode goal = graph.roots().get(0);
        RecipeNode stick = goal.children().get(0).child();

        assertEquals(NodeKind.RAW, stick.kind());
        assertNull(stick.recipe());
        assertTrue(stick.isLeaf());
        assertEquals(1, stick.required());
    }

    @Test
    void aRecipeWithoutIngredientSlotsIsNotIndexed() {
        // A recipe with a non-empty result but no input slots at all. The
        // vanilla isIncomplete() check drops it from the index, so the engine
        // never tries to expand it.
        CraftGraph graph = rebuild(index(recipe("empty_sticks", new ItemStack(Items.STICK))), 4, Items.STICK);
        RecipeNode goal = graph.roots().get(0);

        assertEquals(NodeKind.ROOT, goal.kind());
        assertNull(goal.recipe());
        assertTrue(goal.alternatives().isEmpty());
        assertTrue(goal.isLeaf());
    }

    @Test
    void anIngredientThatMatchesNothingLeavesTheBranchRaw() {
        // A readable input slot whose tag resolves to no item. The recipe stays
        // on the row as its description, but there is nothing below it to
        // expand. The index normally filters an incomplete recipe out; this
        // subclass keeps it indexed so the engine's own fallback is exercised.
        NonNullList<Ingredient> slots = NonNullList.create();
        slots.add(Ingredient.EMPTY);
        RecipeHolder<?> broken = new RecipeHolder<>(
                ResourceLocation.fromNamespaceAndPath(NAMESPACE, "broken_tag"),
                new UnfilteredShapelessRecipe(new ItemStack(Items.STICK), slots));

        CraftGraph graph = rebuild(index(broken), 4, Items.STICK);
        RecipeNode goal = graph.roots().get(0);

        assertNotNull(goal.recipe());
        assertTrue(goal.isLeaf());
    }

    @Test
    void zeroAndNegativeAmountsAreFlooredToACraftableGoal() {
        RecipeIndex index = index(recipe("sticks", new ItemStack(Items.STICK), Ingredient.of(Items.OAK_LOG)));

        for (long amount : new long[] {0, -7}) {
            CraftGraph graph = rebuild(index, amount, Items.STICK);
            RecipeNode goal = graph.roots().get(0);

            assertEquals(1, goal.required(), "a goal of " + amount + " is still one item");
            assertEquals(1, goal.crafts());
            assertEquals(1, goal.children().get(0).child().required());
        }
    }

    @Test
    void aVeryLargeGoalSaturatesInsteadOfWrapping() {
        // 4 sticks per craft from 8 logs: the craft count times the per-craft
        // count of the log child is larger than Long.MAX_VALUE.
        CraftGraph graph = rebuild(
                index(recipe("stick_batch", new ItemStack(Items.STICK, 4), Ingredient.of(new ItemStack(Items.OAK_LOG, 8)))),
                Long.MAX_VALUE, Items.STICK);
        RecipeNode goal = graph.roots().get(0);
        RecipeNode logs = goal.children().get(0).child();

        assertEquals(Math.ceilDiv(Long.MAX_VALUE, 4), goal.crafts());
        assertEquals(1, goal.children().size());
        assertEquals(Long.MAX_VALUE, logs.required());
        assertTrue(logs.required() > 0, "a saturated amount must not wrap into a negative");
        assertEquals(NodeKind.RAW, logs.kind());
    }

    @Test
    void aRecipeCycleIsCutWithACycleMarker() {
        RecipeHolder<?> diamond = recipe("diamond", new ItemStack(Items.DIAMOND), Ingredient.of(Items.EMERALD));
        RecipeHolder<?> emerald = recipe("emerald", new ItemStack(Items.EMERALD), Ingredient.of(Items.GOLD_INGOT));
        RecipeHolder<?> gold = recipe("gold", new ItemStack(Items.GOLD_INGOT), Ingredient.of(Items.DIAMOND));

        CraftGraph graph = rebuild(index(diamond, emerald, gold), 1, Items.DIAMOND);
        RecipeNode diamondNode = graph.roots().get(0);
        RecipeNode emeraldNode = diamondNode.children().get(0).child();
        RecipeNode goldNode = emeraldNode.children().get(0).child();
        RecipeNode loop = goldNode.children().get(0).child();

        assertEquals(ItemKey.of(Items.DIAMOND), loop.item());
        assertEquals(3, loop.depth());
        assertEquals(NodeKind.CYCLE, loop.kind());
        assertTrue(loop.children().isEmpty(), "a cycle marker must not recurse further");
    }

    @Test
    void theInventoryCoversPartOfTheGoalBeforeCrafting() {
        Stock<ItemKey> stock = new Stock<>(Map.of(ItemKey.of(Items.STICK), 3L)::get);
        CraftGraph graph = rebuild(
                index(recipe("sticks", new ItemStack(Items.STICK, 4), Ingredient.of(Items.OAK_LOG))),
                10, Items.STICK, stock);
        RecipeNode goal = graph.roots().get(0);

        assertEquals(3, goal.fromInventory());
        assertEquals(7, goal.toMake());
        assertEquals(2, goal.crafts());
        assertEquals(2, goal.children().get(0).child().required());
    }

    @Test
    void aFullyCoveredGoalKeepsItsRecipeButStopsExpanding() {
        Stock<ItemKey> stock = new Stock<>(Map.of(ItemKey.of(Items.STICK), 10L)::get);
        CraftGraph graph = rebuild(
                index(recipe("sticks", new ItemStack(Items.STICK, 4), Ingredient.of(Items.OAK_LOG))),
                3, Items.STICK, stock);
        RecipeNode goal = graph.roots().get(0);

        assertEquals(3, goal.fromInventory());
        assertEquals(0, goal.toMake());
        assertEquals(0, goal.crafts());
        assertTrue(goal.isLeaf());
        assertNotNull(goal.recipe());
    }

    @Test
    void severalSlotsOfOneItemBecomeOneChild() {
        CraftGraph graph = rebuild(
                index(recipe("sticks", new ItemStack(Items.STICK),
                        Ingredient.of(Items.OAK_LOG), Ingredient.of(Items.OAK_LOG))),
                10, Items.STICK);
        RecipeNode goal = graph.roots().get(0);

        assertEquals(10, goal.crafts());
        assertEquals(1, goal.children().size(), "the two log slots must be one row");
        assertEquals(20, goal.children().get(0).child().required());
    }

    @Test
    void theRecipeOutputDividesTheCraftCount() {
        CraftGraph graph = rebuild(
                index(recipe("stick_batch", new ItemStack(Items.STICK, 4), Ingredient.of(Items.OAK_LOG))),
                10, Items.STICK);
        RecipeNode goal = graph.roots().get(0);

        assertEquals(3, goal.crafts(), "10 sticks at 4 per craft take 3 crafts");
        assertEquals(3, goal.children().get(0).child().required());
    }

    @Test
    void aBaseMaterialEndsTheBranchWithoutARecipe() {
        CraftGraph graph = rebuild(
                index(recipe("planks", new ItemStack(Items.OAK_PLANKS), Ingredient.of(Items.OAK_LOG))),
                4, Items.OAK_PLANKS);
        RecipeNode goal = graph.roots().get(0);

        assertEquals(NodeKind.ROOT, goal.kind());
        assertNull(goal.recipe());
        assertTrue(goal.isLeaf());
        assertEquals(4, goal.required());
    }

    /** A recipe the index keeps although its only input slot resolves to nothing. */
    private static final class UnfilteredShapelessRecipe extends ShapelessRecipe {

        UnfilteredShapelessRecipe(ItemStack result, NonNullList<Ingredient> ingredients) {
            super("", CraftingBookCategory.MISC, result, ingredients);
        }

        @Override
        public boolean isIncomplete() {
            return false;
        }
    }
}
