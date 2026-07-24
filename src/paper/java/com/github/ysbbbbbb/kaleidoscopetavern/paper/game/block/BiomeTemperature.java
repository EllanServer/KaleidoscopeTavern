package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import net.momirealms.craftengine.proxy.minecraft.core.HolderProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelReaderProxy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Reads the source-equivalent biome base temperature instead of height-adjusted weather temperature. */
final class BiomeTemperature {
    private static volatile Method baseTemperatureMethod;

    private BiomeTemperature() {
    }

    static double at(Object level, int blockX, int blockY, int blockZ) {
        Object holder = LevelReaderProxy.INSTANCE.getNoiseBiome(
                level, blockX >> 2, blockY >> 2, blockZ >> 2);
        Object biome = HolderProxy.INSTANCE.value(holder);
        try {
            Method method = baseTemperatureMethod;
            if (method == null || method.getDeclaringClass() != biome.getClass()) {
                method = biome.getClass().getMethod("getBaseTemperature");
                baseTemperatureMethod = method;
            }
            return ((Number) method.invoke(biome)).doubleValue();
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Unable to read biome base temperature", exception);
        }
    }
}
