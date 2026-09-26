package com.globalflashback.motion.format;

/**
 * Shared constants for GFR Client Pose.
 * Keep in sync between Fabric mod and Paper plugin.
 */
public final class GfrMotionFormat {
    public static final String FORMAT_ID = "gfr_client_pose";
    /**
     * v3: sample adds {@code vehicleYaw}/{@code vehiclePitch} (client-auth craft facing).
     * v2: tick-relative {@code timeNs} only (52-byte samples).
     */
    public static final int VERSION = 3;

    /** Legacy process-time rebase model (pre v2 recordings). */
    public static final String TIMING_MODEL_PROCESS_REBASE_V1 = "process_rebase_v1";

    /**
     * Client stamps {@code timeNs} on the recording tick timeline (tick index × 50ms + offset
     * within the tick). Server stores stamps as-is (no process-time rebase).
     */
    public static final String TIMING_MODEL_TICK_RELATIVE_V2 = "tick_relative_v2";

    /** Plugin channel / custom payload path (Fabric Identifier path; Paper channel uses full string). */
    public static final String CHANNEL = "gfr:client_pose";
    public static final String SYNC_CHANNEL = "gfr:pose_sync";

    public static final String ZIP_META = "gfr/motion.meta.json";
    public static final String ZIP_BIN = "gfr/motion.bin";

    /** Current on-wire / on-disk AoS sample size (v3). */
    public static final int SAMPLE_STRIDE = 60;

    /** Pre-v3 sample size (no vehicle facing fields). */
    public static final int SAMPLE_STRIDE_V2 = 52;

    public static final long DEFAULT_TICK_DURATION_NS = 50_000_000L;
    public static final long DEFAULT_MAX_INTERP_GAP_NS = 100_000_000L;

    /**
     * Hard requirement: uniform capture rate (~8.33ms) within each client tick.
     * On higher-FPS displays, sample by wall-clock gate inside the tick.
     */
    public static final int TARGET_SAMPLE_HZ = 120;

    /**
     * One uplink packet per client tick (≈20 Hz), carrying all samples from that tick.
     * Matches vanilla move-packet cadence and BungeeCord-friendly rates.
     */
    public static final int DEFAULT_MAX_UPLOAD_HZ = 20;

    /**
     * Max samples per uplink packet (≥ samples expected in one 50ms tick at 120 Hz).
     */
    public static final int MAX_SAMPLES_PER_PACKET = 8;

    /** Queue depth ≈ 1s at {@link #TARGET_SAMPLE_HZ}. */
    public static final int MAX_PENDING_SAMPLES = 120;

    /**
     * Playback look-back after tick-relative stamping. Residual client prediction vs
     * server-confirmed world; often 0–1 tick. Auto-calib may override.
     */
    public static final long DEFAULT_PLAYBACK_DELAY_NS = 0L;

    /** pose_sync: u64 sessionElapsedNs + u8 active + u32 recordingTick (big-endian). */
    public static final int SYNC_PAYLOAD_BYTES = 13;

    private GfrMotionFormat() {}
}
