package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FurnitureSpatialSemanticsTest {
    @Test
    void columnRangeUsesFloorCorrectlyAcrossNegativeCoordinates() {
        assertEquals(-2, FurnitureSpatialSemantics.minimumColumn(-0.25, 1.5));
        assertEquals(1, FurnitureSpatialSemantics.maximumColumn(-0.25, 1.5));
        assertEquals(-1, FurnitureSpatialSemantics.minimumColumn(0.5, 1.5));
        assertEquals(2, FurnitureSpatialSemantics.maximumColumn(0.5, 1.5));
    }

    @Test
    void indexedQueryPreservesBukkitAxisAlignedRangeBoundaries() {
        assertTrue(FurnitureSpatialSemantics.insideBox(
                -1.75, 65.25, 1.25, -0.25, 64, -0.25, 1.5, 1.25));
        assertFalse(FurnitureSpatialSemantics.insideBox(
                -1.7501, 65.25, 1.25, -0.25, 64, -0.25, 1.5, 1.25));
        assertFalse(FurnitureSpatialSemantics.insideBox(
                -1.75, 65.2501, 1.25, -0.25, 64, -0.25, 1.5, 1.25));
    }
}
