package com.globalflashback.nms.v1_21_11;

import com.globalflashback.format.CreateLocalPlayerPayload;
import com.globalflashback.format.ReplayAction;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.config.SynchronizeRegistriesTask;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import com.mojang.datafixers.util.Pair;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Phase-1 POC snapshot builder for Paper / Minecraft 1.21.11.
 *
 * <p>Synthesizes Flashback actions from <strong>server state</strong> (no Netty capture).
 * NMS usage is confined to this package and verified against paperweight 1.21.11 dev bundle.
 */
public final class SnapshotBuilder1_21_11 {
    /** Soft cap on chunk radius around camera; only already-loaded chunks are included. */
    private static final int SNAPSHOT_CHUNK_RADIUS = 4;

    private SnapshotBuilder1_21_11() {}

    public static List<ReplayAction> build(Player cameraPlayer, List<Player> onlinePlayers) {
        if (!(cameraPlayer instanceof CraftPlayer craftCamera)) {
            throw new IllegalArgumentException("Expected CraftPlayer, got " + cameraPlayer.getClass().getName());
        }
        ServerPlayer camera = craftCamera.getHandle();
        List<ReplayAction> actions = new ArrayList<>();
        actions.addAll(bootstrapActions(cameraPlayer));
        actions.addAll(postLoginActions(camera, onlinePlayers));
        return actions;
    }

    /**
     * Config + login + {@code create_local_player} for a camera ego.
     * Used by Phase-5 Encoder before attaching {@link GlobalSnapshot} world actions.
     */
    public static List<ReplayAction> bootstrapActions(Player cameraPlayer) {
        if (!(cameraPlayer instanceof CraftPlayer craftCamera)) {
            throw new IllegalArgumentException("Expected CraftPlayer, got " + cameraPlayer.getClass().getName());
        }
        ServerPlayer camera = craftCamera.getHandle();
        List<ReplayAction> actions = new ArrayList<>();
        actions.addAll(configActions(camera));
        actions.addAll(loginAction(camera));
        actions.add(ReplayAction.createLocalPlayer(CreateLocalPlayerPayload.fromPlayer(cameraPlayer)));
        return actions;
    }

    public static int protocolVersion() {
        return SharedConstants.getProtocolVersion();
    }

    public static int dataVersion() {
        return org.bukkit.Bukkit.getUnsafe().getDataVersion();
    }

    public static String versionString() {
        return SharedConstants.getCurrentVersion().name();
    }

    private static List<ReplayAction> configActions(ServerPlayer camera) {
        MinecraftServer server = camera.level().getServer();
        List<ReplayAction> actions = new ArrayList<>();

        ClientboundUpdateEnabledFeaturesPacket featuresPacket =
                new ClientboundUpdateEnabledFeaturesPacket(
                        FeatureFlags.REGISTRY.toNames(server.getWorldData().enabledFeatures()));
        actions.add(ReplayAction.configPacket(encodeConfigPacket(featuresPacket)));

        var requestedPacks = server.getResourceManager().listPacks()
                .flatMap(pack -> pack.knownPackInfo().stream())
                .toList();
        SynchronizeRegistriesTask task = new SynchronizeRegistriesTask(requestedPacks, server.registries());

        List<Packet<?>> configOut = new ArrayList<>();
        task.start(configOut::add);
        for (Packet<?> packet : configOut) {
            actions.add(ReplayAction.configPacket(encodeConfigPacket(packet)));
        }

        List<Packet<?>> registryOut = new ArrayList<>();
        task.handleResponse(List.of(), registryOut::add);
        for (Packet<?> packet : registryOut) {
            actions.add(ReplayAction.configPacket(encodeConfigPacket(packet)));
        }

        ClientboundUpdateTagsPacket tagsPacket = new ClientboundUpdateTagsPacket(
                TagNetworkSerialization.serializeTagsToNetwork(server.registries()));
        actions.add(ReplayAction.configPacket(encodeConfigPacket(tagsPacket)));
        return actions;
    }

    private static List<ReplayAction> loginAction(ServerPlayer camera) {
        ServerLevel level = (ServerLevel) camera.level();
        MinecraftServer server = camera.level().getServer();
        ClientboundLoginPacket loginPacket = new ClientboundLoginPacket(
                camera.getId(),
                server.isHardcore(),
                server.levelKeys(),
                server.getPlayerList().getMaxPlayers(),
                server.getPlayerList().getViewDistance(),
                server.getPlayerList().getSimulationDistance(),
                false,
                true,
                false,
                camera.createCommonSpawnInfo(level),
                true
        );
        return List.of(ReplayAction.gamePacket(encodeGamePacket(camera, loginPacket)));
    }

    private static List<ReplayAction> postLoginActions(ServerPlayer camera, List<Player> onlinePlayers) {
        ServerLevel level = (ServerLevel) camera.level();
        MinecraftServer server = camera.level().getServer();
        List<ReplayAction> actions = new ArrayList<>();

        List<ServerPlayer> nmsPlayers = new ArrayList<>();
        for (Player bukkit : onlinePlayers) {
            if (bukkit instanceof CraftPlayer craft) {
                nmsPlayers.add(craft.getHandle());
            }
        }

        // Do NOT emit ClientboundPlayerPositionPacket — Flashback playback rejects it.
        ClientboundPlayerInfoUpdatePacket playerInfo = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME
                ),
                nmsPlayers
        );
        actions.add(ReplayAction.gamePacket(encodeGamePacket(camera, playerInfo)));

        int viewDist = server.getPlayerList().getViewDistance();
        int radius = Math.min(viewDist, SNAPSHOT_CHUNK_RADIUS);
        int cx0 = camera.chunkPosition().x;
        int cz0 = camera.chunkPosition().z;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx0 + dx, cz0 + dz);
                if (chunk == null) {
                    continue; // never force-load
                }
                ClientboundLevelChunkWithLightPacket chunkPacket =
                        new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
                actions.add(ReplayAction.gamePacket(encodeGamePacket(camera, chunkPacket)));
            }
        }

        for (ServerPlayer other : nmsPlayers) {
            if (other.getId() == camera.getId()) {
                continue; // camera ego comes from create_local_player
            }
            actions.addAll(entitySpawnActions(camera, other));
        }

        return actions;
    }

    private static List<ReplayAction> entitySpawnActions(ServerPlayer camera, Entity entity) {
        List<ReplayAction> actions = new ArrayList<>();
        ClientboundAddEntityPacket add = new ClientboundAddEntityPacket(
                entity.getId(),
                entity.getUUID(),
                entity.getX(),
                entity.getY(),
                entity.getZ(),
                entity.getXRot(),
                entity.getYRot(),
                entity.getType(),
                0,
                entity.getDeltaMovement(),
                entity.getYHeadRot()
        );
        actions.add(ReplayAction.gamePacket(encodeGamePacket(camera, add)));

        List<SynchedEntityData.DataValue<?>> nonDefault = entity.getEntityData().getNonDefaultValues();
        if (nonDefault != null && !nonDefault.isEmpty()) {
            actions.add(ReplayAction.gamePacket(encodeGamePacket(
                    camera, new ClientboundSetEntityDataPacket(entity.getId(), nonDefault))));
        }

        if (entity instanceof LivingEntity living) {
            List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    equipment.add(Pair.of(slot, stack.copy()));
                }
            }
            if (!equipment.isEmpty()) {
                actions.add(ReplayAction.gamePacket(encodeGamePacket(
                        camera, new ClientboundSetEquipmentPacket(entity.getId(), equipment))));
            }
        }
        return actions;
    }

    @SuppressWarnings("unchecked")
    private static byte[] encodeConfigPacket(Packet<?> packet) {
        StreamCodec<ByteBuf, Packet<? super ClientConfigurationPacketListener>> codec =
                ConfigurationProtocols.CLIENTBOUND.codec();
        ByteBuf buf = Unpooled.buffer();
        try {
            codec.encode(buf, (Packet<? super ClientConfigurationPacketListener>) packet);
            return ByteBufUtil.getBytes(buf);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to encode config packet " + packet.getClass().getSimpleName(), e);
        } finally {
            buf.release();
        }
    }

    private static byte[] encodeGamePacket(
            ServerPlayer camera,
            Packet<? super ClientGamePacketListener> packet
    ) {
        ProtocolInfo<ClientGamePacketListener> info = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(camera.registryAccess()));
        StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec = info.codec();
        ByteBuf buf = Unpooled.buffer();
        try {
            codec.encode(buf, packet);
            return ByteBufUtil.getBytes(buf);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to encode game packet " + packet.getClass().getSimpleName(), e);
        } finally {
            buf.release();
        }
    }
}
