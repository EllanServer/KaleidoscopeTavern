package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrellisConnectionSemanticsTest {
    @Test
    void verticalPlacementNeverCollapsesIntoAHorizontalShape() {
        assertEquals("single",
                TrellisConnectionSemantics.typeFor("y", false, false, false));
        assertEquals("cross_east_west",
                TrellisConnectionSemantics.typeFor("y", true, false, false));
        assertEquals("cross_north_south",
                TrellisConnectionSemantics.typeFor("y", false, false, true));
        assertEquals("six_direction",
                TrellisConnectionSemantics.typeFor("y", true, false, true));
    }

    @Test
    void nativePlacementAxisIsAlwaysPartOfTheResult() {
        for (String axis : new String[]{"x", "y", "z"}) {
            for (int mask = 0; mask < 8; mask++) {
                String type = TrellisConnectionSemantics.typeFor(
                        axis, (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0);
                assertTrue(TrellisConnectionSemantics.containsAxis(type, axis),
                        axis + " was lost from " + type + " for mask " + mask);
            }
        }
    }
}
