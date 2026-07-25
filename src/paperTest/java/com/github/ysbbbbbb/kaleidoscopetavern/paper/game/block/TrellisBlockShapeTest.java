package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrellisBlockShapeTest {
    private static final TrellisBlockShape.Box VERTICAL =
            new TrellisBlockShape.Box(6, 0, 6, 10, 16, 10);
    private static final TrellisBlockShape.Box NORTH_SOUTH =
            new TrellisBlockShape.Box(6, 6, 0, 10, 10, 16);
    private static final TrellisBlockShape.Box EAST_WEST =
            new TrellisBlockShape.Box(0, 6, 6, 16, 10, 10);

    @Test
    void collisionShapesMatchITrellisAxisUnions() {
        assertEquals(List.of(VERTICAL), TrellisBlockShape.collisionBoxes("single"));
        assertEquals(List.of(NORTH_SOUTH), TrellisBlockShape.collisionBoxes("north_south"));
        assertEquals(List.of(EAST_WEST), TrellisBlockShape.collisionBoxes("east_west"));
        assertEquals(List.of(VERTICAL, NORTH_SOUTH),
                TrellisBlockShape.collisionBoxes("cross_north_south"));
        assertEquals(List.of(VERTICAL, EAST_WEST),
                TrellisBlockShape.collisionBoxes("cross_east_west"));
        assertEquals(List.of(NORTH_SOUTH, EAST_WEST),
                TrellisBlockShape.collisionBoxes("cross_up_down"));
        assertEquals(List.of(VERTICAL, NORTH_SOUTH, EAST_WEST),
                TrellisBlockShape.collisionBoxes("six_direction"));
    }
}
