package com.github.ysbbbbbb.kaleidoscopetavern.paper.integration;

import net.momirealms.customcrops.api.BukkitCustomCropsAPI;
import net.momirealms.customcrops.api.BukkitCustomCropsPlugin;
import net.momirealms.customcrops.api.CustomCropsAPI;
import net.momirealms.customcrops.api.core.world.CustomCropsWorld;
import org.bukkit.Location;

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

    public static void reload() {
        BukkitCustomCropsPlugin plugin = BukkitCustomCropsPlugin.getInstance();
        plugin.reload();
        plugin.getWorldManager().reloadWorlds();
    }

    private static CustomCropsAPI api() {
        return BukkitCustomCropsAPI.get();
    }
}
