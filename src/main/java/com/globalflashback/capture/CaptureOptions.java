package com.globalflashback.capture;

/**
 * Options for a single main-thread capture pass.
 *
 * @param chunkRadiusAroundPlayers circular chunk radius (Chebyshev corners excluded) around each
 *                                 online player; only already-loaded chunks are tracked
 * @param includeNonPlayerEntities whether to capture non-player entities in tracked chunks
 * @param encodeChunkPayloads      when true, fully encode chunk+light packets (initial snapshot
 *                                 only). Per-tick and periodic Keyframes must keep this false:
 *                                 Flashback ZIP does not re-emit mid-stream LevelChunkWithLight for
 *                                 known chunks, and re-encoding every keyframe caused main-thread
 *                                 MSPT spikes. Silent edits use fingerprint + BlockChange instead.
 */
public record CaptureOptions(
        int chunkRadiusAroundPlayers,
        boolean includeNonPlayerEntities,
        boolean encodeChunkPayloads
) {
    /** Default circular radius 8 (~π×8² loaded chunks per player, not a 17×17 square). */
    public static final CaptureOptions DEFAULT = new CaptureOptions(8, true, true);

    public CaptureOptions(int chunkRadiusAroundPlayers, boolean includeNonPlayerEntities) {
        this(chunkRadiusAroundPlayers, includeNonPlayerEntities, true);
    }

    public CaptureOptions {
        if (chunkRadiusAroundPlayers < 0) {
            throw new IllegalArgumentException("chunkRadiusAroundPlayers must be >= 0");
        }
    }

    public CaptureOptions withEncodeChunkPayloads(boolean encodeChunkPayloads) {
        return new CaptureOptions(chunkRadiusAroundPlayers, includeNonPlayerEntities, encodeChunkPayloads);
    }
}
