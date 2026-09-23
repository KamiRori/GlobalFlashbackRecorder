package com.globalflashback.capture;

import com.globalflashback.delta.StateChange;
import com.globalflashback.nms.ChunkBlockDelta;
import com.globalflashback.nms.NmsAdapter;
import com.globalflashback.state.ChunkState;
import com.globalflashback.state.DimensionId;
import com.globalflashback.state.EntityState;
import com.globalflashback.state.GlobalSnapshot;
import com.globalflashback.state.PlayerState;
import com.globalflashback.state.ReplayMath;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Main-thread capture of currently loaded, accessible server state into {@link GlobalSnapshot}.
 *
 * <p>Chunk tracking (Phase 4 / H4): Bukkit-marked dirty chunks skip fingerprint entirely (events
 * already emitted {@code BlockChange}). Clean chunks run a staggered cadence that first checks the
 * ultra-cheap palette guard; only cheap mismatches (or a rarer verify tick) enter section CRC /
 * pack. Tracking uses a <strong>circular</strong> chunk radius (not a square), skips unloaded
 * slots early, and caps how many deep fingerprint checks run per tick so a large multi-player
 * union cannot dominate one MSPT spike.
 *
 * <p>Players / entities use light-field incremental capture with periodic heavy refresh. Heavy ticks
 * are staggered by entity id so 100 players do not all {@code packAll} on the same server tick.
 * Non-player entities are collected only from tracked, already-loaded chunks (same radius as chunk
 * capture) — never {@code World#getEntities()} whole-world scans.
 */
public final class ServerStateCapture {
    /**
     * Staggered silent-scan cadence (ticks). Only ~1/N tracked chunks are considered each tick;
     * most of those exit after a cheap palette check.
     */
    private static final int DEEP_FINGERPRINT_INTERVAL = 40;

    /**
     * Even when cheap matches, force a full section CRC this often (multiple of
     * {@link #DEEP_FINGERPRINT_INTERVAL}). Catches in-place single-block silent edits that do not
     * reshuffle palette identity. 160 ticks ≈ 8s.
     */
    private static final int VERIFY_FINGERPRINT_INTERVAL = 160;

    /**
     * Max chunks that may enter cheap/deep fingerprint work in one capture tick (H4). Excess deep
     * candidates reuse prior state and retry on their next stagger slot — bounds MSPT when many
     * players' radius unions are large.
     */
    private static final int MAX_DEEP_FINGERPRINT_CHECKS_PER_TICK = 48;

    /**
     * Full hotbar / equipment / metadata refresh cadence. Motion and vitals still update every tick
     * when light fields change; unchanged players/entities reuse the previous immutable state.
     */
    private static final int HEAVY_ENTITY_REFRESH_INTERVAL = 20;

    private final NmsAdapter adapter;
    private final ChunkBlockCache blockCache = new ChunkBlockCache();
    private final ChunkDirtyTracker dirtyChunks = new ChunkDirtyTracker();
    /** Main-thread scratch; cleared each capture (never escapes this class). */
    private final Set<World> worldsScratch = new HashSet<>();
    private final Set<ReplayMath.ChunkPosKey> chunkKeysScratch = new HashSet<>();
    /** Entity ids already captured this tick (chunk overlap across players). */
    private final Set<Integer> capturedEntityIdsScratch = new HashSet<>();

    public ServerStateCapture(NmsAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    public ChunkDirtyTracker dirtyChunks() {
        return dirtyChunks;
    }

    public void resetBlockCache() {
        blockCache.clear();
        dirtyChunks.clear();
    }

    public GlobalSnapshot capture(int tick, CaptureOptions options) {
        return capture(tick, options, null, null);
    }

    public GlobalSnapshot capture(
            int tick,
            CaptureOptions options,
            Map<ReplayMath.ChunkPosKey, ChunkState> reusableChunks
    ) {
        return capture(tick, options, reusableChunks == null ? null : stubPrevious(tick, reusableChunks), null);
    }

    /**
     * @param previous           prior snapshot for chunk / player / entity reuse; {@code null} on initial
     * @param silentBlockChanges when non-null, receives {@link StateChange.BlockChange}s for
     *                           fingerprint mismatches (WorldEdit / NMS paste / setBlock without events)
     */
    public GlobalSnapshot capture(
            int tick,
            CaptureOptions options,
            GlobalSnapshot previous,
            List<StateChange> silentBlockChanges
    ) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("ServerStateCapture must run on the main thread");
        }
        Objects.requireNonNull(options, "options");

        Map<ReplayMath.ChunkPosKey, ChunkState> reusableChunks =
                previous != null ? previous.chunks() : Map.of();
        Map<UUID, PlayerState> previousPlayers =
                previous != null ? previous.players() : Map.of();
        Map<Integer, EntityState> previousEntities =
                previous != null ? previous.entities() : Map.of();

        GlobalSnapshot.Builder builder = GlobalSnapshot.builder(tick);
        worldsScratch.clear();
        chunkKeysScratch.clear();
        capturedEntityIdsScratch.clear();

        int radius = options.chunkRadiusAroundPlayers();
        int radiusSq = radius * radius;
        int deepBudget = options.encodeChunkPayloads()
                ? Integer.MAX_VALUE
                : MAX_DEEP_FINGERPRINT_CHECKS_PER_TICK;

        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerState priorPlayer = previousPlayers.get(player.getUniqueId());
            boolean playerHeavy = isHeavyDue(tick, player.getEntityId(), previous == null);
            PlayerState playerState = adapter.capturePlayer(player, priorPlayer, playerHeavy);
            builder.player(playerState);
            builder.entity(toEntityState(playerState, previousEntities.get(playerState.entityId())));
            capturedEntityIdsScratch.add(playerState.entityId());
            worldsScratch.add(player.getWorld());

            int cx = player.getLocation().getBlockX() >> 4;
            int cz = player.getLocation().getBlockZ() >> 4;
            World world = player.getWorld();
            DimensionId dimension = DimensionId.of(world.getKey().toString());

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // H4: circular radius — drops square-corner chunks (~20% fewer at r=8).
                    if (dx * dx + dz * dz > radiusSq) {
                        continue;
                    }
                    int chunkX = cx + dx;
                    int chunkZ = cz + dz;
                    // Never force-load; unloaded slots are omitted → Diff emits ChunkUnload.
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        continue;
                    }
                    ReplayMath.ChunkPos pos = new ReplayMath.ChunkPos(chunkX, chunkZ);
                    ReplayMath.ChunkPosKey key = ReplayMath.ChunkPosKey.of(dimension, pos);
                    if (!chunkKeysScratch.add(key)) {
                        continue;
                    }

                    boolean allowDeep = true;
                    if (!options.encodeChunkPayloads()) {
                        int stagger = tick + key.hashCode();
                        boolean deepDue = Math.floorMod(stagger, DEEP_FINGERPRINT_INTERVAL) == 0;
                        if (deepDue) {
                            if (deepBudget <= 0) {
                                allowDeep = false;
                            } else {
                                deepBudget--;
                            }
                        }
                    }

                    ChunkState chunk = resolveChunk(
                            tick, world, pos, key, options, reusableChunks, silentBlockChanges, allowDeep);
                    if (chunk != null) {
                        builder.chunk(chunk);
                    }
                    if (options.includeNonPlayerEntities()) {
                        captureEntitiesInLoadedChunk(
                                tick, world, pos, previous == null, previousEntities, builder);
                    }
                }
            }
        }

        for (World world : worldsScratch) {
            builder.world(adapter.captureWorld(world));
        }

        return builder.build();
    }

    /**
     * Captures non-player entities in one already-tracked chunk. Never force-loads.
     */
    private void captureEntitiesInLoadedChunk(
            int tick,
            World world,
            ReplayMath.ChunkPos pos,
            boolean forceHeavy,
            Map<Integer, EntityState> previousEntities,
            GlobalSnapshot.Builder builder
    ) {
        if (!world.isChunkLoaded(pos.x(), pos.z())) {
            return;
        }
        // Already-loaded only: generate=false must not pull new chunks (AGENTS / SPEC Dynamic).
        org.bukkit.Chunk bukkitChunk = world.getChunkAt(pos.x(), pos.z(), false);
        if (bukkitChunk == null || !bukkitChunk.isLoaded()) {
            return;
        }
        for (Entity entity : bukkitChunk.getEntities()) {
            if (entity instanceof Player) {
                continue;
            }
            int id = entity.getEntityId();
            if (!capturedEntityIdsScratch.add(id)) {
                continue;
            }
            EntityState prior = previousEntities.get(id);
            boolean heavy = isHeavyDue(tick, id, forceHeavy);
            builder.entity(adapter.captureEntity(entity, prior, heavy));
        }
    }

    /** Spreads heavy metadata/equipment refresh across ticks by entity id. */
    private static boolean isHeavyDue(int tick, int entityId, boolean force) {
        if (force) {
            return true;
        }
        return Math.floorMod(tick + entityId, HEAVY_ENTITY_REFRESH_INTERVAL) == 0;
    }

    public GlobalSnapshot captureDefault(int tick) {
        return capture(tick, CaptureOptions.DEFAULT);
    }

    private static GlobalSnapshot stubPrevious(
            int tick,
            Map<ReplayMath.ChunkPosKey, ChunkState> reusableChunks
    ) {
        return new GlobalSnapshot(tick, Map.of(), reusableChunks, Map.of(), Map.of());
    }

    private ChunkState resolveChunk(
            int tick,
            World world,
            ReplayMath.ChunkPos pos,
            ReplayMath.ChunkPosKey key,
            CaptureOptions options,
            Map<ReplayMath.ChunkPosKey, ChunkState> reusableChunks,
            List<StateChange> silentBlockChanges,
            boolean allowDeep
    ) {
        if (!options.encodeChunkPayloads()) {
            ChunkState prior = reusableChunks.get(key);
            if (prior != null && adapter.isChunkLoaded(world, pos)) {
                // Event-covered chunks: side-channel already has BlockChanges — skip fingerprint.
                if (dirtyChunks.consume(key)) {
                    return prior;
                }
                // Clean chunks: staggered silent scan (H4: may defer when deep budget exhausted).
                int stagger = tick + key.hashCode();
                boolean deepDue = Math.floorMod(stagger, DEEP_FINGERPRINT_INTERVAL) == 0;
                if (!deepDue || !allowDeep) {
                    return prior;
                }

                long cheap = adapter.chunkCheapFingerprint(world, pos);
                boolean cheapChanged = cheap != prior.cheapFingerprint();
                boolean verifyDue = Math.floorMod(stagger, VERIFY_FINGERPRINT_INTERVAL) == 0;
                if (!cheapChanged && !verifyDue) {
                    return prior;
                }

                if (blockCache.get(key) == null) {
                    adapter.seedChunkBlockCache(world, pos, key, blockCache);
                    long live = adapter.chunkContentFingerprint(world, pos);
                    if (live == prior.contentFingerprint() && cheap == prior.cheapFingerprint()) {
                        return prior;
                    }
                    return refreshBlockEntities(world, pos, prior, live, cheap);
                }

                if (silentBlockChanges == null) {
                    if (cheap == prior.cheapFingerprint()) {
                        return prior;
                    }
                    return prior.withFingerprints(prior.contentFingerprint(), cheap);
                }

                int changesBefore = silentBlockChanges.size();
                // Verify-only path (cheap unchanged): force section CRC to catch blind edits.
                boolean forceSectionCrc = verifyDue && !cheapChanged;
                long live = appendSilentDiffs(
                        world, pos, key, dimensionOf(world), silentBlockChanges, forceSectionCrc);
                boolean blockChanges = silentBlockChanges.size() > changesBefore;
                if (!blockChanges) {
                    if (live == prior.contentFingerprint() && cheap == prior.cheapFingerprint()) {
                        return prior;
                    }
                    // Content fingerprint can change from BE update tags alone (signs, spawners).
                    return refreshBlockEntities(world, pos, prior, live, cheap);
                }
                ChunkState withBlocks = new ChunkState(
                        prior.dimension(),
                        prior.position(),
                        prior.blockAndLightPayload(),
                        prior.blockEntities(),
                        live,
                        cheap
                );
                return refreshBlockEntities(world, pos, withBlocks, live, cheap);
            }
            ChunkState captured = adapter.captureChunkIfLoaded(world, pos);
            if (captured != null && blockCache.get(key) == null) {
                adapter.seedChunkBlockCache(world, pos, key, blockCache);
            }
            return captured;
        }

        ChunkState captured = adapter.captureChunkIfLoaded(world, pos);
        // Initial snapshot only: seed block cache once; avoid re-pack on every encode path.
        if (captured != null && blockCache.get(key) == null) {
            adapter.seedChunkBlockCache(world, pos, key, blockCache);
        }
        return captured;
    }

    /**
     * @return updated content fingerprint from section-aware diff
     */
    private long appendSilentDiffs(
            World world,
            ReplayMath.ChunkPos pos,
            ReplayMath.ChunkPosKey key,
            DimensionId dimension,
            List<StateChange> out,
            boolean forceSectionCrc
    ) {
        var result = adapter.diffChunkBlocks(world, pos, key, blockCache, forceSectionCrc);
        for (ChunkBlockDelta delta : result.deltas()) {
            if (delta.payload().isEmpty()) {
                continue;
            }
            out.add(new StateChange.BlockChange(
                    dimension,
                    delta.pos(),
                    "",
                    delta.blockId(),
                    delta.payload()
            ));
        }
        return result.contentFingerprint();
    }

    /**
     * Re-captures client-sync BE payloads ({@code getUpdateTag} only — not container inventories).
     * SnapshotDiffer emits {@link StateChange.BlockEntityChange} when the list differs.
     */
    private ChunkState refreshBlockEntities(
            World world,
            ReplayMath.ChunkPos pos,
            ChunkState prior,
            long contentFingerprint,
            long cheapFingerprint
    ) {
        List<ChunkState.BlockEntityState> live = adapter.captureBlockEntitiesIfLoaded(world, pos);
        if (live == null) {
            return prior.withFingerprints(contentFingerprint, cheapFingerprint);
        }
        if (live.equals(prior.blockEntities())) {
            return prior.withFingerprints(contentFingerprint, cheapFingerprint);
        }
        return prior.withBlockEntities(live, contentFingerprint, cheapFingerprint);
    }

    private static DimensionId dimensionOf(World world) {
        return DimensionId.of(world.getKey().toString());
    }

    /**
     * Players live in both {@code players} and {@code entities} for initial Flashback spawn encoding.
     * Reuse the prior entity row when the derived player→entity projection is unchanged.
     */
    private static EntityState toEntityState(PlayerState player, EntityState previous) {
        EntityState projected = new EntityState(
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
        if (previous != null && previous.equals(projected)) {
            return previous;
        }
        return projected;
    }
}
