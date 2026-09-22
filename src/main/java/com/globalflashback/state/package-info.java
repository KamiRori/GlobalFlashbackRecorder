/**
 * Phase 2: immutable Global Replay state model.
 *
 * <ul>
 *   <li>Server-authoritative fields only (no client-visible / fake state).</li>
 *   <li>No Bukkit/NMS object references — safe for async queues.</li>
 *   <li>Flashback packet encoding stays in {@code format}/Encoder layers.</li>
 * </ul>
 */
package com.globalflashback.state;
