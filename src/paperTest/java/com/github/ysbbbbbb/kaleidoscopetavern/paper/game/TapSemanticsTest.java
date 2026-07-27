package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TapSemanticsTest {
    @Test
    void acceptsOnlyTheMiddleCellOfTheBarrelFront() {
        int[][] facings = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        for (int[] facing : facings) {
            assertTrue(TapSemantics.isBarrelConnection(
                    12 + facing[0], 65, -4 + facing[1], facing[0], facing[1],
                    12, 64, -4, facing[0], facing[1]));
            assertFalse(TapSemantics.isBarrelConnection(
                    12 + facing[0], 64, -4 + facing[1], facing[0], facing[1],
                    12, 64, -4, facing[0], facing[1]));
            assertFalse(TapSemantics.isBarrelConnection(
                    12 + facing[0], 65, -4 + facing[1], -facing[0], -facing[1],
                    12, 64, -4, facing[0], facing[1]));
        }
    }

    @Test
    void emptyOpenUsesTheSourceSixTickLifetimeAndEvenCloudTicks() {
        assertFalse(TapSemantics.emitsEmptyCloud(1));
        assertTrue(TapSemantics.emitsEmptyCloud(2));
        assertFalse(TapSemantics.emitsEmptyCloud(3));
        assertTrue(TapSemantics.emitsEmptyCloud(4));
        assertFalse(TapSemantics.emitsEmptyCloud(5));
        assertTrue(TapSemantics.emitsEmptyCloud(TapSemantics.EMPTY_OPEN_TICKS));
        assertFalse(TapSemantics.emitsEmptyCloud(TapSemantics.EMPTY_OPEN_TICKS + 2));
    }

    @Test
    void droppedCarrierSearchUsesExactlyTheDestinationBlock() {
        assertEquals(new TapSemantics.BlockBounds(10, 64, -4, 11, 65, -3),
                TapSemantics.blockBounds(10, 64, -4));
    }

    @Test
    void onlyMolotovBarrelOutputUsesHotDrips() {
        assertTrue(TapSemantics.isHotBarrelOutput("kaleidoscope_tavern:molotov"));
        assertFalse(TapSemantics.isHotBarrelOutput("kaleidoscope_tavern:wine"));
        assertFalse(TapSemantics.isHotBarrelOutput(null));
    }

    @Test
    void finiteLavaTapConsumesItsSourceCauldron() {
        assertTrue(TapSemantics.shouldConsumeLavaSource(false));
        assertFalse(TapSemantics.shouldConsumeLavaSource(true));
        assertEquals(2, TapSemantics.lavaLevelAfterExtraction(3, 1, false));
        assertEquals(1, TapSemantics.lavaLevelAfterExtraction(2, 1, false));
        assertEquals(0, TapSemantics.lavaLevelAfterExtraction(1, 1, false));
        assertEquals(0, TapSemantics.lavaLevelAfterExtraction(3, 3, false));
        assertEquals(3, TapSemantics.lavaLevelAfterExtraction(3, 1, true));
    }

    @Test
    void sneakingWithTapDelegatesBarrelInteractionToCraftEnginePlacement() {
        assertTrue(TapSemantics.shouldDelegateBarrelTapPlacement(
                true, "kaleidoscope_tavern:tap"));
        assertFalse(TapSemantics.shouldDelegateBarrelTapPlacement(
                false, "kaleidoscope_tavern:tap"));
        assertFalse(TapSemantics.shouldDelegateBarrelTapPlacement(
                true, "kaleidoscope_tavern:barrel"));
        assertFalse(TapSemantics.shouldDelegateBarrelTapPlacement(true, null));
    }
}
