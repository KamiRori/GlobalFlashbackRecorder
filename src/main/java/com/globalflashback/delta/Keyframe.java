package com.globalflashback.delta;

import com.globalflashback.state.GlobalSnapshot;

import java.util.Objects;

/**
 * Periodic full-state restore point (SPEC Keyframe).
 *
 * <p>Maps to Flashback replay-chunk snapshot + {@code forcePlaySnapshot} at encode time.
 * Not an editor camera keyframe.
 */
public record Keyframe(int tick, GlobalSnapshot snapshot) {
    public Keyframe {
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be >= 0");
        }
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.tick() != tick) {
            throw new IllegalArgumentException(
                    "Keyframe tick (" + tick + ") must match snapshot.tick (" + snapshot.tick() + ")");
        }
    }
}
