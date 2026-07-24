package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ThreadLocalRandom;

/** Restores the original charged throw and fire-spreading Molotov impact. */
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

    @EventHandler(priority = EventPriority.HIGH)
    public void onStopUsing(PlayerStopUsingItemEvent event) {
        if (!MOLOTOV.equals(items.id(event.getItem())) || event.getTicksHeldFor() < 10) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack projectileItem = event.getItem().clone();
        projectileItem.setAmount(1);
        Location eye = player.getEyeLocation();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        org.bukkit.util.Vector direction = eye.getDirection().normalize().add(
                new org.bukkit.util.Vector(
                        random.nextGaussian() * 0.0075,
                        random.nextGaussian() * 0.0075,
                        random.nextGaussian() * 0.0075)).normalize().multiply(0.8);
        ThrownPotion projectile = eye.getWorld().spawn(eye.clone().subtract(0, 0.1, 0),
                ThrownPotion.class, potion -> {
                    potion.setShooter(player);
                    potion.setItem(projectileItem);
                    potion.setVelocity(direction);
                });
        mark(projectile);

        if (player.getGameMode() != GameMode.CREATIVE) {
            consumeRaisedMolotov(player, event.getItem());
        }
        eye.getWorld().playSound(eye, "minecraft:entity.snowball.throw", 0.5F,
                0.4F / (random.nextFloat() * 0.4F + 0.8F));
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
        int radius = 3;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double overshoot = Math.sqrt(dx * dx + dz * dz) - radius;
                if (overshoot > 2D || overshoot > 0D && random.nextDouble() >= (1D - overshoot / 2D) * 0.6D) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    Block target = center.getBlock().getRelative(dx, dy, dz);
                    if (!target.getType().isAir()) {
                        continue;
                    }
                    Material fireType = switch (target.getRelative(BlockFace.DOWN).getType()) {
                        case SOUL_SAND, SOUL_SOIL -> Material.SOUL_FIRE;
                        default -> Material.FIRE;
                    };
                    BlockData fire = fireType.createBlockData();
                    if (!target.canPlace(fire)) {
                        continue;
                    }
                    BlockIgniteEvent ignite = new BlockIgniteEvent(
                            target, BlockIgniteEvent.IgniteCause.FIREBALL, source);
                    Bukkit.getPluginManager().callEvent(ignite);
                    if (!ignite.isCancelled()) {
                        target.setBlockData(fire, true);
                        if (target.getType() == fireType) {
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

    private static void consumeRaisedMolotov(Player player, ItemStack expected) {
        EquipmentSlot raised = player.isHandRaised() ? player.getHandRaised() : null;
        if (raised == EquipmentSlot.OFF_HAND
                && player.getInventory().getItemInOffHand().isSimilar(expected)) {
            player.getInventory().getItemInOffHand().subtract(1);
            return;
        }
        if (player.getInventory().getItemInMainHand().isSimilar(expected)) {
            player.getInventory().getItemInMainHand().subtract(1);
        } else if (player.getInventory().getItemInOffHand().isSimilar(expected)) {
            player.getInventory().getItemInOffHand().subtract(1);
        }
    }
}
