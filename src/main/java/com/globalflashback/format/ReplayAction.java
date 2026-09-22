package com.globalflashback.format;

/**
 * One Flashback action: registry identifier + opaque payload.
 */
public record ReplayAction(String identifier, byte[] payload) {
    public static final String NEXT_TICK = "flashback:action/next_tick";
    public static final String GAME_PACKET = "flashback:action/game_packet";
    public static final String CONFIG_PACKET = "flashback:action/configuration_packet";
    public static final String CREATE_LOCAL_PLAYER = "flashback:action/create_local_player";
    /** Absolute entity positions; Flashback client interpolates these (not vanilla MoveEntity). */
    public static final String MOVE_ENTITIES = "flashback:action/move_entities";

    public static ReplayAction nextTick() {
        return new ReplayAction(NEXT_TICK, new byte[0]);
    }

    public static ReplayAction gamePacket(byte[] payload) {
        return new ReplayAction(GAME_PACKET, payload);
    }

    public static ReplayAction configPacket(byte[] payload) {
        return new ReplayAction(CONFIG_PACKET, payload);
    }

    public static ReplayAction createLocalPlayer(byte[] payload) {
        return new ReplayAction(CREATE_LOCAL_PLAYER, payload);
    }

    public static ReplayAction moveEntities(byte[] payload) {
        return new ReplayAction(MOVE_ENTITIES, payload);
    }
}
