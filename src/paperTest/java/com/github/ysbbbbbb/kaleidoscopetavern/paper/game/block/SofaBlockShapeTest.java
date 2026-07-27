package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import net.momirealms.craftengine.core.util.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.SofaConnectionSemantics.Connection.LEFT;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.SofaConnectionSemantics.Connection.LEFT_CORNER;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.SofaConnectionSemantics.Connection.MIDDLE;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.SofaConnectionSemantics.Connection.RIGHT;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.SofaConnectionSemantics.Connection.RIGHT_CORNER;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.SofaConnectionSemantics.Connection.SINGLE;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SofaBlockShapeTest {
    private static final SofaBlockShape.Box BASE =
            new SofaBlockShape.Box(0, 0, 0, 16, 8, 16);

    @Test
    void straightStatesUseTheSourceBaseAndBackrest() {
        assertEquals(List.of(
                        BASE,
                        new SofaBlockShape.Box(0, 8, 11, 16, 18, 16)),
                SofaBlockShape.boxes(Direction.NORTH, SINGLE));
        assertEquals(List.of(
                        BASE,
                        new SofaBlockShape.Box(0, 8, 0, 16, 18, 5)),
                SofaBlockShape.boxes(Direction.SOUTH, LEFT));
        assertEquals(List.of(
                        BASE,
                        new SofaBlockShape.Box(11, 8, 0, 16, 18, 16)),
                SofaBlockShape.boxes(Direction.WEST, SINGLE));
        assertEquals(List.of(
                        BASE,
                        new SofaBlockShape.Box(0, 8, 0, 5, 18, 16)),
                SofaBlockShape.boxes(Direction.EAST, SINGLE));
    }

    @Test
    void fourStraightVisualStatesShareOneCarrierShape() {
        assertEquals(SINGLE, SofaBlockShape.collisionConnection(SINGLE));
        assertEquals(SINGLE, SofaBlockShape.collisionConnection(LEFT));
        assertEquals(SINGLE, SofaBlockShape.collisionConnection(RIGHT));
        assertEquals(SINGLE, SofaBlockShape.collisionConnection(MIDDLE));
        assertEquals(LEFT_CORNER,
                SofaBlockShape.collisionConnection(LEFT_CORNER));
        assertEquals(RIGHT_CORNER,
                SofaBlockShape.collisionConnection(RIGHT_CORNER));
    }

    @Test
    void cornerStatesAddTheExactSourceSideBackrest() {
        assertEquals(List.of(
                        BASE,
                        new SofaBlockShape.Box(0, 8, 11, 16, 18, 16),
                        new SofaBlockShape.Box(11, 8, 0, 16, 18, 16)),
                SofaBlockShape.boxes(Direction.NORTH, LEFT_CORNER));
        assertEquals(List.of(
                        BASE,
                        new SofaBlockShape.Box(0, 8, 11, 16, 18, 16),
                        new SofaBlockShape.Box(0, 8, 0, 5, 18, 16)),
                SofaBlockShape.boxes(Direction.NORTH, RIGHT_CORNER));
        assertEquals(List.of(
                        BASE,
                        new SofaBlockShape.Box(0, 8, 0, 5, 18, 16),
                        new SofaBlockShape.Box(0, 8, 11, 16, 18, 16)),
                SofaBlockShape.boxes(Direction.EAST, LEFT_CORNER));
    }
}
