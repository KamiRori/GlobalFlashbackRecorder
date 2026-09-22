package com.globalflashback.state;

import java.util.Arrays;
import java.util.Objects;

/**
 * Replay-safe item stack. Opaque {@code componentsPayload} holds version-specific
 * serialized item components when Capture provides them; may be empty for Phase 2 stubs.
 */
public record ReplayItemStack(
        String itemId,
        int count,
        byte[] componentsPayload
) {
    public static final ReplayItemStack EMPTY = new ReplayItemStack("minecraft:air", 0, new byte[0]);

    public ReplayItemStack {
        Objects.requireNonNull(itemId, "itemId");
        componentsPayload = componentsPayload == null ? new byte[0] : componentsPayload.clone();
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
    }

    public boolean isEmpty() {
        return count <= 0 || "minecraft:air".equals(itemId);
    }

    @Override
    public byte[] componentsPayload() {
        return componentsPayload.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReplayItemStack that)) return false;
        return count == that.count
                && itemId.equals(that.itemId)
                && Arrays.equals(componentsPayload, that.componentsPayload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(itemId, count);
        result = 31 * result + Arrays.hashCode(componentsPayload);
        return result;
    }
}
