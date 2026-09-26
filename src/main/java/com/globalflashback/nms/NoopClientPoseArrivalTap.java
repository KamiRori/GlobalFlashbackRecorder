package com.globalflashback.nms;

/** No-op when a platform does not ship an inbound timing tap. */
public enum NoopClientPoseArrivalTap implements ClientPoseArrivalTap {
    INSTANCE;

    @Override
    public void start() {}

    @Override
    public void stop() {}
}
