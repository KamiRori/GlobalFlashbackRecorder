package com.globalflashback.nms.v1_21_11;

import com.globalflashback.motion.ClientPoseArrivalStamps;
import com.globalflashback.motion.format.GfrMotionFormat;
import com.globalflashback.nms.ClientPoseArrivalTap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inbound Netty tap for Paper 1.21.11 — same contract as {@code ClientPoseArrivalTap26_2}.
 */
public final class ClientPoseArrivalTap1_21_11 implements Listener, ClientPoseArrivalTap {
    private static final String HANDLER_NAME = "gfr_pose_arrival";
    private static final Identifier CHANNEL_ID = Identifier.parse(GfrMotionFormat.CHANNEL);

    private final Plugin plugin;
    private final ClientPoseArrivalStamps stamps;
    private final Map<UUID, Boolean> attached = new ConcurrentHashMap<>();
    private boolean registered;
    private volatile boolean active;

    public ClientPoseArrivalTap1_21_11(Plugin plugin, ClientPoseArrivalStamps stamps) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.stamps = Objects.requireNonNull(stamps, "stamps");
    }

    @Override
    public void start() {
        active = true;
        stamps.clear();
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            attach(player);
        }
    }

    @Override
    public void stop() {
        active = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            detach(player);
        }
        attached.clear();
        stamps.clear();
        if (registered) {
            HandlerList.unregisterAll(this);
            registered = false;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (active) {
            attach(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        detach(event.getPlayer());
        stamps.remove(event.getPlayer().getUniqueId());
    }

    private void attach(Player player) {
        if (!(player instanceof CraftPlayer craft)) {
            return;
        }
        if (attached.putIfAbsent(player.getUniqueId(), Boolean.TRUE) != null) {
            return;
        }
        ServerPlayer sp = craft.getHandle();
        UUID uuid = player.getUniqueId();
        Channel channel = sp.connection.connection.channel;
        channel.eventLoop().execute(() -> {
            try {
                if (channel.pipeline().get(HANDLER_NAME) != null) {
                    return;
                }
                ArrivalHandler handler = new ArrivalHandler(uuid, stamps, this::isActive);
                if (channel.pipeline().get("packet_handler") != null) {
                    channel.pipeline().addBefore("packet_handler", HANDLER_NAME, handler);
                } else {
                    channel.pipeline().addFirst(HANDLER_NAME, handler);
                    plugin.getLogger().warning("gfr_pose_arrival: packet_handler missing for "
                            + player.getName() + "; attached at pipeline head");
                }
            } catch (Throwable t) {
                attached.remove(uuid);
                plugin.getLogger().warning("gfr_pose_arrival attach failed for "
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
                plugin.getLogger().warning("gfr_pose_arrival detach failed for "
                        + player.getName() + ": " + t);
            }
        });
    }

    private boolean isActive() {
        return active;
    }

    private static final class ArrivalHandler extends ChannelInboundHandlerAdapter {
        private final UUID playerId;
        private final ClientPoseArrivalStamps stamps;
        private final ActiveProbe active;

        ArrivalHandler(UUID playerId, ClientPoseArrivalStamps stamps, ActiveProbe active) {
            this.playerId = playerId;
            this.stamps = stamps;
            this.active = active;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (active.get() && msg instanceof ServerboundCustomPayloadPacket packet) {
                CustomPacketPayload payload = packet.payload();
                if (CHANNEL_ID.equals(payload.type().id())) {
                    stamps.offer(playerId, System.nanoTime());
                }
            }
            ctx.fireChannelRead(msg);
        }

        @FunctionalInterface
        private interface ActiveProbe {
            boolean get();
        }
    }
}
