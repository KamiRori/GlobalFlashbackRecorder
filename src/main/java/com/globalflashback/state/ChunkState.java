package com.globalflashback.state;

import java.util.List;
import java.util.Objects;

/**
 * Immutable chunk snapshot entry.
 *
 * <p>{@link #cheapFingerprint()} is an ultra-cheap palette/identity guard (no section serialize).
 * {@link #contentFingerprint()} is a full section CRC used only on staggered deep scans for
 * silent plugin edits. Event-driven block changes skip fingerprint via {@link com.globalflashback.capture.ChunkDirtyTracker}.
 */
public record ChunkState(
        DimensionId dimension,
        ReplayMath.ChunkPos position,
        MetadataBlob blockAndLightPayload,
        List<BlockEntityState> blockEntities,
        long contentFingerprint,
        long cheapFingerprint
) {
    public ChunkState {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        blockAndLightPayload = Objects.requireNonNullElse(blockAndLightPayload, MetadataBlob.EMPTY);
        blockEntities = List.copyOf(blockEntities == null ? List.of() : blockEntities);
    }

    public ChunkState withFingerprints(long contentFingerprint, long cheapFingerprint) {
        return new ChunkState(dimension, position, blockAndLightPayload, blockEntities,
                contentFingerprint, cheapFingerprint);
    }

    public ChunkState withBlockEntities(List<BlockEntityState> blockEntities, long contentFingerprint, long cheapFingerprint) {
        return new ChunkState(dimension, position, blockAndLightPayload, blockEntities,
                contentFingerprint, cheapFingerprint);
    }

    /**
     * Client-sync block-entity snapshot. {@code nbtPayload} is a pre-encoded
     * {@code ClientboundBlockEntityDataPacket} built from {@code getUpdateTag} (signs, spawners,
     * banners, …). Container inventories are intentionally not included.
     */
    public record BlockEntityState(ReplayMath.BlockPos pos, String typeId, MetadataBlob nbtPayload) {
        public BlockEntityState {
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(typeId, "typeId");
            nbtPayload = Objects.requireNonNullElse(nbtPayload, MetadataBlob.EMPTY);
        }
    }
}
