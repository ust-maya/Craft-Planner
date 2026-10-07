package com.craftplanner.client;

import com.craftplanner.Constants;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/**
 * Registers the mod's key bindings on the mod event bus (client side only).
 *
 * <ul>
 *   <li>{@code ADD_RECIPE} (N)  — capture hovered item and open quantity popup</li>
 *   <li>{@code TOGGLE_OVERLAY} (V) — show/hide the craft-tree overlay</li>
 *   <li>{@code OPEN_RECIPES} (K) — open the recipes-list screen</li>
 *   <li>{@code OPEN_TREE} (G) — open the craft tree as a window</li>
 *   <li>{@code EDIT_LAYOUT} (O) — drag, resize and rescale the overlay panel</li>
 * </ul>
 *
 * <p>The letters are free in vanilla 1.21.1 (which binds W A S D, Space, both
 * shifts, both controls, E, F, Q, T, P, C, X, L, the digits, {@code /}, Tab and
 * the F-keys) and are rarely claimed by popular mods, so they clash neither
 * with the game nor with EMI/JEI. Everything stays rebindable in
 * <em>Options → Controls → Craft Planner</em>.</p>
 *
 * <p>NeoForge routes {@code RegisterKeyMappingsEvent} (an {@code IModBusEvent})
 * to the mod bus automatically, so the annotation carries no bus argument.</p>
 */
@EventBusSubscriber(modid = Constants.MODID, value = Dist.CLIENT)
public class Keybinds {

    /** In 1.21.1 a key category is still a plain translation key. */
    public static final String CATEGORY = "key.category.craftplanner.category";

    public static final KeyMapping ADD_RECIPE = new KeyMapping(
            "key.craftplanner.add",
            KeyConflictContext.UNIVERSAL,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            CATEGORY);

    public static final KeyMapping TOGGLE_OVERLAY = new KeyMapping(
            "key.craftplanner.toggle",
            KeyConflictContext.UNIVERSAL,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            CATEGORY);

    public static final KeyMapping OPEN_RECIPES = new KeyMapping(
            "key.craftplanner.list",
            KeyConflictContext.UNIVERSAL,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            CATEGORY);

    /**
     * Opens the craft tree as a window ({@code CraftTreeScreen}) — the surface
     * where the plan can be folded, scrolled and chosen with a cursor and a
     * keyboard, which the HUD overlay cannot offer. {@code G} is free in vanilla
     * (unlike {@code T}, which is chat), and the letter is a mnemonic for the goal
     * the tree is built around.
     */
    public static final KeyMapping OPEN_TREE = new KeyMapping(
            "key.craftplanner.tree",
            KeyConflictContext.UNIVERSAL,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            CATEGORY);

    /**
     * Enters and leaves the in-game layout editor of the overlay panel (drag to
     * move, corner to resize, wheel for the text size).
     */
    public static final KeyMapping EDIT_LAYOUT = new KeyMapping(
            "key.craftplanner.edit",
            KeyConflictContext.UNIVERSAL,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            CATEGORY);

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(ADD_RECIPE);
        event.register(TOGGLE_OVERLAY);
        event.register(OPEN_RECIPES);
        event.register(OPEN_TREE);
        event.register(EDIT_LAYOUT);
    }
}
