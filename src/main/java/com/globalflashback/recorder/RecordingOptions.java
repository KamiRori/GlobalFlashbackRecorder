package com.globalflashback.recorder;

import com.globalflashback.capture.CaptureOptions;

import java.util.Objects;

/**
 * Options for a Global Replay recording session.
 *
 * @param keyframeIntervalTicks period between optional in-memory seek keyframes (not Flashback ZIP
 *                              forcePlaySnapshot; does not re-encode LevelChunkWithLight)
 * @param captureOptions        main-thread capture scope
 * @param verifySeekOnStop      when true, rebuild snapshot from keyframes+deltas on stop (expensive;
 *                              default false — H7/H8). Also keeps deltas in memory for verify.
 * @param deferEncodeOnStop     when true, Flashback ZIP encode runs on a dedicated worker after stop
 *                              (H10). Main thread only freezes bootstrap via {@code prepareEncodeJob}.
 * @param spillDeltas           when true, non-empty deltas are spilled async to disk during recording
 *                              so heap does not retain the full timeline (H11). Default true.
 */
public record RecordingOptions(
        int keyframeIntervalTicks,
        CaptureOptions captureOptions,
        boolean verifySeekOnStop,
        boolean deferEncodeOnStop,
        boolean spillDeltas
) {
    /** 100 ticks ≈ 5s. Seek verify off; async encode; delta spill on. */
    public static final RecordingOptions DEFAULT =
            new RecordingOptions(100, CaptureOptions.DEFAULT, false, true, true);

    public RecordingOptions(int keyframeIntervalTicks, CaptureOptions captureOptions) {
        this(keyframeIntervalTicks, captureOptions, false, true, true);
    }

    public RecordingOptions(
            int keyframeIntervalTicks,
            CaptureOptions captureOptions,
            boolean verifySeekOnStop,
            boolean deferEncodeOnStop
    ) {
        this(keyframeIntervalTicks, captureOptions, verifySeekOnStop, deferEncodeOnStop, true);
    }

    public RecordingOptions {
        if (keyframeIntervalTicks < 1) {
            throw new IllegalArgumentException("keyframeIntervalTicks must be >= 1");
        }
        Objects.requireNonNull(captureOptions, "captureOptions");
    }
}
