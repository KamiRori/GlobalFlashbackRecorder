package com.globalflashback.nms;

import com.globalflashback.format.ReplayAction;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Phase-1 POC helpers for building a one-shot Flashback snapshot from online players.
 */
public interface PocSnapshotSupport {
    List<ReplayAction> build(Player cameraPlayer, List<Player> onlinePlayers);

    int protocolVersion();

    int dataVersion();

    String versionString();
}
