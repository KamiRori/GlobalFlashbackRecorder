package com.globalflashback.nms.v1_21_11;

import com.globalflashback.state.ReplayItemStack;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Serializes {@link ItemStack} including data components (e.g. item_model) for replay.
 *
 * <p>Main-thread capture should use the instance methods (reusable encode buffer + previous-slot
 * reuse via {@link ItemStack#hashItemAndComponents(ItemStack)}). Static helpers remain for encode
 * paths that do not own an adapter instance.
 */
public final class ItemStackCodec1_21_11 {
    private final ByteBuf encodeScratch = Unpooled.buffer(256);

    public ItemStackCodec1_21_11() {}

    /**
     * Capture-side encode with reuse: when item id / count / component hash match {@code previous},
     * returns the same immutable instance (skips StreamCodec).
     */
    public ReplayItemStack toReplayItem(ServerPlayer registryContext, ItemStack stack, ReplayItemStack previous) {
        if (stack == null || stack.isEmpty()) {
            return ReplayItemStack.EMPTY;
        }
        int count = stack.getCount();
        int contentHash = ItemStack.hashItemAndComponents(stack);
        if (previous != null
                && !previous.isEmpty()
                && previous.count() == count
                && previous.contentHash() == contentHash) {
            Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String id = key != null ? key.toString() : "minecraft:air";
            if (previous.itemId().equals(id)) {
                return previous;
            }
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String id = key != null ? key.toString() : "minecraft:air";
        byte[] payload = encodeIntoScratch(registryContext, stack);
        return new ReplayItemStack(id, count, payload, contentHash);
    }

    public ReplayItemStack toReplayItem(ServerPlayer registryContext, ItemStack stack) {
        return toReplayItem(registryContext, stack, null);
    }

    public static ReplayItemStack toReplayItemStatic(ServerPlayer registryContext, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ReplayItemStack.EMPTY;
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String id = key != null ? key.toString() : "minecraft:air";
        int contentHash = ItemStack.hashItemAndComponents(stack);
        byte[] payload = encodeStatic(registryContext, stack);
        return new ReplayItemStack(id, stack.getCount(), payload, contentHash);
    }

    public static ItemStack toItemStack(ServerPlayer registryContext, ReplayItemStack item) {
        return toItemStack(registryContext.registryAccess(), item);
    }

    public static ItemStack toItemStack(RegistryAccess registryAccess, ReplayItemStack item) {
        if (item == null || item.isEmpty()) {
            return ItemStack.EMPTY;
        }
        byte[] payload = item.componentsPayload();
        if (payload.length > 0) {
            try {
                return decode(registryAccess, payload);
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

    public byte[] encodeIntoScratch(ServerPlayer registryContext, ItemStack stack) {
        encodeScratch.clear();
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                encodeScratch, registryContext.registryAccess());
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack == null ? ItemStack.EMPTY : stack);
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        encodeScratch.clear();
        return out;
    }

    public static byte[] encode(ServerPlayer registryContext, ItemStack stack) {
        return encodeStatic(registryContext, stack);
    }

    private static byte[] encodeStatic(ServerPlayer registryContext, ItemStack stack) {
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
        return decode(registryContext.registryAccess(), payload);
    }

    public static ItemStack decode(RegistryAccess registryAccess, byte[] payload) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.wrappedBuffer(payload), registryAccess);
        try {
            return ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }
}
