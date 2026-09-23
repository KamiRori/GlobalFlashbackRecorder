package com.globalflashback.nms;

import com.globalflashback.state.MetadataBlob;

/**
 * Version-isolated Netty outbound tap that mirrors selected clientbound packets into the
 * recording side-channel. Main-thread start/stop; tap handlers run on Netty threads.
 */
public interface EffectOutboundTap {
    void start();

    void stop();

    /**
     * Offer a main-thread–synthesized effect while claiming its payload key for this tick so the
     * Netty tap does not also record the matching vanilla broadcast (block break LevelEvent 2001,
     * place sound, etc.). Returns {@code false} if an identical payload was already claimed.
     */
    boolean offerClaimedEffect(int tick, MetadataBlob blob);
}
