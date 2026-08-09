package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.storage;

import org.junit.jupiter.api.Test;

import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.storage.StorageSemantics.Kind.BAR_CABINET;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.storage.StorageSemantics.Kind.CELLAR_CABINET;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.storage.StorageSemantics.Kind.CIRCULAR_RACK;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.storage.StorageSemantics.Kind.GLASSWARE_HOLDER;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.storage.StorageSemantics.Kind.HOLDER;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.storage.StorageSemantics.Kind.TILTED_RACK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageSemanticsTest {
    private static final double EPSILON = 1.0E-6;

    @Test
    void blockModelCentersMatchEveryForgeStorageRenderer() {
        assertVisual(StorageSemantics.visual(BAR_CABINET, 0, false, false),
                0.75, 0.5125, 0.5, 0.9F, 0, 0, true);
        assertVisual(StorageSemantics.visual(BAR_CABINET, 0, false, true),
                0.25, 0.5125, 0.5, 0.9F, 0, 0, true);
        assertVisual(StorageSemantics.visual(BAR_CABINET, 0, true, false),
                0.5, 0.5125, 0.5, 0.9F, 0, 0, true);

        assertVisual(StorageSemantics.visual(CELLAR_CABINET, 0, false, false),
                0.825, 0.78, 0.375, 1, 0, -90, true);
        assertVisual(StorageSemantics.visual(CELLAR_CABINET, 8, false, false),
                0.175, 0.20, 0.375, 1, 0, -90, true);

        StorageSemantics.Visual tilted = StorageSemantics.visual(TILTED_RACK, 0, false, false);
        assertEquals(0.8325, tilted.centerX(), EPSILON);
        assertEquals(0.9 * (0.3125 + (Math.cos(Math.toRadians(22.5))
                - Math.sin(Math.toRadians(22.5))) * 0.5), tilted.centerY(), EPSILON);
        assertEquals(0.9 * (0.015 + (Math.sin(Math.toRadians(22.5))
                + Math.cos(Math.toRadians(22.5))) * 0.5), tilted.centerZ(), EPSILON);
        assertEquals(22.5F, tilted.xRot(), EPSILON);

        assertVisual(StorageSemantics.visual(CIRCULAR_RACK, 1, false, false),
                0.875, 0.535, 0.3125, 0.82F, 22.5, 0, true);

        StorageSemantics.Visual holder = StorageSemantics.visual(HOLDER, 0, false, false);
        assertEquals(0.5, holder.centerX(), EPSILON);
        assertEquals(0.125 + Math.cos(Math.toRadians(45)) * 0.475,
                holder.centerY(), EPSILON);
        assertEquals(0.75 - Math.sin(Math.toRadians(45)) * 0.475,
                holder.centerZ(), EPSILON);
        assertEquals(-45F, holder.xRot(), EPSILON);

        assertVisual(StorageSemantics.visual(GLASSWARE_HOLDER, 0, false, false),
                0.25, 0.26, 0.25, 1, 0, -180, false);
        assertVisual(StorageSemantics.visual(GLASSWARE_HOLDER, 3, false, false),
                0.75, 0.26, 0.75, 1, 0, -180, false);
    }

    @Test
    void rackBodiesAndStoredItemsUseTheSameFacingRotation() {
        for (StorageSemantics.Kind kind : new StorageSemantics.Kind[]{
                TILTED_RACK, HOLDER, CIRCULAR_RACK}) {
            assertFacingRotation(kind, 0F, false, 0F, 0F);
            assertFacingRotation(kind, 180F, false, 180F, 180F);
            assertFacingRotation(kind, -90F, true, -90F, -90F);
            assertFacingRotation(kind, 90F, true, 90F, 90F);
        }
    }

    @Test
    void cellarConnectionChangesRefreshStoredBottleDisplays() {
        assertTrue(StorageSemantics.changesRenderedArrangement(
                CELLAR_CABINET, false, true));
        assertTrue(StorageSemantics.changesRenderedArrangement(
                CELLAR_CABINET, true, false));
        assertFalse(StorageSemantics.changesRenderedArrangement(
                CELLAR_CABINET, false, false));
        assertFalse(StorageSemantics.changesRenderedArrangement(
                HOLDER, false, true));
    }

    @Test
    void clickedSlotsMatchForgeBlockAlgorithms() {
        assertEquals(0, StorageSemantics.clickedSlot(BAR_CABINET, 0.75, 0.5, 0, false));
        assertEquals(0, StorageSemantics.clickedSlot(BAR_CABINET, 0.25, 0.5, 0, true));

        assertEquals(0, StorageSemantics.clickedSlot(CELLAR_CABINET, 0.825, 0.78, 0, false));
        assertEquals(8, StorageSemantics.clickedSlot(CELLAR_CABINET, 0.175, 0.20, 0, false));
        assertEquals(-1, StorageSemantics.clickedSlot(CELLAR_CABINET, 0.825, 0.78, 1, false));

        assertEquals(0, StorageSemantics.clickedSlot(TILTED_RACK, 0.825, 0.5, 0.5, false));
        assertEquals(1, StorageSemantics.clickedSlot(TILTED_RACK, 0.5, 0.5, 0.5, false));
        assertEquals(2, StorageSemantics.clickedSlot(TILTED_RACK, 0.175, 0.5, 0.5, false));

        double[][] circular = {
                {0.5, 0.125}, {0.875, 0.3125}, {0.875, 0.6875},
                {0.5, 0.875}, {0.125, 0.6875}, {0.125, 0.3125}
        };
        for (int slot = 0; slot < circular.length; slot++) {
            assertEquals(slot, StorageSemantics.clickedSlot(
                    CIRCULAR_RACK, circular[slot][0], 0.5, circular[slot][1], false));
        }

        assertEquals(0, StorageSemantics.clickedSlot(GLASSWARE_HOLDER, 0.25, 0.5, 0.25, false));
        assertEquals(1, StorageSemantics.clickedSlot(GLASSWARE_HOLDER, 0.75, 0.5, 0.25, false));
        assertEquals(2, StorageSemantics.clickedSlot(GLASSWARE_HOLDER, 0.25, 0.5, 0.75, false));
        assertEquals(3, StorageSemantics.clickedSlot(GLASSWARE_HOLDER, 0.75, 0.5, 0.75, false));
        assertEquals(0, StorageSemantics.clickedSlot(GLASSWARE_HOLDER, 0.25, -0.25, 0.25, false));
        assertEquals(3, StorageSemantics.clickedSlot(GLASSWARE_HOLDER, 0.75, 1.25, 0.75, false));
        assertEquals(-1, StorageSemantics.clickedSlot(GLASSWARE_HOLDER, -0.25, 0.5, 0.25, false));
        assertEquals(-1, StorageSemantics.clickedSlot(GLASSWARE_HOLDER, 0.25, 0.5, 1.25, false));
    }

    private static void assertVisual(StorageSemantics.Visual actual,
                                     double x, double y, double z, float scale,
                                     double yRot, double xRot, boolean facing) {
        assertEquals(x, actual.centerX(), EPSILON);
        assertEquals(y, actual.centerY(), EPSILON);
        assertEquals(z, actual.centerZ(), EPSILON);
        assertEquals(scale, actual.scale(), EPSILON);
        assertEquals(yRot, actual.yRot(), EPSILON);
        assertEquals(xRot, actual.xRot(), EPSILON);
        if (facing) {
            assertTrue(actual.rotateWithFacing());
        } else {
            assertFalse(actual.rotateWithFacing());
        }
    }

    private static void assertFacingRotation(StorageSemantics.Kind kind,
                                             float sourceYaw, boolean axisX,
                                             float positionYaw, float modelYaw) {
        StorageSemantics.FacingRotation actual = StorageSemantics.facingRotation(
                kind, sourceYaw, axisX);
        assertEquals(positionYaw, actual.positionYaw(), EPSILON);
        assertEquals(modelYaw, actual.modelYaw(), EPSILON);
    }
}
