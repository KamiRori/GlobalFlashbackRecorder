package com.globalflashback.delta;

import com.globalflashback.state.ChunkState;
import com.globalflashback.state.EntityState;
import com.globalflashback.state.GlobalSnapshot;
import com.globalflashback.state.PlayerState;
import com.globalflashback.state.ReplayMath;
import com.globalflashback.state.WorldState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Change-driven comparison of two {@link GlobalSnapshot}s into {@link StateChange}s.
 *
 * <p>Pure Java; no Bukkit/NMS. Must be fed immutable snapshots captured on the main thread.
 */
public final class SnapshotDiffer {
    private SnapshotDiffer() {}

    public static DeltaFrame diffFrame(GlobalSnapshot previous, GlobalSnapshot current) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        List<StateChange> changes = diff(previous, current);
        if (changes.isEmpty()) {
            return DeltaFrame.empty(current.tick());
        }
        return new DeltaFrame(current.tick(), changes);
    }

    public static List<StateChange> diff(GlobalSnapshot previous, GlobalSnapshot current) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");

        List<StateChange> changes = null;
        changes = diffWorlds(previous.worlds(), current.worlds(), changes);
        changes = diffChunks(previous.chunks(), current.chunks(), changes);
        changes = diffPlayers(previous.players(), current.players(), changes);
        changes = diffEntities(previous.entities(), current.entities(), changes);
        return changes == null ? List.of() : changes;
    }

    private static List<StateChange> ensure(List<StateChange> out) {
        return out == null ? new ArrayList<>() : out;
    }

    private static List<StateChange> diffWorlds(
            Map<?, WorldState> previous,
            Map<?, WorldState> current,
            List<StateChange> out
    ) {
        for (WorldState world : current.values()) {
            WorldState before = previous.get(world.dimension());
            if (before == null || before != world && !before.equals(world)) {
                out = ensure(out);
                out.add(new StateChange.WorldUpsert(world));
            }
        }
        return out;
    }

    private static List<StateChange> diffChunks(
            Map<ReplayMath.ChunkPosKey, ChunkState> previous,
            Map<ReplayMath.ChunkPosKey, ChunkState> current,
            List<StateChange> out
    ) {
        for (Map.Entry<ReplayMath.ChunkPosKey, ChunkState> entry : current.entrySet()) {
            ChunkState before = previous.get(entry.getKey());
            ChunkState after = entry.getValue();
            if (before == null || before != after && !before.equals(after)) {
                out = ensure(out);
                out.add(new StateChange.ChunkUpsert(after));
                if (before != null) {
                    out = diffBlockEntities(before, after, out);
                }
            }
        }
        for (Map.Entry<ReplayMath.ChunkPosKey, ChunkState> entry : previous.entrySet()) {
            if (!current.containsKey(entry.getKey())) {
                ChunkState gone = entry.getValue();
                out = ensure(out);
                out.add(new StateChange.ChunkUnload(gone.dimension(), gone.position()));
            }
        }
        return out;
    }

    /**
     * Emits {@link StateChange.BlockEntityChange} for client-sync BE updates (signs, spawners, …).
     * Does not invent container-inventory payloads — those are never stored in {@link ChunkState.BlockEntityState}.
     */
    private static List<StateChange> diffBlockEntities(
            ChunkState before,
            ChunkState after,
            List<StateChange> out
    ) {
        Map<ReplayMath.BlockPos, ChunkState.BlockEntityState> previousByPos = new HashMap<>();
        for (ChunkState.BlockEntityState be : before.blockEntities()) {
            previousByPos.put(be.pos(), be);
        }
        for (ChunkState.BlockEntityState be : after.blockEntities()) {
            ChunkState.BlockEntityState prev = previousByPos.remove(be.pos());
            if (prev == null || !prev.equals(be)) {
                if (be.nbtPayload().isEmpty()) {
                    continue;
                }
                out = ensure(out);
                out.add(new StateChange.BlockEntityChange(
                        after.dimension(), be.pos(), be.typeId(), be.nbtPayload()));
            }
        }
        return out;
    }

    private static List<StateChange> diffPlayers(
            Map<UUID, PlayerState> previous,
            Map<UUID, PlayerState> current,
            List<StateChange> out
    ) {
        for (Map.Entry<UUID, PlayerState> entry : current.entrySet()) {
            PlayerState after = entry.getValue();
            PlayerState before = previous.get(entry.getKey());
            if (before == null) {
                out = ensure(out);
                out.add(new StateChange.PlayerUpsert(after));
                continue;
            }
            if (before == after) {
                continue;
            }
            if (!before.dimension().equals(after.dimension())) {
                out = ensure(out);
                out.add(new StateChange.DimensionChange(
                        after.uuid(), after.entityId(), before.dimension(), after.dimension()));
            }
            if (!before.equals(after)) {
                out = ensure(out);
                out.add(new StateChange.PlayerUpsert(after));
            }
        }
        for (Map.Entry<UUID, PlayerState> entry : previous.entrySet()) {
            if (!current.containsKey(entry.getKey())) {
                PlayerState gone = entry.getValue();
                out = ensure(out);
                out.add(new StateChange.PlayerRemove(gone.uuid(), gone.entityId()));
            }
        }
        return out;
    }

    private static List<StateChange> diffEntities(
            Map<Integer, EntityState> previous,
            Map<Integer, EntityState> current,
            List<StateChange> out
    ) {
        for (Map.Entry<Integer, EntityState> entry : current.entrySet()) {
            EntityState after = entry.getValue();
            // Players are authoritative via PlayerState / PlayerUpsert; avoid duplicate EntityUpdate.
            if ("minecraft:player".equals(after.entityType())) {
                continue;
            }
            EntityState before = previous.get(entry.getKey());
            if (before == null) {
                out = ensure(out);
                out.add(new StateChange.EntitySpawn(after));
                continue;
            }
            if (before == after) {
                continue;
            }
            if (!before.dimension().equals(after.dimension())) {
                out = ensure(out);
                out.add(new StateChange.DimensionChange(
                        after.uuid(), after.entityId(), before.dimension(), after.dimension()));
            }
            if (!before.equals(after)) {
                out = ensure(out);
                out.add(new StateChange.EntityUpdate(after));
            }
        }
        for (Map.Entry<Integer, EntityState> entry : previous.entrySet()) {
            if ("minecraft:player".equals(entry.getValue().entityType())) {
                continue;
            }
            if (!current.containsKey(entry.getKey())) {
                EntityState gone = entry.getValue();
                out = ensure(out);
                out.add(new StateChange.EntityDestroy(gone.entityId(), gone.uuid()));
            }
        }
        return out;
    }
}
