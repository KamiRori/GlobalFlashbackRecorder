package com.globalflashback.state;

import java.util.Objects;

/**
 * Per-dimension world state that is not chunk/entity specific.
 *
 * <p>World border size uses vanilla lerp fields: {@link #worldBorderSize()} is the current
 * (possibly interpolated) diameter, {@link #worldBorderLerpTarget()} / {@link #worldBorderLerpTime()}
 * mirror {@code WorldBorder#getLerpTarget()} / {@code #getLerpTime()}. When {@code lerpTime == 0},
 * size and target are equal (static border).
 */
public record WorldState(
        DimensionId dimension,
        long gameTime,
        long dayTime,
        boolean raining,
        float rainLevel,
        float thunderLevel,
        double worldBorderCenterX,
        double worldBorderCenterZ,
        double worldBorderSize,
        double worldBorderLerpTarget,
        long worldBorderLerpTime,
        int seaLevel
) {
    public WorldState {
        Objects.requireNonNull(dimension, "dimension");
    }
}
