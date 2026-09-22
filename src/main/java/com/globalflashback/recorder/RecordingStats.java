package com.globalflashback.recorder;

import java.nio.file.Path;

/**
 * Immutable counters for a recording session (or live status snapshot).
 */
public record RecordingStats(
        boolean recording,
        String name,
        int startTick,
        int lastTick,
        int ticksRecorded,
        int nonEmptyDeltas,
        int emptyTicks,
        int keyframes,
        long totalChanges,
        int lastPlayers,
        int lastEntities,
        int lastChunks,
        boolean seekVerified,
        Path outputFile
) {
    public static RecordingStats idle() {
        return new RecordingStats(false, "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, null);
    }
}
