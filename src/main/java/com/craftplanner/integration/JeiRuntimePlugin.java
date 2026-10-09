package com.craftplanner.integration;

import com.craftplanner.Constants;
import java.util.Optional;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * JEI plugin — the only way to get hold of a JEI runtime.
 *
 * <p>JEI never exposes its runtime statically; it is handed to plugins through
 * {@link IModPlugin#onRuntimeAvailable(IJeiRuntime)}. That is why this class
 * needs the compile-only JEI API from {@code build.gradle}: the annotation is
 * found by JEI's own scanner, which uses the mod's scan data and never loads
 * the class when JEI itself is absent.</p>
 *
 * <p>Consequently {@link JeiIntegration} may only call {@link #hoveredStack()}
 * after it has checked that JEI is present — the class is then guaranteed to
 * be loadable.</p>
 */
@JeiPlugin
public final class JeiRuntimePlugin implements IModPlugin {

    private static IJeiRuntime runtime;

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(Constants.MODID, "jei");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    /** Item hovered in the JEI ingredient list, or empty when there is none. */
    public static Optional<ItemStack> hoveredStack() {
        IJeiRuntime current = runtime;
        if (current == null) {
            return Optional.empty();
        }
        var overlay = current.getIngredientListOverlay();
        if (!overlay.isListDisplayed()) {
            return Optional.empty();
        }
        return overlay.getIngredientUnderMouse()
                .flatMap(ITypedIngredient::getItemStack)
                .filter(stack -> !stack.isEmpty());
    }

    /** True while the player types into JEI's search box. */
    public static boolean searchFocused() {
        IJeiRuntime current = runtime;
        return current != null && current.getIngredientListOverlay().hasKeyboardFocus();
    }
}
