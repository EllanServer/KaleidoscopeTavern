package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape;

import net.momirealms.craftengine.proxy.minecraft.core.HolderProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelReaderProxy;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Reads the source-equivalent biome base temperature instead of height-adjusted weather temperature. */
final class BiomeTemperature {
    // Random ticks hit this on every growth roll, so the reflective lookup is
    // promoted to an invokeExact-able handle after the first call.
    private static volatile Class<?> biomeClass;
    private static volatile MethodHandle baseTemperature;

    private BiomeTemperature() {
    }

    static double at(Object level, int blockX, int blockY, int blockZ) {
        Object holder = LevelReaderProxy.INSTANCE.getNoiseBiome(
                level, blockX >> 2, blockY >> 2, blockZ >> 2);
        Object biome = HolderProxy.INSTANCE.value(holder);
        try {
            MethodHandle handle = baseTemperature;
            if (handle == null || biomeClass != biome.getClass()) {
                handle = MethodHandles.lookup()
                        .unreflect(biome.getClass().getMethod("getBaseTemperature"))
                        .asType(MethodType.methodType(double.class, Object.class));
                baseTemperature = handle;
                biomeClass = biome.getClass();
            }
            return (double) handle.invokeExact(biome);
        } catch (Throwable exception) {
            throw new IllegalStateException("Unable to read biome base temperature", exception);
        }
    }
}
