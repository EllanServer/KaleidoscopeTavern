package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.core;

import java.util.Map;

/** Paper 26.2 resource-id normalization at the migration boundary. */
public final class LegacyIds {
    private static final Map<String, String> RENAMES = Map.of(
            "minecraft:chain", "minecraft:iron_chain",
            "minecraft:grass", "minecraft:short_grass");
    private LegacyIds() {}

    public static String normalize(String id) {
        boolean tag = id.startsWith("#");
        String bare = tag ? id.substring(1) : id;
        return (tag ? "#" : "") + RENAMES.getOrDefault(bare, bare);
    }
}
