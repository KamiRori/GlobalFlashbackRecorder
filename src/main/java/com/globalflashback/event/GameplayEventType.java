package com.globalflashback.event;

/**
 * High-level gameplay event kinds for Event Timeline (Phase 7 will expand usage).
 */
public enum GameplayEventType {
    PLAYER_JOIN,
    PLAYER_QUIT,
    DAMAGE,
    DEATH,
    PLAYER_KILL,
    ITEM_PICKUP,
    ITEM_DROP,
    BLOCK_BREAK,
    BLOCK_PLACE,
    PROJECTILE_LAUNCH,
    PROJECTILE_HIT,
    EXPLOSION,
    CONTAINER_INTERACTION,
    DIMENSION_CHANGE,
    OBJECTIVE,
    CUSTOM
}
