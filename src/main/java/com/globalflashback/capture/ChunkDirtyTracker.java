package com.globalflashback.capture;

import com.globalflashback.state.DimensionId;
import com.globalflashback.state.ReplayMath;
import org.bukkit.World;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Main-thread set of chunks that already received Bukkit/Paper block events this recording.
 * Those chunks skip fingerprint work — side-channel {@code BlockChange}s already cover them.
 * Silent edits (WorldEdit without events) still rely on staggered deep fingerprint scans.
 */
public final class ChunkDirtyTracker {
    private final Set<ReplayMath.ChunkPosKey> dirty = new HashSet<>();

    public void clear() {
        dirty.clear();
    }

    public void markBlock(World world, int blockX, int blockZ) {
        Objects.requireNonNull(world, "world");
        DimensionId dimension = DimensionId.of(world.getKey().toString());
        ReplayMath.ChunkPos pos = new ReplayMath.ChunkPos(blockX >> 4, blockZ >> 4);
        dirty.add(ReplayMath.ChunkPosKey.of(dimension, pos));
    }

    /**
     * @return {@code true} if the chunk was dirty (and is now cleared)
     */
    public boolean consume(ReplayMath.ChunkPosKey key) {
        return dirty.remove(key);
    }

    public boolean isEmpty() {
        return dirty.isEmpty();
    }
}
