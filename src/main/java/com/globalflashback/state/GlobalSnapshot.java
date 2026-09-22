package com.globalflashback.state;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Full recoverable Global Replay state at a single tick (Initial Snapshot or Keyframe body).
 *
 * <p>One Event → one GlobalSnapshot timeline; all online-relevant players live in the same snapshot.
 *
 * <p>Builder transfers map ownership via {@link Collections#unmodifiableMap} (no entry copy).
 * External constructors still defensively {@link Map#copyOf} mutable inputs.
 */
public record GlobalSnapshot(
        int tick,
        Map<DimensionId, WorldState> worlds,
        Map<ReplayMath.ChunkPosKey, ChunkState> chunks,
        Map<UUID, PlayerState> players,
        Map<Integer, EntityState> entities
) {
    public GlobalSnapshot {
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be >= 0");
        }
        worlds = freezeMap(worlds);
        chunks = freezeMap(chunks);
        players = freezeMap(players);
        entities = freezeMap(entities);
    }

    private static <K, V> Map<K, V> freezeMap(Map<K, V> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        // Already frozen by Builder / prior snapshot reuse path.
        if (map.getClass().getName().startsWith("java.util.ImmutableCollections")
                || map.getClass().getName().equals("java.util.Collections$UnmodifiableMap")) {
            return map;
        }
        return Map.copyOf(map);
    }

    public static Builder builder(int tick) {
        return new Builder(tick);
    }

    public static final class Builder {
        private final int tick;
        private final Map<DimensionId, WorldState> worlds = new LinkedHashMap<>();
        private final Map<ReplayMath.ChunkPosKey, ChunkState> chunks = new LinkedHashMap<>();
        private final Map<UUID, PlayerState> players = new LinkedHashMap<>();
        private final Map<Integer, EntityState> entities = new LinkedHashMap<>();

        private Builder(int tick) {
            this.tick = tick;
        }

        public Builder world(WorldState world) {
            Objects.requireNonNull(world, "world");
            worlds.put(world.dimension(), world);
            return this;
        }

        public Builder chunk(ChunkState chunk) {
            Objects.requireNonNull(chunk, "chunk");
            chunks.put(ReplayMath.ChunkPosKey.of(chunk.dimension(), chunk.position()), chunk);
            return this;
        }

        public Builder player(PlayerState player) {
            Objects.requireNonNull(player, "player");
            players.put(player.uuid(), player);
            return this;
        }

        public Builder entity(EntityState entity) {
            Objects.requireNonNull(entity, "entity");
            entities.put(entity.entityId(), entity);
            return this;
        }

        public Builder players(List<PlayerState> list) {
            for (PlayerState player : list) {
                player(player);
            }
            return this;
        }

        public Builder entities(List<EntityState> list) {
            for (EntityState entity : list) {
                entity(entity);
            }
            return this;
        }

        /**
         * Transfers builder maps into an unmodifiable view (no {@link Map#copyOf} of entries).
         * Do not reuse this builder after {@code build()}.
         */
        public GlobalSnapshot build() {
            return new GlobalSnapshot(
                    tick,
                    worlds.isEmpty() ? Map.of() : Collections.unmodifiableMap(worlds),
                    chunks.isEmpty() ? Map.of() : Collections.unmodifiableMap(chunks),
                    players.isEmpty() ? Map.of() : Collections.unmodifiableMap(players),
                    entities.isEmpty() ? Map.of() : Collections.unmodifiableMap(entities)
            );
        }
    }
}
