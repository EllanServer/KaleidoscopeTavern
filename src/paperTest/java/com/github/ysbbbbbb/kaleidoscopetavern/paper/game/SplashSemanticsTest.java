package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SplashSemanticsTest {
    @Test
    void roundsDurationUsingVanillaSplashIntensity() {
        assertEquals(81, SplashSemantics.scaledDuration(161, 0.5));
        assertEquals(120, SplashSemantics.scaledDuration(120, 1.0));
    }

    @Test
    void discardsScaledEffectsAtTwentyTicksOrLess() {
        assertEquals(0, SplashSemantics.scaledDuration(40, 0.5));
        assertEquals(0, SplashSemantics.scaledDuration(400, 0.05));
        assertEquals(21, SplashSemantics.scaledDuration(42, 0.5));
    }

    @Test
    void zeroIntensityNeverCreatesAOneTickEffect() {
        assertEquals(0, SplashSemantics.scaledDuration(54_000, 0.0));
    }
}
