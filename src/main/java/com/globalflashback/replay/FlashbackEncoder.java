package com.globalflashback.replay;

import com.globalflashback.delta.DeltaFrame;
import com.globalflashback.format.ChunkMeta;
import com.globalflashback.format.ChunkWriter;
import com.globalflashback.format.FlashbackContainer;
import com.globalflashback.format.FlashbackMeta;
import com.globalflashback.format.ReplayAction;
import com.globalflashback.nms.v26_2.StateActionEncoder26_2;
import com.globalflashback.state.GlobalSnapshot;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Encodes a logical {@link ReplayDocument} into a Flashback-compatible outer {@code .zip}.
 *
 * <p>Linear playback uses a single initial snapshot on {@code c0} plus a tick stream.
 * In-memory recording Keyframes are <em>not</em> written as extra ZIP chunks with
 * {@code forcePlaySnapshot} — that re-applied login/world every keyframe and caused a visible
 * flash to the chunk-start state about once per second.
 *
 * <p>Rollover at 6000 ticks uses an empty snapshot and {@code forcePlaySnapshot=false}.
 */
public final class FlashbackEncoder {
    /** Flashback default max chunk length: 300s × 20 TPS. */
    private static final int MAX_CHUNK_TICKS = 6000;

    private final StateActionEncoder26_2 actionEncoder = new StateActionEncoder26_2();

    public Path encode(ReplayDocument document, Player camera, Path outputFile) throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(outputFile, "outputFile");

        ReplayMetadata meta = document.metadata();
        GlobalSnapshot initial = document.initialSnapshot();
        int startTick = initial.tick();
        int endTick = startTick + Math.max(0, meta.totalTicks());
        for (DeltaFrame frame : document.deltas()) {
            endTick = Math.max(endTick, frame.tick());
        }

        Map<Integer, DeltaFrame> deltasByTick = new HashMap<>();
        for (DeltaFrame frame : document.deltas()) {
            deltasByTick.put(frame.tick(), frame);
        }

        FlashbackMeta flashMeta = new FlashbackMeta();
        flashMeta.uuid = meta.replayId().toString();
        flashMeta.name = meta.name();
        flashMeta.versionString = meta.versionString();
        flashMeta.worldName = meta.worldName();
        flashMeta.dataVersion = meta.dataVersion();
        flashMeta.protocolVersion = meta.protocolVersion();
        flashMeta.totalTicks = Math.max(0, endTick - startTick);

        List<ReplayAction> initialSnapshotActions = actionEncoder.encodeSnapshot(camera, initial);

        try (FlashbackContainer.Writer writer = FlashbackContainer.create(outputFile)) {
            int chunkIndex = 0;
            int cursor = startTick;
            while (cursor < endTick || chunkIndex == 0) {
                int segmentEnd = Math.min(endTick, cursor + MAX_CHUNK_TICKS);
                boolean first = chunkIndex == 0;
                List<ReplayAction> snapshot = first ? initialSnapshotActions : List.of();
                List<ReplayAction> stream = buildStream(deltasByTick, camera, cursor, segmentEnd);
                int duration = Math.max(0, segmentEnd - cursor);

                String chunkName = "c" + chunkIndex + ".flashback";
                writer.writeChunk(chunkName, ChunkWriter.write(snapshot, stream));
                // Only c0 force-plays snapshot (seek into start). Later chunks continue the stream.
                flashMeta.chunks.put(chunkName, new ChunkMeta(duration, first));
                chunkIndex++;

                if (segmentEnd >= endTick) {
                    break;
                }
                cursor = segmentEnd;
            }

            if (flashMeta.chunks.isEmpty()) {
                writer.writeChunk("c0.flashback", ChunkWriter.write(initialSnapshotActions, List.of()));
                flashMeta.chunks.put("c0.flashback", new ChunkMeta(0, true));
            }

            writer.writeMetadata(flashMeta);
        }

        return outputFile;
    }

    /**
     * Emits deltas + {@code next_tick} for ticks {@code (segmentStart, segmentEnd]}.
     */
    private List<ReplayAction> buildStream(
            Map<Integer, DeltaFrame> deltasByTick,
            Player camera,
            int segmentStart,
            int segmentEnd
    ) {
        List<ReplayAction> streamActions = new ArrayList<>();
        for (int tick = segmentStart + 1; tick <= segmentEnd; tick++) {
            DeltaFrame frame = deltasByTick.get(tick);
            if (frame != null && !frame.isEmpty()) {
                streamActions.addAll(actionEncoder.encodeChanges(camera, frame.changes()));
            }
            streamActions.add(ReplayAction.nextTick());
        }
        return streamActions;
    }
}
