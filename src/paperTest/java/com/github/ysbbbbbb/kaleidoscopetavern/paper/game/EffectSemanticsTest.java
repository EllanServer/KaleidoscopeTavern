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
    void probabilityBoundaryIsExclusiveLikeTheSource() {
        assertTrue(EffectSemantics.rolls(0.299_999, 0.3));
        assertFalse(EffectSemantics.rolls(0.3, 0.3));
        assertFalse(EffectSemantics.rolls(0.0, 0.0));
    }

    @Test
    void oneExperiencePickupConsumesOneMergedOrbOnly() {
        assertEquals(0, EffectSemantics.remainingOrbCountAfterPickup(1));
        assertEquals(3, EffectSemantics.remainingOrbCountAfterPickup(4));
    }

    @Test
    void nmsHeightmapDestinationIsAboveBukkitsHighestBlock() {
        assertEquals(65, EffectSemantics.surfaceY(64));
        assertEquals(-63, EffectSemantics.surfaceY(-64));
    }

    @Test
    void ardentHeatEndsOnlyWhenBothFoodResourcesAreExhausted() {
        assertTrue(EffectSemantics.ardentHeatExhausted(0, 0.0F));
        assertTrue(EffectSemantics.ardentHeatExhausted(0, 0.01F));
        assertFalse(EffectSemantics.ardentHeatExhausted(1, 0.0F));
        assertFalse(EffectSemantics.ardentHeatExhausted(0, 0.02F));
    }

    @Test
    void onlyVanillaCropBlocksRequireMaturityForGrassStealth() {
        assertFalse(EffectSemantics.isGrassStealthPlant(true, false, true));
        assertTrue(EffectSemantics.isGrassStealthPlant(true, true, false));
        assertTrue(EffectSemantics.isGrassStealthPlant(false, false, true));
        assertFalse(EffectSemantics.isGrassStealthPlant(false, true, false));
    }

    @Test
    void strongerShortEffectHidesAndThenRestoresTheOldEffect() {
        EffectSemantics.EffectState weak = new EffectSemantics.EffectState(200, 0, null);
        EffectSemantics.EffectState strong = EffectSemantics.mergeEffect(weak, 40, 2);

        assertEquals(40, strong.remainingTicks());
        assertEquals(2, strong.amplifier());
        assertEquals(weak, strong.hidden());

        EffectSemantics.EffectState restored = EffectSemantics.advanceEffect(strong, 40);
        assertEquals(160, restored.remainingTicks());
        assertEquals(0, restored.amplifier());
    }

    @Test
    void weakerLongEffectWaitsInTheHiddenChain() {
        EffectSemantics.EffectState strong = new EffectSemantics.EffectState(40, 2, null);
        EffectSemantics.EffectState merged = EffectSemantics.mergeEffect(strong, 200, 0);

        assertEquals(40, merged.remainingTicks());
        assertEquals(2, merged.amplifier());
        assertEquals(new EffectSemantics.EffectState(200, 0, null), merged.hidden());

        EffectSemantics.EffectState restored = EffectSemantics.advanceEffect(merged, 40);
        assertEquals(new EffectSemantics.EffectState(160, 0, null), restored);
    }

    @Test
    void hiddenEffectsRetainTheirOwnPriorityChain() {
        EffectSemantics.EffectState state = new EffectSemantics.EffectState(200, 0, null);
        state = EffectSemantics.mergeEffect(state, 40, 2);
        state = EffectSemantics.mergeEffect(state, 100, 1);

        assertEquals(2, state.amplifier());
        assertEquals(1, state.hidden().amplifier());
        assertEquals(0, state.hidden().hidden().amplifier());

        state = EffectSemantics.advanceEffect(state, 40);
        assertEquals(new EffectSemantics.EffectState(60, 1,
                new EffectSemantics.EffectState(160, 0, null)), state);
        state = EffectSemantics.advanceEffect(state, 60);
        assertEquals(new EffectSemantics.EffectState(100, 0, null), state);
    }

    @Test
    void equalAmplifierOnlyExtendsDuration() {
        EffectSemantics.EffectState current = new EffectSemantics.EffectState(100, 1, null);
        assertEquals(current, EffectSemantics.mergeEffect(current, 80, 1));
        assertEquals(new EffectSemantics.EffectState(160, 1, null),
                EffectSemantics.mergeEffect(current, 160, 1));
    }

    @Test
    void directClearCommandsAreDetectedWithoutRequiringAVanillaEffect() {
        EffectSemantics.ClearCommand self = EffectSemantics.parseClearCommand("/effect clear")
                .orElseThrow();
        assertTrue(self.targetsSender());

        EffectSemantics.ClearCommand targets = EffectSemantics.parseClearCommand(
                "minecraft:effect clear @a").orElseThrow();
        assertFalse(targets.targetsSender());
        assertEquals("@a", targets.target());

        assertTrue(EffectSemantics.parseClearCommand("/EFFECT CLEAR").orElseThrow()
                .targetsSender());

        assertTrue(EffectSemantics.parseClearCommand("/effect clear @a minecraft:speed").isEmpty());
        assertTrue(EffectSemantics.parseClearCommand("/execute as @a run effect clear @s").isEmpty());
        assertTrue(EffectSemantics.parseClearCommand("/effects clear").isEmpty());
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
