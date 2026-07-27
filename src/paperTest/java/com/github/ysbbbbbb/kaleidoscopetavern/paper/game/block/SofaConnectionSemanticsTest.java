package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import net.momirealms.craftengine.core.util.Direction;
import org.junit.jupiter.api.Test;

import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.SofaConnectionSemantics.Connection.LEFT;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.SofaConnectionSemantics.Connection.LEFT_CORNER;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.SofaConnectionSemantics.Connection.MIDDLE;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.SofaConnectionSemantics.Connection.RIGHT;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.SofaConnectionSemantics.Connection.RIGHT_CORNER;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.SofaConnectionSemantics.Connection.SINGLE;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SofaConnectionSemanticsTest {
    @Test
    void straightRowsRetainSourceArmStatesForEveryFacing() {
        for (Direction facing : horizontalDirections()) {
            SofaConnectionSemantics.Neighbor same =
                    new SofaConnectionSemantics.Neighbor(facing, SINGLE);
            assertEquals(SINGLE, resolve(facing, null, null, null));
            assertEquals(RIGHT, resolve(facing, same, null, null));
            assertEquals(LEFT, resolve(facing, null, same, null));
            assertEquals(MIDDLE, resolve(facing, same, same, null));
        }
    }

    @Test
    void frontTurnsRetainSourceCornerPriority() {
        for (Direction facing : horizontalDirections()) {
            SofaConnectionSemantics.Neighbor same =
                    new SofaConnectionSemantics.Neighbor(facing, SINGLE);
            SofaConnectionSemantics.Neighbor frontLeft =
                    new SofaConnectionSemantics.Neighbor(facing.clockWise(), SINGLE);
            SofaConnectionSemantics.Neighbor frontRight =
                    new SofaConnectionSemantics.Neighbor(facing.counterClockWise(), SINGLE);

            assertEquals(RIGHT_CORNER,
                    resolve(facing, null, null, frontLeft));
            assertEquals(LEFT,
                    resolve(facing, null, same, frontLeft));
            assertEquals(LEFT_CORNER,
                    resolve(facing, null, null, frontRight));
            assertEquals(RIGHT,
                    resolve(facing, same, null, frontRight));
            assertEquals(MIDDLE,
                    resolve(facing, same, same, frontLeft));
        }
    }

    @Test
    void establishedCornerEndsDoNotReconnectThroughTheirClosedSide() {
        Direction facing = Direction.NORTH;
        SofaConnectionSemantics.Neighbor blockedFrontLeft =
                new SofaConnectionSemantics.Neighbor(
                        facing.clockWise(), LEFT_CORNER);
        SofaConnectionSemantics.Neighbor blockedFrontRight =
                new SofaConnectionSemantics.Neighbor(
                        facing.counterClockWise(), RIGHT_CORNER);
        assertEquals(SINGLE,
                resolve(facing, null, null, blockedFrontLeft));
        assertEquals(SINGLE,
                resolve(facing, null, null, blockedFrontRight));
    }

    @Test
    void perpendicularSideCompatibilityMatchesIConnectionBlock() {
        Direction facing = Direction.NORTH;
        SofaConnectionSemantics.Neighbor leftAcceptsRightEnd =
                new SofaConnectionSemantics.Neighbor(
                        facing.counterClockWise(), RIGHT);
        SofaConnectionSemantics.Neighbor leftRejectsLeftEnd =
                new SofaConnectionSemantics.Neighbor(
                        facing.counterClockWise(), LEFT);
        SofaConnectionSemantics.Neighbor rightAcceptsLeftEnd =
                new SofaConnectionSemantics.Neighbor(
                        facing.clockWise(), LEFT);
        SofaConnectionSemantics.Neighbor rightRejectsRightEnd =
                new SofaConnectionSemantics.Neighbor(
                        facing.clockWise(), RIGHT);

        assertEquals(RIGHT,
                resolve(facing, leftAcceptsRightEnd, null, null));
        assertEquals(SINGLE,
                resolve(facing, leftRejectsLeftEnd, null, null));
        assertEquals(LEFT,
                resolve(facing, null, rightAcceptsLeftEnd, null));
        assertEquals(SINGLE,
                resolve(facing, null, rightRejectsRightEnd, null));
    }

    private static SofaConnectionSemantics.Connection resolve(
            Direction facing,
            SofaConnectionSemantics.Neighbor left,
            SofaConnectionSemantics.Neighbor right,
            SofaConnectionSemantics.Neighbor front) {
        return SofaConnectionSemantics.connectionFor(
                facing, left, right, front);
    }

    private static Direction[] horizontalDirections() {
        return new Direction[] {
                Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
        };
    }
}
