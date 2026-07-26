package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

/**
 * Hit classification for the Forge barrel's 3x3x3 multiblock.
 *
 * <p>The source block only routes interactions from the nine blocks in its
 * ceiling layer.  The centre block (INDEX=4) owns fluid/ingredient access;
 * the other eight ceiling blocks only operate the lid.</p>
 */
final class BarrelSemantics {
    static final int CHECK_INTERVAL = 97;
    private static final double EPSILON = 1.0E-3;

    private BarrelSemantics() {
    }

    enum Hit {
        BODY,
        TOP_RIM,
        TOP_CENTER
    }

    /** Failure order used by {@code BarrelBlockEntity.canTapExtract}. */
    enum TapExtractStatus {
        READY,
        NOT_BREWING,
        EMPTY,
        INVALID_CONTAINER
    }

    /** One source {@code BarrelBlockEntity.tick} check for an active brew. */
    static BrewState advance(int level, int remainingTicks, int unitTicks) {
        if (level < 1 || level >= 6) {
            return new BrewState(level, remainingTicks);
        }
        if (remainingTicks > 0) {
            return new BrewState(level, remainingTicks - CHECK_INTERVAL);
        }
        int nextLevel = Math.min(level + 1, 6);
        return new BrewState(nextLevel, nextLevel >= 6 ? -1 : Math.max(1, unitTicks) * nextLevel);
    }

    record BrewState(int level, int remainingTicks) {
    }

    static TapExtractStatus tapExtractStatus(boolean brewing, int outputCount,
                                             boolean carrierRecipeValid) {
        if (!brewing) {
            return TapExtractStatus.NOT_BREWING;
        }
        if (outputCount <= 0) {
            return TapExtractStatus.EMPTY;
        }
        return carrierRecipeValid ? TapExtractStatus.READY : TapExtractStatus.INVALID_CONTAINER;
    }

    static Hit classify(double sourceX, double sourceY, double sourceZ) {
        if (sourceY < 2 - EPSILON || sourceY > 3 + EPSILON
                || Math.abs(sourceX) > 1.5 + EPSILON
                || Math.abs(sourceZ) > 1.5 + EPSILON) {
            return Hit.BODY;
        }
        if (Math.abs(sourceX) <= 0.5 + EPSILON
                && Math.abs(sourceZ) <= 0.5 + EPSILON) {
            return Hit.TOP_CENTER;
        }
        return Hit.TOP_RIM;
    }
}
