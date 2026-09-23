package com.globalflashback.replay;

import com.globalflashback.delta.DeltaFrame;
import com.globalflashback.delta.Keyframe;
import com.globalflashback.event.GameplayEvent;
import com.globalflashback.state.GlobalSnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable logical Global Replay document (SPEC §31).
 *
 * <p>Deltas may live in-memory ({@link #deltas()}) and/or on an async spill file
 * ({@link #deltaSpill()}) for long recordings (H11). Encoder prefers the spill when present.
 */
public record ReplayDocument(
        ReplayMetadata metadata,
        GlobalSnapshot initialSnapshot,
        List<Keyframe> keyframes,
        List<DeltaFrame> deltas,
        List<GameplayEvent> events,
        ReplayIndex index,
        Path deltaSpill
) {
    public ReplayDocument {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        keyframes = List.copyOf(keyframes == null ? List.of() : keyframes);
        deltas = List.copyOf(deltas == null ? List.of() : deltas);
        events = List.copyOf(events == null ? List.of() : events);
        index = index == null ? new ReplayIndex() : index;
    }

    public static Builder builder(ReplayMetadata metadata, GlobalSnapshot initialSnapshot) {
        return new Builder(metadata, initialSnapshot);
    }

    public static final class Builder {
        private ReplayMetadata metadata;
        private final GlobalSnapshot initialSnapshot;
        private final List<Keyframe> keyframes = new ArrayList<>();
        private final List<DeltaFrame> deltas = new ArrayList<>();
        private final List<GameplayEvent> events = new ArrayList<>();
        private final ReplayIndex index = new ReplayIndex();
        private Path deltaSpill;
        private int lastDeltaTick = -1;

        private Builder(ReplayMetadata metadata, GlobalSnapshot initialSnapshot) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            this.initialSnapshot = Objects.requireNonNull(initialSnapshot, "initialSnapshot");
            this.index.addKeyframe(initialSnapshot.tick());
            this.lastDeltaTick = initialSnapshot.tick();
        }

        public Builder metadata(ReplayMetadata metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            return this;
        }

        public ReplayMetadata metadata() {
            return metadata;
        }

        public Builder deltaSpill(Path deltaSpill) {
            this.deltaSpill = deltaSpill;
            return this;
        }

        public Builder noteDeltaTick(int tick) {
            lastDeltaTick = Math.max(lastDeltaTick, tick);
            return this;
        }

        public Builder addKeyframe(Keyframe keyframe) {
            keyframes.add(Objects.requireNonNull(keyframe, "keyframe"));
            index.addKeyframe(keyframe.tick());
            lastDeltaTick = Math.max(lastDeltaTick, keyframe.tick());
            return this;
        }

        public Builder addDelta(DeltaFrame frame) {
            deltas.add(Objects.requireNonNull(frame, "frame"));
            lastDeltaTick = Math.max(lastDeltaTick, frame.tick());
            return this;
        }

        public Builder addEvent(GameplayEvent event) {
            events.add(Objects.requireNonNull(event, "event"));
            return this;
        }

        public ReplayDocument build() {
            int lastTick = Math.max(initialSnapshot.tick(), lastDeltaTick);
            for (DeltaFrame delta : deltas) {
                lastTick = Math.max(lastTick, delta.tick());
            }
            for (Keyframe keyframe : keyframes) {
                lastTick = Math.max(lastTick, keyframe.tick());
            }
            int duration = Math.max(0, lastTick - initialSnapshot.tick());
            ReplayMetadata finalized = metadata.withTotalTicks(Math.max(metadata.totalTicks(), duration));
            return new ReplayDocument(
                    finalized, initialSnapshot, keyframes, deltas, events, index, deltaSpill);
        }
    }
}
