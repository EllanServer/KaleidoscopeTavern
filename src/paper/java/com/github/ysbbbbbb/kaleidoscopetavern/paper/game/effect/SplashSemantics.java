package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.effect;

/** Pure calculations copied from Minecraft's splash-potion application loop. */
final class SplashSemantics {
    private SplashSemantics() {
    }

    /**
     * Vanilla rounds the distance-scaled duration and discards results of
     * twenty ticks or less. Instantaneous effects do not use this method.
     */
    static int scaledDuration(int baseDurationTicks, double intensity) {
        int scaled = (int) Math.round(baseDurationTicks * intensity);
        return scaled > 20 ? scaled : 0;
    }
}
