package com.globalflashback.state;

import java.util.List;

/**
 * Shared list freezing helpers to avoid redundant {@link List#copyOf} / rebuilds on hot paths.
 */
final class FreezeLists {
    private FreezeLists() {}

    static List<Integer> integers(List<Integer> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return List.copyOf(list);
    }

    static List<PlayerState.PotionEffectState> potions(List<PlayerState.PotionEffectState> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return List.copyOf(list);
    }
}
