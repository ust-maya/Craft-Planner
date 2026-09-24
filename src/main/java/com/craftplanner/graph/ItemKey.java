package com.craftplanner.graph;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Immutable identity of a craftable "thing": item plus its data-component
 * patch (NBT-like extras). Item count is deliberately excluded, so the same
 * key can be used to aggregate arbitrary amounts.
 *
 * <p>Used as a map key everywhere in the graph, therefore it must stay
 * immutable and have stable {@code equals}/{@code hashCode}.</p>
 */
public record ItemKey(Item item, DataComponentPatch components) {

    public static ItemKey of(ItemStack stack) {
        return new ItemKey(stack.getItem(), stack.getComponentsPatch());
    }

    public static ItemKey of(Item item) {
        return new ItemKey(item, DataComponentPatch.EMPTY);
    }

    /** Rebuilds a stack with the given count and the remembered components. */
    public ItemStack toStack(int count) {
        ItemStack stack = new ItemStack(item, count);
        stack.applyComponents(components);
        return stack;
    }

    public ItemStack toStack() {
        return toStack(1);
    }

    public Component name() {
        return toStack().getHoverName();
    }

    public ResourceLocation id() {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    public boolean hasComponents() {
        return !components.isEmpty();
    }

    @Override
    public String toString() {
        return hasComponents() ? id() + components.toString() : id().toString();
    }
}
