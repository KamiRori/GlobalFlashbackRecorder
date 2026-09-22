package com.globalflashback.recorder;

import com.globalflashback.api.ReplayEffectIngress;
import com.globalflashback.capture.CaptureOptions;
import com.globalflashback.capture.RecordingListeners;
import com.globalflashback.capture.RecordingSideChannel;
import com.globalflashback.capture.ServerStateCapture;
import com.globalflashback.capture.SwingSampler;
import com.globalflashback.delta.DeltaFrame;
import com.globalflashback.delta.Keyframe;
import com.globalflashback.delta.SnapshotApplier;
import com.globalflashback.delta.SnapshotDiffer;
import com.globalflashback.delta.StateChange;
import com.globalflashback.nms.NmsAdapter;
import com.globalflashback.nms.v26_2.EffectOutboundTap26_2;
import com.globalflashback.replay.FlashbackEncoder;
import com.globalflashback.replay.ReplayBuffer;
import com.globalflashback.replay.ReplayDocument;
import com.globalflashback.replay.ReplayMetadata;
import com.globalflashback.state.GlobalSnapshot;
import com.globalflashback.state.MetadataBlob;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Main-thread Global Replay session: Initial Snapshot + per-tick Delta + periodic Keyframe.
 *
 * <p>On stop, encodes a Flashback {@code .zip} via {@link FlashbackEncoder}.
 */
public final class GlobalReplayRecorder {
    private final JavaPlugin plugin;
    private final ServerStateCapture capture;
    private final NmsAdapter nmsAdapter;
    private final FlashbackEncoder encoder;
    private final Logger logger;
    private final RecordingSideChannel sideChannel = new RecordingSideChannel();
    private final RecordingListeners listeners;
    private final SwingSampler swingSampler;
    private final EffectOutboundTap26_2 effectTap;

    private Session session;

    public GlobalReplayRecorder(JavaPlugin plugin, ServerStateCapture capture, NmsAdapter nmsAdapter) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.nmsAdapter = Objects.requireNonNull(nmsAdapter, "nmsAdapter");
        this.encoder = new FlashbackEncoder();
        this.logger = plugin.getLogger();
        this.listeners = new RecordingListeners(plugin, sideChannel, capture.dirtyChunks());
        this.swingSampler = new SwingSampler(sideChannel);
        this.effectTap = new EffectOutboundTap26_2(plugin, sideChannel);
    }

    public synchronized boolean isRecording() {
        return session != null;
    }

    public synchronized RecordingStats status() {
        if (session == null) {
            return RecordingStats.idle();
        }
        return session.stats(true, false, null);
    }

    public synchronized boolean start(String name, RecordingOptions options) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("GlobalReplayRecorder.start must run on the main thread");
        }
        if (session != null) {
            return false;
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(options, "options");

        capture.resetBlockCache();
        int tick = Bukkit.getCurrentTick();
        GlobalSnapshot initial = capture.capture(tick, options.captureOptions());

        ReplayMetadata metadata = new ReplayMetadata(
                UUID.randomUUID(),
                name,
                nmsAdapter.versionString(),
                primaryWorldName(initial),
                nmsAdapter.dataVersion(),
                nmsAdapter.protocolVersion(),
                0
        );

        ReplayBuffer buffer = new ReplayBuffer();
        buffer.setInitialSnapshot(initial);
        ReplayDocument.Builder document = ReplayDocument.builder(metadata, initial);

        Session started = new Session(name, options, initial, buffer, document, tick);
        started.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickIfRecording, 1L, 1L);
        this.session = started;

        sideChannel.clear();
        swingSampler.clear();
        listeners.register();
        effectTap.start();
        ReplayEffectIngress.bind((ingressTick, payload) ->
                sideChannel.offer(ingressTick, new StateChange.EffectPacket(new MetadataBlob(payload))));

        logger.info("Recording started name=" + name
                + " tick=" + tick
                + " keyframeInterval=" + options.keyframeIntervalTicks()
                + " players=" + initial.players().size()
                + " entities=" + initial.entities().size()
                + " chunks=" + initial.chunks().size());
        return true;
    }

    /**
     * Stops recording, verifies seek, encodes Flashback {@code .zip}.
     *
     * @param camera ego player for create_local_player / registry bootstrap
     */
    public synchronized RecordingStats stop(Player camera) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("GlobalReplayRecorder.stop must run on the main thread");
        }
        Objects.requireNonNull(camera, "camera");
        if (session == null) {
            return RecordingStats.idle();
        }

        Session ending = session;
        session = null;
        if (ending.task != null) {
            ending.task.cancel();
        }
        listeners.unregister();
        effectTap.stop();
        ReplayEffectIngress.unbind();
        swingSampler.clear();
        sideChannel.clear();

        boolean seekOk = ending.verifySeek();
        ending.buffer.seal();

        int durationTicks = Math.max(0, ending.previous.tick() - ending.startTick);
        ending.document.metadata(ending.document.metadata().withTotalTicks(durationTicks));
        ReplayDocument document = ending.document.build();

        Path outFile = null;
        try {
            Path outDir = plugin.getDataFolder().toPath().resolve("replays");
            outFile = outDir.resolve(sanitize(ending.name) + ".zip");
            encoder.encode(document, camera, outFile);
            logger.info("Flashback zip written: " + outFile.toAbsolutePath());
        } catch (Exception e) {
            logger.severe("Flashback encode failed: " + e.getMessage());
            e.printStackTrace();
        }

        RecordingStats stats = ending.stats(false, seekOk, outFile);
        logger.info("Recording stopped name=" + ending.name
                + " ticks=" + stats.ticksRecorded()
                + " deltas=" + stats.nonEmptyDeltas()
                + " empty=" + stats.emptyTicks()
                + " keyframes=" + stats.keyframes()
                + " changes=" + stats.totalChanges()
                + " seekVerified=" + seekOk
                + " file=" + (outFile != null ? outFile.getFileName() : "none"));
        return stats;
    }

    public synchronized RecordingStats stopWithoutEncode() {
        if (session == null) {
            return RecordingStats.idle();
        }
        Session ending = session;
        session = null;
        if (ending.task != null) {
            ending.task.cancel();
        }
        listeners.unregister();
        effectTap.stop();
        ReplayEffectIngress.unbind();
        swingSampler.clear();
        sideChannel.clear();
        ending.buffer.seal();
        return ending.stats(false, false, null);
    }

    private synchronized void tickIfRecording() {
        if (session != null) {
            session.onTick();
        }
    }

    private static String primaryWorldName(GlobalSnapshot snapshot) {
        if (snapshot.worlds().isEmpty()) {
            return "unknown";
        }
        return snapshot.worlds().keySet().iterator().next().namespacedKey();
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private final class Session {
        private final String name;
        private final RecordingOptions options;
        private final GlobalSnapshot initial;
        private final ReplayBuffer buffer;
        private final ReplayDocument.Builder document;
        private final int startTick;

        private GlobalSnapshot previous;
        private int lastKeyframeTick;
        private int ticksRecorded;
        private int nonEmptyDeltas;
        private int emptyTicks;
        private int keyframes;
        private long totalChanges;
        private final List<Keyframe> keyframeList = new ArrayList<>();
        private final List<DeltaFrame> deltaList = new ArrayList<>();
        /** Reused each tick on the main thread to avoid empty-tick ArrayList churn. */
        private final List<StateChange> silentBlocksScratch = new ArrayList<>();
        private final ArrayList<StateChange> mergeScratch = new ArrayList<>();
        private BukkitTask task;

        private Session(
                String name,
                RecordingOptions options,
                GlobalSnapshot initial,
                ReplayBuffer buffer,
                ReplayDocument.Builder document,
                int startTick
        ) {
            this.name = name;
            this.options = options;
            this.initial = initial;
            this.buffer = buffer;
            this.document = document;
            this.startTick = startTick;
            this.previous = initial;
            this.lastKeyframeTick = startTick;
        }

        private void onTick() {
            try {
                swingSampler.sample();
                int tick = Bukkit.getCurrentTick();
                boolean keyframeDue = (tick - lastKeyframeTick) >= options.keyframeIntervalTicks();
                // Keyframes are in-memory seek anchors only (Flashback ZIP does not forcePlay them).
                // Never re-encode LevelChunkWithLight on the main thread for keyframe ticks.
                CaptureOptions captOpts = options.captureOptions().withEncodeChunkPayloads(false);
                silentBlocksScratch.clear();
                GlobalSnapshot current = capture.capture(tick, captOpts, previous, silentBlocksScratch);
                DeltaFrame diffFrame = SnapshotDiffer.diffFrame(previous, current);
                List<StateChange> side = sideChannel.drainUpTo(tick);
                ticksRecorded++;

                if (diffFrame.isEmpty() && side.isEmpty() && silentBlocksScratch.isEmpty()) {
                    emptyTicks++;
                } else {
                    DeltaFrame frame;
                    if (side.isEmpty() && silentBlocksScratch.isEmpty()) {
                        frame = diffFrame;
                    } else if (diffFrame.isEmpty() && silentBlocksScratch.isEmpty()) {
                        frame = new DeltaFrame(tick, List.copyOf(side));
                    } else if (diffFrame.isEmpty() && side.isEmpty()) {
                        frame = new DeltaFrame(tick, List.copyOf(silentBlocksScratch));
                    } else {
                        mergeScratch.clear();
                        mergeScratch.ensureCapacity(
                                diffFrame.changes().size() + side.size() + silentBlocksScratch.size());
                        mergeScratch.addAll(diffFrame.changes());
                        mergeScratch.addAll(side);
                        mergeScratch.addAll(silentBlocksScratch);
                        frame = new DeltaFrame(tick, List.copyOf(mergeScratch));
                        mergeScratch.clear();
                    }
                    nonEmptyDeltas++;
                    totalChanges += frame.changes().size();
                    document.addDelta(frame);
                    deltaList.add(frame);
                }

                if (keyframeDue) {
                    Keyframe keyframe = new Keyframe(tick, current);
                    document.addKeyframe(keyframe);
                    keyframeList.add(keyframe);
                    keyframes++;
                    lastKeyframeTick = tick;
                }

                previous = current;
            } catch (Exception e) {
                logger.severe("Recording tick failed: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private boolean verifySeek() {
            if (ticksRecorded == 0) {
                return true;
            }
            try {
                GlobalSnapshot reconstructed = SnapshotApplier.seek(
                        initial, keyframeList, deltaList, previous.tick());
                boolean ok = snapshotContentEquals(reconstructed, previous);
                if (!ok) {
                    logger.warning("Seek verify FAILED at tick " + previous.tick());
                }
                return ok;
            } catch (Exception e) {
                logger.warning("Seek verify error: " + e.getMessage());
                e.printStackTrace();
                return false;
            }
        }

        private RecordingStats stats(boolean recording, boolean seekVerified, Path outputFile) {
            GlobalSnapshot last = previous;
            return new RecordingStats(
                    recording,
                    name,
                    startTick,
                    last.tick(),
                    ticksRecorded,
                    nonEmptyDeltas,
                    emptyTicks,
                    keyframes,
                    totalChanges,
                    last.players().size(),
                    last.entities().size(),
                    last.chunks().size(),
                    seekVerified,
                    outputFile
            );
        }
    }

    static boolean snapshotContentEquals(GlobalSnapshot a, GlobalSnapshot b) {
        return a.worlds().equals(b.worlds())
                && a.chunks().equals(b.chunks())
                && a.players().equals(b.players())
                && a.entities().equals(b.entities());
    }
}
