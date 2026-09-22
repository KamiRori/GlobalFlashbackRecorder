package com.globalflashback.api;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Public hook to exclude <strong>any</strong> outbound clientbound packet instance from capture
 * while an active recording's network tap is installed.
 *
 * <p>Works for particles, sounds, level events, explode packets, {@code BundlePacket}s, entity
 * packets, or any other object that will be written through a tapped player connection — the tap
 * checks suppression by <strong>instance identity</strong> before any type-specific recording.
 *
 * <p>Live delivery to the player is unchanged; only Global Flashback Recorder capture is skipped.
 * Marks are no-ops when capture is inactive (empty hot path).
 *
 * <p>Thread-safe across Netty event loops: mark the same object instance that
 * {@code connection.send} / {@code channel.write} will observe.
 */
public final class OutboundPacketCapture {
    private static final AtomicBoolean CAPTURE_ACTIVE = new AtomicBoolean(false);
    /** Identity set: same packet / bundle object that will hit the network pipeline. */
    private static final Set<Object> SUPPRESSED =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private OutboundPacketCapture() {}

    /**
     * @return {@code true} while the recorder's outbound network tap is installed
     */
    public static boolean isCaptureActive() {
        return CAPTURE_ACTIVE.get();
    }

    /**
     * Called by the recorder when the outbound tap starts/stops. Not for normal plugin use.
     */
    public static void setCaptureActive(boolean active) {
        CAPTURE_ACTIVE.set(active);
        if (!active) {
            SUPPRESSED.clear();
        }
    }

    /**
     * Mark {@code packet} so capture ignores it when that instance is written outbound.
     * Accepts any packet or bundle object; no-op when capture is inactive.
     */
    public static void suppress(Object packet) {
        if (packet == null || !CAPTURE_ACTIVE.get()) {
            return;
        }
        SUPPRESSED.add(packet);
    }

    /**
     * Remove a prior {@link #suppress(Object)} mark (safe if absent).
     */
    public static void unsuppress(Object packet) {
        if (packet == null) {
            return;
        }
        SUPPRESSED.remove(packet);
    }

    /**
     * @return whether {@code packet} is currently marked suppressed
     */
    public static boolean isSuppressed(Object packet) {
        return packet != null && SUPPRESSED.contains(packet);
    }

    /**
     * Atomically clears the suppress mark if present.
     *
     * @return {@code true} if the packet was suppressed
     */
    public static boolean consumeSuppressed(Object packet) {
        return packet != null && SUPPRESSED.remove(packet);
    }

    /**
     * Suppress {@code packet}, run {@code send}, then clear the mark on {@code cleanupExecutor}
     * (typically the player's Netty {@code channel.eventLoop()}) so a missed tap cannot leak.
     *
     * <p>{@code packet} may be any clientbound packet or {@code BundlePacket}. When capture is
     * inactive, only {@code send} runs.
     */
    public static void sendSuppressed(Object packet, Runnable send, Executor cleanupExecutor) {
        Objects.requireNonNull(send, "send");
        if (packet == null || !CAPTURE_ACTIVE.get()) {
            send.run();
            return;
        }
        SUPPRESSED.add(packet);
        try {
            send.run();
        } finally {
            if (cleanupExecutor != null) {
                // Double-schedule so cleanup runs after a write already queued by send().
                cleanupExecutor.execute(() -> cleanupExecutor.execute(() -> SUPPRESSED.remove(packet)));
            } else {
                SUPPRESSED.remove(packet);
            }
        }
    }
}
