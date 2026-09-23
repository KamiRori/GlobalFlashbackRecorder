package com.globalflashback.nms;

/**
 * Loads the single {@link NmsPlatform} compiled into this jar.
 *
 * <p>Uses reflective lookup so shared code never hard-imports {@code nms.v26_2} or
 * {@code nms.v1_21_11} (Gradle excludes the inactive package per {@code -Pgfr.mc}).
 */
public final class NmsPlatforms {
    private static final String[] CANDIDATES = {
            "com.globalflashback.nms.v26_2.NmsPlatform26_2",
            "com.globalflashback.nms.v1_21_11.NmsPlatform1_21_11",
    };

    private NmsPlatforms() {}

    public static NmsPlatform create() {
        ClassNotFoundException lastMissing = null;
        for (String name : CANDIDATES) {
            try {
                Class<?> type = Class.forName(name);
                return (NmsPlatform) type.getDeclaredConstructor().newInstance();
            } catch (ClassNotFoundException e) {
                lastMissing = e;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to instantiate NMS platform " + name, e);
            }
        }
        throw new IllegalStateException(
                "No NmsPlatform implementation in this jar (expected v26_2 or v1_21_11)",
                lastMissing
        );
    }
}
