package com.globalflashback.capture;

import com.globalflashback.delta.StateChange;
import com.globalflashback.nms.EffectPacketEncoder;
import com.globalflashback.state.MetadataBlob;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Samples server-authoritative arm swings each tick.
 *
 * <p>{@link org.bukkit.event.player.PlayerAnimationEvent} only covers client-sent swings.
 * Item use / block place call {@code LivingEntity#swing} which broadcasts
 * {@link ClientboundAnimatePacket} without firing Bukkit animation events — and with a lone
 * player that packet may not even leave the server ({@code sendToTrackingPlayers} only).
 *
 * <p>H6: idle players ({@code !swinging} and not previously swinging) skip map updates; offline
 * entity ids are pruned periodically so the sampler stays O(active swingers) in the steady state.
 */
public final class SwingSampler {
    private static final int PRUNE_INTERVAL_TICKS = 100;

    private final EffectPacketEncoder effects;
    private final RecordingSideChannel sideChannel;
    private final Map<Integer, Boolean> wasSwinging = new HashMap<>();
    private final Map<Integer, Integer> lastSwingTime = new HashMap<>();
    private final Set<Integer> seenIdsScratch = new HashSet<>();
    private int ticksSincePrune;

    public SwingSampler(RecordingSideChannel sideChannel, EffectPacketEncoder effects) {
        this.sideChannel = Objects.requireNonNull(sideChannel, "sideChannel");
        this.effects = Objects.requireNonNull(effects, "effects");
    }

    public void clear() {
        wasSwinging.clear();
        lastSwingTime.clear();
        seenIdsScratch.clear();
        ticksSincePrune = 0;
    }

    /** Call once per recording tick on the main thread. */
    public void sample() {
        int tick = Bukkit.getCurrentTick();
        seenIdsScratch.clear();
        boolean prune = ++ticksSincePrune >= PRUNE_INTERVAL_TICKS;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!(player instanceof CraftPlayer craft)) {
                continue;
            }
            ServerPlayer sp = craft.getHandle();
            int id = sp.getId();
            if (prune) {
                seenIdsScratch.add(id);
            }

            boolean swinging = sp.swinging;
            Boolean wasBoxed = wasSwinging.get(id);
            boolean was = wasBoxed != null && wasBoxed;

            // H6: fully idle — no HashMap writes, no encode.
            if (!swinging && !was) {
                continue;
            }

            int swingTime = sp.swingTime;
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

            if (swinging) {
                wasSwinging.put(id, Boolean.TRUE);
                lastSwingTime.put(id, swingTime);
            } else {
                // Falling edge: one write to clear, then future ticks hit the idle fast path.
                wasSwinging.remove(id);
                lastSwingTime.remove(id);
            }
        }

        if (prune) {
            ticksSincePrune = 0;
            pruneGone(wasSwinging, seenIdsScratch);
            pruneGone(lastSwingTime, seenIdsScratch);
        }
    }

    private static void pruneGone(Map<Integer, ?> map, Set<Integer> onlineIds) {
        if (map.isEmpty()) {
            return;
        }
        Iterator<Integer> it = map.keySet().iterator();
        while (it.hasNext()) {
            if (!onlineIds.contains(it.next())) {
                it.remove();
            }
        }
    }
}
