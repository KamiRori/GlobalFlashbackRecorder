package com.globalflashback.state;

import java.util.Objects;
import java.io.Serializable;

/**
 * Stable identity for a dimension in Replay data (not a Bukkit World reference).
 */
public record DimensionId(String namespacedKey) implements Serializable {
    public DimensionId {
        Objects.requireNonNull(namespacedKey, "namespacedKey");
        if (namespacedKey.isBlank()) {
            throw new IllegalArgumentException("namespacedKey must not be blank");
        }
    }

    public static DimensionId of(String namespacedKey) {
        return new DimensionId(namespacedKey);
    }
}
