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
import com.globalflashback.event.GameplayEventListeners;
import com.globalflashback.motion.ClientPoseArrivalStamps;
import com.globalflashback.motion.ClientPoseBridge;
import com.globalflashback.motion.format.ClientPoseStore;
import com.globalflashback.nms.EffectOutboundTap;
import com.globalflashback.nms.NmsPlatform;
import com.globalflashback.replay.AsyncDeltaPipeline;
import com.globalflashback.replay.DeltaSpillFile;
import com.globalflashback.replay.ReplayDocument;
import com.globalflashback.replay.ReplayEncodeJob;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Main-thread Global Replay session: Initial Snapshot + per-tick Delta + optional seek Keyframes.
 *
 * <p>On stop, seals an immutable {@link ReplayDocument}. By default Flashback ZIP encode runs on a
 * dedicated worker after main-thread {@link NmsPlatform#prepareEncodeJob} (H10 / SPEC §34).
 */
public final class GlobalReplayRecorder {
    private final JavaPlugin plugin;
    private final ServerStateCapture capture;
    private final NmsPlatform platform;
    private final Logger logger;
    private final RecordingSideChannel sideChannel = new RecordingSideChannel();
    private final RecordingListeners listeners;
    private final GameplayEventListeners gameplayEvents;
    private final SwingSampler swingSampler;
    private final EffectOutboundTap effectTap;
    private final ClientPoseBridge clientPoseBridge;
    private final ExecutorService encodeExecutor;

    private Session session;

    public GlobalReplayRecorder(JavaPlugin plugin, ServerStateCapture capture, NmsPlatform platform) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.logger = plugin.getLogger();
        this.effectTap = platform.createEffectTap(plugin, sideChannel);
        this.listeners = new RecordingListeners(
                plugin, sideChannel, capture.dirtyChunks(), platform.effects(), effectTap);
        this.gameplayEvents = new GameplayEventListeners(plugin, this::isRecording);
        this.swingSampler = new SwingSampler(sideChannel, platform.effects());
        ClientPoseArrivalStamps arrivalStamps = new ClientPoseArrivalStamps();
        this.clientPoseBridge = new ClientPoseBridge(
                plugin,
                arrivalStamps,
                platform.createClientPoseArrivalTap(plugin, arrivalStamps)
        );
        this.clientPoseBridge.register();
        this.encodeExecutor = Executors.newSingleThreadExecutor(encodeThreadFactory());
    }

    private static ThreadFactory encodeThreadFactory() {
        AtomicInteger n = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, "gfr-flashback-encode-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    public synchronized boolean isRecording() {
        return session != null;
    }

    public synchronized RecordingStats status() {
        if (session == null) {
            return RecordingStats.idle();
        }
        return session.stats(true, false, null, false);
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
                platform.adapter().versionString(),
                primaryWorldName(initial),
                platform.adapter().dataVersion(),
                platform.adapter().protocolVersion(),
                0
        );

        ReplayDocument.Builder document = ReplayDocument.builder(metadata, initial);

        Session started = new Session(name, options, initial, document, tick);
        started.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickIfRecording, 1L, 1L);
        this.session = started;

        sideChannel.clear();
        swingSampler.clear();
        listeners.register();
        gameplayEvents.bind(started.document::addEvent, tick);
        gameplayEvents.register();
        effectTap.start();
        ReplayEffectIngress.bind((ingressTick, payload) ->
                sideChannel.offer(ingressTick, new StateChange.EffectPacket(new MetadataBlob(payload))));

        clientPoseBridge.beginRecording(metadata.replayId(), tick);

        logger.info("Recording started name=" + name
                + " tick=" + tick
                + " keyframeInterval=" + options.keyframeIntervalTicks()
                + " verifySeek=" + options.verifySeekOnStop()
                + " deferEncode=" + options.deferEncodeOnStop()
                + " spillDeltas=" + options.spillDeltas()
                + " players=" + initial.players().size()
                + " entities=" + initial.entities().size()
                + " chunks=" + initial.chunks().size());
        return true;
    }

    /**
     * Stops capture on the main thread, optionally verifies seek, then encodes Flashback {@code .zip}
     * (async worker by default, or synchronously when {@link RecordingOptions#deferEncodeOnStop()} is false).
     *
     * @param camera ego player for create_local_player / registry bootstrap (main-thread freeze only)
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
        gameplayEvents.unregister();
        effectTap.stop();
        ReplayEffectIngress.unbind();
        swingSampler.clear();
        sideChannel.clear();

        int durationTicks = Math.max(0, ending.previous.tick() - ending.startTick);
        ending.document.metadata(ending.document.metadata().withTotalTicks(durationTicks));

        Path spillPath = null;
        if (ending.deltaPipeline != null) {
            try {
                ending.deltaPipeline.sealAndAwait();
                spillPath = ending.deltaPipeline.path();
                ending.document.deltaSpill(spillPath);
                logger.info("Delta spill sealed frames=" + ending.deltaPipeline.spilledFrames()
                        + " path=" + spillPath.getFileName());
            } catch (Exception e) {
                logger.severe("Delta spill seal failed: " + e.getMessage());
                e.printStackTrace();
                ending.deltaPipeline.deleteQuietly();
                ending.deltaPipeline = null;
            }
        }

        ReplayDocument document = ending.document.build();
        logger.info("Gameplay events recorded=" + document.events().size());

        ClientPoseStore poseStore = clientPoseBridge.endRecording();
        ClientPoseStore.WrittenMotion clientPose = null;
        if (poseStore != null) {
            clientPose = poseStore.write();
            if (clientPose != null) {
                logger.info("Client pose samples=" + poseStore.totalSamples());
            }
        }

        boolean seekOk = false;
        if (ending.options.verifySeekOnStop()) {
            seekOk = verifySeek(document, ending.initial, ending.previous);
        }

        Path outFile = plugin.getDataFolder().toPath().resolve("replays")
                .resolve(sanitize(ending.name) + ".zip");

        // Prepare on main thread (bootstrap + RegistryAccess), then encode off-thread when deferred.
        ReplayEncodeJob job = platform.prepareEncodeJob(document, camera, outFile, clientPose);
        UUID cameraId = camera.getUniqueId();
        String recName = ending.name;
        AsyncDeltaPipeline spillToDelete = ending.deltaPipeline;

        if (ending.options.deferEncodeOnStop()) {
            encodeExecutor.execute(() -> {
                try {
                    runEncodeJob(job, cameraId, recName, outFile);
                } finally {
                    if (spillToDelete != null) {
                        spillToDelete.deleteQuietly();
                    }
                }
            });
            RecordingStats stats = ending.stats(false, seekOk, null, true);
            logger.info("Recording stopped name=" + ending.name
                    + " ticks=" + stats.ticksRecorded()
                    + " deltas=" + stats.nonEmptyDeltas()
                    + " empty=" + stats.emptyTicks()
                    + " keyframes=" + stats.keyframes()
                    + " changes=" + stats.totalChanges()
                    + " seekVerified=" + seekOk
                    + " encode=async-worker"
                    + " spill=" + (spillPath != null));
            return stats;
        }

        Path written = runEncodeJobSync(job, outFile);
        if (spillToDelete != null) {
            spillToDelete.deleteQuietly();
        }
        RecordingStats stats = ending.stats(false, seekOk, written, false);
        logger.info("Recording stopped name=" + ending.name
                + " ticks=" + stats.ticksRecorded()
                + " deltas=" + stats.nonEmptyDeltas()
                + " empty=" + stats.emptyTicks()
                + " keyframes=" + stats.keyframes()
                + " changes=" + stats.totalChanges()
                + " seekVerified=" + seekOk
                + " file=" + (written != null ? written.getFileName() : "none"));
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
        gameplayEvents.unregister();
        effectTap.stop();
        ReplayEffectIngress.unbind();
        clientPoseBridge.endRecording();
        swingSampler.clear();
        sideChannel.clear();
        if (ending.deltaPipeline != null) {
            ending.deltaPipeline.close();
            ending.deltaPipeline.deleteQuietly();
        }
        return ending.stats(false, false, null, false);
    }

    /**
     * Shuts down the encode worker. Call from plugin {@code onDisable}.
     */
    public void shutdown() {
        clientPoseBridge.unregister();
        encodeExecutor.shutdown();
        try {
            if (!encodeExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                encodeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            encodeExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void runEncodeJob(ReplayEncodeJob job, UUID cameraId, String name, Path outFile) {
        Path written = null;
        try {
            written = job.run();
            logger.info("Flashback zip written: " + outFile.toAbsolutePath());
        } catch (Exception e) {
            logger.severe("Flashback encode failed for '" + name + "': " + e.getMessage());
            e.printStackTrace();
        }
        Path result = written;
        Bukkit.getScheduler().runTask(plugin, () -> announceEncodeResult(cameraId, name, result));
    }

    private Path runEncodeJobSync(ReplayEncodeJob job, Path outFile) {
        try {
            Path written = job.run();
            logger.info("Flashback zip written: " + outFile.toAbsolutePath());
            return written;
        } catch (Exception e) {
            logger.severe("Flashback encode failed: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private void announceEncodeResult(UUID cameraId, String name, Path written) {
        Player camera = Bukkit.getPlayer(cameraId);
        if (camera == null || !camera.isOnline()) {
            if (written == null) {
                logger.severe("Flashback encode failed for '" + name + "' (camera offline)");
            }
            return;
        }
        if (written != null) {
            camera.sendMessage("Flashback zip ready: " + written.toAbsolutePath());
            camera.sendMessage("Open with Minecraft/Flashback matching this server ("
                    + platform.adapter().versionString() + ").");
        } else {
            camera.sendMessage("Flashback encode failed for '" + name + "' — check server log.");
        }
    }

    private boolean verifySeek(ReplayDocument document, GlobalSnapshot initial, GlobalSnapshot previous) {
        List<DeltaFrame> deltas = document.deltas();
        if (deltas.isEmpty() && document.deltaSpill() != null) {
            try {
                List<DeltaFrame> loaded = new ArrayList<>();
                try (DeltaSpillFile.Reader reader = DeltaSpillFile.open(document.deltaSpill())) {
                    for (DeltaFrame frame : reader) {
                        loaded.add(frame);
                    }
                }
                deltas = loaded;
            } catch (Exception e) {
                logger.warning("Seek verify could not read delta spill: " + e.getMessage());
                return false;
            }
        }
        if (deltas.isEmpty() && document.keyframes().isEmpty()) {
            return true;
        }
        try {
            GlobalSnapshot reconstructed = SnapshotApplier.seek(
                    initial, document.keyframes(), deltas, previous.tick());
            boolean ok = snapshotContentEquals(reconstructed, previous);
            if (!ok) {
                logger.warning("Seek verify FAILED at tick " + previous.tick());
            }
            return ok;
        } catch (Exception e) {
            logger.warning("Seek verify error: " + e.getMessage());
            return false;
        }
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
        private final ReplayDocument.Builder document;
        private final int startTick;
        private AsyncDeltaPipeline deltaPipeline;

        private GlobalSnapshot previous;
        private int lastKeyframeTick;
        private int ticksRecorded;
        private int nonEmptyDeltas;
        private int emptyTicks;
        private int keyframes;
        private long totalChanges;
        /** Reused each tick on the main thread to avoid empty-tick ArrayList churn. */
        private final List<StateChange> silentBlocksScratch = new ArrayList<>();
        private final ArrayList<StateChange> mergeScratch = new ArrayList<>();
        private BukkitTask task;

        private Session(
                String name,
                RecordingOptions options,
                GlobalSnapshot initial,
                ReplayDocument.Builder document,
                int startTick
        ) {
            this.name = name;
            this.options = options;
            this.initial = initial;
            this.document = document;
            this.startTick = startTick;
            this.previous = initial;
            this.lastKeyframeTick = startTick;
            if (options.spillDeltas()) {
                try {
                    Path spill = plugin.getDataFolder().toPath()
                            .resolve("replays")
                            .resolve(".spill")
                            .resolve(sanitize(name) + "-" + startTick + ".deltas");
                    this.deltaPipeline = AsyncDeltaPipeline.start(spill, logger);
                } catch (Exception e) {
                    logger.warning("Delta spill disabled (failed to start): " + e.getMessage());
                    this.deltaPipeline = null;
                }
            }
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
                    document.noteDeltaTick(tick);
                    if (deltaPipeline != null) {
                        try {
                            deltaPipeline.offer(frame);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            logger.severe("Interrupted while offering delta to spill");
                        }
                    }
                    // Keep in-memory deltas only when seek verify needs them (or spill unavailable).
                    if (options.verifySeekOnStop() || deltaPipeline == null) {
                        document.addDelta(frame);
                    }
                }

                if (keyframeDue) {
                    keyframes++;
                    lastKeyframeTick = tick;
                    // H7: full GlobalSnapshot keyframes only when seek verify is enabled.
                    if (options.verifySeekOnStop()) {
                        document.addKeyframe(new Keyframe(tick, current));
                    }
                }

                previous = current;
            } catch (Exception e) {
                logger.severe("Recording tick failed: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private RecordingStats stats(
                boolean recording,
                boolean seekVerified,
                Path outputFile,
                boolean encodePending
        ) {
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
                    outputFile,
                    encodePending
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
