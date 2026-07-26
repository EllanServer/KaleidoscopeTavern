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
        assertFalse(TapSemantics.emitsEmptyCloud(TapSemantics.EMPTY_OPEN_TICKS));
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
}
