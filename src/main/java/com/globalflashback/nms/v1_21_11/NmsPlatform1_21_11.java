package com.globalflashback.nms.v1_21_11;

import com.globalflashback.capture.RecordingSideChannel;
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

/** Paper / Minecraft 1.21.11 platform bundle. */
public final class NmsPlatform1_21_11 implements NmsPlatform {
    private final NmsAdapter adapter = new NmsAdapter1_21_11();
    private final EffectPacketEncoder effects = new EffectPacketFactory1_21_11();
    private final StateActionEncoder stateActionEncoder = new StateActionEncoder1_21_11();
    private final PocSnapshotSupport pocSnapshots = new PocSnapshotSupport1_21_11();

    @Override
    public String line() {
        return "1.21.11";
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
    public ReplayEncodeJob prepareEncodeJob(ReplayDocument document, Player camera, Path outputFile) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(outputFile, "outputFile");
        StateActionEncoder encoder = new StateActionEncoder1_21_11();
        StateActionEncoder.Session session = encoder.openSession(camera);
        FlashbackEncoder flashback = new FlashbackEncoder();
        return () -> flashback.encode(document, session, outputFile);
    }

    @Override
    public PocSnapshotSupport pocSnapshots() {
        return pocSnapshots;
    }

    @Override
    public EffectOutboundTap createEffectTap(Plugin plugin, RecordingSideChannel sideChannel) {
        return new EffectOutboundTap1_21_11(plugin, sideChannel);
    }
}
