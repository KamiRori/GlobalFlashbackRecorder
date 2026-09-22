package com.globalflashback.event;

import com.globalflashback.state.DimensionId;
import com.globalflashback.state.ReplayMath;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Gameplay / cinematic event on the Global Replay timeline (not required for state restore).
 * Consumed later by DirectorAPI / Auto Director.
 */
public record GameplayEvent(
        int tick,
        GameplayEventType type,
        UUID actorUuid,
        UUID targetUuid,
        DimensionId dimension,
        ReplayMath.Vec3d position,
        Map<String, String> data
) {
    public GameplayEvent {
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be >= 0");
        }
        Objects.requireNonNull(type, "type");
        data = Map.copyOf(data == null ? Map.of() : data);
    }
}
