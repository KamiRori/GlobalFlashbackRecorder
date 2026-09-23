package com.globalflashback.nms;

import com.globalflashback.state.MetadataBlob;

/**
 * Result of encoding a player-opened container inventory as a BlockEntityData packet payload.
 */
public record ContainerBlockEntityCapture(String typeId, MetadataBlob packetPayload) {
    public static final ContainerBlockEntityCapture EMPTY =
            new ContainerBlockEntityCapture("", MetadataBlob.EMPTY);

    public ContainerBlockEntityCapture {
        typeId = typeId == null ? "" : typeId;
        packetPayload = packetPayload == null ? MetadataBlob.EMPTY : packetPayload;
    }

    public boolean isEmpty() {
        return packetPayload.isEmpty();
    }
}
