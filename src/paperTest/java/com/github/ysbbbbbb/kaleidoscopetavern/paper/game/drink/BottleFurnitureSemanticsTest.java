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
}
