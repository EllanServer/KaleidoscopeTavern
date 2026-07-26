package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.GrapeSeasonSemantics.Plant;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.GrapeSeasonSemantics.Season;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.integration.CustomCropsBridge;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Runtime holder for the {@code seasons} config section. Block behaviors are
 * instantiated by CraftEngine outside the plugin lifecycle, so the parsed
 * season map is published through a static snapshot that the plugin refreshes
 * on enable and on {@code /kt reload}. An empty snapshot means "no
 * restriction" (feature disabled, config missing, or plugin not enabled).
 */
public final class GrapeSeasonGate {
    private static volatile Map<Plant, EnumSet<Season>> restrictions = Map.of();

    private GrapeSeasonGate() {
    }

    /**
     * Reads {@code seasons} from config.yml. The allowed-season sets are
     * parsed once here so the random-tick path only performs cached
     * {@link EnumSet} lookups; invalid lists fall back to the Forge tag
     * defaults with a warning.
     */
    public static void configure(ConfigurationSection config, Logger logger) {
        ConfigurationSection section = config == null ? null : config.getConfigurationSection("seasons");
        if (section == null || !section.getBoolean("enable", true)) {
            restrictions = Map.of();
            return;
        }
        Map<Plant, EnumSet<Season>> parsed = new EnumMap<>(Plant.class);
        for (Plant plant : Plant.values()) {
            String key = plant.configKey();
            EnumSet<Season> seasons = plant.defaultSeasons();
            if (section.isList(key)) {
                try {
                    seasons = GrapeSeasonSemantics.parseSeasons(section.getStringList(key));
                } catch (IllegalArgumentException exception) {
                    logger.warning("config.yml seasons." + key + " 配置无效（" + exception.getMessage()
                            + "），已回退为 Forge 版默认季节。");
                }
            }
            parsed.put(plant, seasons);
        }
        restrictions = parsed;
    }

    /**
     * Random-tick gate: true when growth may proceed at {@code location}.
     * Bone-meal paths must not call this — SereneSeasons only intercepts
     * random ticks, and the Paper build keeps that behavior.
     */
    static boolean permitsRandomGrowth(Plant plant, Location location) {
        EnumSet<Season> allowed = restrictions.get(plant);
        return allowed == null || CustomCropsBridge.isSeasonSuitable(location, allowed);
    }
}
