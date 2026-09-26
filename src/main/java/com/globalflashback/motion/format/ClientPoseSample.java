package com.globalflashback.motion.format;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.UUID;

/**
 * One render-frame client pose sample (logical). On-wire / on-disk AoS layout (v3):
 * <pre>
 * u64 timeNs | f64 x y z | f32 yaw pitch headYaw bodyYaw
 * | f32 vehicleYaw vehiclePitch | u16 dim | u8 flags | u8 pad
 * </pre>
 * v2 omits {@code vehicleYaw}/{@code vehiclePitch} (52 bytes).
 *
 * <p>Field contract:
 * <ul>
 *   <li>{@code yaw}/{@code pitch} — view / entity look (120 Hz)</li>
 *   <li>{@code headYaw} — same channel as {@code yaw} at capture</li>
 *   <li>{@code bodyYaw} — vanilla torso sample; TP does not hard-apply</li>
 *   <li>{@code vehicleYaw}/{@code vehiclePitch} — root craft facing when
 *       {@link #FLAG_VEHICLE_FACING} (boats / client-auth MoveVehicle craft)</li>
 * </ul>
 */
public final class ClientPoseSample {
    public static final int FLAG_RIDING = 1;
    /** Sample carries meaningful {@link #vehicleYaw}/{@link #vehiclePitch} (boat / minecart). */
    public static final int FLAG_VEHICLE_FACING = 2;
    /** Rider was {@code getControlledVehicle() != null} at capture (saddled horse, etc.). */
    public static final int FLAG_CONTROLLING_VEHICLE = 4;

    public final long timeNs;
    public final double x;
    public final double y;
    public final double z;
    public final float yaw;
    public final float pitch;
    public final float headYaw;
    public final float bodyYaw;
    public final float vehicleYaw;
    public final float vehiclePitch;
    public final int dimensionIndex;
    public final int flags;

    public ClientPoseSample(
            long timeNs,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            float headYaw,
            float bodyYaw,
            float vehicleYaw,
            float vehiclePitch,
            int dimensionIndex,
            int flags
    ) {
        this.timeNs = timeNs;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.headYaw = headYaw;
        this.bodyYaw = bodyYaw;
        this.vehicleYaw = vehicleYaw;
        this.vehiclePitch = vehiclePitch;
        this.dimensionIndex = dimensionIndex & 0xFFFF;
        this.flags = flags & 0xFF;
    }

    /** Convenience when vehicle facing is absent. */
    public ClientPoseSample(
            long timeNs,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            float headYaw,
            float bodyYaw,
            int dimensionIndex,
            int flags
    ) {
        this(timeNs, x, y, z, yaw, pitch, headYaw, bodyYaw, 0f, 0f, dimensionIndex, flags);
    }

    public boolean hasVehicleFacing() {
        return (flags & FLAG_VEHICLE_FACING) != 0;
    }

    public boolean wasControllingVehicle() {
        return (flags & FLAG_CONTROLLING_VEHICLE) != 0;
    }

    public void write(ByteBuffer buf) {
        buf.putLong(timeNs);
        buf.putDouble(x);
        buf.putDouble(y);
        buf.putDouble(z);
        buf.putFloat(yaw);
        buf.putFloat(pitch);
        buf.putFloat(headYaw);
        buf.putFloat(bodyYaw);
        buf.putFloat(vehicleYaw);
        buf.putFloat(vehiclePitch);
        buf.putShort((short) dimensionIndex);
        buf.put((byte) flags);
        buf.put((byte) 0);
    }

    public static ClientPoseSample read(ByteBuffer buf) {
        return read(buf, GfrMotionFormat.SAMPLE_STRIDE);
    }

    public static ClientPoseSample read(ByteBuffer buf, int stride) {
        long timeNs = buf.getLong();
        double x = buf.getDouble();
        double y = buf.getDouble();
        double z = buf.getDouble();
        float yaw = buf.getFloat();
        float pitch = buf.getFloat();
        float headYaw = buf.getFloat();
        float bodyYaw = buf.getFloat();
        float vehicleYaw = 0f;
        float vehiclePitch = 0f;
        if (stride >= GfrMotionFormat.SAMPLE_STRIDE) {
            vehicleYaw = buf.getFloat();
            vehiclePitch = buf.getFloat();
        }
        int dim = buf.getShort() & 0xFFFF;
        int flags = buf.get() & 0xFF;
        buf.get(); // pad
        return new ClientPoseSample(
                timeNs, x, y, z, yaw, pitch, headYaw, bodyYaw, vehicleYaw, vehiclePitch, dim, flags);
    }

    public static ByteBuffer newLittleEndian(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Uplink: uuid + sample count + samples (always current stride). */
    public static byte[] encodePacket(UUID playerUuid, ClientPoseSample[] samples) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(samples, "samples");
        int n = samples.length;
        ByteBuffer buf = newLittleEndian(16 + 4 + n * GfrMotionFormat.SAMPLE_STRIDE);
        buf.putLong(playerUuid.getMostSignificantBits());
        buf.putLong(playerUuid.getLeastSignificantBits());
        buf.putInt(n);
        for (ClientPoseSample sample : samples) {
            sample.write(buf);
        }
        return buf.array();
    }

    public static DecodedPacket decodePacket(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        UUID uuid = new UUID(buf.getLong(), buf.getLong());
        int n = buf.getInt();
        if (n < 0 || n > 512) {
            throw new IllegalArgumentException("bad sample count: " + n);
        }
        int remaining = buf.remaining();
        if (n == 0) {
            return new DecodedPacket(uuid, new ClientPoseSample[0]);
        }
        if (remaining % n != 0) {
            throw new IllegalArgumentException("sample bytes not divisible by count: " + remaining + "/" + n);
        }
        int stride = remaining / n;
        if (stride != GfrMotionFormat.SAMPLE_STRIDE && stride != GfrMotionFormat.SAMPLE_STRIDE_V2) {
            throw new IllegalArgumentException("unsupported sample stride: " + stride);
        }
        ClientPoseSample[] samples = new ClientPoseSample[n];
        for (int i = 0; i < n; i++) {
            samples[i] = read(buf, stride);
        }
        return new DecodedPacket(uuid, samples);
    }

    public record DecodedPacket(UUID playerUuid, ClientPoseSample[] samples) {}
}
