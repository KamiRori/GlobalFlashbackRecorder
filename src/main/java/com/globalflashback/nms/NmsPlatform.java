package com.globalflashback.nms;

import com.globalflashback.capture.RecordingSideChannel;
import com.globalflashback.motion.format.ClientPoseStore;
import com.globalflashback.replay.ReplayDocument;
import com.globalflashback.replay.ReplayEncodeJob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;

/**
 * Bundles all version-isolated NMS services for one Paper / Minecraft line.
 *
 * <p>Each published jar ships exactly one platform implementation (see {@code -Pgfr.mc}).
 */
public interface NmsPlatform {
    String line();

    NmsAdapter adapter();

    EffectPacketEncoder effects();

    StateActionEncoder stateActionEncoder();

    /**
     * Main-thread only: freeze camera bootstrap / registries, return a job safe to run off-thread
     * (Flashback packet encode + ZIP compression + disk IO).
     *
     * @param clientPose optional GFR client-pose attachment written as {@code gfr/} ZIP entries
     */
    ReplayEncodeJob prepareEncodeJob(
            ReplayDocument document,
            Player camera,
            Path outputFile,
            ClientPoseStore.WrittenMotion clientPose
    );

    default ReplayEncodeJob prepareEncodeJob(ReplayDocument document, Player camera, Path outputFile) {
        return prepareEncodeJob(document, camera, outputFile, null);
    }

    PocSnapshotSupport pocSnapshots();

    EffectOutboundTap createEffectTap(Plugin plugin, RecordingSideChannel sideChannel);

    /**
     * Optional Netty inbound timing tap for {@code gfr:client_pose} arrival stamps.
     * Not a world-state capture path.
     */
    ClientPoseArrivalTap createClientPoseArrivalTap(Plugin plugin, com.globalflashback.motion.ClientPoseArrivalStamps stamps);
}
