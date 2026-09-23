package com.globalflashback.nms;

import com.globalflashback.capture.RecordingSideChannel;
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
     */
    ReplayEncodeJob prepareEncodeJob(ReplayDocument document, Player camera, Path outputFile);

    PocSnapshotSupport pocSnapshots();

    EffectOutboundTap createEffectTap(Plugin plugin, RecordingSideChannel sideChannel);
}
