package com.globalflashback.nms.v1_21_11;

import com.globalflashback.api.OutboundPacketCapture;
import com.globalflashback.capture.RecordingSideChannel;
import com.globalflashback.delta.StateChange;
import com.globalflashback.nms.EffectOutboundTap;
import com.globalflashback.state.MetadataBlob;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ephemeral / clientbound side tap: records particle / sound / level-event / explode packets, plus
 * block animations and block-entity sync that are not covered by BlockUpdate alone
 * ({@link ClientboundBlockEventPacket}, {@link ClientboundBlockEntityDataPacket},
 * {@link ClientboundBlockDestructionPacket}).
 *
 * <p>Multi-player broadcast cost (H5): the same packet is often written to many pipelines. This tap
 * claims identity / structural keys <em>before</em> StreamCodec encode so N−1 viewers skip encode.
 * Particles are also capped per tick to bound firework / storm floods.
 *
 * <p>Not used as core world-state capture (SPEC / AGENTS). Volume fields inside sound packets are
 * preserved for correct replay attenuation. Explosion knockback is stripped so multi-player
 * broadcasts dedupe to one replay action (Flashback also ignores knockback on playback).
 * Sound {@code seed} is excluded from structural hashing and zeroed on encode: vanilla
 * {@code Player.sendSoundEffect} broadcasts one packet then sends a second copy to the source
 * player with a different seed (crit / strong / weak attack sounds), which would otherwise
 * record twice.
 *
 * <p>Packets / bundles marked via {@link OutboundPacketCapture} are forwarded but never recorded
 * (any packet type; checked before type filters).
 */
public final class EffectOutboundTap1_21_11 implements Listener, EffectOutboundTap {
    private static final String HANDLER_NAME = "gfr_effect_tap";

    /**
     * Max distinct particle packets recorded per server tick. Excess are dropped from the replay
     * only (live clients still receive them). Block events / BE / destruction are not capped.
     */
    private static final int MAX_PARTICLE_PACKETS_PER_TICK = 128;

    private final Plugin plugin;
    private final RecordingSideChannel sideChannel;
    private final EffectPacketFactory1_21_11 factory = new EffectPacketFactory1_21_11();
    private final Map<UUID, Boolean> attached = new ConcurrentHashMap<>();
    /**
     * Per-tick claim set: identity keys (same packet instance) and structural keys (same content,
     * different instances). Claim happens before encode.
     */
    private final Set<Long> claimedThisTick = ConcurrentHashMap.newKeySet();
    private final AtomicInteger particlesThisTick = new AtomicInteger();
    private volatile int lastTick = -1;
    private boolean registered;

    public EffectOutboundTap1_21_11(Plugin plugin, RecordingSideChannel sideChannel) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sideChannel = Objects.requireNonNull(sideChannel, "sideChannel");
    }

    @Override
    public void start() {
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
        OutboundPacketCapture.setCaptureActive(true);
        for (Player player : Bukkit.getOnlinePlayers()) {
            attach(player);
        }
    }

    @Override
    public void stop() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            detach(player);
        }
        attached.clear();
        claimedThisTick.clear();
        particlesThisTick.set(0);
        OutboundPacketCapture.setCaptureActive(false);
        if (registered) {
            HandlerList.unregisterAll(this);
            registered = false;
        }
    }

    @Override
    public boolean offerClaimedEffect(int tick, MetadataBlob blob) {
        if (blob == null || blob.isEmpty()) {
            return false;
        }
        rotateTickIfNeeded(tick);
        long payloadKey = tickScopedKey(tick, 3, hash(blob.payload()));
        if (!claimedThisTick.add(payloadKey)) {
            return false;
        }
        sideChannel.offer(tick, new StateChange.EffectPacket(blob));
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        attach(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        detach(event.getPlayer());
    }

    private void attach(Player player) {
        if (!(player instanceof CraftPlayer craft)) {
            return;
        }
        if (attached.putIfAbsent(player.getUniqueId(), Boolean.TRUE) != null) {
            return;
        }
        ServerPlayer sp = craft.getHandle();
        Channel channel = sp.connection.connection.channel;
        channel.eventLoop().execute(() -> {
            try {
                if (channel.pipeline().get(HANDLER_NAME) != null) {
                    return;
                }
                if (channel.pipeline().get("packet_handler") != null) {
                    channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new TapHandler(sp));
                } else {
                    channel.pipeline().addFirst(HANDLER_NAME, new TapHandler(sp));
                    plugin.getLogger().warning("gfr_effect_tap: packet_handler missing for "
                            + player.getName() + "; attached at pipeline head");
                }
            } catch (Throwable t) {
                attached.remove(player.getUniqueId());
                plugin.getLogger().warning("gfr_effect_tap attach failed for "
                        + player.getName() + ": " + t);
            }
        });
    }

    private void detach(Player player) {
        attached.remove(player.getUniqueId());
        if (!(player instanceof CraftPlayer craft)) {
            return;
        }
        Channel channel = craft.getHandle().connection.connection.channel;
        channel.eventLoop().execute(() -> {
            try {
                if (channel.pipeline().get(HANDLER_NAME) != null) {
                    channel.pipeline().remove(HANDLER_NAME);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("gfr_effect_tap detach failed for "
                        + player.getName() + ": " + t);
            }
        });
    }

    private void rotateTickIfNeeded(int tick) {
        if (tick == lastTick) {
            return;
        }
        // Best-effort reset; concurrent writers may insert for the old tick briefly — acceptable.
        claimedThisTick.clear();
        particlesThisTick.set(0);
        lastTick = tick;
    }

    private final class TapHandler extends ChannelDuplexHandler {
        private final ServerPlayer player;

        private TapHandler(ServerPlayer player) {
            this.player = player;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            // Any packet/bundle instance marked via OutboundPacketCapture — live write continues.
            if (OutboundPacketCapture.isSuppressed(msg)) {
                super.write(ctx, msg, promise);
                return;
            }
            if (msg instanceof BundlePacket<?> bundle) {
                for (Packet<?> nested : bundle.subPackets()) {
                    if (isEffectPacket(nested) && !OutboundPacketCapture.isSuppressed(nested)) {
                        recordEffect(nested);
                    }
                }
            } else if (msg instanceof Packet<?> packet && isEffectPacket(packet)) {
                recordEffect(packet);
            }
            super.write(ctx, msg, promise);
        }

        private void recordEffect(Packet<?> packet) {
            int tick = Bukkit.getCurrentTick();
            rotateTickIfNeeded(tick);

            // 1) Same packet instance written to many channels → only first pipeline encodes.
            long identityKey = tickScopedKey(tick, 1, System.identityHashCode(packet));
            if (!claimedThisTick.add(identityKey)) {
                return;
            }

            // 2) Different instances, same broadcast content → claim via structural hash (no encode).
            long structural = structuralHash(packet);
            if (structural != 0L) {
                long structuralKey = tickScopedKey(tick, 2, (int) structural);
                if (!claimedThisTick.add(structuralKey)) {
                    return;
                }
            }

            // 3) Bound particle floods (live clients unaffected).
            if (packet instanceof ClientboundLevelParticlesPacket) {
                if (particlesThisTick.incrementAndGet() > MAX_PARTICLE_PACKETS_PER_TICK) {
                    return;
                }
            }

            try {
                Packet<? super ClientGamePacketListener> toEncode = normalizeForReplay(packet);
                MetadataBlob blob = factory.encodeGamePacket(player, toEncode);
                if (blob.isEmpty()) {
                    return;
                }
                // 4) Payload hash catches normalized explode / odd duplicates structural missed.
                long payloadKey = tickScopedKey(tick, 3, hash(blob.payload()));
                if (!claimedThisTick.add(payloadKey)) {
                    return;
                }
                sideChannel.offer(tick, new StateChange.EffectPacket(blob));
            } catch (Exception ignored) {
                // Never break the network pipeline for recording failures.
            }
        }
    }

    /**
     * Strip per-player fields so logically identical effects hash / encode identically.
     * Explosion: knockback. Sound: seed (source-player copy vs broadcast).
     */
    @SuppressWarnings("unchecked")
    private static Packet<? super ClientGamePacketListener> normalizeForReplay(Packet<?> packet) {
        if (packet instanceof ClientboundExplodePacket explode) {
            return new ClientboundExplodePacket(
                    explode.center(),
                    explode.radius(),
                    explode.blockCount(),
                    Optional.empty(),
                    explode.explosionParticle(),
                    explode.explosionSound(),
                    explode.blockParticles()
            );
        }
        if (packet instanceof ClientboundSoundPacket sound) {
            // Seed only drives client-side pitch jitter; zero so payload matches after structural claim.
            return new ClientboundSoundPacket(
                    sound.getSound(),
                    sound.getSource(),
                    sound.getX(),
                    sound.getY(),
                    sound.getZ(),
                    sound.getVolume(),
                    sound.getPitch(),
                    0L
            );
        }
        return (Packet<? super ClientGamePacketListener>) packet;
    }

    /**
     * Cheap content fingerprint without StreamCodec. {@code 0} means “unknown — rely on identity /
     * post-encode payload hash only”.
     */
    private static long structuralHash(Packet<?> packet) {
        if (packet instanceof ClientboundLevelParticlesPacket p) {
            long h = mix(1, System.identityHashCode(p.getParticle()));
            h = mix(h, Double.doubleToLongBits(p.getX()));
            h = mix(h, Double.doubleToLongBits(p.getY()));
            h = mix(h, Double.doubleToLongBits(p.getZ()));
            h = mix(h, Float.floatToIntBits(p.getXDist()));
            h = mix(h, Float.floatToIntBits(p.getYDist()));
            h = mix(h, Float.floatToIntBits(p.getZDist()));
            h = mix(h, Float.floatToIntBits(p.getMaxSpeed()));
            h = mix(h, p.getCount());
            h = mix(h, p.isOverrideLimiter() ? 1 : 0);
            h = mix(h, p.alwaysShow() ? 1 : 0);
            return h == 0L ? 1L : h;
        }
        if (packet instanceof ClientboundSoundPacket p) {
            // Omit seed: sendSoundEffect uses a distinct seed for the source player's copy.
            long h = mix(2, System.identityHashCode(p.getSound()));
            h = mix(h, p.getSource().ordinal());
            h = mix(h, Double.doubleToLongBits(p.getX()));
            h = mix(h, Double.doubleToLongBits(p.getY()));
            h = mix(h, Double.doubleToLongBits(p.getZ()));
            h = mix(h, Float.floatToIntBits(p.getVolume()));
            h = mix(h, Float.floatToIntBits(p.getPitch()));
            return h == 0L ? 1L : h;
        }
        if (packet instanceof ClientboundSoundEntityPacket p) {
            // Omit seed for the same multi-copy reason as ClientboundSoundPacket.
            long h = mix(3, System.identityHashCode(p.getSound()));
            h = mix(h, p.getSource().ordinal());
            h = mix(h, p.getId());
            h = mix(h, Float.floatToIntBits(p.getVolume()));
            h = mix(h, Float.floatToIntBits(p.getPitch()));
            return h == 0L ? 1L : h;
        }
        if (packet instanceof ClientboundLevelEventPacket p) {
            BlockPos pos = p.getPos();
            long h = mix(4, p.getType());
            h = mix(h, pos.getX());
            h = mix(h, pos.getY());
            h = mix(h, pos.getZ());
            h = mix(h, p.getData());
            h = mix(h, p.isGlobalEvent() ? 1 : 0);
            return h == 0L ? 1L : h;
        }
        if (packet instanceof ClientboundExplodePacket p) {
            Vec3 c = p.center();
            long h = mix(5, Double.doubleToLongBits(c.x));
            h = mix(h, Double.doubleToLongBits(c.y));
            h = mix(h, Double.doubleToLongBits(c.z));
            h = mix(h, Float.floatToIntBits(p.radius()));
            h = mix(h, p.blockCount());
            h = mix(h, System.identityHashCode(p.explosionParticle()));
            return h == 0L ? 1L : h;
        }
        if (packet instanceof ClientboundBlockEventPacket p) {
            BlockPos pos = p.getPos();
            long h = mix(6, System.identityHashCode(p.getBlock()));
            h = mix(h, pos.getX());
            h = mix(h, pos.getY());
            h = mix(h, pos.getZ());
            h = mix(h, p.getB0());
            h = mix(h, p.getB1());
            return h == 0L ? 1L : h;
        }
        if (packet instanceof ClientboundBlockDestructionPacket p) {
            BlockPos pos = p.getPos();
            long h = mix(7, p.getId());
            h = mix(h, pos.getX());
            h = mix(h, pos.getY());
            h = mix(h, pos.getZ());
            h = mix(h, p.getProgress());
            return h == 0L ? 1L : h;
        }
        if (packet instanceof ClientboundBlockEntityDataPacket p) {
            BlockPos pos = p.getPos();
            long h = mix(8, System.identityHashCode(p.getType()));
            h = mix(h, pos.getX());
            h = mix(h, pos.getY());
            h = mix(h, pos.getZ());
            var tag = p.getTag();
            h = mix(h, tag != null ? tag.hashCode() : 0);
            return h == 0L ? 1L : h;
        }
        return 0L;
    }

    private static long tickScopedKey(int tick, int kind, int hash) {
        return (((long) tick) << 32) ^ (((long) kind) << 28) ^ (hash & 0xfffffffL);
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        hash *= 0x9E3779B97F4A7C15L;
        return Long.rotateLeft(hash, 13);
    }

    private static long mix(long hash, int value) {
        return mix(hash, Integer.toUnsignedLong(value));
    }

    private static boolean isEffectPacket(Packet<?> packet) {
        return packet instanceof ClientboundLevelParticlesPacket
                || packet instanceof ClientboundSoundPacket
                || packet instanceof ClientboundSoundEntityPacket
                || packet instanceof ClientboundLevelEventPacket
                || packet instanceof ClientboundExplodePacket
                || packet instanceof ClientboundBlockEventPacket
                || packet instanceof ClientboundBlockEntityDataPacket
                || packet instanceof ClientboundBlockDestructionPacket;
    }

    private static int hash(byte[] bytes) {
        int h = 1;
        for (byte b : bytes) {
            h = 31 * h + b;
        }
        return h;
    }
}
