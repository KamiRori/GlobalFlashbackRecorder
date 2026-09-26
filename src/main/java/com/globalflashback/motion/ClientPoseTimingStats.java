package com.globalflashback.motion;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free timing diagnostics for Client Pose uplink.
 *
 * <p>{@code queueDelayNs = processNanoTime - arrivalNanoTime} (clamped). EMA uses shift-4 (≈1/16).
 */
public final class ClientPoseTimingStats {
    /** Ignore absurd gaps (clock weirdness / missed stamps). */
    public static final long MAX_QUEUE_DELAY_NS = 250_000_000L;
    private static final int EMA_SHIFT = 4;

    private final AtomicLong packetCount = new AtomicLong();
    private final AtomicLong arrivalHits = new AtomicLong();
    private final AtomicLong arrivalMisses = new AtomicLong();
    private final AtomicLong queueDelayEmaNs = new AtomicLong();
    private final AtomicLong queueDelayMaxNs = new AtomicLong();

    public void reset() {
        packetCount.set(0L);
        arrivalHits.set(0L);
        arrivalMisses.set(0L);
        queueDelayEmaNs.set(0L);
        queueDelayMaxNs.set(0L);
    }

    public void record(long queueDelayNs, boolean arrivalHit) {
        packetCount.incrementAndGet();
        if (arrivalHit) {
            arrivalHits.incrementAndGet();
        } else {
            arrivalMisses.incrementAndGet();
            return;
        }
        long delay = queueDelayNs;
        if (delay < 0L) {
            delay = 0L;
        } else if (delay > MAX_QUEUE_DELAY_NS) {
            delay = MAX_QUEUE_DELAY_NS;
        }
        updateMax(delay);
        updateEma(delay);
    }

    private void updateMax(long delay) {
        long prev;
        do {
            prev = queueDelayMaxNs.get();
            if (delay <= prev) {
                return;
            }
        } while (!queueDelayMaxNs.compareAndSet(prev, delay));
    }

    private void updateEma(long delay) {
        long prev;
        long next;
        do {
            prev = queueDelayEmaNs.get();
            if (prev == 0L) {
                next = delay;
            } else {
                next = prev + ((delay - prev) >> EMA_SHIFT);
            }
        } while (!queueDelayEmaNs.compareAndSet(prev, next));
    }

    public long packetCount() {
        return packetCount.get();
    }

    public long arrivalHits() {
        return arrivalHits.get();
    }

    public long arrivalMisses() {
        return arrivalMisses.get();
    }

    public long queueDelayEmaNs() {
        return queueDelayEmaNs.get();
    }

    public long queueDelayMaxNs() {
        return queueDelayMaxNs.get();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                packetCount.get(),
                arrivalHits.get(),
                arrivalMisses.get(),
                queueDelayEmaNs.get(),
                queueDelayMaxNs.get()
        );
    }

    public record Snapshot(
            long packetCount,
            long arrivalHits,
            long arrivalMisses,
            long queueDelayEmaNs,
            long queueDelayMaxNs
    ) {}
}
