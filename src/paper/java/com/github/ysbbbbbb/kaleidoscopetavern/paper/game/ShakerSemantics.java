package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import java.util.OptionalInt;

/** Exact timing bands from {@code ShakerItem.releaseUsing}. */
public final class ShakerSemantics {
    static final int MINIMUM_TICKS = 19;
    static final int AUTO_RELEASE_AFTER_TICKS = 110;
    static final int VANILLA_POTION_COLOR = 0xFFFFFF;
    private static final int TICKS_PER_SECOND = 20;

    private ShakerSemantics() {
    }

    enum ResultBand {
        NONE,
        MYSTERY,
        SIGNATURE,
        HAND_RECIPE
    }

    static ResultBand resultBand(int ticks) {
        if (ticks < MINIMUM_TICKS) {
            return ResultBand.NONE;
        }
        if (ticks < 69) {
            return ResultBand.MYSTERY;
        }
        if (ticks < 89) {
            return ResultBand.SIGNATURE;
        }
        if (ticks < 99) {
            return ResultBand.HAND_RECIPE;
        }
        return ResultBand.MYSTERY;
    }

    static boolean playsShakeSound(int ticksUsingItem) {
        return ticksUsingItem >= 0 && ticksUsingItem % 10 == 0;
    }

    static boolean shouldAutoRelease(int ticks) {
        return ticks > AUTO_RELEASE_AFTER_TICKS;
    }

    /**
     * The Forge helper converted potion durations from ticks to whole seconds
     * before merging them into a signature cocktail.
     */
    static int normalizePotionDurationTicks(int durationTicks) {
        return Math.max(0, durationTicks) / TICKS_PER_SECOND * TICKS_PER_SECOND;
    }

    /** Forge treats every vanilla potion as white, independently of its liquid tint. */
    static OptionalInt ingredientColor(String itemId, OptionalInt catalogColor) {
        return itemId.equals("minecraft:potion")
                ? OptionalInt.of(VANILLA_POTION_COLOR)
                : catalogColor;
    }

    /**
     * Forge merged whole-second durations, multiplied by 1.2, truncated the
     * resulting seconds, and only then converted the result back to ticks.
     */
    public static int mergedEffectDurationTicks(int totalDurationTicks) {
        int seconds = Math.max(0, totalDurationTicks) / TICKS_PER_SECOND;
        return (int) (seconds * 1.2F) * TICKS_PER_SECOND;
    }

    /** Exact per-channel integer average used by {@code ColorUtils.mixColors}. */
    static int mixIngredientColors(int... colors) {
        if (colors.length == 0) {
            return VANILLA_POTION_COLOR;
        }
        int red = 0;
        int green = 0;
        int blue = 0;
        for (int rgb : colors) {
            red += rgb >> 16 & 0xFF;
            green += rgb >> 8 & 0xFF;
            blue += rgb & 0xFF;
        }
        return red / colors.length << 16
                | green / colors.length << 8
                | blue / colors.length;
    }
}
