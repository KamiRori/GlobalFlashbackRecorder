package com.globalflashback.nms;

import com.globalflashback.state.MetadataBlob;
import com.globalflashback.state.ReplayMath;

/**
 * One block cell change discovered by comparing live chunk contents to a block-id cache.
 */
public record ChunkBlockDelta(
        ReplayMath.BlockPos pos,
        String blockId,
        MetadataBlob payload
) {
    public ChunkBlockDelta {
        if (blockId == null) {
            blockId = "";
        }
        if (payload == null) {
            payload = MetadataBlob.EMPTY;
        }
    }
}
