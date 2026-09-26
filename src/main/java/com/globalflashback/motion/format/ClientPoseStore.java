package com.globalflashback.motion.format;

import com.globalflashback.motion.ClientPoseTimingStats;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory store for one recording's client poses. Thread-safe append per player.
 */
public final class ClientPoseStore {
    private final Object lock = new Object();
    private final UUID recordingId;
    private final long sessionStartNs;
    private final List<String> dimensions = new ArrayList<>();
    private final Map<UUID, PlayerTrack> tracks = new LinkedHashMap<>();
    private volatile ClientPoseTimingStats.Snapshot timingStats;

    public ClientPoseStore(UUID recordingId, long sessionStartNs) {
        this.recordingId = recordingId;
        this.sessionStartNs = sessionStartNs;
    }

    public UUID recordingId() {
        return recordingId;
    }

    public long sessionStartNs() {
        return sessionStartNs;
    }

    public void setTimingStats(ClientPoseTimingStats.Snapshot timingStats) {
        this.timingStats = timingStats;
    }

    public int dimensionIndex(String dimensionId) {
        synchronized (lock) {
            int idx = dimensions.indexOf(dimensionId);
            if (idx >= 0) {
                return idx;
            }
            dimensions.add(dimensionId);
            return dimensions.size() - 1;
        }
    }

    public void append(UUID playerUuid, ClientPoseSample sample) {
        synchronized (lock) {
            tracks.computeIfAbsent(playerUuid, ignored -> new PlayerTrack()).add(sample);
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            for (PlayerTrack track : tracks.values()) {
                if (track.size() > 0) {
                    return false;
                }
            }
            return true;
        }
    }

    public int totalSamples() {
        synchronized (lock) {
            int n = 0;
            for (PlayerTrack track : tracks.values()) {
                n += track.size();
            }
            return n;
        }
    }

    /**
     * Build ZIP payload pair. Returns null if empty.
     */
    public WrittenMotion write() {
        synchronized (lock) {
            if (isEmptyLocked()) {
                return null;
            }
            List<PlayerEntry> entries = new ArrayList<>();
            int offset = 0;
            List<byte[]> chunks = new ArrayList<>();
            for (Map.Entry<UUID, PlayerTrack> e : tracks.entrySet()) {
                PlayerTrack track = e.getValue();
                if (track.size() == 0) {
                    continue;
                }
                byte[] blob = track.toBytes();
                entries.add(new PlayerEntry(e.getKey().toString(), track.size(), offset, blob.length));
                chunks.add(blob);
                offset += blob.length;
            }
            if (entries.isEmpty()) {
                return null;
            }
            byte[] bin = new byte[offset];
            int at = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, bin, at, chunk.length);
                at += chunk.length;
            }
            String meta = buildMetaJson(entries);
            return new WrittenMotion(meta.getBytes(StandardCharsets.UTF_8), bin);
        }
    }

    private boolean isEmptyLocked() {
        for (PlayerTrack track : tracks.values()) {
            if (track.size() > 0) {
                return false;
            }
        }
        return true;
    }

    private String buildMetaJson(List<PlayerEntry> entries) {
        ClientPoseTimingStats.Snapshot timing = timingStats;
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"format\":\"").append(GfrMotionFormat.FORMAT_ID).append("\",");
        sb.append("\"version\":").append(GfrMotionFormat.VERSION).append(',');
        sb.append("\"sampleStride\":").append(GfrMotionFormat.SAMPLE_STRIDE).append(',');
        sb.append("\"recordingId\":\"").append(recordingId).append("\",");
        sb.append("\"timingModel\":\"").append(GfrMotionFormat.TIMING_MODEL_TICK_RELATIVE_V2).append("\",");
        sb.append("\"tickDurationNs\":").append(GfrMotionFormat.DEFAULT_TICK_DURATION_NS).append(',');
        sb.append("\"maxInterpGapNs\":").append(GfrMotionFormat.DEFAULT_MAX_INTERP_GAP_NS).append(',');
        sb.append("\"playbackDelayNs\":").append(GfrMotionFormat.DEFAULT_PLAYBACK_DELAY_NS).append(',');
        if (timing != null) {
            sb.append("\"queueDelayEmaNs\":").append(timing.queueDelayEmaNs()).append(',');
            sb.append("\"queueDelayMaxNs\":").append(timing.queueDelayMaxNs()).append(',');
            sb.append("\"posePackets\":").append(timing.packetCount()).append(',');
            sb.append("\"arrivalHits\":").append(timing.arrivalHits()).append(',');
            sb.append("\"arrivalMisses\":").append(timing.arrivalMisses()).append(',');
        }
        sb.append("\"dimensions\":[");
        for (int i = 0; i < dimensions.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(dimensions.get(i))).append('"');
        }
        sb.append("],\"players\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            PlayerEntry p = entries.get(i);
            sb.append("{\"uuid\":\"").append(p.uuid).append("\",");
            sb.append("\"sampleCount\":").append(p.sampleCount).append(',');
            sb.append("\"byteOffset\":").append(p.byteOffset).append(',');
            sb.append("\"byteLength\":").append(p.byteLength).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record WrittenMotion(byte[] metaJson, byte[] bin) {}

    private record PlayerEntry(String uuid, int sampleCount, int byteOffset, int byteLength) {}

    private static final class PlayerTrack {
        private final List<ClientPoseSample> samples = new ArrayList<>(256);

        void add(ClientPoseSample sample) {
            if (!samples.isEmpty()) {
                ClientPoseSample last = samples.get(samples.size() - 1);
                if (sample.timeNs < last.timeNs) {
                    return; // drop out-of-order
                }
            }
            samples.add(sample);
        }

        int size() {
            return samples.size();
        }

        byte[] toBytes() {
            ByteBuffer buf = ClientPoseSample.newLittleEndian(samples.size() * GfrMotionFormat.SAMPLE_STRIDE);
            for (ClientPoseSample sample : samples) {
                sample.write(buf);
            }
            return buf.array();
        }
    }
}
