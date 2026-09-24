package com.craftplanner;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * Craft Planner — визуализатор дерева крафта.
 *
 * <p>Stage 1: project skeleton only. The recipe engine, EMI/JEI capture,
 * quantity popup, recipes-list screen and the overlay tree are added in
 * later stages.</p>
 */
@Mod(Constants.MODID)
public class CraftPlanner {

    public CraftPlanner(IEventBus modEventBus) {
        // Stage 10: client config — limits, overlay visibility, deficit colours.
        ModLoadingContext.get().getActiveContainer()
                .registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
    }
}
