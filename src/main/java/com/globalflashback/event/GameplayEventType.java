package com.globalflashback.event;

/**
 * High-level gameplay event kinds for Event Timeline ({@code gfr/events.json}).
 *
 * <p>There is intentionally <b>no</b> {@code ITEM_TRANSFER}: gift/share cannot be proven
 * server-side. Record {@link #ITEM_DROP}/{@link #ITEM_PICKUP} only; Director may infer.
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
