package com.globalflashback.replay;

import java.nio.file.Path;

/**
 * Flashback ZIP encode job prepared on the main thread, safe to {@link #run()} off-thread.
 *
 * <p>Must not touch Bukkit {@code World}/{@code Entity} or live players. Registry bootstrap and
 * camera ego packets are frozen at prepare time.
 */
@FunctionalInterface
public interface ReplayEncodeJob {
    /**
     * Encodes the sealed document to the output path (compression + disk IO).
     *
     * @return the written file path
     */
    Path run() throws Exception;
}
