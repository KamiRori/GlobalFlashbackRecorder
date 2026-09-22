package com.globalflashback.command;

import com.globalflashback.capture.CaptureOptions;
import com.globalflashback.capture.ServerStateCapture;
import com.globalflashback.nms.NmsAdapter;
import com.globalflashback.poc.PocReplayExporter;
import com.globalflashback.recorder.GlobalReplayRecorder;
import com.globalflashback.recorder.RecordingOptions;
import com.globalflashback.recorder.RecordingStats;
import com.globalflashback.state.GlobalSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GfrCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final PocReplayExporter pocExporter;
    private final ServerStateCapture stateCapture;
    private final NmsAdapter nmsAdapter;
    private final GlobalReplayRecorder recorder;

    public GfrCommand(
            JavaPlugin plugin,
            PocReplayExporter pocExporter,
            ServerStateCapture stateCapture,
            NmsAdapter nmsAdapter,
            GlobalReplayRecorder recorder
    ) {
        this.plugin = plugin;
        this.pocExporter = pocExporter;
        this.stateCapture = stateCapture;
        this.nmsAdapter = nmsAdapter;
        this.recorder = recorder;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /gfr <poc|capture|record> ...");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "poc" -> handlePoc(sender, args);
            case "capture" -> handleCapture(sender, args);
            case "record" -> handleRecord(sender, args);
            default -> {
                sender.sendMessage("Unknown subcommand. Usage: /gfr <poc|capture|record>");
                yield true;
            }
        };
    }

    private boolean handlePoc(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("POC must be run by an in-game player (camera ego).");
            return true;
        }

        String name = args.length >= 2 ? args[1] : ("poc_" + System.currentTimeMillis());
        try {
            Path file = pocExporter.export(player, name);
            int players = plugin.getServer().getOnlinePlayers().size();
            sender.sendMessage("POC written: " + file.toAbsolutePath());
            sender.sendMessage("Online players encoded: " + players
                    + " (camera=" + player.getName() + ").");
            sender.sendMessage("IMPORTANT: open with Minecraft/Flashback matching this server version (Paper 26.2).");
        } catch (Exception e) {
            plugin.getLogger().severe("POC export failed: " + e.getMessage());
            e.printStackTrace();
            sender.sendMessage("POC export failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return true;
    }

    private boolean handleCapture(CommandSender sender, String[] args) {
        int radius = CaptureOptions.DEFAULT.chunkRadiusAroundPlayers();
        if (args.length >= 2) {
            try {
                radius = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("Invalid radius: " + args[1]);
                return true;
            }
        }

        CaptureOptions options = new CaptureOptions(radius, true);
        int tick = Bukkit.getCurrentTick();
        try {
            GlobalSnapshot snapshot = stateCapture.capture(tick, options);
            sender.sendMessage("Captured GlobalSnapshot at tick " + tick);
            sender.sendMessage(" players=" + snapshot.players().size()
                    + " entities=" + snapshot.entities().size()
                    + " chunks=" + snapshot.chunks().size()
                    + " worlds=" + snapshot.worlds().size());
            sender.sendMessage(" protocol=" + nmsAdapter.protocolVersion()
                    + " dataVersion=" + nmsAdapter.dataVersion()
                    + " version=" + nmsAdapter.versionString());
            plugin.getLogger().info("ServerStateCapture ok: players=" + snapshot.players().size()
                    + " entities=" + snapshot.entities().size()
                    + " chunks=" + snapshot.chunks().size());
        } catch (Exception e) {
            plugin.getLogger().severe("Capture failed: " + e.getMessage());
            e.printStackTrace();
            sender.sendMessage("Capture failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return true;
    }

    private boolean handleRecord(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /gfr record <start|stop|status> ...");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "start" -> handleRecordStart(sender, args);
            case "stop" -> handleRecordStop(sender);
            case "status" -> handleRecordStatus(sender);
            default -> {
                sender.sendMessage("Usage: /gfr record <start|stop|status> ...");
                yield true;
            }
        };
    }

    /**
     * /gfr record start [name] [keyframeIntervalTicks] [chunkRadius]
     */
    private boolean handleRecordStart(CommandSender sender, String[] args) {
        if (recorder.isRecording()) {
            sender.sendMessage("Already recording. Use /gfr record stop first.");
            return true;
        }

        String name = args.length >= 3 ? args[2] : ("rec_" + System.currentTimeMillis());
        int keyframeInterval = RecordingOptions.DEFAULT.keyframeIntervalTicks();
        int radius = CaptureOptions.DEFAULT.chunkRadiusAroundPlayers();

        if (args.length >= 4) {
            try {
                keyframeInterval = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage("Invalid keyframeInterval: " + args[3]);
                return true;
            }
        }
        if (args.length >= 5) {
            try {
                radius = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                sender.sendMessage("Invalid chunkRadius: " + args[4]);
                return true;
            }
        }

        RecordingOptions options = new RecordingOptions(keyframeInterval, new CaptureOptions(radius, true));
        try {
            boolean started = recorder.start(name, options);
            if (!started) {
                sender.sendMessage("Failed to start (already recording).");
                return true;
            }
            sender.sendMessage("Recording started: " + name);
            sender.sendMessage(" keyframeInterval=" + keyframeInterval + " chunkRadius=" + radius);
            sender.sendMessage(" Use /gfr record status | /gfr record stop");
        } catch (Exception e) {
            plugin.getLogger().severe("Record start failed: " + e.getMessage());
            e.printStackTrace();
            sender.sendMessage("Record start failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return true;
    }

    private boolean handleRecordStop(CommandSender sender) {
        if (!recorder.isRecording()) {
            sender.sendMessage("Not recording.");
            return true;
        }
        if (!(sender instanceof Player camera)) {
            sender.sendMessage("Record stop (encode) must be run by an in-game player (camera ego).");
            return true;
        }
        try {
            RecordingStats stats = recorder.stop(camera);
            sender.sendMessage("Recording stopped: " + stats.name());
            sender.sendMessage(" ticks=" + stats.ticksRecorded()
                    + " deltas=" + stats.nonEmptyDeltas()
                    + " empty=" + stats.emptyTicks()
                    + " keyframes=" + stats.keyframes()
                    + " changes=" + stats.totalChanges());
            sender.sendMessage(" last players=" + stats.lastPlayers()
                    + " entities=" + stats.lastEntities()
                    + " chunks=" + stats.lastChunks());
            sender.sendMessage(" seekVerified=" + stats.seekVerified());
            if (stats.outputFile() != null) {
                sender.sendMessage(" Flashback zip: " + stats.outputFile().toAbsolutePath());
                sender.sendMessage(" Open with Minecraft/Flashback matching Paper 26.2.");
            } else {
                sender.sendMessage(" Encode failed — check server log.");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Record stop failed: " + e.getMessage());
            e.printStackTrace();
            sender.sendMessage("Record stop failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return true;
    }

    private boolean handleRecordStatus(CommandSender sender) {
        RecordingStats stats = recorder.status();
        if (!stats.recording()) {
            sender.sendMessage("Not recording.");
            return true;
        }
        sender.sendMessage("Recording: " + stats.name());
        sender.sendMessage(" ticks=" + stats.ticksRecorded()
                + " deltas=" + stats.nonEmptyDeltas()
                + " empty=" + stats.emptyTicks()
                + " keyframes=" + stats.keyframes()
                + " changes=" + stats.totalChanges());
        sender.sendMessage(" lastTick=" + stats.lastTick()
                + " players=" + stats.lastPlayers()
                + " entities=" + stats.lastEntities()
                + " chunks=" + stats.lastChunks());
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length == 1) {
            return filter(List.of("poc", "capture", "record"), args[0]);
        }
        if (args.length == 2 && "capture".equalsIgnoreCase(args[0])) {
            return filter(List.of("4", "8", "12"), args[1]);
        }
        if (args.length == 2 && "record".equalsIgnoreCase(args[0])) {
            return filter(List.of("start", "stop", "status"), args[1]);
        }
        if (args.length == 4 && "record".equalsIgnoreCase(args[0]) && "start".equalsIgnoreCase(args[1])) {
            return filter(List.of("20", "100", "600"), args[3]);
        }
        if (args.length == 5 && "record".equalsIgnoreCase(args[0]) && "start".equalsIgnoreCase(args[1])) {
            return filter(List.of("4", "8", "12"), args[4]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(option);
            }
        }
        return out;
    }
}
