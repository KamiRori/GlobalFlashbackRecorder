package com.globalflashback.replay;

import com.globalflashback.delta.DeltaFrame;
import com.globalflashback.delta.Keyframe;
import com.globalflashback.event.GameplayEvent;
import com.globalflashback.state.GlobalSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-memory buffer between main-thread Capture/Diff and async Encoder.
 *
 * <p>Accepts already-immutable frames only. Does not touch Bukkit objects.
 */
public final class ReplayBuffer {
    private final ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();
    private volatile GlobalSnapshot initialSnapshot;
    private volatile boolean sealed;

    public synchronized void setInitialSnapshot(GlobalSnapshot snapshot) {
        ensureOpen();
        this.initialSnapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public GlobalSnapshot initialSnapshot() {
        return initialSnapshot;
    }

    public void appendDelta(DeltaFrame frame) {
        ensureOpen();
        queue.add(Objects.requireNonNull(frame, "frame"));
    }

    public void appendKeyframe(Keyframe keyframe) {
        ensureOpen();
        queue.add(Objects.requireNonNull(keyframe, "keyframe"));
    }

    public void appendEvent(GameplayEvent event) {
        ensureOpen();
        queue.add(Objects.requireNonNull(event, "event"));
    }

    /**
     * Drain pending items for an async writer. Order is preserved FIFO.
     */
    public List<Object> drain() {
        if (queue.isEmpty()) {
            return List.of();
        }
        List<Object> out = new ArrayList<>();
        Object item;
        while ((item = queue.poll()) != null) {
            out.add(item);
        }
        return Collections.unmodifiableList(out);
    }

    public synchronized void seal() {
        sealed = true;
    }

    public boolean isSealed() {
        return sealed;
    }

    public boolean hasInitialSnapshot() {
        return initialSnapshot != null;
    }

    private void ensureOpen() {
        if (sealed) {
            throw new IllegalStateException("ReplayBuffer is sealed");
        }
    }
}
