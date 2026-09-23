package com.globalflashback.nms;

import com.globalflashback.state.MetadataBlob;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Version-isolated encoder for ephemeral effect / block / animation game packets.
 *
 * <p>Main-thread only.
 */
public interface EffectPacketEncoder {
    MetadataBlob encodeAnimate(Player player, int action);

    MetadataBlob encodeAnimate(Entity entity, int action);

    MetadataBlob encodeBlockUpdate(Block block);

    ContainerBlockEntityCapture encodeContainerInventory(Block block);

    MetadataBlob encodeDestroyBlockParticles(Block block);

    MetadataBlob encodeBlockBreakSound(Block block);

    MetadataBlob encodeBlockPlaceSound(Block block);

    MetadataBlob encodeGamePacket(ServerPlayer camera, Packet<? super ClientGamePacketListener> packet);
}
