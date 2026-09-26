package com.globalflashback.nms;

/**
 * Netty-thread arrival timestamps for {@code gfr:client_pose} only.
 * Not a capture source — timing instrumentation for Client Pose rebasing.
 */
public interface ClientPoseArrivalTap {
    void start();

    void stop();
}
