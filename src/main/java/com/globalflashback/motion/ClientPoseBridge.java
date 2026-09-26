package com.globalflashback.motion;

import com.globalflashback.motion.format.ClientPoseSample;
import com.globalflashback.motion.format.ClientPoseStore;
import com.globalflashback.motion.format.GfrMotionFormat;
import com.globalflashback.nms.ClientPoseArrivalTap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Receives Fabric client pose packets and feeds the active recording store.
 *
 * <p><b>Timing model ({@link GfrMotionFormat#TIMING_MODEL_TICK_RELATIVE_V2})</b>:
 * samples arrive with {@code timeNs} already on the recording tick timeline
 * ({@code recordingTick × 50ms + offset}). This listener stores them as-is. Netty arrival
 * stamps remain diagnostics only — no process-time rebase.
 */
public final class ClientPoseBridge implements PluginMessageListener {
    private final Plugin plugin;
    private final ClientPoseArrivalStamps arrivalStamps;
    private final ClientPoseArrivalTap arrivalTap;
    private final ClientPoseTimingStats timingStats = new ClientPoseTimingStats();
    private final AtomicReference<ClientPoseStore> active = new AtomicReference<>();
    private int syncTaskId = -1;
    /** {@link Bukkit#getCurrentTick()} when recording started. */
    private int recordingStartTick;

    public ClientPoseBridge(Plugin plugin, ClientPoseArrivalStamps arrivalStamps, ClientPoseArrivalTap arrivalTap) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.arrivalStamps = Objects.requireNonNull(arrivalStamps, "arrivalStamps");
        this.arrivalTap = Objects.requireNonNull(arrivalTap, "arrivalTap");
    }

    public void register() {
        var messenger = Bukkit.getMessenger();
        messenger.registerIncomingPluginChannel(plugin, GfrMotionFormat.CHANNEL, this);
        messenger.registerOutgoingPluginChannel(plugin, GfrMotionFormat.SYNC_CHANNEL);
    }

    public void unregister() {
        stopSyncTask();
        arrivalTap.stop();
        var messenger = Bukkit.getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin, GfrMotionFormat.CHANNEL, this);
        messenger.unregisterOutgoingPluginChannel(plugin, GfrMotionFormat.SYNC_CHANNEL);
        active.set(null);
    }

    public void beginRecording(UUID recordingId, int startTick) {
        timingStats.reset();
        arrivalTap.start();
        this.recordingStartTick = startTick;
        long startNs = System.nanoTime();
        ClientPoseStore store = new ClientPoseStore(recordingId, startNs);
        active.set(store);
        broadcastSync(true, 0L, 0);
        stopSyncTask();
        // Every server tick: keep client recordingTick aligned with Flashback tick axis.
        syncTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            ClientPoseStore s = active.get();
            if (s != null) {
                long elapsed = System.nanoTime() - s.sessionStartNs();
                int recordingTick = Math.max(0, Bukkit.getCurrentTick() - recordingStartTick);
                broadcastSync(true, elapsed, recordingTick);
            }
        }, 1L, 1L);
        plugin.getLogger().info("Client pose capture armed recordingId=" + recordingId
                + " timing=" + GfrMotionFormat.TIMING_MODEL_TICK_RELATIVE_V2
                + " startTick=" + startTick);
    }

    public ClientPoseStore endRecording() {
        stopSyncTask();
        broadcastSync(false, 0L, 0);
        arrivalTap.stop();
        ClientPoseStore store = active.getAndSet(null);
        if (store != null) {
            store.setTimingStats(timingStats.snapshot());
            ClientPoseTimingStats.Snapshot snap = timingStats.snapshot();
            plugin.getLogger().info("Client pose timing packets=" + snap.packetCount()
                    + " arrivalHits=" + snap.arrivalHits()
                    + " arrivalMisses=" + snap.arrivalMisses()
                    + " queueDelayEmaMs=" + (snap.queueDelayEmaNs() / 1_000_000L)
                    + " queueDelayMaxMs=" + (snap.queueDelayMaxNs() / 1_000_000L));
        }
        return store;
    }

    private void stopSyncTask() {
        if (syncTaskId != -1) {
            Bukkit.getScheduler().cancelTask(syncTaskId);
            syncTaskId = -1;
        }
    }

    private void broadcastSync(boolean recording, long sessionElapsedNs, int recordingTick) {
        byte[] payload = encodeSync(sessionElapsedNs, recording, recordingTick);
        int sent = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                if (!player.getListeningPluginChannels().contains(GfrMotionFormat.SYNC_CHANNEL)) {
                    continue;
                }
                player.sendPluginMessage(plugin, GfrMotionFormat.SYNC_CHANNEL, payload);
                sent++;
            } catch (Throwable t) {
                plugin.getLogger().warning("pose sync failed for " + player.getName() + ": " + t.getMessage());
            }
        }
        if (recording && sent == 0 && !Bukkit.getOnlinePlayers().isEmpty()) {
            plugin.getLogger().info("pose sync: no online player registered channel "
                    + GfrMotionFormat.SYNC_CHANNEL + " (install gfr-motion / check channel limit)");
        }
    }

    /** Big-endian: u64 elapsed + u8 active + u32 recordingTick. */
    private static byte[] encodeSync(long sessionElapsedNs, boolean recording, int recordingTick) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(GfrMotionFormat.SYNC_PAYLOAD_BYTES);
            DataOutputStream out = new DataOutputStream(baos);
            out.writeLong(sessionElapsedNs);
            out.writeBoolean(recording);
            out.writeInt(Math.max(0, recordingTick));
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!GfrMotionFormat.CHANNEL.equals(channel)) {
            return;
        }
        ClientPoseStore store = active.get();
        if (store == null || message == null || message.length < 20) {
            return;
        }
        try {
            long processNs = System.nanoTime();
            UUID uuid = player.getUniqueId();
            long arrivalNs = arrivalStamps.poll(uuid);
            boolean arrivalHit = arrivalNs != 0L;
            long queueDelayNs = arrivalHit ? processNs - arrivalNs : 0L;
            timingStats.record(queueDelayNs, arrivalHit);

            ClientPoseSample.DecodedPacket decoded = ClientPoseSample.decodePacket(message);
            String dim = player.getWorld().getName();
            try {
                dim = player.getWorld().getKey().toString();
            } catch (Throwable ignored) {
                // older API
            }
            int dimIndex = store.dimensionIndex(dim);

            // Trust client tick-relative timeNs (tick_relative_v2). Do not rebase to process now.
            for (ClientPoseSample sample : decoded.samples()) {
                long timeNs = sample.timeNs;
                if (timeNs < 0L) {
                    timeNs = 0L;
                }
                store.append(uuid, new ClientPoseSample(
                        timeNs,
                        sample.x, sample.y, sample.z,
                        sample.yaw, sample.pitch, sample.headYaw, sample.bodyYaw,
                        sample.vehicleYaw, sample.vehiclePitch,
                        dimIndex,
                        sample.flags
                ));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Bad client pose from " + player.getName() + ": " + e.getMessage());
        }
    }
}
