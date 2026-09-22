package com.globalflashback.state;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable entity state for Global Replay (includes players as entities when needed).
 *
 * @param spawnData type-specific {@link net.minecraft.network.protocol.game.ClientboundAddEntityPacket}
 *                  data (e.g. fishing bobber owner entity id). Zero when unused.
 */
public record EntityState(
        int entityId,
        UUID uuid,
        String entityType,
        DimensionId dimension,
        ReplayMath.Vec3d position,
        ReplayMath.Rotation rotation,
        ReplayMath.Vec3d velocity,
        boolean onGround,
        Integer vehicleEntityId,
        List<Integer> passengerEntityIds,
        EquipmentState equipment,
        MetadataBlob metadata,
        int spawnData
) {
    public EntityState {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(velocity, "velocity");
        passengerEntityIds = FreezeLists.integers(passengerEntityIds);
        equipment = Objects.requireNonNullElse(equipment, EquipmentState.EMPTY);
        metadata = Objects.requireNonNullElse(metadata, MetadataBlob.EMPTY);
    }
}
