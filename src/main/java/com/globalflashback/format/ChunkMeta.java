package com.globalflashback.format;

import com.google.gson.annotations.SerializedName;

public final class ChunkMeta {
    @SerializedName("duration")
    public int duration;

    @SerializedName("forcePlaySnapshot")
    public boolean forcePlaySnapshot;

    public ChunkMeta() {}

    public ChunkMeta(int duration, boolean forcePlaySnapshot) {
        this.duration = duration;
        this.forcePlaySnapshot = forcePlaySnapshot;
    }
}
