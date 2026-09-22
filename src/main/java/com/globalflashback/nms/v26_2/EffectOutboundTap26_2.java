package com.globalflashback.nms.v26_2;

import com.globalflashback.api.OutboundPacketCapture;
import com.globalflashback.capture.RecordingSideChannel;
import com.globalflashback.delta.StateChange;
import com.globalflashback.state.MetadataBlob;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ephemeral / clientbound side tap: records particle / sound / level-event / explode packets, plus
 * block animations and block-entity sync that are not covered by BlockUpdate alone
 * ({@link ClientboundBlockEventPacket}, {@link ClientboundBlockEntityDataPacket},
 * {@link ClientboundBlockDestructionPacket}), once per tick (deduped).
 *
 * <p>Not used as core world-state capture (SPEC / AGENTS). Volume fields inside sound packets are
 * preserved for correct replay attenuation. Explosion knockback is stripped so multi-player
 * broadcasts dedupe to one replay action (Flashback also ignores knockback on playback).
 *
 * <p>Packets / bundles marked via {@link OutboundPacketCapture} are forwarded but never recorded
 * (any packet type; checked before type filters).
 */
public final class EffectOutboundTap26_2 implements Listener {
    private static final String HANDLER_NAME = "gfr_effect_tap";

    private final Plugin plugin;
    private final RecordingSideChannel sideChannel;
    private final EffectPacketFactory26_2 factory = new EffectPacketFactory26_2();
    private final Map<UUID, Boolean> attached = new ConcurrentHashMap<>();
    /** tick -> contentHash -> present (dedupe same effect sent to many players). */
    private final ConcurrentHashMap<Long, Boolean> seenThisTick = new ConcurrentHashMap<>();
    private int lastTick = -1;
    private boolean registered;

    public EffectOutboundTap26_2(Plugin plugin, RecordingSideChannel sideChannel) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sideChannel = Objects.requireNonNull(sideChannel, "sideChannel");
    }

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

    public void stop() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            detach(player);
        }
        attached.clear();
        seenThisTick.clear();
        OutboundPacketCapture.setCaptureActive(false);
        if (registered) {
            HandlerList.unregisterAll(this);
            registered = false;
        }
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
            if (channel.pipeline().get(HANDLER_NAME) == null) {
                channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new TapHandler(sp));
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
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }
        });
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
            if (tick != lastTick) {
                seenThisTick.clear();
                lastTick = tick;
            }
            try {
                Packet<? super ClientGamePacketListener> toEncode = normalizeForReplay(packet);
                MetadataBlob blob = factory.encodeGamePacket(player, toEncode);
                if (blob.isEmpty()) {
                    return;
                }
                long key = (((long) tick) << 32) ^ (hash(blob.payload()) & 0xffffffffL);
                if (seenThisTick.putIfAbsent(key, Boolean.TRUE) != null) {
                    return; // already recorded from another player's pipeline this tick
                }
                sideChannel.offer(tick, new StateChange.EffectPacket(blob));
            } catch (Exception ignored) {
                // Never break the network pipeline for recording failures.
            }
        }
    }

    /**
     * Strip per-player explosion knockback so the same blast hashes identically across viewers.
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
        return (Packet<? super ClientGamePacketListener>) packet;
    }

    private static boolean isEffectPacket(Packet<?> packet) {
        return packet instanceof ClientboundLevelParticlesPacket
                || packet instanceof ClientboundSoundPacket
                || packet instanceof ClientboundSoundEntityPacket
                || packet instanceof ClientboundLevelEventPacket
                || packet instanceof ClientboundExplodePacket
                // Chest / shulker lid, note block, etc. — not inventory contents
                || packet instanceof ClientboundBlockEventPacket
                // Sign text, spawner display, banner, etc. (client update tag — not container items)
                || packet instanceof ClientboundBlockEntityDataPacket
                // Mining crack stages
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
