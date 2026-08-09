package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.decor;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.TickingFurnitureBehavior;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;

import java.util.concurrent.ThreadLocalRandom;

/** Restores the legacy ticking, redstone and ambient behavior of decorative furniture. */
public final class AmbientFurnitureService {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final Key MYSTERY_COCKTAIL = Key.of(PREFIX + "mystery_cocktail");
    private final TickingFurnitureBehavior.Handler mysteryParticleHandler =
            AmbientFurnitureService::tickMysteryCocktail;

    public AmbientFurnitureService() {
    }

    public void start() {
        TickingFurnitureBehavior.bind(
                TickingFurnitureBehavior.Channel.MYSTERY_PARTICLE, mysteryParticleHandler);
    }

    public void stop() {
        TickingFurnitureBehavior.unbind(
                TickingFurnitureBehavior.Channel.MYSTERY_PARTICLE, mysteryParticleHandler);
    }

    private static void tickMysteryCocktail(BukkitFurniture furniture) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (!furniture.id().equals(MYSTERY_COCKTAIL)) {
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

}
