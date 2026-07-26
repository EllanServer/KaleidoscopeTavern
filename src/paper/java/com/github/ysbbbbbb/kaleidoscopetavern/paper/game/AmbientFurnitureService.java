package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Restores the legacy ticking, redstone and ambient behavior of decorative furniture. */
public final class AmbientFurnitureService implements Listener {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final Key MYSTERY_COCKTAIL = Key.of(PREFIX + "mystery_cocktail");
    private static final Key CIRCULAR_RACK = Key.of(PREFIX + "circular_rack");
    private static final Map<Key, IncenseSpec> INCENSE = Map.ofEntries(
            Map.entry(Key.of(PREFIX + "sakura_incense"), incense("CHERRY_LEAVES", "CHERRY_LEAVES")),
            Map.entry(Key.of(PREFIX + "pine_incense"), incense("SMOKE", "CAMPFIRE_COSY_SMOKE")),
            Map.entry(Key.of(PREFIX + "ginkgo_incense"), incense("WAX_OFF", "COMPOSTER")),
            Map.entry(Key.of(PREFIX + "spore_incense"), incense("SPORE_BLOSSOM_AIR", "SPORE_BLOSSOM_AIR")),
            Map.entry(Key.of(PREFIX + "catnip_incense"), incense("HAPPY_VILLAGER", "HAPPY_VILLAGER")),
            Map.entry(Key.of(PREFIX + "snow_incense"), incense("SNOWFLAKE", "SNOWFLAKE")),
            Map.entry(Key.of(PREFIX + "butterfly_incense"), incense("GLOW", "GLOW")),
            Map.entry(Key.of(PREFIX + "firefly_incense"),
                    new IncenseSpec(particle("FIREFLY"), particle("FIREFLY"), -0.67, 5.33))
    );

    private final JavaPlugin plugin;
    private final DisplayStorageService displayStorage;
    private final Set<UUID> tracked = new HashSet<>();
    private BukkitTask task;

    public AmbientFurnitureService(JavaPlugin plugin, DisplayStorageService displayStorage) {
        this.plugin = plugin;
        this.displayStorage = displayStorage;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        Bukkit.getScheduler().runTask(plugin, this::bootstrap);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        tracked.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(FurniturePlaceEvent event) {
        track(event.furniture());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(FurnitureBreakEvent event) {
        Entity entity = event.furniture().bukkitEntity();
        if (entity != null) {
            tracked.remove(entity.getUniqueId());
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ItemDisplay && CraftEngineFurniture.isFurniture(entity)) {
                track(CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity));
            }
        }
    }

    private void tick() {
        List<UUID> invalid = null;
        for (UUID uuid : tracked) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity == null || !entity.isValid()) {
                if (invalid == null) {
                    invalid = new ArrayList<>();
                }
                invalid.add(uuid);
                continue;
            }
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture == null || !furniture.isValid()) {
                if (invalid == null) {
                    invalid = new ArrayList<>();
                }
                invalid.add(uuid);
                continue;
            }
            Key id = furniture.id();
            IncenseSpec incense = INCENSE.get(id);
            if (incense != null) {
                tickIncense(furniture, incense);
            } else if (id.equals(MYSTERY_COCKTAIL)) {
                tickMysteryCocktail(furniture);
            } else if (id.equals(CIRCULAR_RACK)) {
                tickCircularRack(furniture);
            } else {
                if (invalid == null) {
                    invalid = new ArrayList<>();
                }
                invalid.add(uuid);
            }
        }
        if (invalid != null) {
            tracked.removeAll(invalid);
        }
    }

    private void tickIncense(BukkitFurniture furniture, IncenseSpec spec) {
        FurnitureState state = new FurnitureState(plugin, furniture);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location center = furniture.location().clone().add(0, 0.5, 0);
        if (random.nextInt(3) == 0) {
            double dx = random.nextGaussian() * 0.01;
            double dy = 0.02 + random.nextDouble() * 0.01;
            double dz = random.nextGaussian() * 0.01;
            // A zero count makes the packet offsets an exact velocity vector,
            // matching Level.addParticle rather than random Bukkit spread.
            center.getWorld().spawnParticle(spec.small(), center, 0, dx, dy, dz, 1);
        }
        if (!state.bool("incense_active")
                && !furniture.currentVariant().name().endsWith("_open")) {
            return;
        }
        for (int index = 0; index < 5; index++) {
            Location point = center.clone().add(
                    random.nextDouble(-16, 16),
                    spec.largeYOffset() + random.nextDouble() * spec.largeYRange(),
                    random.nextDouble(-16, 16));
            point.getWorld().spawnParticle(spec.large(), point, 1, 0, 0, 0, 0);
        }
    }

    private static void tickMysteryCocktail(BukkitFurniture furniture) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location point = furniture.location().clone().add(
                0.2 * random.nextDouble(-1, 1),
                0.5 + random.nextDouble(0, 0.2),
                0.2 * random.nextDouble(-1, 1));
        Particle.Spell spell = new Particle.Spell(Color.fromRGB(
                random.nextInt(256), random.nextInt(256), random.nextInt(256)), 1.0F);
        point.getWorld().spawnParticle(Particle.EFFECT, point, 1,
                0, 0, 0, 0, spell);
    }

    private void tickCircularRack(BukkitFurniture furniture) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextInt(8) != 0 || !displayStorage.hasAnyStoredItem(furniture)) {
            return;
        }
        Location base = furniture.location();
        double x = random.nextBoolean()
                ? -0.375 + random.nextDouble(0, 0.25)
                : 0.375 - random.nextDouble(0, 0.25);
        double z = random.nextBoolean()
                ? -0.375 + random.nextDouble(0, 0.25)
                : 0.375 - random.nextDouble(0, 0.25);
        Location point = base.clone().add(x, random.nextDouble(), z);
        point.getWorld().spawnParticle(Particle.END_ROD, point, 0,
                0.01, 0.01, 0.01, 1);
    }

    private void track(BukkitFurniture furniture) {
        if (furniture == null || !isManaged(furniture.id())) {
            return;
        }
        Entity entity = furniture.bukkitEntity();
        if (entity != null) {
            tracked.add(entity.getUniqueId());
        }
    }

    private void bootstrap() {
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (CraftEngineFurniture.isFurniture(display)) {
                    track(CraftEngineFurniture.getLoadedFurnitureByMetaEntity(display));
                }
            }
        }
    }

    private static boolean isManaged(Key id) {
        return INCENSE.containsKey(id) || id.equals(MYSTERY_COCKTAIL) || id.equals(CIRCULAR_RACK);
    }

    private static IncenseSpec incense(String small, String large) {
        return new IncenseSpec(particle(small), particle(large), -2, 16);
    }

    private static Particle particle(String name) {
        try {
            return Particle.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return Particle.END_ROD;
        }
    }

    private record IncenseSpec(Particle small, Particle large,
                               double largeYOffset, double largeYRange) {
    }
}
