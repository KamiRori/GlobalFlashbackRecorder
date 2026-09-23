package com.globalflashback.api;

import com.globalflashback.nms.EffectPacketEncoder;
import com.globalflashback.nms.NmsPlatforms;
import com.globalflashback.state.MetadataBlob;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;

import java.util.Objects;

/**
 * Ingress for third-party plugins to inject clientbound game packets into an active Global
 * Flashback recording (side-channel → EffectPacket → Flashback game_packet) <em>without</em>
 * sending those packets to live players.
 *
 * <p>Typical pattern with {@link OutboundPacketCapture}:
 * <ul>
 *   <li>Live players: distance-culled / personalized packets via {@code connection.send}, marked
 *       suppressed so the outbound tap does not double-record them.</li>
 *   <li>Replay: a dense or camera-independent packet (often a {@code BundlePacket}) offered here
 *       once per tick.</li>
 * </ul>
 *
 * <p>The recorder binds/unbinds the sink on record start/stop. All methods are no-ops when unbound.
 */
public final class ReplayEffectIngress {
    @FunctionalInterface
    public interface Sink {
        void offer(int tick, byte[] clientboundPayload);
    }

    private static final EffectPacketEncoder ENCODER = NmsPlatforms.create().effects();
    private static volatile Sink sink;

    private ReplayEffectIngress() {}

    /** Called by the recorder when a session starts. Not for normal plugin use. */
    public static void bind(Sink next) {
        sink = Objects.requireNonNull(next, "sink");
    }

    /** Called by the recorder when a session ends. */
    public static void unbind() {
        sink = null;
    }

    /** @return {@code true} while a recording sink is bound */
    public static boolean isBound() {
        return sink != null;
    }

    /**
     * @return {@code true} while ingress will accept packets (same as {@link #isBound()})
     */
    public static boolean isActive() {
        return sink != null;
    }

    /**
     * Encodes {@code packet} with {@code codecPlayer}'s registry and offers it for the current
     * server tick. No-op when inactive or arguments are not a clientbound {@link Packet} /
     * {@link ServerPlayer}.
     *
     * @param packet      any clientbound packet or {@code BundlePacket} instance
     * @param codecPlayer {@link ServerPlayer} used only for protocol/registry encoding
     */
    @SuppressWarnings("unchecked")
    public static void offer(Object packet, Object codecPlayer) {
        Sink active = sink;
        if (active == null || packet == null || codecPlayer == null) {
            return;
        }
        if (!(codecPlayer instanceof ServerPlayer camera)) {
            return;
        }
        if (!(packet instanceof Packet<?> raw)) {
            return;
        }
        try {
            Packet<? super ClientGamePacketListener> gamePacket =
                    (Packet<? super ClientGamePacketListener>) raw;
            MetadataBlob blob = ENCODER.encodeGamePacket(camera, gamePacket);
            if (blob.isEmpty()) {
                return;
            }
            active.offer(Bukkit.getCurrentTick(), blob.payload());
        } catch (Exception ignored) {
            // Never break callers (e.g. border render) for recording failures.
        }
    }

    /**
     * Offers one already-encoded clientbound payload for {@code tick}. No-op when unbound or
     * {@code payload} is null/empty.
     */
    public static void offerClientboundPayload(int tick, byte[] payload) {
        Sink active = sink;
        if (active == null || payload == null || payload.length == 0) {
            return;
        }
        active.offer(tick, payload);
    }
}
