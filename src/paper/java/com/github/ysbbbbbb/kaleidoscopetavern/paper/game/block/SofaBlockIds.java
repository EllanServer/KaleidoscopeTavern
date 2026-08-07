package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import net.momirealms.craftengine.core.util.Key;

import java.util.Set;

/** Stable ids shared by the active tint-source sofa and one-release aliases. */
public final class SofaBlockIds {
    public static final Key SHARED =
            Key.of("kaleidoscope_tavern", "_internal/sofa");

    private static final Set<Key> LEGACY = Set.of(
            Key.of("kaleidoscope_tavern", "white_sofa"),
            Key.of("kaleidoscope_tavern", "orange_sofa"),
            Key.of("kaleidoscope_tavern", "magenta_sofa"),
            Key.of("kaleidoscope_tavern", "light_blue_sofa"),
            Key.of("kaleidoscope_tavern", "yellow_sofa"),
            Key.of("kaleidoscope_tavern", "lime_sofa"),
            Key.of("kaleidoscope_tavern", "pink_sofa"),
            Key.of("kaleidoscope_tavern", "gray_sofa"),
            Key.of("kaleidoscope_tavern", "light_gray_sofa"),
            Key.of("kaleidoscope_tavern", "cyan_sofa"),
            Key.of("kaleidoscope_tavern", "purple_sofa"),
            Key.of("kaleidoscope_tavern", "blue_sofa"),
            Key.of("kaleidoscope_tavern", "brown_sofa"),
            Key.of("kaleidoscope_tavern", "green_sofa"),
            Key.of("kaleidoscope_tavern", "red_sofa"),
            Key.of("kaleidoscope_tavern", "black_sofa")
    );

    private SofaBlockIds() {
    }

    public static boolean isLegacy(Key id) {
        return id != null && LEGACY.contains(id);
    }

    public static Set<Key> legacyIds() {
        return LEGACY;
    }
}
