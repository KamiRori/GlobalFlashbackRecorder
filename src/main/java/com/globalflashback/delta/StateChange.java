package com.globalflashback.delta;

import com.globalflashback.state.ChunkState;
import com.globalflashback.state.DimensionId;
import com.globalflashback.state.EntityState;
import com.globalflashback.state.MetadataBlob;
import com.globalflashback.state.PlayerState;
import com.globalflashback.state.ReplayMath;
import com.globalflashback.state.WorldState;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, change-driven updates bound to a server tick.
 *
 * <p>Capture/Diff produce these; FlashbackEncoder consumes them later.
 * No Bukkit/NMS references.
 */
public sealed interface StateChange permits
        StateChange.PlayerUpsert,
        StateChange.PlayerRemove,
        StateChange.EntitySpawn,
        StateChange.EntityUpdate,
        StateChange.EntityDestroy,
        StateChange.ChunkUpsert,
        StateChange.ChunkUnload,
        StateChange.BlockChange,
        StateChange.BlockEntityChange,
        StateChange.WorldUpsert,
        StateChange.DimensionChange,
        StateChange.EntityAnimate,
        StateChange.EffectPacket {

    record PlayerUpsert(PlayerState player) implements StateChange {
        public PlayerUpsert {
            Objects.requireNonNull(player, "player");
        }
    }

    record PlayerRemove(UUID uuid, int entityId) implements StateChange {
        public PlayerRemove {
            Objects.requireNonNull(uuid, "uuid");
        }
    }

    record EntitySpawn(EntityState entity) implements StateChange {
        public EntitySpawn {
            Objects.requireNonNull(entity, "entity");
        }
    }

    record EntityUpdate(EntityState entity) implements StateChange {
        public EntityUpdate {
            Objects.requireNonNull(entity, "entity");
        }
    }

    record EntityDestroy(int entityId, UUID uuid) implements StateChange {
        public EntityDestroy {
            Objects.requireNonNull(uuid, "uuid");
        }
    }

    record ChunkUpsert(ChunkState chunk) implements StateChange {
        public ChunkUpsert {
            Objects.requireNonNull(chunk, "chunk");
        }
    }

    record ChunkUnload(DimensionId dimension, ReplayMath.ChunkPos position) implements StateChange {
        public ChunkUnload {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(position, "position");
        }
    }

    record BlockChange(
            DimensionId dimension,
            ReplayMath.BlockPos pos,
            String oldBlockId,
            String newBlockId,
            MetadataBlob newBlockPayload
    ) implements StateChange {
        public BlockChange {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(newBlockId, "newBlockId");
            newBlockPayload = newBlockPayload == null ? MetadataBlob.EMPTY : newBlockPayload;
        }
    }

    /**
     * Block-entity update for replay. Payload is a pre-encoded {@code ClientboundBlockEntityDataPacket}.
     *
     * <p>Sources: client-sync {@code getUpdateTag} (signs / spawners / …), or full
     * {@code saveWithoutMetadata} when a player opens / edits a block container (chest inventory).
     * Hopper and other non-player container moves are not recorded.
     */
    record BlockEntityChange(
            DimensionId dimension,
            ReplayMath.BlockPos pos,
            String typeId,
            MetadataBlob nbtPayload
    ) implements StateChange {
        public BlockEntityChange {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(typeId, "typeId");
            nbtPayload = nbtPayload == null ? MetadataBlob.EMPTY : nbtPayload;
        }
    }

    record WorldUpsert(WorldState world) implements StateChange {
        public WorldUpsert {
            Objects.requireNonNull(world, "world");
        }
    }

    record DimensionChange(UUID entityUuid, int entityId, DimensionId from, DimensionId to) implements StateChange {
        public DimensionChange {
            Objects.requireNonNull(entityUuid, "entityUuid");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /**
     * Arm swing / hurt animation etc. Payload is a pre-encoded {@code ClientboundAnimatePacket}.
     */
    record EntityAnimate(int entityId, int action, MetadataBlob packetPayload) implements StateChange {
        public EntityAnimate {
            packetPayload = Objects.requireNonNullElse(packetPayload, MetadataBlob.EMPTY);
        }
    }

    /**
     * Ephemeral effect (particle / sound / level event) as a pre-encoded game packet.
     * Volume is preserved inside sound packets for correct replay attenuation.
     */
    record EffectPacket(MetadataBlob gamePacketPayload) implements StateChange {
        public EffectPacket {
            Objects.requireNonNull(gamePacketPayload, "gamePacketPayload");
        }
    }
}
