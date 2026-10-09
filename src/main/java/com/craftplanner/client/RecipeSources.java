package com.craftplanner.client;

import com.craftplanner.engine.SourceFilter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mods and machines the player switched off, kept between game starts.
 *
 * <p>A JSON object with two arrays of ids: {@code hiddenMods} (namespaces) and
 * {@code hiddenTypes} (recipe type ids). Unlike {@link RecipeCuts}, an id this
 * session does not know is kept: a mod missing for one start is no reason to
 * forget that the player had switched it off.</p>
 */
public final class RecipeSources {

    private static final Logger LOGGER = LoggerFactory.getLogger("craftplanner/RecipeSources");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private RecipeSources() {
    }

    /** The saved filter, or {@link SourceFilter#NONE} when there is none or it cannot be read. */
    public static SourceFilter read() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return SourceFilter.NONE;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return SourceFilter.NONE;
            }
            JsonObject json = parsed.getAsJsonObject();
            return new SourceFilter(strings(json, "hiddenMods"), strings(json, "hiddenTypes"));
        } catch (Exception e) {
            // A hand-edited or half-written file must not cost the player the
            // session: with nothing switched off the plan is what it always was.
            LOGGER.warn("Craft Planner could not read {}; every recipe source is used.", file, e);
            return SourceFilter.NONE;
        }
    }

    public static void write(SourceFilter filter) {
        JsonObject json = new JsonObject();
        json.add("hiddenMods", array(filter.hiddenMods()));
        json.add("hiddenTypes", array(filter.hiddenTypes()));
        Path file = file();
        try {
            JsonFiles.writeAtomically(file, json, GSON);
        } catch (Exception e) {
            LOGGER.warn("Craft Planner could not save the recipe sources to {}.", file, e);
        }
    }

    private static Set<String> strings(JsonObject json, String key) {
        Set<String> values = new TreeSet<>();
        JsonElement element = json.get(key);
        if (element != null && element.isJsonArray()) {
            for (JsonElement value : element.getAsJsonArray()) {
                if (value.isJsonPrimitive()) {
                    values.add(value.getAsString());
                }
            }
        }
        return values;
    }

    /** Sorted, so the file does not reshuffle on every save. */
    private static JsonArray array(Set<String> values) {
        JsonArray array = new JsonArray();
        for (String value : new TreeSet<>(values)) {
            array.add(value);
        }
        return array;
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("craftplanner-sources.json");
    }
}
