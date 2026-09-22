package com.globalflashback.nms.v26_2;

import com.globalflashback.state.ReplayItemStack;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Serializes {@link ItemStack} including data components (e.g. item_model) for replay.
 */
public final class ItemStackCodec26_2 {
    private ItemStackCodec26_2() {}

    public static ReplayItemStack toReplayItem(ServerPlayer registryContext, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ReplayItemStack.EMPTY;
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String id = key != null ? key.toString() : "minecraft:air";
        byte[] payload = encode(registryContext, stack);
        return new ReplayItemStack(id, stack.getCount(), payload);
    }

    public static ItemStack toItemStack(ServerPlayer registryContext, ReplayItemStack item) {
        if (item == null || item.isEmpty()) {
            return ItemStack.EMPTY;
        }
        byte[] payload = item.componentsPayload();
        if (payload.length > 0) {
            try {
                return decode(registryContext, payload);
            } catch (Exception ignored) {
                // Fall through to id+count reconstruction.
            }
        }
        Identifier id = Identifier.tryParse(item.itemId());
        if (id == null) {
            return ItemStack.EMPTY;
        }
        Item nmsItem = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (nmsItem == null) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(nmsItem, Math.max(1, item.count()));
    }

    public static byte[] encode(ServerPlayer registryContext, ItemStack stack) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), registryContext.registryAccess());
        try {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack == null ? ItemStack.EMPTY : stack);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    public static ItemStack decode(ServerPlayer registryContext, byte[] payload) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.wrappedBuffer(payload), registryContext.registryAccess());
        try {
            return ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }
}
