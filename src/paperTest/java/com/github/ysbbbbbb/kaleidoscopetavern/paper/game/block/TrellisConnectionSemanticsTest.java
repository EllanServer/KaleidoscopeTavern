package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrellisConnectionSemanticsTest {
    @Test
    void verticalPlacementNeverCollapsesIntoAHorizontalShape() {
        assertEquals("single",
                TrellisBehavior.typeFor("y", false, false, false));
        assertEquals("cross_east_west",
                TrellisBehavior.typeFor("y", true, false, false));
        assertEquals("cross_north_south",
                TrellisBehavior.typeFor("y", false, false, true));
        assertEquals("six_direction",
                TrellisBehavior.typeFor("y", true, false, true));
    }

    @Test
    void nativePlacementAxisIsAlwaysPartOfTheResult() {
        for (String axis : new String[]{"x", "y", "z"}) {
            for (int mask = 0; mask < 8; mask++) {
                String type = TrellisBehavior.typeFor(
                        axis, (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0);
                assertTrue(containsAxis(type, axis),
                        axis + " was lost from " + type + " for mask " + mask);
            }
        }
    }

    private static boolean containsAxis(String type, String axis) {
        return switch (axis) {
            case "x" -> type.equals("east_west")
                    || type.equals("cross_east_west")
                    || type.equals("cross_up_down")
                    || type.equals("six_direction");
            case "y" -> type.equals("single")
                    || type.equals("cross_east_west")
                    || type.equals("cross_north_south")
                    || type.equals("six_direction");
            case "z" -> type.equals("north_south")
                    || type.equals("cross_north_south")
                    || type.equals("cross_up_down")
                    || type.equals("six_direction");
            default -> false;
        };
    }
}
