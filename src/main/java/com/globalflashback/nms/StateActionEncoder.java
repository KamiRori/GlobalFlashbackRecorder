package com.globalflashback.nms;

import com.globalflashback.delta.StateChange;
import com.globalflashback.format.ReplayAction;
import com.globalflashback.state.GlobalSnapshot;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Encodes logical Global Snapshot / Delta changes into Flashback {@link ReplayAction} packet streams.
 *
 * <p>{@link #openSession(Player)} <strong>must</strong> run on the main thread (reads live camera /
 * server registries for bootstrap). The returned {@link Session} may encode on a worker thread —
 * it must not access Bukkit World/Entity.
 */
public interface StateActionEncoder {
    /**
     * Main-thread only: freeze create_local_player / login / registry config packets and bind
     * {@link net.minecraft.core.RegistryAccess} for subsequent packet StreamCodec encodes.
     */
    Session openSession(Player camera);

    /** Convenience: {@code openSession(camera).encodeSnapshot(snapshot)} (main-thread). */
    default List<ReplayAction> encodeSnapshot(Player camera, GlobalSnapshot snapshot) {
        return openSession(camera).encodeSnapshot(snapshot);
    }

    /** Convenience: {@code openSession(camera).encodeChanges(changes)} (main-thread). */
    default List<ReplayAction> encodeChanges(Player camera, List<StateChange> changes) {
        return openSession(camera).encodeChanges(changes);
    }

    void seedTracking(GlobalSnapshot snapshot, UUID cameraUuid);

    /**
     * Bound encode session. Thread-safe only for a single encode job (not concurrent callers).
     */
    interface Session {
        UUID cameraUuid();

        List<ReplayAction> encodeSnapshot(GlobalSnapshot snapshot);

        List<ReplayAction> encodeChanges(List<StateChange> changes);
    }
}
