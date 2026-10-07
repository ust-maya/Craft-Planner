package com.craftplanner.client;

import com.craftplanner.graph.ItemKey;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The recipe choices the player made, kept between game starts.
 *
 * <p>One JSON object of {@code "item id": "recipe id"} next to the client
 * config. Recipe ids rather than holders, exactly as in
 * {@link PlannerState#preferences()}: the holders belong to the level that
 * produced them and are worthless after a reload, while an id resolves again
 * against whatever recipes the next session has.</p>
 *
 * <p>Two deliberate limits. Only plain items are stored — an {@link ItemKey}
 * carrying data components (an enchanted book, a machine part with a custom
 * durability) would need the component patch serialised as well, and that is a
 * format of its own; such keys simply are not persisted. And an id that no
 * longer exists in the new session is dropped on read, so removing a mod does
 * not leave a choice pointing at nothing.</p>
 *
 * <p>The plan itself is still not saved anywhere: the choices outlive a restart,
 * the list of targets does not.</p>
 */
public final class RecipeChoices {

    private static final Logger LOGGER = LoggerFactory.getLogger("craftplanner/RecipeChoices");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private RecipeChoices() {
    }

    /** Saved choices, ready to be merged into the live preference map. */
    public static Map<ItemKey, ResourceLocation> read() {
        Map<ItemKey, ResourceLocation> choices = new HashMap<>();
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return choices;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return choices;
            }
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                ResourceLocation itemId = ResourceLocation.tryParse(entry.getKey());
                ResourceLocation recipeId = ResourceLocation.tryParse(entry.getValue().getAsString());
                if (itemId == null || recipeId == null) {
                    continue;
                }
                var item = BuiltInRegistries.ITEM.get(itemId);
                if (item == Items.AIR) {
                    continue; // the mod that added it is gone
                }
                choices.put(ItemKey.of(item), recipeId);
            }
        } catch (Exception e) {
            // A hand-edited or half-written file must not cost the player the
            // session: the choices are a convenience, so fall back to the engine.
            LOGGER.warn("Craft Planner could not read {}; recipe choices are ignored.", file, e);
        }
        return choices;
    }

    /** Writes every choice that can be written; the rest is left in memory. */
    public static void write(Map<ItemKey, ResourceLocation> choices) {
        JsonObject json = new JsonObject();
        for (Map.Entry<ItemKey, ResourceLocation> entry : choices.entrySet()) {
            if (entry.getKey().hasComponents()) {
                continue;
            }
            json.addProperty(entry.getKey().id().toString(), entry.getValue().toString());
        }
        Path file = file();
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(json, writer);
        } catch (Exception e) {
            LOGGER.warn("Craft Planner could not save recipe choices to {}.", file, e);
        }
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("craftplanner-choices.json");
    }
}
