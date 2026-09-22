package com.globalflashback.capture;

import com.globalflashback.delta.StateChange;
import com.globalflashback.nms.v26_2.EffectPacketFactory26_2;
import com.globalflashback.state.MetadataBlob;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Samples server-authoritative arm swings each tick.
 *
 * <p>{@link org.bukkit.event.player.PlayerAnimationEvent} only covers client-sent swings.
 * Item use / block place call {@code LivingEntity#swing} which broadcasts
 * {@link ClientboundAnimatePacket} without firing Bukkit animation events — and with a lone
 * player that packet may not even leave the server ({@code sendToTrackingPlayers} only).
 */
public final class SwingSampler {
    private final EffectPacketFactory26_2 effects = new EffectPacketFactory26_2();
    private final RecordingSideChannel sideChannel;
    private final Map<Integer, Boolean> wasSwinging = new HashMap<>();
    private final Map<Integer, Integer> lastSwingTime = new HashMap<>();

    public SwingSampler(RecordingSideChannel sideChannel) {
        this.sideChannel = Objects.requireNonNull(sideChannel, "sideChannel");
    }

    public void clear() {
        wasSwinging.clear();
        lastSwingTime.clear();
    }

    /** Call once per recording tick on the main thread. */
    public void sample() {
        int tick = Bukkit.getCurrentTick();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!(player instanceof CraftPlayer craft)) {
                continue;
            }
            ServerPlayer sp = craft.getHandle();
            int id = sp.getId();
            boolean swinging = sp.swinging;
            int swingTime = sp.swingTime;
            boolean was = wasSwinging.getOrDefault(id, false);
            Integer previousTime = lastSwingTime.get(id);

            // Rising edge, or swing restart (time counter reset while still swinging).
            boolean started = swinging && (!was || (previousTime != null && previousTime > swingTime));
            if (started) {
                InteractionHand hand = sp.swingingArm != null ? sp.swingingArm : InteractionHand.MAIN_HAND;
                int action = hand == InteractionHand.OFF_HAND
                        ? ClientboundAnimatePacket.SWING_OFF_HAND
                        : ClientboundAnimatePacket.SWING_MAIN_HAND;
                MetadataBlob packet = effects.encodeAnimate(player, action);
                if (!packet.isEmpty()) {
                    sideChannel.offer(tick, new StateChange.EntityAnimate(id, action, packet));
                }
            }

            wasSwinging.put(id, swinging);
            lastSwingTime.put(id, swingTime);
        }
    }
}
