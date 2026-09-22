package com.globalflashback.format;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds {@code flashback:action/create_local_player} payload.
 *
 * <p>Field order verified against Flashback replay reader expectations
 * (documented by interoperable server writers):
 * UUID, xyz, xRot/yRot/yHeadRot, velocity, GameProfile wire, gameMode varint.
 */
public final class CreateLocalPlayerPayload {
    private CreateLocalPlayerPayload() {}

    public static byte[] fromPlayer(Player player) {
        PlayerProfile profile = player.getPlayerProfile();
        UUID profileId = profile.getId() != null ? profile.getId() : player.getUniqueId();
        String profileName = profile.getName() != null ? profile.getName() : player.getName();

        List<String[]> props = new ArrayList<>();
        for (ProfileProperty prop : profile.getProperties()) {
            props.add(new String[]{prop.getName(), prop.getValue(), prop.getSignature()});
        }

        return build(
                player.getUniqueId(),
                player.getLocation().getX(),
                player.getLocation().getY(),
                player.getLocation().getZ(),
                player.getLocation().getPitch(),
                player.getLocation().getYaw(),
                player.getLocation().getYaw(),
                player.getVelocity().getX(),
                player.getVelocity().getY(),
                player.getVelocity().getZ(),
                profileId,
                profileName,
                props,
                gameModeId(player.getGameMode())
        );
    }

    static byte[] build(
            UUID uuid,
            double x, double y, double z,
            float xRot, float yRot, float yHeadRot,
            double vx, double vy, double vz,
            UUID profileId,
            String profileName,
            List<String[]> props,
            int gameModeId
    ) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeLong(uuid.getMostSignificantBits());
            dos.writeLong(uuid.getLeastSignificantBits());
            dos.writeDouble(x);
            dos.writeDouble(y);
            dos.writeDouble(z);
            dos.writeFloat(xRot);
            dos.writeFloat(yRot);
            dos.writeFloat(yHeadRot);
            dos.writeDouble(vx);
            dos.writeDouble(vy);
            dos.writeDouble(vz);
            writeGameProfile(dos, profileId, profileName, props);
            VarCodec.writeVarInt(dos, gameModeId);
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode create_local_player payload", e);
        }
    }

    private static void writeGameProfile(
            DataOutputStream out,
            UUID id,
            String name,
            List<String[]> props
    ) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
        VarCodec.writeString(out, name);
        VarCodec.writeVarInt(out, props.size());
        for (String[] prop : props) {
            VarCodec.writeString(out, prop[0]);
            VarCodec.writeString(out, prop[1]);
            boolean hasSig = prop.length > 2 && prop[2] != null;
            out.writeBoolean(hasSig);
            if (hasSig) {
                VarCodec.writeString(out, prop[2]);
            }
        }
    }

    private static int gameModeId(GameMode mode) {
        return switch (mode) {
            case SURVIVAL -> 0;
            case CREATIVE -> 1;
            case ADVENTURE -> 2;
            case SPECTATOR -> 3;
        };
    }
}
