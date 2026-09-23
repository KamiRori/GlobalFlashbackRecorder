package com.globalflashback.poc;

import com.globalflashback.format.ChunkMeta;
import com.globalflashback.format.ChunkWriter;
import com.globalflashback.format.FlashbackContainer;
import com.globalflashback.format.FlashbackMeta;
import com.globalflashback.format.ReplayAction;
import com.globalflashback.nms.PocSnapshotSupport;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Phase-1 POC: capture current server-visible state once and write a minimal Flashback replay.
 *
 * <p>Flashback client only lists/opens files ending in {@code .zip}. The archive content is still
 * a ZIP containing {@code metadata.json} and inner {@code cN.flashback} chunk streams.
 *
 * <p>Not a full Global Recorder — only validates Flashback encoding from server state.
 */
public final class PocReplayExporter {
    /** Short idle timeline so the file is not zero-duration. */
    private static final int POC_DURATION_TICKS = 40;

    private final JavaPlugin plugin;
    private final PocSnapshotSupport snapshots;

    public PocReplayExporter(JavaPlugin plugin, PocSnapshotSupport snapshots) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    public Path export(Player camera, String replayName) throws IOException {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("POC export must run on the main thread");
        }

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) {
            throw new IllegalStateException("Need at least one online player for POC");
        }

        List<ReplayAction> snapshot = snapshots.build(camera, online);

        List<ReplayAction> stream = new ArrayList<>(POC_DURATION_TICKS);
        for (int i = 0; i < POC_DURATION_TICKS; i++) {
            stream.add(ReplayAction.nextTick());
        }

        byte[] chunkBytes = ChunkWriter.write(snapshot, stream);

        FlashbackMeta meta = new FlashbackMeta();
        meta.name = replayName;
        meta.versionString = snapshots.versionString();
        meta.worldName = camera.getWorld().getName();
        meta.dataVersion = snapshots.dataVersion();
        meta.protocolVersion = snapshots.protocolVersion();
        meta.totalTicks = POC_DURATION_TICKS;
        // Inner entry names still use ".flashback" (Flashback chunk stream naming).
        meta.chunks.put("c0.flashback", new ChunkMeta(POC_DURATION_TICKS, true));

        Path outDir = plugin.getDataFolder().toPath().resolve("poc");
        // Outer file MUST be .zip — Flashback ReplaySelectionList / open path only accept .zip.
        Path outFile = outDir.resolve(sanitize(replayName) + ".zip");

        try (FlashbackContainer.Writer writer = FlashbackContainer.create(outFile)) {
            writer.writeMetadata(meta);
            writer.writeChunk("c0.flashback", chunkBytes);
        }

        plugin.getLogger().info(
                "POC replay written for protocol_version=" + meta.protocolVersion
                        + " data_version=" + meta.dataVersion
                        + " version_string=" + meta.versionString
                        + ". Open ONLY with a matching Minecraft client + Flashback build."
        );

        return outFile;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
