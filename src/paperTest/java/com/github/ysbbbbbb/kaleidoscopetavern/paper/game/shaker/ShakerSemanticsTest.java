package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.shaker;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.shaker.ShakerSemantics.ResultBand.HAND_RECIPE;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.shaker.ShakerSemantics.ResultBand.MYSTERY;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.shaker.ShakerSemantics.ResultBand.NONE;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.shaker.ShakerSemantics.ResultBand.SIGNATURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShakerSemanticsTest {
    @Test
    void preservesEverySourceTimingBoundary() {
        assertEquals(NONE, ShakerSemantics.resultBand(18));
        assertEquals(MYSTERY, ShakerSemantics.resultBand(19));
        assertEquals(MYSTERY, ShakerSemantics.resultBand(68));
        assertEquals(SIGNATURE, ShakerSemantics.resultBand(69));
        assertEquals(SIGNATURE, ShakerSemantics.resultBand(88));
        assertEquals(HAND_RECIPE, ShakerSemantics.resultBand(89));
        assertEquals(HAND_RECIPE, ShakerSemantics.resultBand(98));
        assertEquals(MYSTERY, ShakerSemantics.resultBand(99));
    }

    @Test
    void repeatsSoundEveryTenTicksAndReleasesAfterOneHundredTen() {
        assertTrue(ShakerSemantics.playsShakeSound(0));
        assertFalse(ShakerSemantics.playsShakeSound(9));
        assertTrue(ShakerSemantics.playsShakeSound(10));
        assertFalse(ShakerSemantics.shouldAutoRelease(110));
        assertTrue(ShakerSemantics.shouldAutoRelease(111));
    }

    @Test
    void preservesSourcePotionColorAndWholeSecondDurations() {
        assertEquals(40, ShakerSemantics.normalizePotionDurationTicks(59));
        assertEquals(0, ShakerSemantics.normalizePotionDurationTicks(-1));
        assertEquals(0xFFFFFF,
                ShakerSemantics.ingredientColor(
                        "minecraft:potion", OptionalInt.of(0x123456)).orElseThrow());
        assertEquals(0x123456,
                ShakerSemantics.ingredientColor(
                        "kaleidoscope_tavern:wine", OptionalInt.of(0x123456)).orElseThrow());
        assertTrue(ShakerSemantics.ingredientColor(
                "kaleidoscope_tavern:uncolored", OptionalInt.empty()).isEmpty());
    }

    @Test
    void mergesDurationsInWholeSecondsBeforeReturningTicks() {
        assertEquals(0, ShakerSemantics.mergedEffectDurationTicks(0));
        assertEquals(20, ShakerSemantics.mergedEffectDurationTicks(20));
        assertEquals(40, ShakerSemantics.mergedEffectDurationTicks(59));
        assertEquals(120, ShakerSemantics.mergedEffectDurationTicks(100));
    }

    @Test
    void averagesIngredientColorsPerChannelLikeForge() {
        assertEquals(0x7F7F7F,
                ShakerSemantics.mixIngredientColors(0x000000, 0xFFFFFF));
        assertEquals(0xFFFFFF, ShakerSemantics.mixIngredientColors());
    }
}
