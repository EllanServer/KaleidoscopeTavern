package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PressingTubSemanticsTest {
    private static final double EPSILON = 1.0E-9;

    @Test
    void reproducesTheForgeFacingSouthTiltMatrix() {
        PressingTubSemantics.Point center = PressingTubSemantics.tiltSouth(0.5, 0.2, 0.5);
        assertEquals(0.5, center.x(), EPSILON);
        assertEquals(Math.sqrt(0.5) * 0.7, center.y(), EPSILON);
        assertEquals(1 - Math.sqrt(0.5) * 0.8, center.z(), EPSILON);

        PressingTubSemantics.Point offset = PressingTubSemantics.tiltSouth(0.65, 0.25, 0.35);
        assertEquals(0.35, offset.x(), EPSILON);
        assertEquals(Math.sqrt(0.5) * 0.6, offset.y(), EPSILON);
        assertEquals(1 - Math.sqrt(0.5) * 0.6, offset.z(), EPSILON);
    }
}
