package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.tap;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

/** Paper 26.2 particle adapter for a tap flow selected by {@link TapFlowAppearance}. */
final class TapParticleEmitter {
    private static final int COLORED_DROP_INTERVAL_TICKS = 2;
    private static final int COLORED_DROP_DURATION_TICKS = 8;
    private static final double COLORED_DROP_DISTANCE = 0.72;
    private static final TapParticleEmitter WATER = new TapParticleEmitter(
            TapFlowAppearance.WATER);
    private static final TapParticleEmitter LAVA = new TapParticleEmitter(
            TapFlowAppearance.LAVA);
    private static final TapParticleEmitter HONEY = new TapParticleEmitter(
            TapFlowAppearance.HONEY);
    private static final TapParticleEmitter OBSIDIAN_TEAR = new TapParticleEmitter(
            TapFlowAppearance.OBSIDIAN_TEAR);

    private final TapFlowAppearance appearance;
    private final Color coloredDropColor;
    private Particle.Trail coloredDrop;

    private TapParticleEmitter(TapFlowAppearance appearance) {
        this.appearance = appearance;
        this.coloredDropColor = appearance.style() == TapFlowAppearance.Style.COLOR
                ? Color.fromRGB(appearance.rgb())
                : null;
    }

    static TapParticleEmitter forAppearance(TapFlowAppearance appearance) {
        return switch (appearance.style()) {
            case WATER -> WATER;
            case LAVA -> LAVA;
            case HONEY -> HONEY;
            case OBSIDIAN_TEAR -> OBSIDIAN_TEAR;
            case COLOR -> new TapParticleEmitter(appearance);
        };
    }

    void emit(World world, Location origin, int tick) {
        switch (appearance.style()) {
            case WATER -> emitNative(
                    world, origin, tick, Particle.DRIPPING_WATER,
                    Particle.FALLING_DRIPSTONE_WATER);
            case LAVA -> emitNative(
                    world, origin, tick, Particle.DRIPPING_LAVA,
                    Particle.FALLING_DRIPSTONE_LAVA);
            case HONEY -> emitNative(
                    world, origin, tick, Particle.DRIPPING_HONEY,
                    Particle.FALLING_HONEY);
            case OBSIDIAN_TEAR -> emitNative(
                    world, origin, tick, Particle.DRIPPING_OBSIDIAN_TEAR,
                    Particle.FALLING_OBSIDIAN_TEAR);
            case COLOR -> emitColored(world, origin, tick);
        }
    }

    private static void emitNative(World world, Location origin, int tick,
                                   Particle hanging, Particle falling) {
        if (tick <= TapBlockBehavior.TAKE_PARTICLE_TICKS) {
            world.spawnParticle(hanging, origin, 1, 0, 0, 0, 0);
        }
        if (tick <= TapBlockBehavior.TAKE_PARTICLE_TICKS
                + TapBlockBehavior.DRIP_LIFETIME_TICKS) {
            world.spawnParticle(falling, origin, 1, 0, 0, 0, 0);
        }
    }

    private void emitColored(World world, Location origin, int tick) {
        if (!coloredDropDue(tick)) {
            return;
        }
        if (coloredDrop == null) {
            // DUST is an expanding powder sprite and scales supplied motion
            // down on the client. TRAIL is Paper 26.2's native RGB particle
            // that interpolates smoothly to an exact target, so it reads as
            // a falling liquid bead without a server-side entity or task.
            coloredDrop = new Particle.Trail(
                    origin.clone().subtract(0, COLORED_DROP_DISTANCE, 0),
                    coloredDropColor, COLORED_DROP_DURATION_TICKS);
        }
        world.spawnParticle(Particle.TRAIL, origin, 1, 0, 0, 0, 0, coloredDrop);
    }

    static boolean coloredDropDue(int tick) {
        return tick > 0
                && tick <= TapBlockBehavior.TAKE_PARTICLE_TICKS
                + TapBlockBehavior.DRIP_LIFETIME_TICKS
                && (tick - 1) % COLORED_DROP_INTERVAL_TICKS == 0;
    }
}
