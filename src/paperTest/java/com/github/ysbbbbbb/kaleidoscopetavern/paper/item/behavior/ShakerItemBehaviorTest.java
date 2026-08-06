package com.github.ysbbbbbb.kaleidoscopetavern.paper.item.behavior;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShakerItemBehaviorTest {
    @Test
    void ordinaryBlockUseStartsPortableMixing() {
        assertTrue(ShakerItemBehavior.shouldUsePortableOnBlock(false));
    }

    @Test
    void secondaryBlockUseFallsThroughToFurniturePlacement() {
        assertFalse(ShakerItemBehavior.shouldUsePortableOnBlock(true));
    }
}
