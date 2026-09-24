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

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(ADD_RECIPE);
        event.register(TOGGLE_OVERLAY);
        event.register(OPEN_RECIPES);
    }
}
