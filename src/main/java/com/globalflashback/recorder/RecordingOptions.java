package com.globalflashback.recorder;

import com.globalflashback.capture.CaptureOptions;

import java.util.Objects;

/**
 * Options for a Global Replay recording session (Phase 4).
 *
 * @param keyframeIntervalTicks period between in-memory seek keyframes (not Flashback ZIP
 *                              forcePlaySnapshot chunks; does not re-encode LevelChunkWithLight)
 * @param captureOptions        main-thread capture scope
 */
public record RecordingOptions(int keyframeIntervalTicks, CaptureOptions captureOptions) {
    /** 100 ticks ≈ 5s — short interval for Phase 4 validation; production will tune. */
    public static final RecordingOptions DEFAULT = new RecordingOptions(100, CaptureOptions.DEFAULT);

    public RecordingOptions {
        if (keyframeIntervalTicks < 1) {
            throw new IllegalArgumentException("keyframeIntervalTicks must be >= 1");
        }
        Objects.requireNonNull(captureOptions, "captureOptions");
    }
}
