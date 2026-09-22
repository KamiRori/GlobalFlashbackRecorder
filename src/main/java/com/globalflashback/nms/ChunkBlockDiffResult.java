package com.globalflashback.nms;

import java.util.List;

/**
 * Result of a silent chunk block diff, including an updated content fingerprint for {@code ChunkState}.
 */
public record ChunkBlockDiffResult(List<ChunkBlockDelta> deltas, long contentFingerprint) {
    public ChunkBlockDiffResult {
        if (deltas == null) {
            deltas = List.of();
        }
    }

    public boolean isEmpty() {
        return deltas.isEmpty();
    }
}
