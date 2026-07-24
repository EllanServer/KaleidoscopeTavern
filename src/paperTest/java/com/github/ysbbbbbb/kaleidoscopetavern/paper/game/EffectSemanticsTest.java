package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectSemanticsTest {
    @Test
    void persistedTickDurationsDoNotAdvanceWhileOffline() {
        assertEquals(1_200, EffectSemantics.decodeRemainingTicks(
                1_200, 9_999_999_999L, false));
    }

    @Test
    void legacyEpochExpiryIsConvertedOnce() {
        assertEquals(21, EffectSemantics.decodeRemainingTicks(
                1_700_000_001_050L, 1_700_000_000_000L, true));
        assertEquals(20, EffectSemantics.decodeRemainingTicks(
                12_000_000, 11_999_000, true));
    }

    @Test
    void sourceCallbacksUseRemainingDurationModulo() {
        assertTrue(EffectSemantics.ticksAt(600, 50));
        assertTrue(EffectSemantics.ticksAt(590, 10));
        assertFalse(EffectSemantics.ticksAt(599, 50));
    }

    @Test
    void lastSurvivalDrinkIsReplacedInHand() {
        EffectSemantics.ContainerResult result = EffectSemantics.consumedContainer(1, false);
        assertEquals(0, result.remainingDrinks());
        assertTrue(result.containerReplacesHand());
        assertFalse(result.returnContainerToInventory());
    }

    @Test
    void stackedSurvivalDrinkLosesOnlyOneAndReturnsContainer() {
        EffectSemantics.ContainerResult result = EffectSemantics.consumedContainer(16, false);
        assertEquals(15, result.remainingDrinks());
        assertFalse(result.containerReplacesHand());
        assertTrue(result.returnContainerToInventory());
    }

    @Test
    void creativeDrinkIsKeptAndReturnsContainer() {
        EffectSemantics.ContainerResult result = EffectSemantics.consumedContainer(16, true);
        assertEquals(16, result.remainingDrinks());
        assertFalse(result.containerReplacesHand());
        assertTrue(result.returnContainerToInventory());
    }
}
