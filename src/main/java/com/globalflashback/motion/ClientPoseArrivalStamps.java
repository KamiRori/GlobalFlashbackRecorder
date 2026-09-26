package com.globalflashback.motion;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player FIFO of Netty-thread {@link System#nanoTime()} stamps for {@code gfr:client_pose}.
 *
 * <p>Netty event-loop offers; server main thread polls when the matching plugin message is
 * processed. Bounded ring avoids unbounded growth if the main thread stalls.
 *
 * <p>Hot path: one synchronized offer/poll per pose packet (≤20 Hz/player) — no boxing, no CAS loops.
 */
public final class ClientPoseArrivalStamps {
    private static final int CAPACITY = 64;

    private final ConcurrentHashMap<UUID, LongRing> rings = new ConcurrentHashMap<>();

    public void offer(UUID playerId, long nanoTime) {
        rings.computeIfAbsent(playerId, ignored -> new LongRing()).offer(nanoTime);
    }

    /**
     * @return arrival {@code nanoTime}, or {@code 0} if none (miss / tap not attached)
     */
    public long poll(UUID playerId) {
        LongRing ring = rings.get(playerId);
        return ring == null ? 0L : ring.poll();
    }

    public void remove(UUID playerId) {
        rings.remove(playerId);
    }

    public void clear() {
        rings.clear();
    }

    private static final class LongRing {
        private final long[] buf = new long[CAPACITY];
        private int head;
        private int size;

        synchronized void offer(long value) {
            if (size == CAPACITY) {
                // Drop oldest — main thread is behind; keep newest arrivals.
                head = (head + 1) & (CAPACITY - 1);
                size--;
            }
            int idx = (head + size) & (CAPACITY - 1);
            buf[idx] = value;
            size++;
        }

        synchronized long poll() {
            if (size == 0) {
                return 0L;
            }
            long v = buf[head];
            head = (head + 1) & (CAPACITY - 1);
            size--;
            return v;
        }
    }
}
