package com.craftplanner;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * Craft Planner — визуализатор дерева крафта.
 *
 * <p>The mod itself has nothing to register on the game event bus: every feature
 * is client side and lives in its own {@code @EventBusSubscriber} class
 * ({@code ClientEvents}, {@code Keybinds}, {@code Config}, the overlay renderer).
 * The constructor only publishes the client configuration, so that
 * {@code config/craftplanner-client.toml} exists and {@link Config} can read it.</p>
 */
@Mod(Constants.MODID)
public class CraftPlanner {

    public CraftPlanner(IEventBus modEventBus, ModContainer container) {
        // Client config — limits, overlay visibility, deficit colours.
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
    }
}
