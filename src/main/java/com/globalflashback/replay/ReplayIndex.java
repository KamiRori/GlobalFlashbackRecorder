package com.globalflashback.replay;

import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Seek index: tick → nearest keyframe tick / delta span hints.
 *
 * <p>Phase 2 provides structure only; Phase 4/5 will populate during recording/encoding.
 */
public final class ReplayIndex {
    private final TreeMap<Integer, Integer> keyframeTicksByTick = new TreeMap<>();

    public void addKeyframe(int tick) {
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be >= 0");
        }
        keyframeTicksByTick.put(tick, tick);
    }

    /**
     * @return the greatest keyframe tick {@code <= targetTick}, or empty if none
     */
    public java.util.OptionalInt findKeyframeAtOrBefore(int targetTick) {
        var entry = keyframeTicksByTick.floorEntry(targetTick);
        return entry == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(entry.getKey());
    }

    public SortedMap<Integer, Integer> keyframesView() {
        return java.util.Collections.unmodifiableSortedMap(keyframeTicksByTick);
    }

    public boolean isEmpty() {
        return keyframeTicksByTick.isEmpty();
    }
}
