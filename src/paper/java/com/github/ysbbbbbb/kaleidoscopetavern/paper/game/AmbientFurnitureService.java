package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.TickingFurnitureBehavior;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.ZombieVillager;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Restores the legacy ticking, redstone and ambient behavior of decorative furniture. */
public final class AmbientFurnitureService {
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

    private final DisplayStorageService displayStorage;
    private final TickingFurnitureBehavior.Handler tickingHandler = this::tickFurniture;
    private Tag<EntityType> undeadTag;

    public AmbientFurnitureService(DisplayStorageService displayStorage) {
        this.displayStorage = displayStorage;
    }

    public void start() {
        TickingFurnitureBehavior.bind(
                TickingFurnitureBehavior.Channel.AMBIENT, tickingHandler);
    }

    public void stop() {
        TickingFurnitureBehavior.unbind(
                TickingFurnitureBehavior.Channel.AMBIENT, tickingHandler);
    }

    private void tickFurniture(BukkitFurniture furniture) {
        Key id = furniture.id();
        IncenseSpec incense = INCENSE.get(id);
        if (incense != null) {
            tickIncense(furniture, incense);
        } else if (id.equals(MYSTERY_COCKTAIL)) {
            tickMysteryCocktail(furniture);
        } else if (id.equals(CIRCULAR_RACK)) {
            tickCircularRack(furniture);
        }
    }

    /**
     * Level#doAnimateTick reaches a specific nearby block with roughly a
     * 667/32768 chance per client tick. All three ambient emitters replay
     * that average so the vanilla-visible density stays intact instead of
     * firing every server tick.
     */
    private static boolean animateTickSampled(ThreadLocalRandom random) {
        return random.nextInt(49) == 0;
    }

    private void tickIncense(BukkitFurniture furniture, IncenseSpec spec) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean burstTick = furniture.location().getWorld().getGameTime() % 120 == 0;
        boolean particleTick = animateTickSampled(random);
        if (!burstTick && !particleTick) {
            return;
        }
        // The *_open furniture variant is the single source of truth for a
        // lit incense; both CE toggle events and the CE redstone behavior set it.
        boolean open = furniture.currentVariant().name().endsWith("_open");
        if (burstTick && open) {
            hurtNearbyUndead(furniture);
        }
        if (!particleTick) {
            return;
        }
        Location center = furniture.location().clone().add(0, 0.5, 0);
        if (random.nextInt(3) == 0) {
            double dx = random.nextGaussian() * 0.01;
            double dy = 0.02 + random.nextDouble() * 0.01;
            double dz = random.nextGaussian() * 0.01;
            // A zero count makes the packet offsets an exact velocity vector,
            // matching Level.addParticle rather than random Bukkit spread.
            center.getWorld().spawnParticle(spec.small(), center, 0, dx, dy, dz, 1);
        }
        if (!open) {
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

    /**
     * IncenseBlockEntity#serverTick: every 120 ticks a lit incense deals one
     * point of magic damage to undead within 32 blocks, and near-death zombie
     * villagers begin the vanilla 60-tick conversion without a curing player.
     */
    private void hurtNearbyUndead(BukkitFurniture furniture) {
        Tag<EntityType> undead = undeadTag();
        if (undead == null) {
            return;
        }
        Location center = furniture.location().clone().add(0.5, 0.5, 0.5);
        DamageSource magic = DamageSource.builder(DamageType.MAGIC).build();
        for (LivingEntity living : center.getWorld().getNearbyLivingEntities(center, 32.5)) {
            if (living.isDead() || !undead.isTagged(living.getType())) {
                continue;
            }
            living.damage(1.0, magic);
            if (living instanceof ZombieVillager zombieVillager
                    && zombieVillager.getHealth() <= 1.0) {
                zombieVillager.setConversionPlayer(null);
                zombieVillager.setConversionTime(60);
            }
        }
    }

    private Tag<EntityType> undeadTag() {
        if (undeadTag == null) {
            undeadTag = Bukkit.getTag(Tag.REGISTRY_ENTITY_TYPES,
                    NamespacedKey.minecraft("undead"), EntityType.class);
        }
        return undeadTag;
    }

    private static void tickMysteryCocktail(BukkitFurniture furniture) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (!animateTickSampled(random)) {
            return;
        }
        Location point = furniture.location().clone().add(
                0.2 * random.nextDouble(-1, 1),
                0.5 + random.nextDouble(0, 0.2),
                0.2 * random.nextDouble(-1, 1));
        Particle.Spell spell = new Particle.Spell(Color.fromRGB(
                random.nextInt(256), random.nextInt(256), random.nextInt(256)), 1.0F);
        // The source hands addParticle three 0..1 speeds; count zero keeps the
        // offsets as that exact velocity vector.
        point.getWorld().spawnParticle(Particle.EFFECT, point, 0,
                random.nextDouble(), random.nextDouble(), random.nextDouble(), 1, spell);
    }

    private void tickCircularRack(BukkitFurniture furniture) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (!animateTickSampled(random) || random.nextInt(8) != 0
                || !displayStorage.hasAnyStoredItem(furniture)) {
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
