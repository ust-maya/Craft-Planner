package com.craftplanner.client;

import com.craftplanner.graph.ItemKey;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The items the player stopped the tree at, kept between game starts.
 *
 * <p>A cut is the only way to stop a branch that the engine would otherwise
 * expand: the branch ends at this item, which is then planned as something to
 * gather rather than crafted from its own ingredients. The tree never decides
 * that on its own — a machine hop the player does not want in the plan is cut
 * by hand, from the {@code ✂} row of the recipe picker. Like the recipe choices
 * of {@link RecipeChoices} it is keyed by item and applies to every place that
 * item appears in the plan, so the two live in sibling files and are loaded
 * together.</p>
 *
 * <p>A JSON array of item ids — a cut carries no recipe id, only the decision
 * "end here". The same limits as {@link RecipeChoices} applies: items with data
 * components are not written, and an id the current session does not know is
 * dropped on read.</p>
 */
public final class RecipeCuts {

    private static final Logger LOGGER = LoggerFactory.getLogger("craftplanner/RecipeCuts");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private RecipeCuts() {
    }

    /** Saved cuts, ready to be merged into the live cut set. */
    public static Set<ItemKey> read() {
        Set<ItemKey> cuts = new LinkedHashSet<>();
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return cuts;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonArray()) {
                return cuts;
            }
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonPrimitive()) {
                    continue;
                }
                ResourceLocation itemId = ResourceLocation.tryParse(element.getAsString());
                if (itemId == null) {
                    continue;
                }
                var item = BuiltInRegistries.ITEM.get(itemId);
                if (item == Items.AIR) {
                    continue; // the mod that added it is gone
                }
                cuts.add(ItemKey.of(item));
            }
        } catch (Exception e) {
            // A hand-edited or half-written file must not cost the player the
            // session: the cuts are a convenience, so fall back to the engine.
            LOGGER.warn("Craft Planner could not read {}; tree cuts are ignored.", file, e);
        }
        return cuts;
    }

    /** Writes every cut that can be written; the rest is left in memory. */
    public static void write(Set<ItemKey> cuts) {
        JsonArray json = new JsonArray();
        for (ItemKey key : cuts) {
            if (key.hasComponents()) {
                continue;
            }
            json.add(key.id().toString());
        }
        Path file = file();
        try {
            JsonFiles.writeAtomically(file, json, GSON);
        } catch (Exception e) {
            LOGGER.warn("Craft Planner could not save tree cuts to {}.", file, e);
        }
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("craftplanner-cuts.json");
    }
}
