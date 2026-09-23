package com.globalflashback.state;

import java.util.Objects;
import java.io.Serializable;

/**
 * Equipment snapshot for living entities / players.
 */
public record EquipmentState(
        ReplayItemStack mainHand,
        ReplayItemStack offHand,
        ReplayItemStack helmet,
        ReplayItemStack chestplate,
        ReplayItemStack leggings,
        ReplayItemStack boots
) implements Serializable {
    public static final EquipmentState EMPTY = new EquipmentState(
            ReplayItemStack.EMPTY,
            ReplayItemStack.EMPTY,
            ReplayItemStack.EMPTY,
            ReplayItemStack.EMPTY,
            ReplayItemStack.EMPTY,
            ReplayItemStack.EMPTY
    );

    public EquipmentState {
        mainHand = Objects.requireNonNullElse(mainHand, ReplayItemStack.EMPTY);
        offHand = Objects.requireNonNullElse(offHand, ReplayItemStack.EMPTY);
        helmet = Objects.requireNonNullElse(helmet, ReplayItemStack.EMPTY);
        chestplate = Objects.requireNonNullElse(chestplate, ReplayItemStack.EMPTY);
        leggings = Objects.requireNonNullElse(leggings, ReplayItemStack.EMPTY);
        boots = Objects.requireNonNullElse(boots, ReplayItemStack.EMPTY);
    }
}
