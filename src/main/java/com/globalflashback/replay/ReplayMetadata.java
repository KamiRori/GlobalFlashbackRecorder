package com.globalflashback.replay;

import java.util.Objects;
import java.util.UUID;

/**
 * Logical replay metadata (maps to Flashback {@code metadata.json} fields at encode time).
 */
public record ReplayMetadata(
        UUID replayId,
        String name,
        String versionString,
        String worldName,
        int dataVersion,
        int protocolVersion,
        int totalTicks
) {
    public ReplayMetadata {
        Objects.requireNonNull(replayId, "replayId");
        Objects.requireNonNull(name, "name");
        if (totalTicks < 0) {
            throw new IllegalArgumentException("totalTicks must be >= 0");
        }
    }

    public static ReplayMetadata createNew(String name) {
        return new ReplayMetadata(UUID.randomUUID(), name, null, null, 0, 0, 0);
    }

    public ReplayMetadata withTotalTicks(int ticks) {
        return new ReplayMetadata(replayId, name, versionString, worldName, dataVersion, protocolVersion, ticks);
    }
}
