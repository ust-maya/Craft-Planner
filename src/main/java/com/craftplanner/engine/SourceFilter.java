package com.craftplanner.engine;

import java.util.HashSet;
import java.util.Set;

/**
 * Which mods and machines the player keeps out of the plan.
 *
 * <p>A recipe is out when its machine — its recipe type — is switched off, or
 * when its mod is: the mod that wrote the recipe (the namespace of its id) or
 * the mod whose machine runs it (the namespace of its type). Switching Modern
 * Industrialization off therefore removes both its machines and the crafting
 * table recipes it adds. Ids are plain strings here, so the rule is tested
 * without a game.</p>
 */
public record SourceFilter(Set<String> hiddenMods, Set<String> hiddenTypes) {

    /** Nothing switched off: the plan uses every indexed recipe. */
    public static final SourceFilter NONE = new SourceFilter(Set.of(), Set.of());

    public SourceFilter {
        hiddenMods = Set.copyOf(hiddenMods);
        hiddenTypes = Set.copyOf(hiddenTypes);
    }

    public boolean isEmpty() {
        return hiddenMods.isEmpty() && hiddenTypes.isEmpty();
    }

    /** Whether a recipe with this id, run by this recipe type, is kept out of the plan. */
    public boolean hides(String recipeId, String typeId) {
        return hiddenTypes.contains(typeId)
                || hiddenMods.contains(namespaceOf(recipeId))
                || hiddenMods.contains(namespaceOf(typeId));
    }

    public boolean modHidden(String mod) {
        return hiddenMods.contains(mod);
    }

    public boolean typeHidden(String typeId) {
        return hiddenTypes.contains(typeId);
    }

    /** The same filter with one mod switched off or back on. */
    public SourceFilter withMod(String mod, boolean hidden) {
        Set<String> next = new HashSet<>(hiddenMods);
        if (hidden) {
            next.add(mod);
        } else {
            next.remove(mod);
        }
        return new SourceFilter(next, hiddenTypes);
    }

    /** The same filter with one machine switched off or back on. */
    public SourceFilter withType(String typeId, boolean hidden) {
        Set<String> next = new HashSet<>(hiddenTypes);
        if (hidden) {
            next.add(typeId);
        } else {
            next.remove(typeId);
        }
        return new SourceFilter(hiddenMods, next);
    }

    /** The namespace of an id; an id without one belongs to Minecraft, as in {@code ResourceLocation}. */
    static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }
}
