package com.craftplanner.client;

import com.craftplanner.graph.ItemKey;
import com.craftplanner.graph.RecipeNode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persists the planner's target items and quantities between game starts. */
public final class PlanStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("craftplanner/PlanStore");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PlanStore() {
    }

    /** Saved target roots, in display order. Derived recipe trees are rebuilt at runtime. */
    public static List<Goal> read() {
        List<Goal> goals = new ArrayList<>();
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return goals;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return goals;
            }
            JsonElement roots = parsed.getAsJsonObject().get("roots");
            if (roots == null || !roots.isJsonArray()) {
                return goals;
            }
            for (JsonElement element : roots.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject root = element.getAsJsonObject();
                JsonElement itemElement = root.get("item");
                JsonElement amountElement = root.get("amount");
                if (itemElement == null || !itemElement.isJsonPrimitive()
                        || amountElement == null || !amountElement.isJsonPrimitive()) {
                    continue;
                }
                ResourceLocation itemId = ResourceLocation.tryParse(itemElement.getAsString());
                if (itemId == null) {
                    continue;
                }
                var item = BuiltInRegistries.ITEM.get(itemId);
                if (item == Items.AIR) {
                    continue; // the mod that added it is gone
                }
                long amount;
                try {
                    amount = amountElement.getAsLong();
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (amount > 0) {
                    goals.add(new Goal(ItemKey.of(item), amount));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Craft Planner could not read {}; the saved plan is ignored.", file, e);
        }
        return goals;
    }

    /** Writes the current roots; recipe nodes and their children are derived from the active world. */
    public static void write(List<RecipeNode> roots) {
        JsonArray savedRoots = new JsonArray();
        for (RecipeNode node : roots) {
            ItemKey key = node.item();
            // Match the existing recipe-choice format: component-bearing keys need a richer codec.
            if (key.hasComponents()) {
                continue;
            }
            JsonObject root = new JsonObject();
            root.addProperty("item", key.id().toString());
            root.addProperty("amount", node.required());
            savedRoots.add(root);
        }
        JsonObject json = new JsonObject();
        json.addProperty("version", 1);
        json.add("roots", savedRoots);

        Path file = file();
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(json, writer);
        } catch (Exception e) {
            LOGGER.warn("Craft Planner could not save the plan to {}.", file, e);
        }
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("craftplanner-plan.json");
    }

    public record Goal(ItemKey item, long amount) {
    }
}