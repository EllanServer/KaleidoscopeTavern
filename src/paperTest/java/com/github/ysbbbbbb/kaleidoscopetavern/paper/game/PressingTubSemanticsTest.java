package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PressingTubSemanticsTest {
    private static final double EPSILON = 1.0E-9;

    @Test
    void ignoresGroundMovementWhenNoFallIsBeingTracked() {
        assertFalse(PressingTubSemantics.needsMovementInspection(0, false));
        assertFalse(PressingTubSemantics.needsMovementInspection(-0.1F, false));
        assertTrue(PressingTubSemantics.needsMovementInspection(0.5F, false));
        assertTrue(PressingTubSemantics.needsMovementInspection(0, true));
    }

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

    @Test
    void groundTubOwnsOnlyItsSourceColumnAndLandingHeight() {
        assertTrue(PressingTubSemantics.isLandingPosition(
                10.5, 0.35, -2.5, 10, 0, -2));
        assertTrue(PressingTubSemantics.isLandingPosition(
                9.5, 1.25, -1.5, 10, 0, -2));

        assertFalse(PressingTubSemantics.isLandingPosition(
                10.5001, 0.5, -2, 10, 0, -2));
        assertFalse(PressingTubSemantics.isLandingPosition(
                10, 0.3499, -2, 10, 0, -2));
        assertFalse(PressingTubSemantics.isLandingPosition(
                10, 1.2501, -2, 10, 0, -2));
    }

    @Test
    void fallingTrackerAcceptsTheSameColumnAtAnyHeightAboveTheTub() {
        assertTrue(PressingTubSemantics.isAboveColumn(
                10.25, 80, -2.25, 10, 64, -2));
        assertFalse(PressingTubSemantics.isAboveColumn(
                10.51, 80, -2, 10, 64, -2));
        assertFalse(PressingTubSemantics.isAboveColumn(
                10, 64.34, -2, 10, 64, -2));
    }
}
