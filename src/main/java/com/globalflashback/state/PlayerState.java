package com.globalflashback.state;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.io.Serializable;

/**
 * Immutable player state for Global Replay (server-authoritative).
 */
public record PlayerState(
        UUID uuid,
        int entityId,
        DimensionId dimension,
        ReplayMath.Vec3d position,
        ReplayMath.Rotation rotation,
        ReplayMath.Vec3d velocity,
        String pose,
        boolean onGround,
        boolean sneaking,
        boolean sprinting,
        boolean swimming,
        boolean gliding,
        boolean sleeping,
        Integer vehicleEntityId,
        List<Integer> passengerEntityIds,
        String gameMode,
        float health,
        int foodLevel,
        float saturation,
        float experienceProgress,
        int experienceLevel,
        int totalExperience,
        int selectedSlot,
        List<ReplayItemStack> hotbar,
        EquipmentState equipment,
        List<PotionEffectState> potionEffects,
        MetadataBlob metadata,
        String profileName,
        MetadataBlob profilePropertiesPayload
) implements Serializable {
    public static final List<ReplayItemStack> EMPTY_HOTBAR = List.of(
            ReplayItemStack.EMPTY, ReplayItemStack.EMPTY, ReplayItemStack.EMPTY,
            ReplayItemStack.EMPTY, ReplayItemStack.EMPTY, ReplayItemStack.EMPTY,
            ReplayItemStack.EMPTY, ReplayItemStack.EMPTY, ReplayItemStack.EMPTY
    );

    public PlayerState {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(velocity, "velocity");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(gameMode, "gameMode");
        passengerEntityIds = FreezeLists.integers(passengerEntityIds);
        hotbar = canonicalizeHotbar(hotbar);
        equipment = Objects.requireNonNullElse(equipment, EquipmentState.EMPTY);
        potionEffects = FreezeLists.potions(potionEffects);
        metadata = Objects.requireNonNullElse(metadata, MetadataBlob.EMPTY);
        profileName = profileName == null ? "" : profileName;
        profilePropertiesPayload = Objects.requireNonNullElse(profilePropertiesPayload, MetadataBlob.EMPTY);
    }

    private static List<ReplayItemStack> canonicalizeHotbar(List<ReplayItemStack> hotbar) {
        if (hotbar == null || hotbar.isEmpty()) {
            return EMPTY_HOTBAR;
        }
        if (hotbar.size() == 9) {
            boolean clean = true;
            for (int i = 0; i < 9; i++) {
                if (hotbar.get(i) == null) {
                    clean = false;
                    break;
                }
            }
            if (clean) {
                // List.copyOf is a no-op for already-immutable lists (JDK).
                return List.copyOf(hotbar);
            }
        }
        ReplayItemStack[] slots = new ReplayItemStack[9];
        for (int i = 0; i < 9; i++) {
            slots[i] = i < hotbar.size() && hotbar.get(i) != null ? hotbar.get(i) : ReplayItemStack.EMPTY;
        }
        return List.of(slots);
    }

    public record PotionEffectState(String effectId, int amplifier, int durationTicks, boolean ambient, boolean particles) implements Serializable {
        public PotionEffectState {
            Objects.requireNonNull(effectId, "effectId");
        }
    }
}
