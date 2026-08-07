package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SofaBlockIdsTest {
    @Test
    void allSixteenFormerColourIdsRemainMigrationAliases() {
        assertEquals(16, SofaBlockIds.legacyIds().size());
        assertTrue(SofaBlockIds.isLegacy(
                Key.of("kaleidoscope_tavern", "black_sofa")));
        assertTrue(SofaBlockIds.isLegacy(
                Key.of("kaleidoscope_tavern", "light_blue_sofa")));
    }

    @Test
    void activeSharedAndOtherConnectedBlocksAreNotAliases() {
        assertFalse(SofaBlockIds.isLegacy(SofaBlockIds.SHARED));
        assertFalse(SofaBlockIds.isLegacy(
                Key.of("kaleidoscope_tavern", "table")));
    }
}
