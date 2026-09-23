package com.globalflashback.state;

import java.util.Objects;
import java.io.Serializable;

/**
 * Immutable value types for Global Replay server-authoritative state.
 *
 * <p>These types must not hold Bukkit/NMS object references so they can cross
 * the main-thread → async writer boundary safely.
 */
public final class ReplayMath {
    private ReplayMath() {}

    public record Vec3d(double x, double y, double z) implements Serializable {
        public static final Vec3d ZERO = new Vec3d(0, 0, 0);

        public boolean matches(double ox, double oy, double oz) {
            return Double.compare(x, ox) == 0
                    && Double.compare(y, oy) == 0
                    && Double.compare(z, oz) == 0;
        }
    }

    /** Pitch (xRot) and yaw (yRot), degrees. */
    public record Rotation(float pitch, float yaw, float headYaw) implements Serializable {
        public boolean matches(float oPitch, float oYaw, float oHeadYaw) {
            return Float.compare(pitch, oPitch) == 0
                    && Float.compare(yaw, oYaw) == 0
                    && Float.compare(headYaw, oHeadYaw) == 0;
        }
    }

    public record BlockPos(int x, int y, int z) implements Serializable {}

    public record ChunkPos(int x, int z) implements Serializable {
        public long pack() {
            return ((long) x & 0xffffffffL) | (((long) z) << 32);
        }
    }

    /** Dimension + chunk coordinates for map keys. */
    public record ChunkPosKey(DimensionId dimension, ChunkPos position) implements Serializable {
        public ChunkPosKey {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(position, "position");
        }

        public static ChunkPosKey of(DimensionId dimension, ChunkPos position) {
            return new ChunkPosKey(dimension, position);
        }
    }
}
