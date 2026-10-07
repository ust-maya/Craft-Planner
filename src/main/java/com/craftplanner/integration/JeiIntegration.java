package com.craftplanner.integration;

import java.util.Optional;
import net.minecraft.world.item.ItemStack;

/**
 * Soft JEI integration (Stage 5).
 *
 * <p>Unlike EMI, JEI hands out its runtime exclusively through a registered
 * plugin ({@code @JeiPlugin} + {@code IModPlugin#onRuntimeAvailable}), which
 * cannot be written with reflection alone. {@link JeiRuntimePlugin} does it
 * against the compile-only JEI API and keeps the runtime for us.</p>
 *
 * <p>The plugin carries JEI types, so this class only touches it after
 * {@link #isPresent()} has confirmed JEI is loaded — without JEI the plugin
 * is never loaded and {@link #hovered()} stays empty, leaving the inventory
 * capture of Stage 4 in charge.</p>
 */
public final class JeiIntegration {

    private static final String JEI_PLUGIN = "mezz.jei.api.JeiPlugin";

    private static boolean checked;
    private static boolean available;

    private JeiIntegration() {
    }

    public static synchronized boolean isPresent() {
        if (!checked) {
            checked = true;
            available = resolve();
        }
        return available;
    }

    /**
     * Item hovered in the JEI ingredient list, or empty when JEI is missing,
     * has no runtime yet or shows nothing under the cursor.
     *
     * <p>The presence flag is set once, but JEI can still disappear during the
     * session (its own mod failed, the runtime was unloaded): then touching
     * {@link JeiRuntimePlugin} would raise a linkage error. The capture hotkey
     * must degrade to "nothing hovered", never crash.</p>
     */
    public static Optional<ItemStack> hovered() {
        if (!isPresent()) {
            return Optional.empty();
        }
        try {
            return JeiRuntimePlugin.hoveredStack();
        } catch (LinkageError jeiGone) {
            return Optional.empty();
        }
    }

    private static boolean resolve() {
        try {
            Class.forName(JEI_PLUGIN);
            return true;
        } catch (ClassNotFoundException expected) {
            return false;
        }
    }
}
