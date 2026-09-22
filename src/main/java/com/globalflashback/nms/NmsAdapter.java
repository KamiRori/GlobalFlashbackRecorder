package com.globalflashback.nms;

import com.globalflashback.capture.ChunkBlockCache;
import com.globalflashback.state.ChunkState;
import com.globalflashback.state.EntityState;
import com.globalflashback.state.PlayerState;
import com.globalflashback.state.ReplayMath;
import com.globalflashback.state.WorldState;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Version-isolated reader of server-authoritative state into immutable Replay models.
 *
 * <p>Must be called on the server main thread only.
 */
public interface NmsAdapter {
    PlayerState capturePlayer(Player player);

    /**
     * Incremental player capture. When {@code previous} is non-null and {@code captureHeavy} is
     * false, reuses hotbar / equipment / metadata / potion / profile payloads unless light fields
     * that imply appearance change differ. Never calls {@code packDirty()} (would steal vanilla
     * client dirty bits).
     */
    PlayerState capturePlayer(Player player, PlayerState previous, boolean captureHeavy);

    EntityState captureEntity(Entity entity);

    /**
     * Incremental entity capture. Reuses equipment / metadata when motion/mounts unchanged and
     * {@code captureHeavy} is false.
     */
    EntityState captureEntity(Entity entity, EntityState previous, boolean captureHeavy);

    /**
     * @return chunk state if the chunk is already loaded; {@code null} if not loaded (never force-loads)
     */
    ChunkState captureChunkIfLoaded(World world, ReplayMath.ChunkPos position);

    /**
     * Client-sync block-entity snapshots ({@code getUpdateTag} / BlockEntityData packets) for an
     * already-loaded chunk. Does not include container inventories. {@code null} if unloaded.
     */
    List<ChunkState.BlockEntityState> captureBlockEntitiesIfLoaded(World world, ReplayMath.ChunkPos position);

    /**
     * Cheap loaded check without encoding chunk payloads.
     */
    boolean isChunkLoaded(World world, ReplayMath.ChunkPos position);

    /**
     * O(sections) guard fingerprint (serialized sizes + block-entity count). Returns {@code 0} if unloaded.
     */
    long chunkCheapFingerprint(World world, ReplayMath.ChunkPos position);

    /**
     * Full content digest of an already-loaded chunk (section bytes + block-entity identities).
     * Expensive — call only when {@link #chunkCheapFingerprint} changes or on a staggered deep scan.
     * Returns {@code 0} if unloaded.
     */
    long chunkContentFingerprint(World world, ReplayMath.ChunkPos position);

    /**
     * Seeds {@code cache} with per-section packed block-state ids + section fingerprints.
     */
    void seedChunkBlockCache(
            World world,
            ReplayMath.ChunkPos position,
            ReplayMath.ChunkPosKey key,
            ChunkBlockCache cache
    );

    /**
     * Section-aware silent diff: only packs sections whose fingerprint changed.
     * Updates the cache and returns deltas plus a combined content fingerprint.
     *
     * @param forceSectionCrc when {@code false}, skip {@code section.write} CRC for sections whose
     *                        cheap palette guard is unchanged; when {@code true}, CRC every section
     *                        (rare verify path for cheap-blind in-place edits)
     */
    ChunkBlockDiffResult diffChunkBlocks(
            World world,
            ReplayMath.ChunkPos position,
            ReplayMath.ChunkPosKey key,
            ChunkBlockCache cache,
            boolean forceSectionCrc
    );

    WorldState captureWorld(World world);

    int protocolVersion();

    int dataVersion();

    String versionString();
}
