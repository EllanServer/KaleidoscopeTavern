package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.shaker;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.LifecycleFurnitureBehavior;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Resolves a looked-at shaker through the Tavern index and CraftEngine collider API. */
final class ShakerHudTargetResolver {
    private static final double TARGET_RANGE = 5.0;
    private static final double PREFILTER_RADIUS = 5.75;

    private final String shakerId;

    ShakerHudTargetResolver(String shakerId) {
        this.shakerId = Objects.requireNonNull(shakerId, "shakerId");
    }

    BukkitFurniture resolve(Player player, Map<UUID, BukkitFurniture> loaded) {
        World world = player.getWorld();
        double x = player.getX();
        double y = player.getY() + player.getEyeHeight();
        double z = player.getZ();

        if (!LifecycleFurnitureBehavior.hasNearby(
                LifecycleFurnitureBehavior.Channel.SHAKER, world,
                x, y, z, PREFILTER_RADIUS, PREFILTER_RADIUS)) {
            return null;
        }
        BukkitFurniture target = CraftEngineFurniture.rayTrace(player, TARGET_RANGE);
        return usable(target, loaded) ? target : null;
    }

    private boolean usable(BukkitFurniture furniture,
                           Map<UUID, BukkitFurniture> loaded) {
        return furniture != null
                && furniture.isValid()
                && furniture.id().toString().equals(shakerId)
                && loaded.get(furniture.uuid()) == furniture;
    }
}
