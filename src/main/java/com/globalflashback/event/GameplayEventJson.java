package com.globalflashback.event;

import com.globalflashback.state.DimensionId;
import com.globalflashback.state.ReplayMath;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Serializes {@link GameplayEvent} list to {@link GfrEventsFormat#ZIP_ENTRY} JSON bytes.
 */
public final class GameplayEventJson {
    private GameplayEventJson() {}

    public static byte[] toBytes(List<GameplayEvent> events) {
        return toBytes(events, 0);
    }

    /**
     * @param recordingStartTick if {@code > 0} and events still look absolute (max tick above
     *     replay length), subtract this origin so ZIP ticks match Flashback {@code total_ticks}.
     */
    public static byte[] toBytes(List<GameplayEvent> events, int recordingStartTick) {
        Objects.requireNonNull(events, "events");
        List<GameplayEvent> out = rebaseIfAbsolute(events, recordingStartTick);
        StringBuilder sb = new StringBuilder(256 + out.size() * 160);
        sb.append('{');
        sb.append("\"format\":\"").append(GfrEventsFormat.FORMAT_ID).append("\",");
        sb.append("\"version\":").append(GfrEventsFormat.VERSION).append(',');
        sb.append("\"tickAxis\":\"").append(GfrEventsFormat.TICK_AXIS_RECORDING_RELATIVE).append("\",");
        sb.append("\"events\":[");
        boolean first = true;
        for (GameplayEvent e : out) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            appendEvent(sb, e);
        }
        sb.append("]}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Events must use recording-relative ticks (0 = first recorded tick). If a list still carries
     * absolute {@link org.bukkit.Bukkit#getCurrentTick()} values, rebase using {@code startTick}.
     */
    static List<GameplayEvent> rebaseIfAbsolute(List<GameplayEvent> events, int recordingStartTick) {
        if (events.isEmpty() || recordingStartTick <= 0) {
            return events;
        }
        int max = 0;
        for (GameplayEvent e : events) {
            max = Math.max(max, e.tick());
        }
        // Already relative (or empty span): leave unchanged.
        if (max < recordingStartTick) {
            return events;
        }
        List<GameplayEvent> out = new ArrayList<>(events.size());
        for (GameplayEvent e : events) {
            out.add(new GameplayEvent(
                    Math.max(0, e.tick() - recordingStartTick),
                    e.type(),
                    e.actorUuid(),
                    e.targetUuid(),
                    e.dimension(),
                    e.position(),
                    e.data()
            ));
        }
        return out;
    }

    private static void appendEvent(StringBuilder sb, GameplayEvent e) {
        sb.append('{');
        sb.append("\"tick\":").append(e.tick()).append(',');
        sb.append("\"type\":\"").append(e.type().name()).append("\",");
        appendUuid(sb, "actorUuid", e.actorUuid());
        sb.append(',');
        appendUuid(sb, "targetUuid", e.targetUuid());
        sb.append(',');
        DimensionId dim = e.dimension();
        if (dim != null) {
            sb.append("\"dimension\":\"").append(escape(dim.namespacedKey())).append("\",");
        } else {
            sb.append("\"dimension\":null,");
        }
        ReplayMath.Vec3d pos = e.position();
        if (pos != null) {
            sb.append("\"x\":").append(num(pos.x())).append(',');
            sb.append("\"y\":").append(num(pos.y())).append(',');
            sb.append("\"z\":").append(num(pos.z())).append(',');
        } else {
            sb.append("\"x\":null,\"y\":null,\"z\":null,");
        }
        sb.append("\"data\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : e.data().entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(entry.getKey())).append("\":\"")
                    .append(escape(entry.getValue())).append('"');
        }
        sb.append("}}");
    }

    private static void appendUuid(StringBuilder sb, String key, UUID uuid) {
        sb.append('"').append(key).append("\":");
        if (uuid == null) {
            sb.append("null");
        } else {
            sb.append('"').append(uuid).append('"');
        }
    }

    private static String num(double v) {
        if (!Double.isFinite(v)) {
            return "0";
        }
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
