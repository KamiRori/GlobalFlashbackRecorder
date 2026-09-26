package com.globalflashback.nms.v26_2;

import com.globalflashback.capture.RecordingSideChannel;
import com.globalflashback.motion.ClientPoseArrivalStamps;
import com.globalflashback.nms.ClientPoseArrivalTap;
import com.globalflashback.nms.EffectOutboundTap;
import com.globalflashback.nms.EffectPacketEncoder;
import com.globalflashback.nms.NmsAdapter;
import com.globalflashback.nms.NmsPlatform;
import com.globalflashback.nms.PocSnapshotSupport;
import com.globalflashback.nms.StateActionEncoder;
import com.globalflashback.replay.FlashbackEncoder;
import com.globalflashback.replay.ReplayDocument;
import com.globalflashback.replay.ReplayEncodeJob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.Objects;

/** Paper / Minecraft 26.2 platform bundle. */
public final class NmsPlatform26_2 implements NmsPlatform {
    private final NmsAdapter adapter = new NmsAdapter26_2();
    private final EffectPacketEncoder effects = new EffectPacketFactory26_2();
    private final StateActionEncoder stateActionEncoder = new StateActionEncoder26_2();
    private final PocSnapshotSupport pocSnapshots = new PocSnapshotSupport26_2();

    @Override
    public String line() {
        return "26.2";
    }

    @Override
    public NmsAdapter adapter() {
        return adapter;
    }

    @Override
    public EffectPacketEncoder effects() {
        return effects;
    }

    @Override
    public StateActionEncoder stateActionEncoder() {
        return stateActionEncoder;
    }

    @Override
    public ReplayEncodeJob prepareEncodeJob(
            ReplayDocument document,
            Player camera,
            Path outputFile,
            com.globalflashback.motion.format.ClientPoseStore.WrittenMotion clientPose
    ) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(outputFile, "outputFile");
        StateActionEncoder encoder = new StateActionEncoder26_2();
        StateActionEncoder.Session session = encoder.openSession(camera);
        FlashbackEncoder flashback = new FlashbackEncoder();
        return () -> flashback.encode(document, session, outputFile, clientPose);
    }

    @Override
    public PocSnapshotSupport pocSnapshots() {
        return pocSnapshots;
    }

    @Override
    public EffectOutboundTap createEffectTap(Plugin plugin, RecordingSideChannel sideChannel) {
        return new EffectOutboundTap26_2(plugin, sideChannel);
    }

    @Override
    public ClientPoseArrivalTap createClientPoseArrivalTap(Plugin plugin, ClientPoseArrivalStamps stamps) {
        return new ClientPoseArrivalTap26_2(plugin, stamps);
    }
}
