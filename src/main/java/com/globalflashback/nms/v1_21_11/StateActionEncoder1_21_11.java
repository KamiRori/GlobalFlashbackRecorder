package com.globalflashback.nms.v1_21_11;

import com.globalflashback.nms.StateActionEncoder;

import com.globalflashback.delta.StateChange;
import com.globalflashback.format.MoveEntitiesCodec;
import com.globalflashback.format.ReplayAction;
import com.globalflashback.state.ChunkState;
import com.globalflashback.state.DimensionId;
import com.globalflashback.state.EntityState;
import com.globalflashback.state.EquipmentState;
import com.globalflashback.state.GlobalSnapshot;
import com.globalflashback.state.MetadataBlob;
import com.globalflashback.state.PlayerState;
import com.globalflashback.state.ReplayItemStack;
import com.globalflashback.state.WorldState;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.datafixers.util.Pair;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Encodes {@link GlobalSnapshot} / {@link StateChange} into Flashback {@link ReplayAction}s (1.21.11).
 *
 * <p>Movement uses {@code flashback:action/move_entities} (Flashback refuses vanilla MoveEntity
 * packets and interpolates from move_entities). Riding uses {@link ClientboundSetPassengersPacket}.
 * Pose / sneak / sprint / swim flags travel via entity metadata ({@code packAll} on living entities).
 *
 * <p>{@link #openSession(Player)} must run on the main thread. The bound {@link Session} may encode
 * on an async worker (uses frozen bootstrap + {@link RegistryAccess} only).
 */
public final class StateActionEncoder1_21_11 implements StateActionEncoder {
    private static final int[] EMPTY_PASSENGERS = new int[0];

    private ProtocolInfo<ClientGamePacketListener> cachedProtocolInfo;
    private RegistryAccess cachedRegistryAccess;
    /** Bound for the duration of one snapshot/changes encode call (worker-thread safe). */
    private RegistryAccess activeRegistries;

    private final Set<UUID> knownPlayers = new HashSet<>();
    private final Set<Integer> knownEntities = new HashSet<>();
    private final Map<Integer, MoveEntitiesCodec.Pose> lastPoses = new HashMap<>();
    /** Chunks that already had a LevelChunkWithLightPacket written into the replay stream. */
    private final Set<Long> knownChunkKeys = new HashSet<>();

    /**
     * Last encoded world-border geometry per dimension.
     * WorldUpsert also carries time/weather; only emit border packets when these change.
     */
    private final Map<DimensionId, BorderGeometry> lastBorders = new HashMap<>();

    /** Dedup ego hotbar packets so PlayerUpsert does not reset RecordViewer first-person HUD. */
    private UUID lastHotbarPlayer;
    private int lastHotbarSelected = Integer.MIN_VALUE;
    private List<ReplayItemStack> lastHotbarItems = List.of();

    private final Map<UUID, Float> lastEmittedHealth = new HashMap<>();
    private final Map<UUID, Integer> lastEmittedFood = new HashMap<>();
    private final Map<UUID, Float> lastEmittedSaturation = new HashMap<>();

    /** passenger entity id → vehicle entity id */
    private final Map<Integer, Integer> entityToVehicle = new HashMap<>();
    /** vehicle entity id → passenger entity ids (order preserved) */
    private final Map<Integer, int[]> vehiclePassengers = new HashMap<>();
    private final Set<Integer> dirtyVehicles = new HashSet<>();

    private record BorderGeometry(
            double centerX,
            double centerZ,
            double size,
            double lerpTarget,
            long lerpTime
    ) {
        static BorderGeometry of(WorldState world) {
            return new BorderGeometry(
                    world.worldBorderCenterX(),
                    world.worldBorderCenterZ(),
                    world.worldBorderSize(),
                    world.worldBorderLerpTarget(),
                    world.worldBorderLerpTime()
            );
        }

        boolean lerping() {
            return lerpTime > 0L && Double.compare(size, lerpTarget) != 0;
        }

        /**
         * Same shrink/grow command (ignore per-tick interpolated size / remaining time).
         */
        boolean sameLerpCommand(BorderGeometry other) {
            return Double.compare(centerX, other.centerX) == 0
                    && Double.compare(centerZ, other.centerZ) == 0
                    && Double.compare(lerpTarget, other.lerpTarget) == 0
                    && lerping()
                    && other.lerping();
        }
    }

    @Override
    public Session openSession(Player camera) {
        Objects.requireNonNull(camera, "camera");
        if (!org.bukkit.Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("StateActionEncoder.openSession must run on the main thread");
        }
        if (!(camera instanceof CraftPlayer craft)) {
            throw new IllegalArgumentException("Expected CraftPlayer, got " + camera.getClass().getName());
        }
        ServerPlayer sp = craft.getHandle();
        UUID cameraUuid = camera.getUniqueId();
        // Freeze bootstrap while camera / server registries are still live.
        List<ReplayAction> bootstrap = List.copyOf(SnapshotBuilder1_21_11.bootstrapActions(camera));
        RegistryAccess registries = sp.registryAccess();
        return new Session() {
            @Override
            public UUID cameraUuid() {
                return cameraUuid;
            }

            @Override
            public List<ReplayAction> encodeSnapshot(GlobalSnapshot snapshot) {
                return encodeSnapshotBound(registries, cameraUuid, bootstrap, snapshot);
            }

            @Override
            public List<ReplayAction> encodeChanges(List<StateChange> changes) {
                return encodeChangesBound(registries, cameraUuid, changes);
            }
        };
    }

    private List<ReplayAction> encodeSnapshotBound(
            RegistryAccess registries,
            UUID cameraUuid,
            List<ReplayAction> bootstrap,
            GlobalSnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        activeRegistries = Objects.requireNonNull(registries, "registries");
        try {
            knownPlayers.clear();
            knownEntities.clear();
            lastPoses.clear();
            knownChunkKeys.clear();
            lastBorders.clear();
            lastHotbarPlayer = null;
            lastHotbarSelected = Integer.MIN_VALUE;
            lastHotbarItems = List.of();
            clearVitalsTracking();
            clearMountTracking();

            List<ReplayAction> actions = new ArrayList<>(bootstrap);
            actions.addAll(encodePlayerInfo(snapshot));
            actions.addAll(encodeWorldBorders(snapshot, cameraUuid));
            actions.addAll(encodeChunks(snapshot));
            actions.addAll(encodeEntities(snapshot, cameraUuid));
            actions.addAll(encodeMountsFromSnapshot(snapshot));
            PlayerState ego = snapshot.players().get(cameraUuid);
            if (ego != null) {
                actions.addAll(encodePlayerHotbar(ego));
                actions.addAll(encodeCameraHealthIfChanged(ego));
            }
            seedVitalsTracking(snapshot);
            seedTracking(snapshot, cameraUuid);
            return actions;
        } finally {
            activeRegistries = null;
        }
    }

    private List<ReplayAction> encodeChangesBound(
            RegistryAccess registries,
            UUID cameraUuid,
            List<StateChange> changes
    ) {
        Objects.requireNonNull(changes, "changes");
        activeRegistries = Objects.requireNonNull(registries, "registries");
        try {
            List<ReplayAction> actions = new ArrayList<>();
            List<MoveEntitiesCodec.Pose> moved = new ArrayList<>();
            dirtyVehicles.clear();

            for (StateChange change : changes) {
                encodeChange(cameraUuid, change, actions, moved);
            }

            actions.addAll(flushDirtyMounts());

            if (!moved.isEmpty()) {
                Map<Integer, MoveEntitiesCodec.Pose> unique = new HashMap<>();
                for (MoveEntitiesCodec.Pose pose : moved) {
                    unique.put(pose.entityId(), pose);
                }
                List<MoveEntitiesCodec.Pose> changed = new ArrayList<>();
                for (MoveEntitiesCodec.Pose pose : unique.values()) {
                    MoveEntitiesCodec.Pose previous = lastPoses.get(pose.entityId());
                    if (previous == null || !previous.equals(pose)) {
                        changed.add(pose);
                        lastPoses.put(pose.entityId(), pose);
                    }
                }
                if (!changed.isEmpty()) {
                    actions.add(ReplayAction.moveEntities(MoveEntitiesCodec.encode(changed)));
                }
            }
            return actions;
        } finally {
            activeRegistries = null;
        }
    }

    @Override
    public void seedTracking(GlobalSnapshot snapshot, UUID cameraUuid) {
        lastPoses.clear();
        knownPlayers.clear();
        knownEntities.clear();
        for (PlayerState player : snapshot.players().values()) {
            knownPlayers.add(player.uuid());
            knownEntities.add(player.entityId());
            lastPoses.put(player.entityId(), poseOf(player));
        }
        for (EntityState entity : snapshot.entities().values()) {
            knownEntities.add(entity.entityId());
            if (!entity.uuid().equals(cameraUuid) || !lastPoses.containsKey(entity.entityId())) {
                lastPoses.put(entity.entityId(), poseOf(entity));
            }
        }
    }

    private void encodeChange(
            UUID cameraUuid,
            StateChange change,
            List<ReplayAction> out,
            List<MoveEntitiesCodec.Pose> moved
    ) {
        switch (change) {
            case StateChange.ChunkUpsert(ChunkState chunk) -> {
                // Mid-stream full chunk packets call Flashback replaceWithPacketData and briefly
                // remove entities in that chunk (RecordViewer force-respectate + hotbar reset).
                // Only emit LevelChunkWithLight for chunks not yet present in the stream.
                // BE update tags are already embedded in that packet — mid-stream BE edits use
                // BlockEntityChange / outbound BlockEntityData tap instead.
                long key = chunkStreamKey(chunk);
                if (knownChunkKeys.add(key)) {
                    appendPayload(out, chunk.blockAndLightPayload());
                }
            }
            case StateChange.ChunkUnload ignored -> { }
            case StateChange.WorldUpsert(WorldState world) -> encodeWorldUpsert(world, out);
            case StateChange.BlockChange(
                    var ignoredDim, var ignoredPos, var ignoredOld, var ignoredNew, MetadataBlob payload
            ) -> appendPayload(out, payload);
            case StateChange.BlockEntityChange(
                    var ignoredDim, var ignoredPos, var ignoredType, MetadataBlob nbtPayload
            ) -> appendPayload(out, nbtPayload);
            case StateChange.EntityAnimate(int ignoredId, int ignoredAction, MetadataBlob payload) ->
                    appendPayload(out, payload);
            case StateChange.EffectPacket(MetadataBlob payload) -> appendPayload(out, payload);
            case StateChange.PlayerUpsert(PlayerState player) -> {
                boolean isCamera = player.uuid().equals(cameraUuid);
                boolean firstSeen = knownPlayers.add(player.uuid());
                knownEntities.add(player.entityId());
                Float previousHealth = lastEmittedHealth.get(player.uuid());
                boolean respawned = previousHealth != null
                        && previousHealth <= 0.0f
                        && player.health() > 0.0f;

                if (!isCamera && firstSeen) {
                    out.addAll(encodePlayerInfoEntries(List.of(player)));
                    out.addAll(encodeEntitySpawn(toEntity(player)));
                } else if (!isCamera && respawned) {
                    out.add(ReplayAction.gamePacket(
                            encode(new ClientboundRemoveEntitiesPacket(player.entityId()))));
                    out.addAll(encodeEntitySpawn(toEntity(player)));
                }
                moved.add(poseOf(player));
                appendPayload(out, player.metadata());
                out.addAll(encodeEquipment(player.entityId(), player.equipment()));
                if (isCamera) {
                    out.addAll(encodePlayerHotbar(player));
                    out.addAll(encodeCameraHealthIfChanged(player));
                }
                rememberVitals(player);
                noteMountState(player.entityId(), player.vehicleEntityId(), player.passengerEntityIds());
            }
            case StateChange.PlayerRemove(UUID uuid, int entityId) -> {
                knownPlayers.remove(uuid);
                knownEntities.remove(entityId);
                lastPoses.remove(entityId);
                noteMountState(entityId, null, List.of());
                if (!uuid.equals(cameraUuid)) {
                    out.add(ReplayAction.gamePacket(encode(new ClientboundRemoveEntitiesPacket(entityId))));
                    out.add(ReplayAction.gamePacket(encode(new ClientboundPlayerInfoRemovePacket(List.of(uuid)))));
                }
            }
            case StateChange.EntitySpawn(EntityState entity) -> {
                if (entity.uuid().equals(cameraUuid)) {
                    return;
                }
                if (knownEntities.add(entity.entityId())) {
                    out.addAll(encodeEntitySpawn(entity));
                }
                moved.add(poseOf(entity));
                noteMountState(entity.entityId(), entity.vehicleEntityId(), entity.passengerEntityIds());
            }
            case StateChange.EntityUpdate(EntityState entity) -> {
                knownEntities.add(entity.entityId());
                moved.add(poseOf(entity));
                appendPayload(out, entity.metadata());
                out.addAll(encodeEquipment(entity.entityId(), entity.equipment()));
                noteMountState(entity.entityId(), entity.vehicleEntityId(), entity.passengerEntityIds());
            }
            case StateChange.EntityDestroy(int entityId, UUID uuid) -> {
                knownEntities.remove(entityId);
                knownPlayers.remove(uuid);
                lastPoses.remove(entityId);
                noteMountState(entityId, null, List.of());
                if (!uuid.equals(cameraUuid)) {
                    out.add(ReplayAction.gamePacket(encode(new ClientboundRemoveEntitiesPacket(entityId))));
                }
            }
            case StateChange.DimensionChange ignored -> { }
        }
    }

    private static MoveEntitiesCodec.Pose poseOf(PlayerState player) {
        return new MoveEntitiesCodec.Pose(
                player.entityId(),
                player.dimension().namespacedKey(),
                player.position().x(),
                player.position().y(),
                player.position().z(),
                player.rotation().yaw(),
                player.rotation().pitch(),
                player.rotation().headYaw(),
                player.onGround()
        );
    }

    private static MoveEntitiesCodec.Pose poseOf(EntityState entity) {
        return new MoveEntitiesCodec.Pose(
                entity.entityId(),
                entity.dimension().namespacedKey(),
                entity.position().x(),
                entity.position().y(),
                entity.position().z(),
                entity.rotation().yaw(),
                entity.rotation().pitch(),
                entity.rotation().headYaw(),
                entity.onGround()
        );
    }

    private void clearMountTracking() {
        entityToVehicle.clear();
        vehiclePassengers.clear();
        dirtyVehicles.clear();
    }

    private List<ReplayAction> encodeMountsFromSnapshot(GlobalSnapshot snapshot) {
        Map<Integer, LinkedHashSet<Integer>> mounts = collectMounts(snapshot);
        List<ReplayAction> actions = new ArrayList<>(mounts.size());
        for (Map.Entry<Integer, LinkedHashSet<Integer>> entry : mounts.entrySet()) {
            int vehicleId = entry.getKey();
            int[] passengers = toPassengerArray(entry.getValue());
            vehiclePassengers.put(vehicleId, passengers);
            for (int passengerId : passengers) {
                entityToVehicle.put(passengerId, vehicleId);
            }
            actions.add(encodeSetPassengers(vehicleId, passengers));
        }
        dirtyVehicles.clear();
        return actions;
    }

    private static Map<Integer, LinkedHashSet<Integer>> collectMounts(GlobalSnapshot snapshot) {
        Map<Integer, LinkedHashSet<Integer>> mounts = new LinkedHashMap<>();
        for (EntityState entity : snapshot.entities().values()) {
            absorbMount(mounts, entity.entityId(), entity.vehicleEntityId(), entity.passengerEntityIds());
        }
        for (PlayerState player : snapshot.players().values()) {
            absorbMount(mounts, player.entityId(), player.vehicleEntityId(), player.passengerEntityIds());
        }
        return mounts;
    }

    private static void absorbMount(
            Map<Integer, LinkedHashSet<Integer>> mounts,
            int entityId,
            Integer vehicleId,
            List<Integer> passengersAsVehicle
    ) {
        if (passengersAsVehicle != null && !passengersAsVehicle.isEmpty()) {
            mounts.computeIfAbsent(entityId, ignored -> new LinkedHashSet<>()).addAll(passengersAsVehicle);
        }
        if (vehicleId != null) {
            mounts.computeIfAbsent(vehicleId, ignored -> new LinkedHashSet<>()).add(entityId);
        }
    }

    private void noteMountState(int entityId, Integer vehicleId, List<Integer> passengersAsVehicle) {
        int[] asVehicle = toPassengerArray(passengersAsVehicle);
        int[] previousAsVehicle = vehiclePassengers.get(entityId);
        if (!Arrays.equals(previousAsVehicle, asVehicle)) {
            if (asVehicle.length == 0) {
                vehiclePassengers.remove(entityId);
            } else {
                vehiclePassengers.put(entityId, asVehicle);
            }
            dirtyVehicles.add(entityId);
            // Keep reverse map aligned when we learn the full passenger list from the vehicle.
            if (previousAsVehicle != null) {
                for (int passengerId : previousAsVehicle) {
                    Integer mapped = entityToVehicle.get(passengerId);
                    if (mapped != null && mapped == entityId && !containsId(asVehicle, passengerId)) {
                        entityToVehicle.remove(passengerId);
                    }
                }
            }
            for (int passengerId : asVehicle) {
                entityToVehicle.put(passengerId, entityId);
            }
        }

        Integer previousVehicle = entityToVehicle.get(entityId);
        if (!Objects.equals(previousVehicle, vehicleId)) {
            if (previousVehicle != null) {
                removePassengerFromVehicle(previousVehicle, entityId);
                dirtyVehicles.add(previousVehicle);
            }
            if (vehicleId != null) {
                entityToVehicle.put(entityId, vehicleId);
                addPassengerToVehicle(vehicleId, entityId);
                dirtyVehicles.add(vehicleId);
            } else {
                entityToVehicle.remove(entityId);
            }
        }
    }

    private void addPassengerToVehicle(int vehicleId, int passengerId) {
        int[] current = vehiclePassengers.get(vehicleId);
        if (containsId(current, passengerId)) {
            return;
        }
        if (current == null || current.length == 0) {
            vehiclePassengers.put(vehicleId, new int[]{passengerId});
            return;
        }
        int[] next = Arrays.copyOf(current, current.length + 1);
        next[current.length] = passengerId;
        vehiclePassengers.put(vehicleId, next);
    }

    private void removePassengerFromVehicle(int vehicleId, int passengerId) {
        int[] current = vehiclePassengers.get(vehicleId);
        if (current == null || current.length == 0) {
            vehiclePassengers.remove(vehicleId);
            return;
        }
        int count = 0;
        for (int id : current) {
            if (id != passengerId) {
                count++;
            }
        }
        if (count == 0) {
            vehiclePassengers.remove(vehicleId);
            return;
        }
        if (count == current.length) {
            return;
        }
        int[] next = new int[count];
        int i = 0;
        for (int id : current) {
            if (id != passengerId) {
                next[i++] = id;
            }
        }
        vehiclePassengers.put(vehicleId, next);
    }

    private List<ReplayAction> flushDirtyMounts() {
        if (dirtyVehicles.isEmpty()) {
            return List.of();
        }
        List<ReplayAction> actions = new ArrayList<>(dirtyVehicles.size());
        for (int vehicleId : dirtyVehicles) {
            int[] passengers = vehiclePassengers.getOrDefault(vehicleId, EMPTY_PASSENGERS);
            actions.add(encodeSetPassengers(vehicleId, passengers));
        }
        dirtyVehicles.clear();
        return actions;
    }

    private ReplayAction encodeSetPassengers(int vehicleId, int[] passengerIds) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(vehicleId);
            buf.writeVarIntArray(passengerIds == null ? EMPTY_PASSENGERS : passengerIds);
            ClientboundSetPassengersPacket packet = ClientboundSetPassengersPacket.STREAM_CODEC.decode(buf);
            return ReplayAction.gamePacket(encode(packet));
        } finally {
            buf.release();
        }
    }

    private static int[] toPassengerArray(List<Integer> passengers) {
        if (passengers == null || passengers.isEmpty()) {
            return EMPTY_PASSENGERS;
        }
        int[] ids = new int[passengers.size()];
        for (int i = 0; i < passengers.size(); i++) {
            ids[i] = passengers.get(i);
        }
        return ids;
    }

    private static int[] toPassengerArray(LinkedHashSet<Integer> passengers) {
        if (passengers == null || passengers.isEmpty()) {
            return EMPTY_PASSENGERS;
        }
        int[] ids = new int[passengers.size()];
        int i = 0;
        for (int id : passengers) {
            ids[i++] = id;
        }
        return ids;
    }

    private static boolean containsId(int[] ids, int id) {
        if (ids == null) {
            return false;
        }
        for (int value : ids) {
            if (value == id) {
                return true;
            }
        }
        return false;
    }

    private List<ReplayAction> encodePlayerInfo(GlobalSnapshot snapshot) {
        List<PlayerState> players = new ArrayList<>(snapshot.players().values());
        if (players.isEmpty()) {
            return List.of();
        }
        for (PlayerState player : players) {
            knownPlayers.add(player.uuid());
        }
        return encodePlayerInfoEntries(players);
    }

    private List<ReplayAction> encodePlayerInfoEntries(List<PlayerState> players) {
        List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>();
        for (PlayerState player : players) {
            entries.add(toPlayerInfoEntry(player));
        }
        ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME
                ),
                entries
        );
        return List.of(ReplayAction.gamePacket(encode(packet)));
    }

    private static ClientboundPlayerInfoUpdatePacket.Entry toPlayerInfoEntry(PlayerState player) {
        String name = player.profileName().isEmpty() ? "Player" : player.profileName();
        GameProfile profile = new GameProfile(
                player.uuid(),
                name,
                buildPropertyMap(player.profilePropertiesPayload())
        );
        GameType gameType = GameType.byName(player.gameMode().toLowerCase(), GameType.SURVIVAL);
        return new ClientboundPlayerInfoUpdatePacket.Entry(
                player.uuid(),
                profile,
                true,
                0,
                gameType,
                Component.literal(name),
                true,
                0,
                null
        );
    }

    private static PropertyMap buildPropertyMap(MetadataBlob blob) {
        if (blob == null || blob.isEmpty()) {
            return PropertyMap.EMPTY;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob.payload()))) {
            int count = in.readInt();
            if (count <= 0) {
                return PropertyMap.EMPTY;
            }
            Multimap<String, Property> mutable = ArrayListMultimap.create();
            for (int i = 0; i < count; i++) {
                String name = readUtf(in);
                String value = readUtf(in);
                boolean hasSig = in.readBoolean();
                String sig = hasSig ? readUtf(in) : null;
                mutable.put(name, new Property(name, value, sig));
            }
            return new PropertyMap(mutable);
        } catch (IOException e) {
            return PropertyMap.EMPTY;
        }
    }

    private static String readUtf(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 1_000_000) {
            throw new IOException("invalid utf length " + len);
        }
        return new String(in.readNBytes(len), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Flashback snapshot path: one {@link ClientboundInitializeBorderPacket} for the camera
     * ego dimension (packet is not dimension-scoped). Seed geometry for all captured worlds
     * so mid-stream WorldUpsert only emits when the border <em>command</em> changes (not every
     * interpolated size tick during a vanilla lerp).
     */
    private List<ReplayAction> encodeWorldBorders(GlobalSnapshot snapshot, UUID cameraUuid) {
        List<ReplayAction> actions = new ArrayList<>();
        for (WorldState world : snapshot.worlds().values()) {
            lastBorders.put(world.dimension(), BorderGeometry.of(world));
        }

        DimensionId primaryDim = null;
        PlayerState ego = snapshot.players().get(cameraUuid);
        if (ego != null) {
            primaryDim = ego.dimension();
        }

        WorldState primary = snapshot.worlds().get(primaryDim);
        if (primary == null && !snapshot.worlds().isEmpty()) {
            primary = snapshot.worlds().values().iterator().next();
        }
        if (primary != null) {
            actions.add(ReplayAction.gamePacket(encode(
                    new ClientboundInitializeBorderPacket(borderFromState(primary))
            )));
        }
        return actions;
    }

    private void encodeWorldUpsert(WorldState world, List<ReplayAction> out) {
        BorderGeometry next = BorderGeometry.of(world);
        BorderGeometry previous = lastBorders.put(world.dimension(), next);
        if (previous != null && previous.equals(next)) {
            return;
        }
        // Ongoing vanilla shrink/grow: size + remaining lerpTime change every tick — do not
        // re-emit SetBorderSize (that teleports the client border each tick).
        if (previous != null && previous.sameLerpCommand(next)) {
            return;
        }

        WorldBorder border = borderFromState(world);
        if (previous == null) {
            out.add(ReplayAction.gamePacket(encode(new ClientboundInitializeBorderPacket(border))));
            return;
        }

        if (Double.compare(previous.centerX(), next.centerX()) != 0
                || Double.compare(previous.centerZ(), next.centerZ()) != 0) {
            out.add(ReplayAction.gamePacket(encode(new ClientboundSetBorderCenterPacket(border))));
        }

        if (next.lerping()) {
            // New or retargeted lerp: oldSize=current, newSize=target, lerpTime=remaining.
            out.add(ReplayAction.gamePacket(encode(new ClientboundSetBorderLerpSizePacket(border))));
        } else if (Double.compare(previous.size(), next.size()) != 0
                || Double.compare(previous.lerpTarget(), next.lerpTarget()) != 0
                || previous.lerping()) {
            // Instant resize, or lerp just finished — lock final diameter.
            out.add(ReplayAction.gamePacket(encode(new ClientboundSetBorderSizePacket(border))));
        }
    }

    /**
     * Builds a detached {@link WorldBorder} whose packet constructors read current size, lerp
     * target, and remaining lerp time (same fields vanilla uses for Initialize / LerpSize).
     */
    private static WorldBorder borderFromState(WorldState state) {
        WorldBorder border = new WorldBorder();
        border.setCenter(state.worldBorderCenterX(), state.worldBorderCenterZ());
        double size = state.worldBorderSize();
        double target = state.worldBorderLerpTarget();
        long lerpTime = state.worldBorderLerpTime();
        if (lerpTime > 0L && Double.compare(size, target) != 0) {
            border.lerpSizeBetween(size, target, lerpTime, 0L);
        } else {
            border.setSize(size);
        }
        return border;
    }

    private List<ReplayAction> encodeChunks(GlobalSnapshot snapshot) {
        List<ReplayAction> actions = new ArrayList<>();
        for (ChunkState chunk : snapshot.chunks().values()) {
            knownChunkKeys.add(chunkStreamKey(chunk));
            appendPayload(actions, chunk.blockAndLightPayload());
        }
        return actions;
    }

    private static long chunkStreamKey(ChunkState chunk) {
        long dim = chunk.dimension().namespacedKey().hashCode();
        return (dim << 42)
                ^ ((((long) chunk.position().x() & 0x1FFFFFL) << 21)
                | ((long) chunk.position().z() & 0x1FFFFFL));
    }

    private List<ReplayAction> encodeEntities(GlobalSnapshot snapshot, UUID cameraUuid) {
        List<ReplayAction> actions = new ArrayList<>();
        for (EntityState entity : snapshot.entities().values()) {
            if (entity.uuid().equals(cameraUuid)) {
                knownEntities.add(entity.entityId());
                continue;
            }
            knownEntities.add(entity.entityId());
            actions.addAll(encodeEntitySpawn(entity));
        }
        return actions;
    }

    private List<ReplayAction> encodeEntitySpawn(EntityState entity) {
        EntityType<?> type = resolveEntityType(entity.entityType());
        if (type == null) {
            return List.of();
        }
        List<ReplayAction> actions = new ArrayList<>();
        ClientboundAddEntityPacket add = new ClientboundAddEntityPacket(
                entity.entityId(),
                entity.uuid(),
                entity.position().x(),
                entity.position().y(),
                entity.position().z(),
                entity.rotation().pitch(),
                entity.rotation().yaw(),
                type,
                entity.spawnData(),
                new Vec3(entity.velocity().x(), entity.velocity().y(), entity.velocity().z()),
                entity.rotation().headYaw()
        );
        actions.add(ReplayAction.gamePacket(encode(add)));
        appendPayload(actions, entity.metadata());
        actions.addAll(encodeEquipment(entity.entityId(), entity.equipment()));
        return actions;
    }

    private void clearVitalsTracking() {
        lastEmittedHealth.clear();
        lastEmittedFood.clear();
        lastEmittedSaturation.clear();
    }

    private void seedVitalsTracking(GlobalSnapshot snapshot) {
        for (PlayerState player : snapshot.players().values()) {
            rememberVitals(player);
        }
    }

    private void rememberVitals(PlayerState player) {
        lastEmittedHealth.put(player.uuid(), player.health());
        lastEmittedFood.put(player.uuid(), player.foodLevel());
        lastEmittedSaturation.put(player.uuid(), player.saturation());
    }

    private List<ReplayAction> encodeCameraHealthIfChanged(PlayerState player) {
        Float prevH = lastEmittedHealth.get(player.uuid());
        Integer prevF = lastEmittedFood.get(player.uuid());
        Float prevS = lastEmittedSaturation.get(player.uuid());
        if (prevH != null
                && Float.compare(prevH, player.health()) == 0
                && prevF != null
                && prevF == player.foodLevel()
                && prevS != null
                && Float.compare(prevS, player.saturation()) == 0) {
            return List.of();
        }
        return List.of(ReplayAction.gamePacket(encode(new ClientboundSetHealthPacket(
                player.health(),
                player.foodLevel(),
                player.saturation()
        ))));
    }

    /**
     * Flashback {@code recordHotbar}: SetHeldSlot + ContainerSetSlot(0,0,i,item) for slots 0-8.
     * Applied to create_local_player ego inventory on playback.
     * Skips when unchanged so mid-tick PlayerUpsert does not thrash RecordViewer HUD.
     */
    private List<ReplayAction> encodePlayerHotbar(PlayerState player) {
        if (player.uuid().equals(lastHotbarPlayer)
                && player.selectedSlot() == lastHotbarSelected
                && player.hotbar().equals(lastHotbarItems)) {
            return List.of();
        }
        lastHotbarPlayer = player.uuid();
        lastHotbarSelected = player.selectedSlot();
        lastHotbarItems = player.hotbar();

        List<ReplayAction> actions = new ArrayList<>();
        int selected = Math.max(0, Math.min(8, player.selectedSlot()));
        actions.add(ReplayAction.gamePacket(encode(new ClientboundSetHeldSlotPacket(selected))));
        List<ReplayItemStack> hotbar = player.hotbar();
        for (int i = 0; i < 9; i++) {
            ReplayItemStack item = i < hotbar.size() ? hotbar.get(i) : ReplayItemStack.EMPTY;
            ItemStack stack = ItemStackCodec1_21_11.toItemStack(activeRegistries, item);
            actions.add(ReplayAction.gamePacket(encode(
                    new ClientboundContainerSetSlotPacket(0, 0, i, stack))));
        }
        return actions;
    }

    private List<ReplayAction> encodeEquipment(int entityId, EquipmentState equipment) {
        if (equipment == null) {
            return List.of();
        }
        List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>();
        // Always include all slots so empties clear previous items; keep full components.
        slots.add(Pair.of(EquipmentSlot.MAINHAND, ItemStackCodec1_21_11.toItemStack(activeRegistries, equipment.mainHand())));
        slots.add(Pair.of(EquipmentSlot.OFFHAND, ItemStackCodec1_21_11.toItemStack(activeRegistries, equipment.offHand())));
        slots.add(Pair.of(EquipmentSlot.HEAD, ItemStackCodec1_21_11.toItemStack(activeRegistries, equipment.helmet())));
        slots.add(Pair.of(EquipmentSlot.CHEST, ItemStackCodec1_21_11.toItemStack(activeRegistries, equipment.chestplate())));
        slots.add(Pair.of(EquipmentSlot.LEGS, ItemStackCodec1_21_11.toItemStack(activeRegistries, equipment.leggings())));
        slots.add(Pair.of(EquipmentSlot.FEET, ItemStackCodec1_21_11.toItemStack(activeRegistries, equipment.boots())));
        return List.of(ReplayAction.gamePacket(encode(new ClientboundSetEquipmentPacket(entityId, slots))));
    }

    private static EntityType<?> resolveEntityType(String typeId) {
        Identifier id = Identifier.tryParse(typeId);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    private static EntityState toEntity(PlayerState player) {
        return new EntityState(
                player.entityId(),
                player.uuid(),
                "minecraft:player",
                player.dimension(),
                player.position(),
                player.rotation(),
                player.velocity(),
                player.onGround(),
                player.vehicleEntityId(),
                player.passengerEntityIds(),
                player.equipment(),
                player.metadata(),
                0
        );
    }

    private static void appendPayload(List<ReplayAction> actions, MetadataBlob blob) {
        if (blob != null && !blob.isEmpty()) {
            actions.add(ReplayAction.gamePacket(blob.payload()));
        }
    }

    private byte[] encode(Packet<? super ClientGamePacketListener> packet) {
        StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec = codecFor(activeRegistries);
        ByteBuf buf = Unpooled.buffer();
        try {
            codec.encode(buf, packet);
            return ByteBufUtil.getBytes(buf);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode " + packet.getClass().getSimpleName(), e);
        } finally {
            buf.release();
        }
    }

    private StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codecFor(RegistryAccess access) {
        Objects.requireNonNull(access, "activeRegistries");
        if (cachedProtocolInfo == null || cachedRegistryAccess != access) {
            cachedRegistryAccess = access;
            cachedProtocolInfo = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                    RegistryFriendlyByteBuf.decorator(access));
        }
        return cachedProtocolInfo.codec();
    }
}
