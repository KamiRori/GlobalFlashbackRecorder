package com.globalflashback.nms.v26_2;

import com.globalflashback.format.ReplayAction;
import com.globalflashback.nms.PocSnapshotSupport;
import org.bukkit.entity.Player;

import java.util.List;

final class PocSnapshotSupport26_2 implements PocSnapshotSupport {
    @Override
    public List<ReplayAction> build(Player cameraPlayer, List<Player> onlinePlayers) {
        return SnapshotBuilder26_2.build(cameraPlayer, onlinePlayers);
    }

    @Override
    public int protocolVersion() {
        return SnapshotBuilder26_2.protocolVersion();
    }

    @Override
    public int dataVersion() {
        return SnapshotBuilder26_2.dataVersion();
    }

    @Override
    public String versionString() {
        return SnapshotBuilder26_2.versionString();
    }
}
