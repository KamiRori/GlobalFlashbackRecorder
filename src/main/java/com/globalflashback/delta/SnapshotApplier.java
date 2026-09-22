package com.globalflashback.delta;

import com.globalflashback.state.ChunkState;
import com.globalflashback.state.DimensionId;
import com.globalflashback.state.EntityState;
import com.globalflashback.state.GlobalSnapshot;
import com.globalflashback.state.MetadataBlob;
import com.globalflashback.state.PlayerState;
import com.globalflashback.state.ReplayMath;
import com.globalflashback.state.WorldState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Applies {@link StateChange}s onto a base {@link GlobalSnapshot} (seek / reconstruct).
 *
 * <p>Pure Java. Used to validate that Keyframe + Delta can restore a later tick.
 */
public final class SnapshotApplier {
    private SnapshotApplier() {}

    public static GlobalSnapshot apply(GlobalSnapshot base, List<StateChange> changes, int tick) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(changes, "changes");
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be >= 0");
        }

        Map<DimensionId, WorldState> worlds = new LinkedHashMap<>(base.worlds());
        Map<ReplayMath.ChunkPosKey, ChunkState> chunks = new LinkedHashMap<>(base.chunks());
        Map<UUID, PlayerState> players = new LinkedHashMap<>(base.players());
        Map<Integer, EntityState> entities = new LinkedHashMap<>(base.entities());

        for (StateChange change : changes) {
            applyOne(change, worlds, chunks, players, entities);
        }

        return new GlobalSnapshot(tick, worlds, chunks, players, entities);
    }

    public static GlobalSnapshot applyFrame(GlobalSnapshot base, DeltaFrame frame) {
        Objects.requireNonNull(frame, "frame");
        return apply(base, frame.changes(), frame.tick());
    }

    /**
     * Reconstruct state at {@code targetTick} using the nearest keyframe (or initial) then deltas.
     *
     * @param keyframes periodic keyframes only (initial is separate)
     * @param deltas    tick deltas; empty frames may be omitted
     */
    public static GlobalSnapshot seek(
            GlobalSnapshot initial,
            List<Keyframe> keyframes,
            List<DeltaFrame> deltas,
            int targetTick
    ) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(keyframes, "keyframes");
        Objects.requireNonNull(deltas, "deltas");
        if (targetTick < initial.tick()) {
            throw new IllegalArgumentException(
                    "targetTick (" + targetTick + ") < initial.tick (" + initial.tick() + ")");
        }

        GlobalSnapshot base = initial;
        int fromTick = initial.tick();
        for (Keyframe keyframe : keyframes) {
            if (keyframe.tick() <= targetTick && keyframe.tick() >= fromTick) {
                base = keyframe.snapshot();
                fromTick = keyframe.tick();
            }
        }

        GlobalSnapshot current = base;
        for (DeltaFrame frame : deltas) {
            if (frame.tick() <= fromTick) {
                continue;
            }
            if (frame.tick() > targetTick) {
                break;
            }
            current = applyFrame(current, frame);
        }
        if (current.tick() != targetTick) {
            // No delta exactly at targetTick (empty ticks omitted): retarget tick only.
            current = new GlobalSnapshot(targetTick, current.worlds(), current.chunks(),
                    current.players(), current.entities());
        }
        return current;
    }

    private static void applyOne(
            StateChange change,
            Map<DimensionId, WorldState> worlds,
            Map<ReplayMath.ChunkPosKey, ChunkState> chunks,
            Map<UUID, PlayerState> players,
            Map<Integer, EntityState> entities
    ) {
        switch (change) {
            case StateChange.WorldUpsert(WorldState world) -> worlds.put(world.dimension(), world);
            case StateChange.ChunkUpsert(ChunkState chunk) ->
                    chunks.put(ReplayMath.ChunkPosKey.of(chunk.dimension(), chunk.position()), chunk);
            case StateChange.ChunkUnload(DimensionId dimension, ReplayMath.ChunkPos position) ->
                    chunks.remove(ReplayMath.ChunkPosKey.of(dimension, position));
            case StateChange.PlayerUpsert(PlayerState player) -> {
                players.put(player.uuid(), player);
                entities.put(player.entityId(), toEntity(player));
            }
            case StateChange.PlayerRemove(UUID uuid, int entityId) -> {
                players.remove(uuid);
                entities.remove(entityId);
            }
            case StateChange.EntitySpawn(EntityState entity) -> entities.put(entity.entityId(), entity);
            case StateChange.EntityUpdate(EntityState entity) -> entities.put(entity.entityId(), entity);
            case StateChange.EntityDestroy(int entityId, UUID uuid) -> {
                entities.remove(entityId);
                players.remove(uuid);
            }
            case StateChange.DimensionChange ignored -> {
                // Dimension is carried by the following Upsert/Update in the same frame.
            }
            case StateChange.BlockChange ignored -> {
                // Applied at encode time as BlockUpdate packets; snapshot chunk bytes refresh on keyframe.
            }
            case StateChange.BlockEntityChange(
                    DimensionId dimension,
                    ReplayMath.BlockPos pos,
                    String typeId,
                    MetadataBlob nbtPayload
            ) -> {
                ReplayMath.ChunkPosKey key = ReplayMath.ChunkPosKey.of(
                        dimension, new ReplayMath.ChunkPos(pos.x() >> 4, pos.z() >> 4));
                ChunkState chunk = chunks.get(key);
                if (chunk == null) {
                    return;
                }
                List<ChunkState.BlockEntityState> next = new ArrayList<>(chunk.blockEntities().size() + 1);
                boolean replaced = false;
                for (ChunkState.BlockEntityState be : chunk.blockEntities()) {
                    if (be.pos().equals(pos)) {
                        next.add(new ChunkState.BlockEntityState(pos, typeId, nbtPayload));
                        replaced = true;
                    } else {
                        next.add(be);
                    }
                }
                if (!replaced) {
                    next.add(new ChunkState.BlockEntityState(pos, typeId, nbtPayload));
                }
                chunks.put(key, chunk.withBlockEntities(next, chunk.contentFingerprint(), chunk.cheapFingerprint()));
            }
            case StateChange.EntityAnimate ignored -> {
            }
            case StateChange.EffectPacket ignored -> {
            }
        }
    }

    private static EntityState toEntity(PlayerState player) {
        return new EntityState(
                player.entityId(),
                player.uuid(),
                "minecraft:player",
                player.dimension(),
                player.position(),
                player.rotation(),
                player.velocity(),
                player.onGround(),
                player.vehicleEntityId(),
                player.passengerEntityIds(),
                player.equipment(),
                player.metadata(),
                0
        );
    }
}
