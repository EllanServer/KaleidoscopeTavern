package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.drink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BottleFurnitureSemanticsTest {
    @Test
    void singleBottleUsesCraftEngineSourceItemWithoutDuplicateState() {
        assertFalse(BottleFurnitureSemantics.needsExpandedItemState(0));
        assertFalse(BottleFurnitureSemantics.needsExpandedItemState(1));
        assertEquals("ground", BottleFurnitureSemantics.variantForCount(1));
    }

    @Test
    void stackedBottlesRetainTheirIndividualItemsAndCountVariant() {
        assertTrue(BottleFurnitureSemantics.needsExpandedItemState(2));
        assertTrue(BottleFurnitureSemantics.needsExpandedItemState(4));
        assertEquals("ground_count_2", BottleFurnitureSemantics.variantForCount(2));
        assertEquals("ground_count_4", BottleFurnitureSemantics.variantForCount(4));
    }

    @Test
    void cardinalBottleVariantPreservesFourWayDisplayAxis() {
        assertEquals("ground", BottleFurnitureSemantics.variantForCount(1, 0));
        assertEquals("ground_axis_x", BottleFurnitureSemantics.variantForCount(1, 90));
        assertEquals("ground_count_4", BottleFurnitureSemantics.variantForCount(4, 180));
        assertEquals("ground_count_4_axis_x",
                BottleFurnitureSemantics.variantForCount(4, -90));
        assertEquals("ground_count_2_axis_x",
                BottleFurnitureSemantics.withCardinalAxis(
                        "ground_count_2_axis_x", 270));
        assertEquals("ground_count_2",
                BottleFurnitureSemantics.withCardinalAxis(
                        "ground_count_2_axis_x", 360));
    }
}
