package com.globalflashback.format;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Clean-room writer for a single {@code cN.flashback} binary chunk stream.
 *
 * <p>Layout (verified against Flashback format documentation):
 * magic → action registry → snapshot(size+actions) → stream actions.
 */
public final class ChunkWriter {
    /** Confirmed Flashback magic: {@code 0xD780E884}. */
    public static final int MAGIC = 0xD780E884;

    private ChunkWriter() {}

    public static byte[] write(List<ReplayAction> snapshotActions, List<ReplayAction> streamActions)
            throws IOException {
        LinkedHashMap<String, Integer> registry = new LinkedHashMap<>();
        for (ReplayAction action : snapshotActions) {
            registry.computeIfAbsent(action.identifier(), ignored -> registry.size());
        }
        for (ReplayAction action : streamActions) {
            registry.computeIfAbsent(action.identifier(), ignored -> registry.size());
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);

        dos.writeInt(MAGIC);
        VarCodec.writeVarInt(dos, registry.size());
        for (String identifier : registry.keySet()) {
            VarCodec.writeString(dos, identifier);
        }

        byte[] snapshotBytes = encodeActions(registry, snapshotActions);
        dos.writeInt(snapshotBytes.length);
        dos.write(snapshotBytes);

        dos.write(encodeActions(registry, streamActions));
        dos.flush();
        return out.toByteArray();
    }

    private static byte[] encodeActions(Map<String, Integer> registry, List<ReplayAction> actions)
            throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(buf);
        for (ReplayAction action : actions) {
            Integer id = registry.get(action.identifier());
            if (id == null) {
                throw new IllegalStateException("Action not in registry: " + action.identifier());
            }
            VarCodec.writeVarInt(dos, id);
            dos.writeInt(action.payload().length);
            dos.write(action.payload());
        }
        dos.flush();
        return buf.toByteArray();
    }
}
