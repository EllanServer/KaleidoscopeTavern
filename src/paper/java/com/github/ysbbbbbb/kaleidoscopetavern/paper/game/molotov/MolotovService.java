package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.molotov;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.drink.BottleFurnitureService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.block.UpdateFlags;
import net.momirealms.craftengine.proxy.bukkit.craftbukkit.CraftWorldProxy;
import net.momirealms.craftengine.proxy.minecraft.core.BlockPosProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelWriterProxy;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
    // Client updates are required, but cascading neighbour/shape physics for
    // every fire in the same burst is not. Unlike Bukkit's applyPhysics=false
    // flags, this deliberately does not include UPDATE_SKIP_ON_PLACE: vanilla
    // FireBlock#onPlace must still schedule the first fire tick.
    private static final int FIRE_UPDATE_FLAGS =
            UpdateFlags.UPDATE_CLIENTS | UpdateFlags.UPDATE_KNOWN_SHAPE;

    private final JavaPlugin plugin;
    private final ItemService items;
    private final NamespacedKey projectileKey;
    private final BlockData fireData;
    private final BlockData soulFireData;
    private final Object fireState;
    private final Object soulFireState;
    private final CraftWorldProxy craftWorlds;
    private final BlockPosProxy blockPositions;
    private final LevelWriterProxy levelWriter;

    public MolotovService(JavaPlugin plugin, ItemService items) {
        this.plugin = plugin;
        this.items = items;
        this.projectileKey = new NamespacedKey(plugin, "molotov_projectile");
        this.fireData = Material.FIRE.createBlockData();
        this.soulFireData = Material.SOUL_FIRE.createBlockData();
        this.fireState = BlockStateUtils.blockDataToBlockState(fireData);
        this.soulFireState = BlockStateUtils.blockDataToBlockState(soulFireData);
        // Resolve CE's generated NMS bridges during plugin enable rather than
        // charging their first-use linkage to the first Molotov impact.
        this.craftWorlds = CraftWorldProxy.INSTANCE;
        this.blockPositions = BlockPosProxy.INSTANCE;
        this.levelWriter = LevelWriterProxy.INSTANCE;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if ((event.getEntity() instanceof Snowball snowball
                && MOLOTOV.equals(items.id(snowball.getItem())))
                || (event.getEntity() instanceof ThrownPotion potion
                && MOLOTOV.equals(items.id(potion.getItem())))) {
            mark(event.getEntity());
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
        Snowball projectile = eye.getWorld().spawn(eye.clone().subtract(0, 0.1, 0),
                Snowball.class, snowball -> {
                    snowball.setShooter(player);
                    snowball.setItem(projectileItem);
                    snowball.setVelocity(direction);
                });
        mark(projectile);

        if (player.getGameMode() != GameMode.CREATIVE) {
            consumeRaisedMolotov(player, event.getItem());
        }
        eye.getWorld().playSound(eye, "minecraft:entity.snowball.throw", SoundCategory.PLAYERS, 0.5F,
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

        // MolotovBlock reacts only when that exact block was hit. It first
        // creates a zero-velocity ThrownMolotovEntity at block centre, then
        // BottleBlock independently decides whether mayInteract permits the
        // placed bottle itself to be removed without drops.
        BukkitFurniture placed = BottleFurnitureService.directlyHitFurniture(event);
        if (placed != null && MOLOTOV.equals(placed.id().toString())) {
            Location origin = placed.location().getBlock().getLocation().add(0.5, 0.5, 0.5);
            launchFallingMolotov(origin);
            if (BottleFurnitureService.mayBreak(projectile, placed)) {
                CraftEngineFurniture.remove(placed, false, false);
                origin.getWorld().playSound(origin, "minecraft:block.glass.break",
                        SoundCategory.BLOCKS, 1F, 1F);
                origin.getWorld().spawnParticle(Particle.BLOCK, origin, 24,
                        0.25, 0.25, 0.25, 0.08, Material.GLASS.createBlockData());
            }
        }

        if (isMolotov(projectile)) {
            spreadFire(impact, projectile);
            projectile.remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileDamage(EntityDamageByEntityEvent event) {
        // Snowball is the closest vanilla carrier to the original generic
        // ThrowableProjectile, but it has a Blaze-only damage special case.
        // The archived Molotov never dealt direct projectile damage.
        if (event.getDamager() instanceof Snowball snowball && isMolotov(snowball)) {
            event.setCancelled(true);
        }
    }

    private void launchFallingMolotov(Location origin) {
        items.build(MOLOTOV, null).ifPresent(payload -> {
            payload.setAmount(1);
            Snowball falling = origin.getWorld().spawn(origin, Snowball.class, snowball -> {
                snowball.setItem(payload);
                snowball.setVelocity(new org.bukkit.util.Vector());
            });
            mark(falling);
        });
    }

    private void spreadFire(Location center, Entity source) {
        int radius = 3;
        int radiusSquared = radius * radius;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Block centerBlock = center.getBlock();
        Object level = craftWorlds.getWorld(center.getWorld());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared > radiusSquared) {
                    double overshoot = Math.sqrt(distanceSquared) - radius;
                    if (overshoot > 2D || random.nextDouble()
                            >= (1D - overshoot / 2D) * 0.6D) {
                        continue;
                    }
                }
                for (int dy = -1; dy <= 1; dy++) {
                    Block target = centerBlock.getRelative(dx, dy, dz);
                    if (!target.getType().isAir()) {
                        continue;
                    }
                    boolean soulFire = switch (target.getRelative(BlockFace.DOWN).getType()) {
                        case SOUL_SAND, SOUL_SOIL -> true;
                        default -> false;
                    };
                    BlockData fire = soulFire ? soulFireData : fireData;
                    if (!target.canPlace(fire)) {
                        continue;
                    }
                    BlockIgniteEvent ignite = new BlockIgniteEvent(
                            target, BlockIgniteEvent.IgniteCause.FIREBALL, source);
                    Bukkit.getPluginManager().callEvent(ignite);
                    if (!ignite.isCancelled()) {
                        Object position = blockPositions.newInstance(
                                target.getX(), target.getY(), target.getZ());
                        Object state = soulFire ? soulFireState : fireState;
                        if (levelWriter.setBlock(
                                level, position, state, FIRE_UPDATE_FLAGS)) {
                            break;
                        }
                    }
                }
            }
        }
        center.getWorld().playSound(center, "minecraft:item.firecharge.use", SoundCategory.BLOCKS, 2F, 1F);
        center.getWorld().playSound(center, "minecraft:block.glass.break", SoundCategory.BLOCKS, 2F, 1F);
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
                || projectile instanceof Snowball snowball && MOLOTOV.equals(items.id(snowball.getItem()))
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
