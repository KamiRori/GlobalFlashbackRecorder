package com.globalflashback.capture;

import com.globalflashback.delta.StateChange;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe queue of mid-tick side-channel changes (animations, blocks, effects).
 * Drained on the main-thread recording tick into the corresponding {@link com.globalflashback.delta.DeltaFrame}.
 */
public final class RecordingSideChannel {
    public record Pending(int tick, StateChange change) {
        public Pending {
            Objects.requireNonNull(change, "change");
        }
    }

    private final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();

    public void offer(int tick, StateChange change) {
        queue.add(new Pending(tick, Objects.requireNonNull(change, "change")));
    }

    /**
     * Drain all pending entries with {@code tick <= currentTick} (inclusive).
     * Returns an immutable empty list when nothing is ready (no allocation).
     */
    public List<StateChange> drainUpTo(int currentTick) {
        if (queue.isEmpty()) {
            return List.of();
        }
        List<StateChange> out = null;
        List<Pending> deferred = null;
        Pending pending;
        while ((pending = queue.poll()) != null) {
            if (pending.tick() <= currentTick) {
                if (out == null) {
                    out = new ArrayList<>();
                }
                out.add(pending.change());
            } else {
                if (deferred == null) {
                    deferred = new ArrayList<>(2);
                }
                deferred.add(pending);
            }
        }
        if (deferred != null) {
            queue.addAll(deferred);
        }
        return out == null ? List.of() : out;
    }

    public void clear() {
        queue.clear();
    }
}
