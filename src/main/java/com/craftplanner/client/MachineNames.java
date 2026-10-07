package com.craftplanner.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The machine a recipe type belongs to, named the way the player's language
 * names it — {@code «Кузнечный молот»} rather than {@code "Forge hammer"}.
 *
 * <p>A {@code RecipeType} registry key is written by the mod that registers it,
 * so beautifying it always reads as English. The machine the player actually
 * places does carry a translated name: its block. So the block is looked up
 * instead, in the order a player would recognise it:</p>
 *
 * <ol>
 *   <li>the block with the type's own id ({@code modern_industrialization:forge_hammer});</li>
 *   <li>the block in the same namespace whose id ends with the type path — machine
 *       mods register one recipe type per machine kind and a block per tier, so
 *       MI's type {@code macerator} is the block {@code steel_macerator},
 *       {@code bronze_macerator} or {@code electric_macerator}. The shortest id
 *       wins, then the alphabetically first, so the answer never depends on
 *       registry order;</li>
 *   <li>the block a vanilla type belongs to — {@code minecraft:smelting} is the
 *       furnace, not a block called "smelting".</li>
 * </ol>
 *
 * <p>A block without a translation of its own is skipped: a mod that registers
 * the machine but ships no language entry for it would otherwise print the raw
 * key, which is worse than the English type name {@link RecipeLabel} falls back
 * to. Names are cached because the lookup walks a registry, and the picker and
 * the tooltips ask while they are being drawn; the cache is dropped when the
 * language changes, the same way vanilla's own tooltip cache is.</p>
 */
public final class MachineNames {

    /** Vanilla recipe types name an activity, not a block — this is where it happens. */
    private static final Map<String, Block> VANILLA_TYPE_BLOCKS = Map.of(
            "crafting", Blocks.CRAFTING_TABLE,
            "smelting", Blocks.FURNACE,
            "blasting", Blocks.BLAST_FURNACE,
            "smoking", Blocks.SMOKER,
            "campfire_cooking", Blocks.CAMPFIRE,
            "stonecutting", Blocks.STONECUTTER,
            "smithing", Blocks.SMITHING_TABLE);

    /** Cached answers per recipe type; empty means "no block names this machine". */
    private static final Map<ResourceLocation, String> NAMES = new HashMap<>();
    /** Block ids grouped by namespace, in the order the lookup prefers them. */
    private static final Map<String, List<RegisteredBlock>> BLOCKS_BY_NAMESPACE = new HashMap<>();
    private static Language cachedLanguage;

    private MachineNames() {
    }

    /** A block with the id it is registered under. */
    private record RegisteredBlock(ResourceLocation id, Block block) {
    }

    /** Translated machine name of {@code typeId}, or null when no block carries one. */
    public static String of(ResourceLocation typeId) {
        if (typeId == null) {
            return null;
        }
        Language language = Language.getInstance();
        if (cachedLanguage != language) {
            NAMES.clear();
            cachedLanguage = language;
        }
        if (!NAMES.containsKey(typeId)) {
            String found = resolve(typeId);
            NAMES.put(typeId, found == null ? "" : found);
        }
        String name = NAMES.get(typeId);
        return name.isEmpty() ? null : name;
    }

    private static String resolve(ResourceLocation typeId) {
        String path = typeId.getPath();
        for (RegisteredBlock registered : blocksOf(typeId.getNamespace())) {
            if (registered.id().getPath().equals(path)) {
                String name = named(registered.block());
                if (name != null) {
                    return name;
                }
            }
        }
        String suffix = "_" + path;
        for (RegisteredBlock registered : blocksOf(typeId.getNamespace())) {
            if (registered.id().getPath().endsWith(suffix)) {
                String name = named(registered.block());
                if (name != null) {
                    return name;
                }
            }
        }
        if ("minecraft".equals(typeId.getNamespace())) {
            Block vanilla = VANILLA_TYPE_BLOCKS.get(path);
            if (vanilla != null) {
                return named(vanilla);
            }
        }
        return null;
    }

    /** The block's own name, only when the current language actually has it. */
    private static String named(Block block) {
        String key = block.getDescriptionId();
        return block != Blocks.AIR && I18n.exists(key) ? block.getName().getString() : null;
    }

    private static List<RegisteredBlock> blocksOf(String namespace) {
        return BLOCKS_BY_NAMESPACE.computeIfAbsent(namespace, id -> {
            List<RegisteredBlock> built = new ArrayList<>();
            for (Map.Entry<ResourceKey<Block>, Block> entry : BuiltInRegistries.BLOCK.entrySet()) {
                ResourceLocation blockId = entry.getKey().location();
                if (blockId.getNamespace().equals(id)) {
                    built.add(new RegisteredBlock(blockId, entry.getValue()));
                }
            }
            built.sort(Comparator.comparingInt((RegisteredBlock registered) ->
                            registered.id().getPath().length())
                    .thenComparing(registered -> registered.id().getPath()));
            return built;
        });
    }
}
