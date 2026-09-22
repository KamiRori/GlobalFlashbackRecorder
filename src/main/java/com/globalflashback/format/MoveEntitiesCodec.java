package com.globalflashback.format;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Clean-room encoder for {@code flashback:action/move_entities}.
 *
 * <p>Layout (documented by interoperable server writers / Flashback playback handler):
 * <pre>
 *   varint  levelCount
 *   per level:
 *     string  dimensionId
 *     varint  entityCount
 *     per entity:
 *       varint entityId
 *       double x,y,z
 *       float  yaw, pitch, headYaw
 *       bool   onGround
 * </pre>
 *
 * <p>Flashback refuses vanilla {@code ClientboundMoveEntityPacket}; movement must use this action
 * so the client can interpolate.
 */
public final class MoveEntitiesCodec {
    private MoveEntitiesCodec() {}

    public record Pose(
            int entityId,
            String dimensionId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            float headYaw,
            boolean onGround
    ) {
        public Pose {
            Objects.requireNonNull(dimensionId, "dimensionId");
        }
    }

    public static byte[] encode(List<Pose> poses) {
        if (poses == null || poses.isEmpty()) {
            throw new IllegalArgumentException("poses must not be empty");
        }
        Map<String, List<Pose>> byDimension = new LinkedHashMap<>();
        for (Pose pose : poses) {
            byDimension.computeIfAbsent(pose.dimensionId(), ignored -> new ArrayList<>()).add(pose);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            VarCodec.writeVarInt(out, byDimension.size());
            for (Map.Entry<String, List<Pose>> entry : byDimension.entrySet()) {
                VarCodec.writeString(out, entry.getKey());
                List<Pose> list = entry.getValue();
                VarCodec.writeVarInt(out, list.size());
                for (Pose pose : list) {
                    VarCodec.writeVarInt(out, pose.entityId());
                    out.writeDouble(pose.x());
                    out.writeDouble(pose.y());
                    out.writeDouble(pose.z());
                    out.writeFloat(pose.yaw());
                    out.writeFloat(pose.pitch());
                    out.writeFloat(pose.headYaw());
                    out.writeBoolean(pose.onGround());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return baos.toByteArray();
    }
}
