package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

/** Exact timing bands from {@code ShakerItem.releaseUsing}. */
final class ShakerSemantics {
    static final int MINIMUM_TICKS = 19;
    static final int AUTO_RELEASE_AFTER_TICKS = 110;

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
}
