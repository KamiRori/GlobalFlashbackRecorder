package com.globalflashback;

import com.globalflashback.capture.ServerStateCapture;
import com.globalflashback.command.GfrCommand;
import com.globalflashback.nms.NmsAdapter;
import com.globalflashback.nms.v26_2.NmsAdapter26_2;
import com.globalflashback.poc.PocReplayExporter;
import com.globalflashback.recorder.GlobalReplayRecorder;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class GlobalFlashbackPlugin extends JavaPlugin {
    private GlobalReplayRecorder recorder;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        NmsAdapter nmsAdapter = new NmsAdapter26_2();
        ServerStateCapture stateCapture = new ServerStateCapture(nmsAdapter);
        PocReplayExporter pocExporter = new PocReplayExporter(this);
        recorder = new GlobalReplayRecorder(this, stateCapture, nmsAdapter);

        GfrCommand command = new GfrCommand(this, pocExporter, stateCapture, nmsAdapter, recorder);
        PluginCommand gfr = getCommand("gfr");
        if (gfr == null) {
            getLogger().severe("Command 'gfr' missing from plugin.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        gfr.setExecutor(command);
        gfr.setTabCompleter(command);
        getLogger().info("Ready. /gfr poc | capture | record <start|stop|status>");
    }

    @Override
    public void onDisable() {
        if (recorder != null && recorder.isRecording()) {
            try {
                recorder.stopWithoutEncode();
            } catch (Exception e) {
                getLogger().warning("Failed to stop recording on disable: " + e.getMessage());
            }
        }
    }
}
