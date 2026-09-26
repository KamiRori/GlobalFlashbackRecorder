package com.globalflashback.event;

/**
 * On-disk layout for Gameplay Event Timeline inside a Flashback replay zip.
 *
 * <p><b>Not authoritative:</b> player-to-player item “gift/share” cannot be proven server-side.
 * Plugin records only {@code ITEM_DROP} / {@code ITEM_PICKUP}; Auto Director may <em>infer</em>
 * a possible share later. Never emit {@code ITEM_TRANSFER} from this format.
 */
public final class GfrEventsFormat {
    public static final String FORMAT_ID = "gfr_events";
    public static final int VERSION = 1;
    public static final String ZIP_ENTRY = "gfr/events.json";
    /** Event {@code tick} is {@code Bukkit.getCurrentTick() - recordingStartTick}. */
    public static final String TICK_AXIS_RECORDING_RELATIVE = "recording_relative";

    private GfrEventsFormat() {}
}
