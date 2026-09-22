package com.globalflashback.capture;

import com.globalflashback.state.ReplayMath;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-recording, per-section block-id cache for silent chunk diffs.
 * Main-thread only. Avoids packing an entire chunk height when only a few sections changed.
 */
public final class ChunkBlockCache {
    /**
     * @param sectionFingerprints CRC digests parallel to {@link #sectionBlocks}; 0 = air-only
     * @param sectionCheaps       ultra-cheap palette guards parallel to sections (skip CRC when equal)
     * @param sectionBlocks       each entry is {@code null} (air) or {@code int[4096]} packed as
     *                            {@code y*256 + z*16 + x} within the section
     */
    public record Entry(long[] sectionFingerprints, long[] sectionCheaps, int[][] sectionBlocks) {
        public Entry {
            if (sectionFingerprints == null || sectionCheaps == null || sectionBlocks == null) {
                throw new IllegalArgumentException("section arrays required");
            }
            if (sectionFingerprints.length != sectionBlocks.length
                    || sectionFingerprints.length != sectionCheaps.length) {
                throw new IllegalArgumentException("fingerprint/cheap/blocks length mismatch");
            }
        }

        public int sectionCount() {
            return sectionFingerprints.length;
        }
    }

    private final Map<ReplayMath.ChunkPosKey, Entry> byChunk = new HashMap<>();

    public Entry get(ReplayMath.ChunkPosKey key) {
        return byChunk.get(key);
    }

    public void put(ReplayMath.ChunkPosKey key, Entry entry) {
        byChunk.put(key, entry);
    }

    public void clear() {
        byChunk.clear();
    }
}
