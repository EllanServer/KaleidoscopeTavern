package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import org.junit.jupiter.api.Test;

import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.ShakerSemantics.ResultBand.HAND_RECIPE;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.ShakerSemantics.ResultBand.MYSTERY;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.ShakerSemantics.ResultBand.NONE;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.ShakerSemantics.ResultBand.SIGNATURE;
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
}
