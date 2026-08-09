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
    private final ShakerHudTargetCache cache = new ShakerHudTargetCache();

    ShakerHudTargetResolver(String shakerId) {
        this.shakerId = Objects.requireNonNull(shakerId, "shakerId");
    }

    void beginPoll() {
        cache.beginPoll();
    }

    void endPoll() {
        cache.endPoll();
    }

    void invalidate() {
        cache.clear();
    }

    BukkitFurniture resolve(Player player, Map<UUID, BukkitFurniture> loaded) {
        UUID playerId = player.getUniqueId();
        World world = player.getWorld();
        UUID worldId = world.getUID();
        long gameTick = world.getGameTime();
        double x = player.getX();
        double y = player.getY() + player.getEyeHeight();
        double z = player.getZ();
        float yaw = player.getYaw();
        float pitch = player.getPitch();

        ShakerHudTargetCache.CachedTarget cached = cache.reusable(
                playerId, worldId, gameTick, x, y, z, yaw, pitch);
        if (cached != null) {
            UUID targetId = cached.targetId();
            if (targetId == null) {
                return null;
            }
            BukkitFurniture target = loaded.get(targetId);
            if (usable(target, loaded)) {
                return target;
            }
            cache.remove(playerId);
        }

        BukkitFurniture target = null;
        if (LifecycleFurnitureBehavior.hasNearby(
                LifecycleFurnitureBehavior.Channel.SHAKER, world,
                x, y, z, PREFILTER_RADIUS, PREFILTER_RADIUS)) {
            BukkitFurniture traced = CraftEngineFurniture.rayTrace(player, TARGET_RANGE);
            if (usable(traced, loaded)) {
                target = traced;
            }
        }
        cache.record(playerId, worldId, gameTick, x, y, z, yaw, pitch,
                target == null ? null : target.uuid());
        return target;
    }

    private boolean usable(BukkitFurniture furniture,
                           Map<UUID, BukkitFurniture> loaded) {
        return furniture != null
                && furniture.isValid()
                && furniture.id().toString().equals(shakerId)
                && loaded.get(furniture.uuid()) == furniture;
    }
}
