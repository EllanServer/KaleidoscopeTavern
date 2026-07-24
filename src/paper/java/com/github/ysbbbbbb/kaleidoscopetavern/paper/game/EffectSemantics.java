package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

/** Pure drink/effect rules shared by the runtime and its parity tests. */
final class EffectSemantics {
    private EffectSemantics() {
    }

    static int decodeRemainingTicks(long stored, long nowMillis, boolean legacyEpochMillis) {
        long ticks = legacyEpochMillis ? (stored - nowMillis + 49L) / 50L : stored;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, ticks));
    }

    static boolean ticksAt(int remainingTicks, int interval) {
        return remainingTicks > 0 && remainingTicks % interval == 0;
    }

    static ContainerResult consumedContainer(int carriedDrinks, boolean creative) {
        if (carriedDrinks < 1) {
            throw new IllegalArgumentException("carriedDrinks must be positive");
        }
        if (creative) {
            return new ContainerResult(carriedDrinks, false, true);
        }
        if (carriedDrinks == 1) {
            return new ContainerResult(0, true, false);
        }
        return new ContainerResult(carriedDrinks - 1, false, true);
    }

    record ContainerResult(int remainingDrinks, boolean containerReplacesHand,
                           boolean returnContainerToInventory) {
    }
}
