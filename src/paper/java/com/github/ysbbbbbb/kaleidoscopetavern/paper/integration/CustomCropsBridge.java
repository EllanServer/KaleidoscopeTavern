package com.github.ysbbbbbb.kaleidoscopetavern.paper.integration;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.GrapeSeasonSemantics;
import net.momirealms.customcrops.api.BukkitCustomCropsAPI;
import net.momirealms.customcrops.api.BukkitCustomCropsPlugin;
import net.momirealms.customcrops.api.CustomCropsAPI;
import net.momirealms.customcrops.api.core.ConfigManager;
import net.momirealms.customcrops.api.core.block.GreenhouseBlock;
import net.momirealms.customcrops.api.core.world.CustomCropsBlockState;
import net.momirealms.customcrops.api.core.world.CustomCropsWorld;
import net.momirealms.customcrops.api.core.world.Pos3;
import net.momirealms.customcrops.api.core.world.Season;
import org.bukkit.Location;

import java.util.Optional;
import java.util.Set;

/** Narrow public-API boundary between Tavern structures and CustomCrops. */
public final class CustomCropsBridge {
    private static final String PREFIX = "kaleidoscope_tavern:";

    private CustomCropsBridge() {
    }

    public static void requireReady() {
        if (api() == null) {
            throw new IllegalStateException("CustomCrops API is not initialized");
        }
    }

    public static boolean placeHangingGrapes(Location location, String vineBlockId) {
        String cropId = switch (vineBlockId) {
            case PREFIX + "ice_grapevine_trellis" -> "kaleidoscope_tavern_ice_grape";
            case PREFIX + "gold_grapevine_trellis" -> "kaleidoscope_tavern_gold_grape";
            case PREFIX + "grapevine_trellis" -> "kaleidoscope_tavern_grape";
            default -> null;
        };
        return cropId != null && api().placeCrop(location, cropId, 0);
    }

    public static void addGrowthPoints(Location location, int points) {
        api().addPointToCrop(location, points);
    }

    public static void removeCrop(Location location) {
        CustomCropsWorld<?> world = api().getCustomCropsWorld(location.getWorld());
        if (world != null) {
            world.removeBlockState(api().adapt(location));
        }
    }

    /**
     * True when season-gated random-tick growth may proceed at
     * {@code location}: the world season is {@code DISABLE}, matches
     * {@code allowedSeasons}, or greenhouse glass sits above. This mirrors
     * CustomCrops' own "season" requirement
     * ({@code AbstractRequirementManager#registerSeasonRequirement}) so
     * Tavern plants obey exactly the greenhouse rules server owners already
     * configured for CustomCrops crops.
     */
    public static boolean isSeasonSuitable(Location location,
            Set<GrapeSeasonSemantics.Season> allowedSeasons) {
        Season season = BukkitCustomCropsPlugin.getInstance().getWorldManager()
                .getSeason(location.getWorld());
        if (season == null || GrapeSeasonSemantics.allowsGrowth(allowedSeasons, season.name())) {
            return true;
        }
        return isInGreenhouse(location);
    }

    /**
     * Greenhouse exemption copied from the CustomCrops season requirement:
     * scan {@code greenhouseRange} blocks straight up for a recorded
     * greenhouse-glass block state. Range, toggle and glass ids all come from
     * CustomCrops' {@link ConfigManager}, nothing is duplicated here.
     */
    private static boolean isInGreenhouse(Location location) {
        if (!ConfigManager.enableGreenhouse()) {
            return false;
        }
        CustomCropsWorld<?> world = api().getCustomCropsWorld(location.getWorld());
        if (world == null) {
            return false;
        }
        Pos3 pos = api().adapt(location);
        for (int i = 1, range = ConfigManager.greenhouseRange(); i <= range; i++) {
            Optional<CustomCropsBlockState> state = world.getBlockState(pos.add(0, i, 0));
            if (state.isPresent() && state.get().type() instanceof GreenhouseBlock) {
                return true;
            }
        }
        return false;
    }

    public static void reload() {
        BukkitCustomCropsPlugin plugin = BukkitCustomCropsPlugin.getInstance();
        plugin.reload();
        plugin.getWorldManager().reloadWorlds();
    }

    private static CustomCropsAPI api() {
        return BukkitCustomCropsAPI.get();
    }
}
