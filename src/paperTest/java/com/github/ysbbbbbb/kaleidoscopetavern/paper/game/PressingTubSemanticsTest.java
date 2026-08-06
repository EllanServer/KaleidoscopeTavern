package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PressingTubSemanticsTest {
    private static final double EPSILON = 1.0E-9;

    @Test
    void reproducesTheForgeFacingNorthTiltMatrix() {
        PressingTubSemantics.Point center = PressingTubSemantics.tiltNorth(0.5, 0.2, 0.5);
        assertEquals(0.5, center.x(), EPSILON);
        assertEquals(Math.sqrt(0.5) * 0.7, center.y(), EPSILON);
        assertEquals(Math.sqrt(0.5) * 0.8, center.z(), EPSILON);

        PressingTubSemantics.Point offset = PressingTubSemantics.tiltNorth(0.65, 0.25, 0.35);
        assertEquals(0.65, offset.x(), EPSILON);
        assertEquals(Math.sqrt(0.5) * 0.6, offset.y(), EPSILON);
        assertEquals(Math.sqrt(0.5) * 0.6, offset.z(), EPSILON);
    }

    @Test
    void mapsTiltedPointsIntoCraftEngineWallCoordinates() {
        PressingTubSemantics.Point offset = PressingTubSemantics.toWallFurnitureOffset(
                new PressingTubSemantics.Point(0.25, 0.75, 0.8));

        assertEquals(0.25, offset.x(), EPSILON);
        assertEquals(0.25, offset.y(), EPSILON);
        assertEquals(0.2, offset.z(), EPSILON);
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

    @Test
    void picksTheNearestTubAmongSameColumnDifferentHeights() {
        List<PressingTubSemantics.LandingTarget> targets = List.of(
                new PressingTubSemantics.LandingTarget(10, 60, -2),
                new PressingTubSemantics.LandingTarget(10, 64, -2),
                new PressingTubSemantics.LandingTarget(10, 68, -2));

        // 同一 X/Z、不同 Y：只有相对高度在 [0.35, 1.25] 的桶符合落地条件。
        assertEquals(1, PressingTubSemantics.nearestLanding(10, 64.5, -2, targets));
        assertEquals(2, PressingTubSemantics.nearestLanding(10, 68.5, -2, targets));
        // 距地面 68 的桶更远，但 64 高度的桶已不符合落地高度窗口。
        assertEquals(0, PressingTubSemantics.nearestLanding(10, 60.5, -2, targets));
        // 高度差超过窗口：没有可落地的桶。
        assertEquals(-1, PressingTubSemantics.nearestLanding(10, 70, -2, targets));
        assertEquals(-1, PressingTubSemantics.nearestLanding(10, 60, -2, targets));
        // 同一高度下选择水平距离更近的桶。
        List<PressingTubSemantics.LandingTarget> horizontal = List.of(
                new PressingTubSemantics.LandingTarget(9.9, 0, -2),
                new PressingTubSemantics.LandingTarget(10.4, 0, -2));
        assertEquals(1, PressingTubSemantics.nearestLanding(10.3, 0.6, -2, horizontal));
    }
}
