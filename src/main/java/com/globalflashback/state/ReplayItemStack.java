package com.globalflashback.state;

import java.util.Arrays;
import java.util.Objects;
import java.io.Serializable;

/**
 * Replay-safe item stack. Opaque {@code componentsPayload} holds version-specific
 * serialized item components when Capture provides them; may be empty for Phase 2 stubs.
 *
 * <p>{@link #contentHash()} is a capture-side fast fingerprint ({@code ItemStack.hashItemAndComponents}
 * style) used to reuse an immutable previous instance without re-encoding. It is intentionally
 * excluded from {@link #equals}/{@link #hashCode}.
 */
public record ReplayItemStack(
        String itemId,
        int count,
        byte[] componentsPayload,
        int contentHash
) implements Serializable {
    private static final byte[] EMPTY_BYTES = new byte[0];

    public static final ReplayItemStack EMPTY = new ReplayItemStack("minecraft:air", 0, EMPTY_BYTES, 0);

    public ReplayItemStack {
        Objects.requireNonNull(itemId, "itemId");
        if (componentsPayload == null || componentsPayload.length == 0) {
            componentsPayload = EMPTY_BYTES;
        }
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
    }

    /** Convenience for callers that do not track a content hash. */
    public ReplayItemStack(String itemId, int count, byte[] componentsPayload) {
        this(itemId, count, componentsPayload, 0);
    }

    public boolean isEmpty() {
        return count <= 0 || "minecraft:air".equals(itemId);
    }

    /**
     * Returns the owned payload. Callers must not mutate the array.
     */
    @Override
    public byte[] componentsPayload() {
        return componentsPayload;
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
