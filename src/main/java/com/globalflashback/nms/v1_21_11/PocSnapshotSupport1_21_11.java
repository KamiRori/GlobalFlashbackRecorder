package com.globalflashback.nms.v1_21_11;

import com.globalflashback.format.ReplayAction;
import com.globalflashback.nms.PocSnapshotSupport;
import org.bukkit.entity.Player;

import java.util.List;

final class PocSnapshotSupport1_21_11 implements PocSnapshotSupport {
    @Override
    public List<ReplayAction> build(Player cameraPlayer, List<Player> onlinePlayers) {
        return SnapshotBuilder1_21_11.build(cameraPlayer, onlinePlayers);
    }

    @Override
    public int protocolVersion() {
        return SnapshotBuilder1_21_11.protocolVersion();
    }

    @Override
    public int dataVersion() {
        return SnapshotBuilder1_21_11.dataVersion();
    }

    @Override
    public String versionString() {
        return SnapshotBuilder1_21_11.versionString();
    }
}
