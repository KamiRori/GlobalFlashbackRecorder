package com.globalflashback.nms.v26_2;

import com.globalflashback.state.MetadataBlob;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/**
 * Encodes ephemeral effect / block / animation packets on the main thread at capture time.
 */
public final class EffectPacketFactory26_2 {
    private ProtocolInfo<ClientGamePacketListener> cachedProtocolInfo;
    private net.minecraft.core.RegistryAccess cachedRegistryAccess;

    public MetadataBlob encodeAnimate(Player player, int action) {
        if (!(player instanceof CraftPlayer craft)) {
            return MetadataBlob.EMPTY;
        }
        ServerPlayer sp = craft.getHandle();
        return encode(sp, new ClientboundAnimatePacket(sp, action));
    }

    public MetadataBlob encodeAnimate(Entity entity, int action) {
        if (!(entity.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return MetadataBlob.EMPTY;
        }
        ServerPlayer any = level.getRandomPlayer();
        if (any == null && !level.players().isEmpty()) {
            any = level.players().get(0);
        }
        if (any == null) {
            return MetadataBlob.EMPTY;
        }
        return encode(any, new ClientboundAnimatePacket(entity, action));
    }

    public MetadataBlob encodeBlockUpdate(org.bukkit.block.Block block) {
        if (!(block instanceof CraftBlock craft)) {
            return MetadataBlob.EMPTY;
        }
        BlockState state = craft.getBlockState();
        BlockPos pos = craft.getPosition();
        ServerPlayer encoder = encoderPlayer(craft);
        if (encoder == null) {
            return MetadataBlob.EMPTY;
        }
        return encode(encoder, new ClientboundBlockUpdatePacket(pos, state));
    }

    /**
     * Full container inventory as {@link ClientboundBlockEntityDataPacket} via
     * {@link BlockEntity#saveWithoutMetadata}. Used only for player-opened containers — not for
     * hopper / redstone-driven updates. Returns {@link MetadataBlob#EMPTY} when there is no BE.
     */
    public ContainerBlockEntityCapture encodeContainerInventory(org.bukkit.block.Block block) {
        if (!(block instanceof CraftBlock craft)) {
            return ContainerBlockEntityCapture.EMPTY;
        }
        if (!(craft.getLevel() instanceof ServerLevel level)) {
            return ContainerBlockEntityCapture.EMPTY;
        }
        BlockPos pos = craft.getPosition();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return ContainerBlockEntityCapture.EMPTY;
        }
        ServerPlayer encoder = encoderPlayer(craft);
        if (encoder == null) {
            return ContainerBlockEntityCapture.EMPTY;
        }
        Identifier typeKey = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
        String typeId = typeKey != null ? typeKey.toString() : blockEntity.getType().toString();
        try {
            ClientboundBlockEntityDataPacket packet = ClientboundBlockEntityDataPacket.create(
                    blockEntity,
                    (be, registryAccess) -> be.saveWithoutMetadata(registryAccess)
            );
            MetadataBlob payload = encode(encoder, packet);
            if (payload.isEmpty()) {
                return ContainerBlockEntityCapture.EMPTY;
            }
            return new ContainerBlockEntityCapture(typeId, payload);
        } catch (Exception e) {
            return ContainerBlockEntityCapture.EMPTY;
        }
    }

    /** Result of {@link #encodeContainerInventory}. */
    public record ContainerBlockEntityCapture(String typeId, MetadataBlob packetPayload) {
        public static final ContainerBlockEntityCapture EMPTY =
                new ContainerBlockEntityCapture("", MetadataBlob.EMPTY);

        public ContainerBlockEntityCapture {
            typeId = typeId == null ? "" : typeId;
            packetPayload = packetPayload == null ? MetadataBlob.EMPTY : packetPayload;
        }

        public boolean isEmpty() {
            return packetPayload.isEmpty();
        }
    }

    public MetadataBlob encodeDestroyBlockParticles(org.bukkit.block.Block block) {
        if (!(block instanceof CraftBlock craft)) {
            return MetadataBlob.EMPTY;
        }
        BlockState state = craft.getBlockState();
        BlockPos pos = craft.getPosition();
        ServerPlayer encoder = encoderPlayer(craft);
        if (encoder == null) {
            return MetadataBlob.EMPTY;
        }
        int data = net.minecraft.world.level.block.Block.getId(state);
        return encode(encoder, new ClientboundLevelEventPacket(
                LevelEvent.PARTICLES_DESTROY_BLOCK, pos, data, false));
    }

    public MetadataBlob encodeBlockBreakSound(org.bukkit.block.Block block) {
        return encodeBlockSound(block, true);
    }

    public MetadataBlob encodeBlockPlaceSound(org.bukkit.block.Block block) {
        return encodeBlockSound(block, false);
    }

    private MetadataBlob encodeBlockSound(org.bukkit.block.Block block, boolean breakSound) {
        if (!(block instanceof CraftBlock craft)) {
            return MetadataBlob.EMPTY;
        }
        BlockState state = craft.getBlockState();
        BlockPos pos = craft.getPosition();
        ServerPlayer encoder = encoderPlayer(craft);
        if (encoder == null) {
            return MetadataBlob.EMPTY;
        }
        SoundType soundType = state.getSoundType();
        SoundEvent sound = breakSound ? soundType.getBreakSound() : soundType.getPlaceSound();
        Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound);
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;
        // Preserve block sound volume/pitch — Flashback uses these for attenuation.
        float volume = (soundType.getVolume() + 1.0f) / 2.0f;
        float pitch = soundType.getPitch() * 0.8f;
        long seed = encoder.getRandom().nextLong();
        return encode(encoder, new ClientboundSoundPacket(
                holder, SoundSource.BLOCKS, x, y, z, volume, pitch, seed));
    }

    public MetadataBlob encodeGamePacket(ServerPlayer camera, Packet<? super ClientGamePacketListener> packet) {
        return encode(camera, packet);
    }

    private static ServerPlayer encoderPlayer(CraftBlock craft) {
        if (craft.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            ServerPlayer any = level.getRandomPlayer();
            if (any != null) {
                return any;
            }
            if (!level.players().isEmpty()) {
                return level.players().get(0);
            }
        }
        return null;
    }

    private MetadataBlob encode(ServerPlayer camera, Packet<? super ClientGamePacketListener> packet) {
        StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec = codecFor(camera);
        ByteBuf buf = Unpooled.buffer();
        try {
            codec.encode(buf, packet);
            return new MetadataBlob(ByteBufUtil.getBytes(buf));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode " + packet.getClass().getSimpleName(), e);
        } finally {
            buf.release();
        }
    }

    private StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codecFor(ServerPlayer camera) {
        net.minecraft.core.RegistryAccess access = camera.registryAccess();
        if (cachedProtocolInfo == null || cachedRegistryAccess != access) {
            cachedRegistryAccess = access;
            cachedProtocolInfo = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                    RegistryFriendlyByteBuf.decorator(access));
        }
        return cachedProtocolInfo.codec();
    }
}
