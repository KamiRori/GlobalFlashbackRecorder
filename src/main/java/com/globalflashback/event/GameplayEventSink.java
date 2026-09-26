package com.globalflashback.event;

/**
 * Main-thread sink for gameplay events during an active recording session.
 */
@FunctionalInterface
public interface GameplayEventSink {
    void accept(GameplayEvent event);
}
