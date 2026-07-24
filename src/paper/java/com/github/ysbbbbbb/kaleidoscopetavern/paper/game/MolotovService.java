package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.BlockDispenseProjectileEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ThreadLocalRandom;

/** Turns the CraftEngine splash-potion item into the original fire-spreading Molotov. */
public final class MolotovService implements Listener {
    private static final String MOLOTOV = "kaleidoscope_tavern:molotov";
    private final JavaPlugin plugin;
    private final ItemService items;
    private final NamespacedKey projectileKey;

    public MolotovService(JavaPlugin plugin, ItemService items) {
        this.plugin = plugin;
        this.items = items;
        this.projectileKey = new NamespacedKey(plugin, "molotov_projectile");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof ThrownPotion potion && MOLOTOV.equals(items.id(potion.getItem()))) {
            mark(potion);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDispense(BlockDispenseProjectileEvent event) {
        if (MOLOTOV.equals(items.id(event.getItem()))) {
            mark(event.getProjectile());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPotionSplash(PotionSplashEvent event) {
        if (isMolotov(event.getPotion())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        Location impact = projectile.getLocation();
        if (isMolotov(projectile)) {
            spreadFire(impact, projectile);
            projectile.remove();
            return;
        }

        // A placed Molotov is furniture, not a block. Any projectile hitting its
        // immediate position breaks the bottle and triggers the same fire burst.
        for (Entity nearby : impact.getWorld().getNearbyEntities(impact, 0.8, 0.8, 0.8)) {
            if (!CraftEngineFurniture.isFurniture(nearby)) {
                continue;
            }
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(nearby);
            if (furniture != null && MOLOTOV.equals(furniture.id().toString())) {
                Location origin = furniture.location().clone();
                CraftEngineFurniture.remove(furniture, false, true);
                spreadFire(origin, projectile);
                return;
            }
        }
    }

    private void spreadFire(Location center, Entity source) {
        int radius = Math.max(1, plugin.getConfig().getInt("gameplay.molotov-radius", 3));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int dx = -radius - 2; dx <= radius + 2; dx++) {
            for (int dz = -radius - 2; dz <= radius + 2; dz++) {
                double overshoot = Math.sqrt(dx * dx + dz * dz) - radius;
                if (overshoot > 2D || overshoot > 0D && random.nextDouble() >= (1D - overshoot / 2D) * 0.6D) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    Block target = center.getBlock().getRelative(dx, dy, dz);
                    if (!target.getType().isAir()) {
                        continue;
                    }
                    BlockIgniteEvent ignite = new BlockIgniteEvent(
                            target, BlockIgniteEvent.IgniteCause.FIREBALL, source);
                    Bukkit.getPluginManager().callEvent(ignite);
                    if (!ignite.isCancelled()) {
                        target.setType(Material.FIRE, true);
                        if (target.getType() == Material.FIRE) {
                            break;
                        }
                    }
                }
            }
        }
        center.getWorld().playSound(center, "minecraft:item.firecharge.use", 2F, 1F);
        center.getWorld().playSound(center, "minecraft:block.glass.break", 2F, 1F);
        center.getWorld().spawnParticle(Particle.FLAME, center.clone().add(0, 0.5, 0),
                30, radius, 1, radius, 0.1);
        center.getWorld().spawnParticle(Particle.SMOKE, center.clone().add(0, 0.5, 0),
                20, radius, 1, radius, 0.1);
    }

    private void mark(Projectile projectile) {
        projectile.getPersistentDataContainer().set(projectileKey, PersistentDataType.BYTE, (byte) 1);
    }

    private boolean isMolotov(Projectile projectile) {
        return projectile.getPersistentDataContainer().has(projectileKey, PersistentDataType.BYTE)
                || projectile instanceof ThrownPotion potion && MOLOTOV.equals(items.id(potion.getItem()));
    }
}
