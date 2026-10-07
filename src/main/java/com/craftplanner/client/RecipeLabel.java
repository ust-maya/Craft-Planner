package com.craftplanner.client;

import com.craftplanner.engine.RecipeIngredients;
import com.craftplanner.graph.IngredientUtil;
import com.craftplanner.graph.ItemKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.fml.ModList;

/**
 * A readable name for one recipe, and the lines that describe it.
 *
 * <p>A {@code RecipeHolder} carries no display name in 1.21.1: a machine's GUI
 * title lives in the mod that registered it, which the planner may not even
 * depend on. What is always available is the {@link RecipeType} registry key and
 * the namespace of the recipe id, and together they say exactly what a player
 * needs to tell two alternatives apart — {@code Centrifuge · Modern
 * Industrialization} against {@code Crafting · Minecraft}. Machine mods register
 * one type per machine, so the type key is the machine's name, not a generic
 * "machine". The key itself is English, so {@link MachineNames} is asked for the
 * name of the block that machine is, and only a machine with no block of its own
 * is still named by its registry path.</p>
 *
 * <p>Names are cached because both lookups walk a registry or a mod list and the
 * labels are produced while the tooltip or the picker is being drawn.</p>
 */
public final class RecipeLabel {

    private static final Map<String, String> MOD_NAMES = new HashMap<>();

    private RecipeLabel() {
    }

    /** {@code "Кузнечный молот · Modern Industrialization"} for a machine recipe. */
    public static Component of(RecipeHolder<?> holder) {
        ResourceLocation typeKey = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
        String what = MachineNames.of(typeKey);
        if (what == null) {
            // No block names this machine in the player's language: the registry
            // path is still the one thing that tells two alternatives apart.
            what = beautify(typeKey != null ? typeKey.getPath() : holder.id().getPath());
        }
        String from = modName(holder.id().getNamespace());
        return Component.literal(from.isEmpty() ? what : what + " · " + from);
    }

    /**
     * The recipe as tooltip or picker rows: its name, what it produces and what
     * it consumes. Ingredients are the representative of each slot — a tag shows
     * one of its items, which is also what the tree expands to.
     */
    public static List<Component> describe(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        List<Component> lines = new ArrayList<>();
        lines.add(of(holder));
        ItemStack result = holder.value().getResultItem(registries);
        if (!result.isEmpty()) {
            lines.add(Component.translatable("gui.craftplanner.recipe.output",
                    result.getHoverName(), Integer.toString(result.getCount())));
        }
        StringBuilder needs = new StringBuilder();
        for (Ingredient ingredient : RecipeIngredients.of(holder.value())) {
            ItemKey key = IngredientUtil.representativeKey(ingredient);
            if (key == null) {
                continue;
            }
            ItemStack representative = IngredientUtil.representative(ingredient);
            if (needs.length() > 0) {
                needs.append(", ");
            }
            needs.append(key.name().getString())
                    .append(" ×")
                    .append(representative == null ? 1 : Math.max(1, representative.getCount()));
        }
        lines.add(needs.length() == 0
                ? Component.translatable("gui.craftplanner.recipe.none")
                : Component.translatable("gui.craftplanner.recipe.needs", needs.toString()));
        return lines;
    }

    /** Display name of the mod that owns {@code namespace}; empty for vanilla. */
    private static String modName(String namespace) {
        return MOD_NAMES.computeIfAbsent(namespace, id -> ModList.get()
                .getModContainerById(id)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(""));
    }

    /**
     * Registry path to words: the last segment only ({@code alloy/mixer/dust}
     * says nothing a plain {@code dust} would not), underscores turned into
     * spaces and the first letter capitalised.
     */
    private static String beautify(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        name = name.replace('_', ' ').trim();
        if (name.isEmpty()) {
            return path;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
