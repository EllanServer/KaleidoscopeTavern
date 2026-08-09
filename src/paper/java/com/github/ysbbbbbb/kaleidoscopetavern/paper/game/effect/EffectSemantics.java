package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.effect;

import java.util.Optional;

/** Pure drink/effect rules shared by the runtime and its parity tests. */
final class EffectSemantics {
    private EffectSemantics() {
    }

    static long[] encodeState(EffectState state) {
        int layers = 0;
        for (EffectState current = state; current != null; current = current.hidden()) {
            layers++;
        }
        long[] encoded = new long[layers * 2];
        int index = 0;
        for (EffectState current = state; current != null; current = current.hidden()) {
            encoded[index++] = current.remainingTicks();
            encoded[index++] = current.amplifier();
        }
        return encoded;
    }

    static EffectState decodeState(long[] encoded) {
        if (encoded == null || encoded.length < 2 || encoded.length % 2 != 0) {
            return null;
        }
        EffectState state = null;
        for (int index = encoded.length - 2; index >= 0; index -= 2) {
            long remaining = encoded[index];
            long amplifier = encoded[index + 1];
            if (remaining < Integer.MIN_VALUE || remaining > Integer.MAX_VALUE
                    || amplifier < Integer.MIN_VALUE || amplifier > Integer.MAX_VALUE) {
                return null;
            }
            state = new EffectState((int) remaining, (int) amplifier, state);
        }
        return state;
    }

    static boolean ticksAt(int remainingTicks, int interval) {
        return remainingTicks > 0 && remainingTicks % interval == 0;
    }

    static boolean rolls(double roll, double probability) {
        return roll < probability;
    }

    static int remainingOrbCountAfterPickup(int orbCount) {
        if (orbCount < 1) {
            throw new IllegalArgumentException("orbCount must be positive");
        }
        return orbCount - 1;
    }

    /** Bukkit reports the top blocking block; NMS Heightmap#getHeight reports the air above it. */
    static int surfaceY(int highestBlockingBlockY) {
        return highestBlockingBlockY + 1;
    }

    static boolean ardentHeatExhausted(int foodLevel, float saturation) {
        return foodLevel <= 0 && saturation <= 0.01F;
    }

    static boolean isGrassStealthPlant(boolean vanillaCropBlock, boolean mature, boolean tagged) {
        return vanillaCropBlock ? mature : tagged;
    }

    /**
     * Mirrors {@code MobEffectInstance#update}: a stronger, shorter effect hides
     * the current one, while a weaker effect is retained only when it outlasts
     * the currently visible layer.
     */
    static EffectState mergeEffect(EffectState current, int durationTicks, int amplifier) {
        if (durationTicks <= 0) {
            return current;
        }
        if (current == null) {
            return new EffectState(durationTicks, amplifier, null);
        }
        if (amplifier > current.amplifier()) {
            EffectState hidden = durationTicks < current.remainingTicks()
                    ? current : current.hidden();
            return new EffectState(durationTicks, amplifier, hidden);
        }
        if (durationTicks <= current.remainingTicks()) {
            return current;
        }
        if (amplifier == current.amplifier()) {
            return new EffectState(durationTicks, amplifier, current.hidden());
        }
        return new EffectState(current.remainingTicks(), current.amplifier(),
                mergeEffect(current.hidden(), durationTicks, amplifier));
    }

    /** Decrements the visible and hidden layers together and promotes on expiry. */
    static EffectState advanceEffect(EffectState current, int elapsedTicks) {
        if (current == null || elapsedTicks == 0) {
            return current;
        }
        if (elapsedTicks < 0) {
            throw new IllegalArgumentException("elapsedTicks must not be negative");
        }
        EffectState next = decrementChain(current, elapsedTicks);
        while (next != null && next.remainingTicks() <= 0) {
            next = next.hidden();
        }
        return next;
    }

    private static EffectState decrementChain(EffectState state, int elapsedTicks) {
        if (state == null) {
            return null;
        }
        return new EffectState(state.remainingTicks() - elapsedTicks, state.amplifier(),
                decrementChain(state.hidden(), elapsedTicks));
    }

    /**
     * Allocation-free runtime form of the visible/hidden effect chain. The
     * immutable form above remains the reference for merges and persistence;
     * this form only avoids rebuilding every layer on every server tick.
     */
    static final class MutableEffectState {
        private final int[] remainingTicks;
        private final int[] amplifiers;
        private int firstLayer;

        MutableEffectState(EffectState state) {
            if (state == null) {
                throw new IllegalArgumentException("state must not be null");
            }
            int layers = 0;
            for (EffectState current = state; current != null; current = current.hidden()) {
                layers++;
            }
            remainingTicks = new int[layers];
            amplifiers = new int[layers];
            int index = 0;
            for (EffectState current = state; current != null; current = current.hidden()) {
                remainingTicks[index] = current.remainingTicks();
                amplifiers[index] = current.amplifier();
                index++;
            }
        }

        int remainingTicks() {
            requireLive();
            return remainingTicks[firstLayer];
        }

        int amplifier() {
            requireLive();
            return amplifiers[firstLayer];
        }

        boolean advance(int elapsedTicks) {
            if (elapsedTicks < 0) {
                throw new IllegalArgumentException("elapsedTicks must not be negative");
            }
            if (elapsedTicks == 0) {
                return firstLayer < remainingTicks.length;
            }
            for (int index = firstLayer; index < remainingTicks.length; index++) {
                remainingTicks[index] -= elapsedTicks;
            }
            while (firstLayer < remainingTicks.length
                    && remainingTicks[firstLayer] <= 0) {
                firstLayer++;
            }
            return firstLayer < remainingTicks.length;
        }

        EffectState snapshot() {
            return snapshotAfter(0);
        }

        /**
         * Builds a persistence snapshot at a later tick without mutating the
         * runtime countdown. This lets passive effects skip per-tick writes
         * while quit/unload saves still contain an exact remaining duration.
         */
        EffectState snapshotAfter(int elapsedTicks) {
            if (elapsedTicks < 0) {
                throw new IllegalArgumentException("elapsedTicks must not be negative");
            }
            int projectedFirst = firstLayer;
            while (projectedFirst < remainingTicks.length
                    && (long) remainingTicks[projectedFirst] - elapsedTicks <= 0L) {
                projectedFirst++;
            }
            EffectState result = null;
            for (int index = remainingTicks.length - 1; index >= projectedFirst; index--) {
                long projected = (long) remainingTicks[index] - elapsedTicks;
                int remaining = (int) Math.max(Integer.MIN_VALUE, projected);
                result = new EffectState(remaining, amplifiers[index], result);
            }
            return result;
        }

        private void requireLive() {
            if (firstLayer >= remainingTicks.length) {
                throw new IllegalStateException("effect state has expired");
            }
        }
    }

    static Optional<ClearCommand> parseClearCommand(String command) {
        if (command == null) {
            return Optional.empty();
        }
        String normalized = command.strip();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1).stripLeading();
        }
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        if (!normalized.regionMatches(true, 0, "effect", 0, "effect".length())
                && !normalized.regionMatches(true, 0, "minecraft:effect", 0,
                "minecraft:effect".length())) {
            return Optional.empty();
        }
        String[] fields = normalized.split("\\s+");
        if (fields.length < 2 || fields.length > 3
                || !(fields[0].equalsIgnoreCase("effect")
                || fields[0].equalsIgnoreCase("minecraft:effect"))
                || !fields[1].equalsIgnoreCase("clear")) {
            return Optional.empty();
        }
        return Optional.of(fields.length == 2
                ? new ClearCommand(true, "")
                : new ClearCommand(false, fields[2]));
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

    record EffectState(int remainingTicks, int amplifier, EffectState hidden) {
    }

    record ClearCommand(boolean targetsSender, String target) {
    }
}
