package com.globalflashback.replay;

import com.globalflashback.delta.DeltaFrame;
import com.globalflashback.event.GameplayEventJson;
import com.globalflashback.event.GfrEventsFormat;
import com.globalflashback.format.ChunkMeta;
import com.globalflashback.format.ChunkWriter;
import com.globalflashback.format.FlashbackContainer;
import com.globalflashback.format.FlashbackMeta;
import com.globalflashback.format.ReplayAction;
import com.globalflashback.motion.format.ClientPoseStore;
import com.globalflashback.motion.format.GfrMotionFormat;
import com.globalflashback.nms.StateActionEncoder;
import com.globalflashback.state.GlobalSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Encodes a logical {@link ReplayDocument} into a Flashback-compatible outer {@code .zip}.
 *
 * <p>Uses a {@link StateActionEncoder.Session} prepared on the main thread so {@link #encode}
 * may run on an async worker (compression + disk IO) without touching live Bukkit entities.
 *
 * <p>When {@link ReplayDocument#deltaSpill()} is set, deltas are streamed from disk (H11) instead
 * of loading the full list into a HashMap.
 */
public final class FlashbackEncoder {
    /** Flashback default max chunk length: 300s × 20 TPS. */
    private static final int MAX_CHUNK_TICKS = 6000;

    public FlashbackEncoder() {}

    public Path encode(
            ReplayDocument document,
            StateActionEncoder.Session session,
            Path outputFile
    ) throws IOException {
        return encode(document, session, outputFile, null);
    }

    public Path encode(
            ReplayDocument document,
            StateActionEncoder.Session session,
            Path outputFile,
            ClientPoseStore.WrittenMotion clientPose
    ) throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(outputFile, "outputFile");

        ReplayMetadata meta = document.metadata();
        GlobalSnapshot initial = document.initialSnapshot();
        int startTick = initial.tick();
        int endTick = startTick + Math.max(0, meta.totalTicks());

        for (DeltaFrame frame : document.deltas()) {
            endTick = Math.max(endTick, frame.tick());
        }

        FlashbackMeta flashMeta = new FlashbackMeta();
        flashMeta.uuid = meta.replayId().toString();
        flashMeta.name = meta.name();
        flashMeta.versionString = meta.versionString();
        flashMeta.worldName = meta.worldName();
        flashMeta.dataVersion = meta.dataVersion();
        flashMeta.protocolVersion = meta.protocolVersion();
        flashMeta.totalTicks = Math.max(0, endTick - startTick);

        List<ReplayAction> initialSnapshotActions = session.encodeSnapshot(initial);

        try (FlashbackContainer.Writer writer = FlashbackContainer.create(outputFile)) {
            if (document.deltaSpill() != null) {
                encodeWithSpill(document, session, writer, flashMeta, initialSnapshotActions,
                        startTick, endTick);
            } else {
                encodeWithMemoryDeltas(document, session, writer, flashMeta, initialSnapshotActions,
                        startTick, endTick);
            }

            if (flashMeta.chunks.isEmpty()) {
                writer.writeChunk("c0.flashback", ChunkWriter.write(initialSnapshotActions, List.of()));
                flashMeta.chunks.put("c0.flashback", new ChunkMeta(0, true));
            }

            if (clientPose != null) {
                writer.writeEntry(GfrMotionFormat.ZIP_META, clientPose.metaJson());
                writer.writeEntry(GfrMotionFormat.ZIP_BIN, clientPose.bin());
            }

            if (!document.events().isEmpty()) {
                // ZIP ticks must match Flashback/motion (recording-relative 0..totalTicks).
                writer.writeEntry(
                        GfrEventsFormat.ZIP_ENTRY,
                        GameplayEventJson.toBytes(document.events(), startTick));
            }

            writer.writeMetadata(flashMeta);
        }

        return outputFile;
    }

    private void encodeWithMemoryDeltas(
            ReplayDocument document,
            StateActionEncoder.Session session,
            FlashbackContainer.Writer writer,
            FlashbackMeta flashMeta,
            List<ReplayAction> initialSnapshotActions,
            int startTick,
            int endTick
    ) throws IOException {
        Map<Integer, DeltaFrame> deltasByTick = new HashMap<>();
        for (DeltaFrame frame : document.deltas()) {
            deltasByTick.put(frame.tick(), frame);
            endTick = Math.max(endTick, frame.tick());
        }
        flashMeta.totalTicks = Math.max(flashMeta.totalTicks, Math.max(0, endTick - startTick));

        int chunkIndex = 0;
        int cursor = startTick;
        while (cursor < endTick || chunkIndex == 0) {
            int segmentEnd = Math.min(endTick, cursor + MAX_CHUNK_TICKS);
            boolean first = chunkIndex == 0;
            List<ReplayAction> snapshot = first ? initialSnapshotActions : List.of();
            List<ReplayAction> stream = buildStreamFromMap(deltasByTick, session, cursor, segmentEnd);
            int duration = Math.max(0, segmentEnd - cursor);

            String chunkName = "c" + chunkIndex + ".flashback";
            writer.writeChunk(chunkName, ChunkWriter.write(snapshot, stream));
            flashMeta.chunks.put(chunkName, new ChunkMeta(duration, first));
            chunkIndex++;

            if (segmentEnd >= endTick) {
                break;
            }
            cursor = segmentEnd;
        }
    }

    private void encodeWithSpill(
            ReplayDocument document,
            StateActionEncoder.Session session,
            FlashbackContainer.Writer writer,
            FlashbackMeta flashMeta,
            List<ReplayAction> initialSnapshotActions,
            int startTick,
            int endTick
    ) throws IOException {
        // totalTicks already finalized from the recording session timeline.
        flashMeta.totalTicks = Math.max(0, endTick - startTick);

        try (DeltaSpillFile.Reader reader = DeltaSpillFile.open(document.deltaSpill())) {
            Iterator<DeltaFrame> it = reader.iterator();
            DeltaFrame pending = it.hasNext() ? it.next() : null;

            int chunkIndex = 0;
            int cursor = startTick;
            while (cursor < endTick || chunkIndex == 0) {
                int segmentEnd = Math.min(endTick, cursor + MAX_CHUNK_TICKS);
                boolean first = chunkIndex == 0;
                List<ReplayAction> snapshot = first ? initialSnapshotActions : List.of();
                List<ReplayAction> stream = new ArrayList<>();
                for (int tick = cursor + 1; tick <= segmentEnd; tick++) {
                    while (pending != null && pending.tick() < tick) {
                        pending = it.hasNext() ? it.next() : null;
                    }
                    if (pending != null && pending.tick() == tick) {
                        if (!pending.isEmpty()) {
                            stream.addAll(session.encodeChanges(pending.changes()));
                        }
                        pending = it.hasNext() ? it.next() : null;
                    }
                    stream.add(ReplayAction.nextTick());
                }
                int duration = Math.max(0, segmentEnd - cursor);
                String chunkName = "c" + chunkIndex + ".flashback";
                writer.writeChunk(chunkName, ChunkWriter.write(snapshot, stream));
                flashMeta.chunks.put(chunkName, new ChunkMeta(duration, first));
                chunkIndex++;
                if (segmentEnd >= endTick) {
                    break;
                }
                cursor = segmentEnd;
            }
        }
    }

    private List<ReplayAction> buildStreamFromMap(
            Map<Integer, DeltaFrame> deltasByTick,
            StateActionEncoder.Session session,
            int segmentStart,
            int segmentEnd
    ) {
        List<ReplayAction> streamActions = new ArrayList<>();
        for (int tick = segmentStart + 1; tick <= segmentEnd; tick++) {
            DeltaFrame frame = deltasByTick.get(tick);
            if (frame != null && !frame.isEmpty()) {
                streamActions.addAll(session.encodeChanges(frame.changes()));
            }
            streamActions.add(ReplayAction.nextTick());
        }
        return streamActions;
    }
}
