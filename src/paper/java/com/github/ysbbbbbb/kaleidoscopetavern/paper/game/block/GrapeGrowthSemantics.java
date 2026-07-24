package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

/** Climate probability from {@code GrapeCropBlock.randomTick}. */
public final class GrapeGrowthSemantics {
    private static final double NORMAL_CHANCE = 0.25D;
    private static final double FAVOURED_CHANCE = 0.8D;

    private GrapeGrowthSemantics() {
    }

    public enum Variety {
        NORMAL,
        ICE,
        GOLD
    }

    public static double overallChance(Variety variety, double temperature) {
        return switch (variety) {
            case NORMAL -> NORMAL_CHANCE;
            case ICE -> temperature < 0.15D ? FAVOURED_CHANCE : NORMAL_CHANCE;
            case GOLD -> temperature > 1.0D ? FAVOURED_CHANCE : NORMAL_CHANCE;
        };
    }

}
