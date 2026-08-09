package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Season gating semantics mirroring the Forge build's SereneSeasons block
 * tags ({@code datagen/tag/TagBlock}) for trellis propagation: the plain
 * grapevine trellis climbs in spring and summer, gold in summer, and ice in
 * winter. Hanging crop seasons belong to the managed CustomCrops crop file.
 * Wild grapevines are intentionally absent because they never carried a
 * season tag. Matching SereneSeasons, only random-tick propagation is gated;
 * bone meal stays unrestricted.
 */
public final class GrapeSeasonSemantics {

    /** Mirror of the CustomCrops season cycle; DISABLE is modelled as "no season". */
    public enum Season {
        SPRING,
        SUMMER,
        AUTUMN,
        WINTER
    }

    /** The three structural plants whose propagation is not a CustomCrops crop tick. */
    public enum Plant {
        GRAPEVINE_TRELLIS("grapevine-trellis", Season.SPRING, Season.SUMMER),
        GOLD_GRAPEVINE_TRELLIS("gold-grapevine-trellis", Season.SUMMER),
        ICE_GRAPEVINE_TRELLIS("ice-grapevine-trellis", Season.WINTER);

        private final String configKey;
        private final EnumSet<Season> defaults;

        Plant(String configKey, Season first, Season... rest) {
            this.configKey = configKey;
            this.defaults = EnumSet.of(first, rest);
        }

        /** Key of this plant under the {@code seasons} config section. */
        public String configKey() {
            return configKey;
        }

        /** Forge SereneSeasons tag parity, used when the config omits the key. */
        public EnumSet<Season> defaultSeasons() {
            return EnumSet.copyOf(defaults);
        }
    }

    private GrapeSeasonSemantics() {
    }

    /** Trellis block id to gated plant; {@code null} for untagged blocks. */
    public static Plant plantForTrellis(String blockId) {
        return switch (blockId) {
            case "kaleidoscope_tavern:grapevine_trellis" -> Plant.GRAPEVINE_TRELLIS;
            case "kaleidoscope_tavern:gold_grapevine_trellis" -> Plant.GOLD_GRAPEVINE_TRELLIS;
            case "kaleidoscope_tavern:ice_grapevine_trellis" -> Plant.ICE_GRAPEVINE_TRELLIS;
            default -> null;
        };
    }

    /**
     * Parses a config season list. Entries are case-insensitive and trimmed;
     * an unknown entry fails the whole list so the caller can fall back to
     * the Forge defaults instead of silently dropping seasons.
     */
    public static EnumSet<Season> parseSeasons(List<String> raw) {
        EnumSet<Season> seasons = EnumSet.noneOf(Season.class);
        for (String entry : raw) {
            String normalized = entry == null ? "" : entry.trim().toUpperCase(Locale.ROOT);
            try {
                seasons.add(Season.valueOf(normalized));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("未知季节：" + entry);
            }
        }
        return seasons;
    }

    /**
     * Random-tick gate. {@code worldSeason} is the CustomCrops season name;
     * {@code DISABLE} (world seasons off — the Paper equivalent of "no season
     * mod installed" on Forge), {@code null} and unrecognized values never
     * restrict growth.
     */
    public static boolean allowsGrowth(Set<Season> allowed, String worldSeason) {
        if (worldSeason == null) {
            return true;
        }
        Season season = switch (worldSeason) {
            case "SPRING" -> Season.SPRING;
            case "SUMMER" -> Season.SUMMER;
            case "AUTUMN" -> Season.AUTUMN;
            case "WINTER" -> Season.WINTER;
            default -> null;
        };
        return season == null || allowed.contains(season);
    }
}
