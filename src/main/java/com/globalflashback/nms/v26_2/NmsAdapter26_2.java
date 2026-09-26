package com.globalflashback.nms.v26_2;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.globalflashback.capture.ChunkBlockCache;
import com.globalflashback.nms.ChunkBlockDelta;
import com.globalflashback.nms.ChunkBlockDiffResult;
import com.globalflashback.nms.NmsAdapter;
import com.globalflashback.state.ChunkState;
import com.globalflashback.state.DimensionId;
import com.globalflashback.state.EntityState;
import com.globalflashback.state.EquipmentState;
import com.globalflashback.state.MetadataBlob;
import com.globalflashback.state.PlayerState;
import com.globalflashback.state.ReplayItemStack;
import com.globalflashback.state.ReplayMath;
import com.globalflashback.state.WorldState;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.util.BitStorage;
import net.minecraft.world.entity.projectile.FishingHook;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Paper / Minecraft 26.2 NMS adapter. All {@code net.minecraft.*} access for capture lives here.
 *
 * <p>Main-thread only. Section fingerprints hash palette + bit-storage in-place (no
 * {@code section.write} / CRC). Item capture reuses prior {@link ReplayItemStack} when
 * {@link ItemStack#hashItemAndComponents} matches.
 */
public final class NmsAdapter26_2 implements NmsAdapter {
    /**
     * Local palettes in a section never exceed 4096 distinct entries; {@link net.minecraft.world.level.chunk.GlobalPalette}
     * reports the full registry size and must not be iterated.
     */
    private static final int MAX_LOCAL_PALETTE_MIX = 4096;

    /** Reused for game-packet encoding (metadata / block updates); main thread only. */
    private final ByteBuf encodeScratch = Unpooled.buffer(512);
    private final ItemStackCodec26_2 itemCodec = new ItemStackCodec26_2();

    @Override
    public PlayerState capturePlayer(Player player) {
        return capturePlayer(player, null, true);
    }

    @Override
    public PlayerState capturePlayer(Player player, PlayerState previous, boolean captureHeavy) {
        if (!(player instanceof CraftPlayer craft)) {
            throw new IllegalArgumentException("Expected CraftPlayer, got " + player.getClass().getName());
        }
        ServerPlayer sp = craft.getHandle();
        ServerLevel level = (ServerLevel) sp.level();

        List<Integer> passengers = passengerIds(sp, previous != null ? previous.passengerEntityIds() : null);
        Integer vehicleId = sp.isPassenger() && sp.getVehicle() != null ? sp.getVehicle().getId() : null;
        DimensionId dimension = dimensionOf(level);
        double x = sp.getX();
        double y = sp.getY();
        double z = sp.getZ();
        float pitch = sp.getXRot();
        float yaw = sp.getYRot();
        float headYaw = sp.getYHeadRot();
        double vx = sp.getDeltaMovement().x;
        double vy = sp.getDeltaMovement().y;
        double vz = sp.getDeltaMovement().z;
        ReplayMath.Vec3d position = reuseOrNewVec(previous != null ? previous.position() : null, x, y, z);
        ReplayMath.Rotation rotation = reuseOrNewRot(previous != null ? previous.rotation() : null, pitch, yaw, headYaw);
        ReplayMath.Vec3d velocity = reuseOrNewVec(previous != null ? previous.velocity() : null, vx, vy, vz);
        String pose = poseName(sp.getPose());
        boolean onGround = sp.onGround();
        boolean sneaking = sp.isShiftKeyDown();
        boolean sprinting = sp.isSprinting();
        boolean swimming = sp.isSwimming();
        boolean gliding = sp.isFallFlying();
        boolean sleeping = sp.isSleeping();
        String gameMode = gameModeName(sp.gameMode());
        float health = sp.getHealth();
        int foodLevel = sp.getFoodData().getFoodLevel();
        float saturation = sp.getFoodData().getSaturationLevel();
        float experienceProgress = sp.experienceProgress;
        int experienceLevel = sp.experienceLevel;
        int totalExperience = sp.totalExperience;
        int selectedSlot = sp.getInventory().getSelectedSlot();
        String profileName = sp.getGameProfile().name();

        // Fast path: unchanged light fields + not a staggered heavy tick → reuse immutable previous
        // (H1). Still paid: NMS field reads below; avoided: hotbar/equipment/metadata encode.
        boolean appearanceChanged = previous == null
                || !previous.pose().equals(pose)
                || previous.sneaking() != sneaking
                || previous.sprinting() != sprinting
                || previous.swimming() != swimming
                || previous.gliding() != gliding
                || previous.sleeping() != sleeping
                || !previous.dimension().equals(dimension)
                || !previous.gameMode().equals(gameMode)
                || !previous.profileName().equals(profileName);
        boolean mountChanged = previous == null
                || !Objects.equals(previous.vehicleEntityId(), vehicleId)
                || !previous.passengerEntityIds().equals(passengers);
        boolean slotChanged = previous == null || previous.selectedSlot() != selectedSlot;
        boolean vitalsChanged = previous == null
                || Float.compare(previous.health(), health) != 0
                || previous.foodLevel() != foodLevel
                || Float.compare(previous.saturation(), saturation) != 0
                || Float.compare(previous.experienceProgress(), experienceProgress) != 0
                || previous.experienceLevel() != experienceLevel
                || previous.totalExperience() != totalExperience;
        boolean healthChanged = previous == null
                || Float.compare(previous.health(), health) != 0;
        boolean heavyNeeded = captureHeavy
                || appearanceChanged
                || mountChanged
                || slotChanged
                || vitalsChanged;

        if (!heavyNeeded) {
            if (previous.entityId() == sp.getId()
                    && previous.onGround() == onGround
                    && previous.position().equals(position)
                    && previous.rotation().equals(rotation)
                    && previous.velocity().equals(velocity)) {
                return previous;
            }
            return new PlayerState(
                    sp.getUUID(),
                    sp.getId(),
                    dimension,
                    position,
                    rotation,
                    velocity,
                    pose,
                    onGround,
                    sneaking,
                    sprinting,
                    swimming,
                    gliding,
                    sleeping,
                    vehicleId,
                    passengers,
                    gameMode,
                    health,
                    foodLevel,
                    saturation,
                    experienceProgress,
                    experienceLevel,
                    totalExperience,
                    selectedSlot,
                    previous.hotbar(),
                    previous.equipment(),
                    previous.potionEffects(),
                    previous.metadata(),
                    profileName,
                    previous.profilePropertiesPayload()
            );
        }

        boolean refreshInventory = captureHeavy || slotChanged || appearanceChanged || previous == null;
        boolean refreshPotions = captureHeavy || previous == null;
        // Appearance (incl. returning to default pose/flags) needs a full packAll; periodic heavy can
        // use non-default only. Never packDirty — would steal vanilla client dirty bits.
        // Health lives in LivingEntity synched data: vitals-only changes must refresh metadata or
        // replay keeps the previous SetEntityData (e.g. health=0 → stuck death animation after respawn).
        boolean refreshMetadata = previous == null || appearanceChanged || captureHeavy || healthChanged;
        boolean metadataPackAll = previous == null || appearanceChanged || healthChanged;
        boolean refreshProfile = captureHeavy || previous == null || !previous.profileName().equals(profileName);

        List<PlayerState.PotionEffectState> effects;
        if (refreshPotions) {
            effects = new ArrayList<>();
            sp.getActiveEffects().forEach(instance -> {
                Identifier key = BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect().value());
                String id = key != null ? key.toString() : "unknown";
                effects.add(new PlayerState.PotionEffectState(
                        id,
                        instance.getAmplifier(),
                        instance.getDuration(),
                        instance.isAmbient(),
                        instance.isVisible()
                ));
            });
        } else {
            effects = previous.potionEffects();
        }

        return new PlayerState(
                sp.getUUID(),
                sp.getId(),
                dimension,
                position,
                rotation,
                velocity,
                pose,
                onGround,
                sneaking,
                sprinting,
                swimming,
                gliding,
                sleeping,
                vehicleId,
                passengers,
                gameMode,
                health,
                foodLevel,
                saturation,
                experienceProgress,
                experienceLevel,
                totalExperience,
                selectedSlot,
                refreshInventory ? captureHotbar(sp, previous != null ? previous.hotbar() : null) : previous.hotbar(),
                refreshInventory ? captureEquipment(sp, previous != null ? previous.equipment() : null) : previous.equipment(),
                effects,
                refreshMetadata ? captureEntityMetadata(sp, metadataPackAll) : previous.metadata(),
                profileName,
                refreshProfile ? captureProfileProperties(player) : previous.profilePropertiesPayload()
        );
    }

    @Override
    public EntityState captureEntity(org.bukkit.entity.Entity bukkitEntity) {
        return captureEntity(bukkitEntity, null, true);
    }

    @Override
    public EntityState captureEntity(
            org.bukkit.entity.Entity bukkitEntity,
            EntityState previous,
            boolean captureHeavy
    ) {
        if (!(bukkitEntity instanceof CraftEntity craft)) {
            throw new IllegalArgumentException("Expected CraftEntity, got " + bukkitEntity.getClass().getName());
        }
        Entity entity = craft.getHandle();
        ServerLevel level = (ServerLevel) entity.level();

        List<Integer> passengers = passengerIds(entity, previous != null ? previous.passengerEntityIds() : null);
        Integer vehicleId = entity.isPassenger() && entity.getVehicle() != null ? entity.getVehicle().getId() : null;
        DimensionId dimension = dimensionOf(level);
        double x = entity.getX();
        double y = entity.getY();
        double z = entity.getZ();
        float pitch = entity.getXRot();
        float yaw = entity.getYRot();
        float headYaw = entity.getYHeadRot();
        double vx = entity.getDeltaMovement().x;
        double vy = entity.getDeltaMovement().y;
        double vz = entity.getDeltaMovement().z;
        ReplayMath.Vec3d position = reuseOrNewVec(previous != null ? previous.position() : null, x, y, z);
        ReplayMath.Rotation rotation = reuseOrNewRot(previous != null ? previous.rotation() : null, pitch, yaw, headYaw);
        ReplayMath.Vec3d velocity = reuseOrNewVec(previous != null ? previous.velocity() : null, vx, vy, vz);
        boolean onGround = entity.onGround();
        int spawnData = resolveSpawnData(entity);

        Identifier typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String typeId = typeKey != null ? typeKey.toString() : entity.getType().toString();

        boolean mountChanged = previous == null
                || !Objects.equals(previous.vehicleEntityId(), vehicleId)
                || !previous.passengerEntityIds().equals(passengers)
                || !previous.dimension().equals(dimension)
                || previous.spawnData() != spawnData
                || !previous.entityType().equals(typeId);
        boolean heavyNeeded = captureHeavy || mountChanged;

        if (!heavyNeeded) {
            if (previous.entityId() == entity.getId()
                    && previous.onGround() == onGround
                    && previous.position().equals(position)
                    && previous.rotation().equals(rotation)
                    && previous.velocity().equals(velocity)) {
                return previous;
            }
            return new EntityState(
                    entity.getId(),
                    entity.getUUID(),
                    typeId,
                    dimension,
                    position,
                    rotation,
                    velocity,
                    onGround,
                    vehicleId,
                    passengers,
                    previous.equipment(),
                    previous.metadata(),
                    spawnData
            );
        }

        // H3: refresh equipment/metadata only on heavy cadence or mount/type/dimension change.
        boolean refreshGear = captureHeavy || previous == null || mountChanged;
        EquipmentState equipment = previous != null ? previous.equipment() : EquipmentState.EMPTY;
        MetadataBlob metadata = previous != null ? previous.metadata() : MetadataBlob.EMPTY;
        if (refreshGear) {
            if (entity instanceof LivingEntity living) {
                equipment = captureEquipment(living, previous != null ? previous.equipment() : null);
            } else {
                equipment = EquipmentState.EMPTY;
            }
            metadata = captureEntityMetadata(entity, false);
        }

        return new EntityState(
                entity.getId(),
                entity.getUUID(),
                typeId,
                dimension,
                position,
                rotation,
                velocity,
                onGround,
                vehicleId,
                passengers,
                equipment,
                metadata,
                spawnData
        );
    }

    private static List<Integer> passengerIds(Entity entity, List<Integer> previousIds) {
        List<Entity> passengers = entity.getPassengers();
        if (passengers.isEmpty()) {
            return previousIds != null && previousIds.isEmpty() ? previousIds : List.of();
        }
        if (previousIds != null && previousIds.size() == passengers.size()) {
            boolean same = true;
            for (int i = 0; i < passengers.size(); i++) {
                if (previousIds.get(i) != passengers.get(i).getId()) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return previousIds;
            }
        }
        if (passengers.size() == 1) {
            return List.of(passengers.get(0).getId());
        }
        List<Integer> ids = new ArrayList<>(passengers.size());
        for (Entity passenger : passengers) {
            ids.add(passenger.getId());
        }
        return ids;
    }

    private static ReplayMath.Vec3d reuseOrNewVec(ReplayMath.Vec3d previous, double x, double y, double z) {
        if (previous != null && previous.matches(x, y, z)) {
            return previous;
        }
        if (x == 0.0 && y == 0.0 && z == 0.0) {
            return ReplayMath.Vec3d.ZERO;
        }
        return new ReplayMath.Vec3d(x, y, z);
    }

    private static ReplayMath.Rotation reuseOrNewRot(
            ReplayMath.Rotation previous, float pitch, float yaw, float headYaw
    ) {
        if (previous != null && previous.matches(pitch, yaw, headYaw)) {
            return previous;
        }
        return new ReplayMath.Rotation(pitch, yaw, headYaw);
    }

    /**
     * Matches vanilla {@code Entity#getAddEntityPacket} type-specific data.
     * Fishing bobber requires the owner player entity id or the client refuses the hook.
     */
    private static int resolveSpawnData(Entity entity) {
        if (entity instanceof FishingHook hook) {
            Entity owner = hook.getOwner();
            return owner == null ? hook.getId() : owner.getId();
        }
        return 0;
    }

    @Override
    public ChunkState captureChunkIfLoaded(World world, ReplayMath.ChunkPos position) {
        if (!(world instanceof CraftWorld craftWorld)) {
            throw new IllegalArgumentException("Expected CraftWorld, got " + world.getClass().getName());
        }
        ServerLevel level = craftWorld.getHandle();
        LevelChunk chunk = level.getChunkSource().getChunkNow(position.x(), position.z());
        if (chunk == null) {
            return null;
        }

        ClientboundLevelChunkWithLightPacket packet =
                new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
        byte[] payload = encodeGamePacket(level, packet);
        long fingerprint = fingerprintChunk(chunk);
        long cheap = cheapFingerprintChunk(chunk);

        List<ChunkState.BlockEntityState> blockEntities = captureBlockEntityStates(level, chunk);

        return new ChunkState(
                dimensionOf(level),
                position,
                new MetadataBlob(payload),
                blockEntities,
                fingerprint,
                cheap
        );
    }

    /**
     * Client-sync BE snapshots only ({@link ClientboundBlockEntityDataPacket#create(BlockEntity)} →
     * {@code getUpdateTag}). Container inventories are not recorded.
     */
    private List<ChunkState.BlockEntityState> captureBlockEntityStates(ServerLevel level, LevelChunk chunk) {
        List<ChunkState.BlockEntityState> blockEntities = new ArrayList<>(chunk.getBlockEntities().size());
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockEntity blockEntity = entry.getValue();
            BlockPos blockPos = entry.getKey();
            Identifier typeKey = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
            String typeId = typeKey != null ? typeKey.toString() : blockEntity.getType().toString();
            MetadataBlob nbtPayload = MetadataBlob.EMPTY;
            try {
                ClientboundBlockEntityDataPacket packet = ClientboundBlockEntityDataPacket.create(blockEntity);
                nbtPayload = new MetadataBlob(encodeGamePacket(level, packet));
            } catch (Exception ignored) {
                // Some BEs may fail encode; leave EMPTY rather than aborting chunk capture.
            }
            blockEntities.add(new ChunkState.BlockEntityState(
                    new ReplayMath.BlockPos(blockPos.getX(), blockPos.getY(), blockPos.getZ()),
                    typeId,
                    nbtPayload
            ));
        }
        return blockEntities;
    }

    /**
     * Re-reads client-sync block entities for an already-tracked chunk (no LevelChunkWithLight re-encode).
     *
     * @return {@code null} if unloaded
     */
    @Override
    public List<ChunkState.BlockEntityState> captureBlockEntitiesIfLoaded(World world, ReplayMath.ChunkPos position) {
        if (!(world instanceof CraftWorld craftWorld)) {
            throw new IllegalArgumentException("Expected CraftWorld, got " + world.getClass().getName());
        }
        ServerLevel level = craftWorld.getHandle();
        LevelChunk chunk = level.getChunkSource().getChunkNow(position.x(), position.z());
        if (chunk == null) {
            return null;
        }
        return captureBlockEntityStates(level, chunk);
    }

    @Override
    public boolean isChunkLoaded(World world, ReplayMath.ChunkPos position) {
        if (!(world instanceof CraftWorld craftWorld)) {
            throw new IllegalArgumentException("Expected CraftWorld, got " + world.getClass().getName());
        }
        return craftWorld.getHandle().getChunkSource().getChunkNow(position.x(), position.z()) != null;
    }

    @Override
    public long chunkCheapFingerprint(World world, ReplayMath.ChunkPos position) {
        if (!(world instanceof CraftWorld craftWorld)) {
            throw new IllegalArgumentException("Expected CraftWorld, got " + world.getClass().getName());
        }
        LevelChunk chunk = craftWorld.getHandle().getChunkSource().getChunkNow(position.x(), position.z());
        if (chunk == null) {
            return 0L;
        }
        return cheapFingerprintChunk(chunk);
    }

    @Override
    public long chunkContentFingerprint(World world, ReplayMath.ChunkPos position) {
        if (!(world instanceof CraftWorld craftWorld)) {
            throw new IllegalArgumentException("Expected CraftWorld, got " + world.getClass().getName());
        }
        LevelChunk chunk = craftWorld.getHandle().getChunkSource().getChunkNow(position.x(), position.z());
        if (chunk == null) {
            return 0L;
        }
        return fingerprintChunk(chunk);
    }

    @Override
    public void seedChunkBlockCache(
            World world,
            ReplayMath.ChunkPos position,
            ReplayMath.ChunkPosKey key,
            ChunkBlockCache cache
    ) {
        if (!(world instanceof CraftWorld craftWorld)) {
            throw new IllegalArgumentException("Expected CraftWorld, got " + world.getClass().getName());
        }
        ServerLevel level = craftWorld.getHandle();
        LevelChunk chunk = level.getChunkSource().getChunkNow(position.x(), position.z());
        if (chunk == null) {
            return;
        }
        cache.put(key, buildSectionCache(chunk));
    }

    @Override
    public ChunkBlockDiffResult diffChunkBlocks(
            World world,
            ReplayMath.ChunkPos position,
            ReplayMath.ChunkPosKey key,
            ChunkBlockCache cache,
            boolean forceSectionCrc
    ) {
        if (!(world instanceof CraftWorld craftWorld)) {
            throw new IllegalArgumentException("Expected CraftWorld, got " + world.getClass().getName());
        }
        ServerLevel level = craftWorld.getHandle();
        LevelChunk chunk = level.getChunkSource().getChunkNow(position.x(), position.z());
        if (chunk == null) {
            return new ChunkBlockDiffResult(List.of(), 0L);
        }

        LevelChunkSection[] sections = chunk.getSections();
        ChunkBlockCache.Entry previous = cache.get(key);
        if (previous == null || previous.sectionCount() != sections.length) {
            ChunkBlockCache.Entry seeded = buildSectionCache(chunk);
            cache.put(key, seeded);
            return new ChunkBlockDiffResult(List.of(), combineSectionFingerprints(chunk, seeded));
        }

        ServerPlayer encoder = level.getRandomPlayer();
        if (encoder == null && !level.players().isEmpty()) {
            encoder = level.players().get(0);
        }
        if (encoder == null) {
            return new ChunkBlockDiffResult(List.of(), combineSectionFingerprints(chunk, previous));
        }

        List<ChunkBlockDelta> deltas = new ArrayList<>();
        long[] nextFp = null;
        long[] nextCheap = null;
        int[][] nextBlocks = null;
        int baseX = position.x() << 4;
        int baseZ = position.z() << 4;
        boolean cacheTouched = false;

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            long liveCheap = cheapSectionFingerprint(section);
            long previousCheap = previous.sectionCheaps()[sectionIndex];

            if (!forceSectionCrc && liveCheap == previousCheap) {
                continue;
            }

            long liveFp = sectionFingerprint(section);
            if (liveFp == previous.sectionFingerprints()[sectionIndex]) {
                // Palette identity drifted without block-id change — refresh cheap only.
                if (liveCheap != previousCheap) {
                    if (nextCheap == null) {
                        nextFp = previous.sectionFingerprints().clone();
                        nextCheap = previous.sectionCheaps().clone();
                        nextBlocks = previous.sectionBlocks().clone();
                    }
                    nextCheap[sectionIndex] = liveCheap;
                    cacheTouched = true;
                }
                continue;
            }

            if (nextFp == null) {
                nextFp = previous.sectionFingerprints().clone();
                nextCheap = previous.sectionCheaps().clone();
                nextBlocks = previous.sectionBlocks().clone();
            }

            int[] packed = packSection(section);
            int[] before = previous.sectionBlocks()[sectionIndex];
            nextFp[sectionIndex] = liveFp;
            nextCheap[sectionIndex] = liveCheap;
            nextBlocks[sectionIndex] = packed;
            cacheTouched = true;

            int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
            int baseY = sectionY << 4;
            if (before == null && packed == null) {
                continue;
            }
            if (before == null) {
                for (int i = 0; i < 4096; i++) {
                    if (packed[i] == 0) {
                        continue;
                    }
                    deltas.add(blockDelta(level, chunk, baseX, baseY, baseZ, i));
                }
            } else if (packed == null) {
                for (int i = 0; i < 4096; i++) {
                    if (before[i] == 0) {
                        continue;
                    }
                    deltas.add(blockDelta(level, chunk, baseX, baseY, baseZ, i));
                }
            } else {
                for (int i = 0; i < 4096; i++) {
                    if (before[i] == packed[i]) {
                        continue;
                    }
                    deltas.add(blockDelta(level, chunk, baseX, baseY, baseZ, i));
                }
            }
        }

        if (!cacheTouched) {
            return new ChunkBlockDiffResult(List.of(), combineSectionFingerprints(chunk, previous));
        }

        ChunkBlockCache.Entry updated = new ChunkBlockCache.Entry(nextFp, nextCheap, nextBlocks);
        cache.put(key, updated);
        return new ChunkBlockDiffResult(deltas, combineSectionFingerprints(chunk, updated));
    }

    private ChunkBlockDelta blockDelta(
            ServerLevel level,
            LevelChunk chunk,
            int baseX,
            int baseY,
            int baseZ,
            int sectionLocalIndex
    ) {
        int y = baseY + (sectionLocalIndex >> 8);
        int xz = sectionLocalIndex & 0xFF;
        int x = baseX + (xz & 0xF);
        int z = baseZ + (xz >> 4);
        BlockPos blockPos = new BlockPos(x, y, z);
        BlockState state = chunk.getBlockState(blockPos);
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String blockId = id != null ? id.toString() : state.toString();
        byte[] payload = encodeGamePacket(level, new ClientboundBlockUpdatePacket(blockPos, state));
        return new ChunkBlockDelta(
                new ReplayMath.BlockPos(x, y, z),
                blockId,
                new MetadataBlob(payload)
        );
    }

    private ChunkBlockCache.Entry buildSectionCache(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        long[] fps = new long[sections.length];
        long[] cheaps = new long[sections.length];
        int[][] blocks = new int[sections.length][];
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            fps[i] = sectionFingerprint(section);
            cheaps[i] = cheapSectionFingerprint(section);
            blocks[i] = packSection(section);
        }
        return new ChunkBlockCache.Entry(fps, cheaps, blocks);
    }

    private long combineSectionFingerprints(LevelChunk chunk, ChunkBlockCache.Entry entry) {
        long hash = 0x9E3779B97F4A7C15L;
        for (long fp : entry.sectionFingerprints()) {
            hash = mix(hash, (int) fp);
            hash = mix(hash, (int) (fp >>> 32));
        }
        return mixBlockEntityUpdateTags(hash, chunk);
    }

    /**
     * Per-section content digest: mix palette entries + bit-storage words (+ biomes) without
     * {@link LevelChunkSection#write} / CRC allocation.
     */
    private long sectionFingerprint(LevelChunkSection section) {
        if (section == null || section.hasOnlyAir()) {
            return 0L;
        }
        long h = 0x9E3779B97F4A7C15L;
        h = mixPalettedContainer(h, section.getStates());
        h = mixPalettedContainerRo(h, section.getBiomes());
        return h == 0L ? 1L : h;
    }

    private static long mixPalettedContainer(long h, PalettedContainer<?> container) {
        if (container == null) {
            return mix(h, 0);
        }
        var data = container.data;
        h = mix(h, container.bitsPerEntry());
        var palette = data.palette();
        int paletteSize = palette.getSize();
        h = mix(h, paletteSize);
        // GlobalPalette.getSize() is the full registry — do not iterate.
        if (paletteSize > 0 && paletteSize <= MAX_LOCAL_PALETTE_MIX) {
            for (int i = 0; i < paletteSize; i++) {
                Object value = palette.valueFor(i);
                if (value instanceof BlockState state) {
                    h = mix(h, Block.getId(state));
                } else {
                    h = mix(h, System.identityHashCode(value));
                }
            }
        }
        BitStorage storage = data.storage();
        long[] raw = storage.getRaw();
        if (raw != null) {
            h = mix(h, raw.length);
            for (long word : raw) {
                h = mix(h, (int) word);
                h = mix(h, (int) (word >>> 32));
            }
        }
        return h;
    }

    private static long mixPalettedContainerRo(long h, PalettedContainerRO<?> container) {
        if (container instanceof PalettedContainer<?> typed) {
            return mixPalettedContainer(h, typed);
        }
        if (container == null) {
            return mix(h, 0);
        }
        // Fallback: bits only (should not happen — biomes are PalettedContainer).
        return mix(h, container.bitsPerEntry());
    }

    /**
     * Ultra-cheap per-section guard (palette identity / bits / size). Used to skip
     * {@link #sectionFingerprint} when the section cannot have changed in-place via a
     * palette-stable path — except on force-verify ticks.
     */
    private static long cheapSectionFingerprint(LevelChunkSection section) {
        if (section == null || section.hasOnlyAir()) {
            return 0L;
        }
        long h = 0x9E3779B97F4A7C15L;
        var states = section.getStates();
        var data = states.data;
        h = mix(h, System.identityHashCode(data));
        h = mix(h, states.bitsPerEntry());
        h = mix(h, data.palette().getSize());
        h = mix(h, data.storage().getSize());
        h = mix(h, section.hasFluid() ? 1 : 0);
        return h == 0L ? 1L : h;
    }

    /** @return {@code null} for air-only sections */
    private static int[] packSection(LevelChunkSection section) {
        if (section == null || section.hasOnlyAir()) {
            return null;
        }
        var states = section.getStates();
        // Single-value (bits==0): fill without 4096 palette lookups.
        if (states.bitsPerEntry() == 0) {
            BlockState only = states.data.palette().valueFor(0);
            int id = Block.getId(only);
            int[] ids = new int[4096];
            Arrays.fill(ids, id);
            return ids;
        }
        int[] ids = new int[4096];
        for (int i = 0; i < 4096; i++) {
            ids[i] = Block.getId(states.get(i));
        }
        return ids;
    }

    /**
     * Ultra-cheap O(sections) guard: palette identity / bits / size + block-entity count.
     * Avoids {@link LevelChunkSection#getSerializedSize()} which dominated MSPT in Spark
     * (PalettedContainer size walks). In-place single-block edits may not change this guard;
     * staggered deep CRC still catches silent pastes.
     */
    private static long cheapFingerprintChunk(LevelChunk chunk) {
        long h = 0x9E3779B97F4A7C15L;
        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null || section.hasOnlyAir()) {
                h = mix(h, 0);
                continue;
            }
            var states = section.getStates();
            var data = states.data;
            h = mix(h, System.identityHashCode(data));
            h = mix(h, states.bitsPerEntry());
            h = mix(h, data.palette().getSize());
            h = mix(h, data.storage().getSize());
            h = mix(h, section.hasFluid() ? 1 : 0);
        }
        h = mix(h, chunk.getBlockEntities().size());
        return h == 0L ? 1L : h;
    }

    /**
     * Digests block-section fingerprints + block-entity identities. Must match
     * {@link #combineSectionFingerprints} so deep section-diff does not spuriously
     * rewrite {@link ChunkState} every scan.
     */
    private long fingerprintChunk(LevelChunk chunk) {
        long hash = 0x9E3779B97F4A7C15L;
        for (LevelChunkSection section : chunk.getSections()) {
            long fp = sectionFingerprint(section);
            hash = mix(hash, (int) fp);
            hash = mix(hash, (int) (fp >>> 32));
        }
        return mixBlockEntityUpdateTags(hash, chunk);
    }

    /**
     * Mixes BE identities + {@code getUpdateTag} digests (client-visible sync data only —
     * container item lists are not in update tags and are intentionally ignored).
     */
    private long mixBlockEntityUpdateTags(long hash, LevelChunk chunk) {
        net.minecraft.core.HolderLookup.Provider registries = chunk.getLevel().registryAccess();
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockPos pos = entry.getKey();
            BlockEntity be = entry.getValue();
            hash = mix(hash, pos.getX());
            hash = mix(hash, pos.getY());
            hash = mix(hash, pos.getZ());
            Identifier typeKey = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
            hash = mix(hash, typeKey != null ? typeKey.hashCode() : 0);
            try {
                CompoundTag tag = be.getUpdateTag(registries);
                hash = mix(hash, tag != null ? tag.hashCode() : 0);
            } catch (Exception ignored) {
                // Leave identity-only mix if tag read fails.
            }
        }
        return hash == 0L ? 1L : hash;
    }

    private static long mix(long hash, int value) {
        hash ^= Integer.toUnsignedLong(value);
        hash *= 0x9E3779B97F4A7C15L;
        return Long.rotateLeft(hash, 13);
    }

    @Override
    public WorldState captureWorld(World world) {
        if (!(world instanceof CraftWorld craftWorld)) {
            throw new IllegalArgumentException("Expected CraftWorld, got " + world.getClass().getName());
        }
        ServerLevel level = craftWorld.getHandle();
        var border = level.getWorldBorder();
        return new WorldState(
                dimensionOf(level),
                level.getGameTime(),
                world.getTime(),
                level.isRaining(),
                level.getRainLevel(1.0f),
                level.getThunderLevel(1.0f),
                border.getCenterX(),
                border.getCenterZ(),
                border.getSize(),
                border.getLerpTarget(),
                border.getLerpTime(),
                level.getSeaLevel()
        );
    }

    @Override
    public int protocolVersion() {
        return SharedConstants.getProtocolVersion();
    }

    @Override
    public int dataVersion() {
        return org.bukkit.Bukkit.getUnsafe().getDataVersion();
    }

    @Override
    public String versionString() {
        return SharedConstants.getCurrentVersion().name();
    }

    private static DimensionId dimensionOf(ServerLevel level) {
        return DimensionId.of(level.dimension().identifier().toString());
    }

    private static String poseName(Pose pose) {
        return pose == null ? "STANDING" : pose.name();
    }

    private static String gameModeName(GameType type) {
        return type == null ? "SURVIVAL" : type.getName().toUpperCase();
    }

    private EquipmentState captureEquipment(LivingEntity living, EquipmentState previous) {
        ServerPlayer registry = living instanceof ServerPlayer sp ? sp : registryPlayer(living);
        if (registry == null) {
            return EquipmentState.EMPTY;
        }
        ReplayItemStack main = itemCodec.toReplayItem(
                registry, living.getItemBySlot(EquipmentSlot.MAINHAND),
                previous != null ? previous.mainHand() : null);
        ReplayItemStack off = itemCodec.toReplayItem(
                registry, living.getItemBySlot(EquipmentSlot.OFFHAND),
                previous != null ? previous.offHand() : null);
        ReplayItemStack head = itemCodec.toReplayItem(
                registry, living.getItemBySlot(EquipmentSlot.HEAD),
                previous != null ? previous.helmet() : null);
        ReplayItemStack chest = itemCodec.toReplayItem(
                registry, living.getItemBySlot(EquipmentSlot.CHEST),
                previous != null ? previous.chestplate() : null);
        ReplayItemStack legs = itemCodec.toReplayItem(
                registry, living.getItemBySlot(EquipmentSlot.LEGS),
                previous != null ? previous.leggings() : null);
        ReplayItemStack feet = itemCodec.toReplayItem(
                registry, living.getItemBySlot(EquipmentSlot.FEET),
                previous != null ? previous.boots() : null);
        if (previous != null
                && main == previous.mainHand()
                && off == previous.offHand()
                && head == previous.helmet()
                && chest == previous.chestplate()
                && legs == previous.leggings()
                && feet == previous.boots()) {
            return previous;
        }
        return new EquipmentState(main, off, head, chest, legs, feet);
    }

    private List<ReplayItemStack> captureHotbar(ServerPlayer sp, List<ReplayItemStack> previous) {
        ReplayItemStack[] slots = new ReplayItemStack[9];
        boolean allEmpty = true;
        boolean allReused = previous != null && previous.size() == 9;
        for (int i = 0; i < 9; i++) {
            ReplayItemStack prior = previous != null && i < previous.size() ? previous.get(i) : null;
            ReplayItemStack item = itemCodec.toReplayItem(sp, sp.getInventory().getItem(i), prior);
            slots[i] = item;
            if (item != ReplayItemStack.EMPTY && !item.isEmpty()) {
                allEmpty = false;
            }
            if (allReused && item != prior) {
                allReused = false;
            }
        }
        if (allEmpty) {
            return PlayerState.EMPTY_HOTBAR;
        }
        if (allReused) {
            return previous;
        }
        return List.of(slots);
    }

    private static ServerPlayer registryPlayer(LivingEntity living) {
        if (living.level() instanceof ServerLevel level) {
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

    /**
     * Encodes entity metadata for replay.
     *
     * <p>H3: prefer {@link SynchedEntityData#getNonDefaultValues()}. For players, when pose /
     * shared-flags may have returned to defaults, use {@code packAll} so un-sneak / un-sprint are
     * visible in replay. Never {@code packDirty()} (steals vanilla client dirty bits).
     *
     * @param packAllPlayer when true and entity is a player, use {@code packAll}
     */
    private MetadataBlob captureEntityMetadata(Entity entity, boolean packAllPlayer) {
        List<SynchedEntityData.DataValue<?>> values;
        if (packAllPlayer && entity instanceof ServerPlayer) {
            values = entity.getEntityData().packAll();
        } else {
            values = entity.getEntityData().getNonDefaultValues();
        }
        if (values == null || values.isEmpty()) {
            return MetadataBlob.EMPTY;
        }
        ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(entity.getId(), values);
        ServerLevel level = (ServerLevel) entity.level();
        return new MetadataBlob(encodeGamePacket(level, packet));
    }

    private static MetadataBlob captureProfileProperties(Player player) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            var props = player.getPlayerProfile().getProperties();
            out.writeInt(props.size());
            for (ProfileProperty prop : props) {
                writeUtf(out, prop.getName());
                writeUtf(out, prop.getValue());
                boolean hasSig = prop.getSignature() != null;
                out.writeBoolean(hasSig);
                if (hasSig) {
                    writeUtf(out, prop.getSignature());
                }
            }
            out.flush();
            return new MetadataBlob(baos.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode profile properties", e);
        }
    }

    private static void writeUtf(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private ProtocolInfo<ClientGamePacketListener> cachedProtocolInfo;
    private net.minecraft.core.RegistryAccess cachedRegistryAccess;

    private byte[] encodeGamePacket(ServerLevel level, Packet<? super ClientGamePacketListener> packet) {
        StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec = codecFor(level);
        encodeScratch.clear();
        try {
            codec.encode(encodeScratch, packet);
            return ByteBufUtil.getBytes(encodeScratch);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to encode " + packet.getClass().getSimpleName(), e);
        }
    }

    private StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codecFor(ServerLevel level) {
        net.minecraft.core.RegistryAccess access = level.registryAccess();
        if (cachedProtocolInfo == null || cachedRegistryAccess != access) {
            cachedRegistryAccess = access;
            cachedProtocolInfo = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                    RegistryFriendlyByteBuf.decorator(access));
        }
        return cachedProtocolInfo.codec();
    }
}
