package com.globalflashback.delta;

import java.util.List;
import java.util.Objects;
import java.io.Serializable;

/**
 * All state changes that occurred during a single Minecraft server tick.
 * Empty change lists are allowed (tick still advances the timeline).
 */
public record DeltaFrame(int tick, List<StateChange> changes) implements Serializable {
    public DeltaFrame {
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be >= 0");
        }
        Objects.requireNonNull(changes, "changes");
        changes = freezeChanges(changes);
    }

    private static List<StateChange> freezeChanges(List<StateChange> changes) {
        if (changes.isEmpty()) {
            return List.of();
        }
        if (changes.getClass().getName().startsWith("java.util.ImmutableCollections")) {
            return changes;
        }
        return List.copyOf(changes);
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public static DeltaFrame empty(int tick) {
        return new DeltaFrame(tick, List.of());
    }
}
