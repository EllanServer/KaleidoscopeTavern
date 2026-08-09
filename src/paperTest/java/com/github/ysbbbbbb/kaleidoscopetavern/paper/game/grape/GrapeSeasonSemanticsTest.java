package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape.GrapeSeasonSemantics.Plant.GOLD_GRAPEVINE_TRELLIS;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape.GrapeSeasonSemantics.Plant.GRAPEVINE_TRELLIS;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape.GrapeSeasonSemantics.Plant.HANGING_GOLD_GRAPE;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape.GrapeSeasonSemantics.Plant.HANGING_GRAPE;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape.GrapeSeasonSemantics.Plant.HANGING_ICE_GRAPE;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape.GrapeSeasonSemantics.Plant.ICE_GRAPEVINE_TRELLIS;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape.GrapeSeasonSemantics.Season.AUTUMN;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape.GrapeSeasonSemantics.Season.SPRING;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape.GrapeSeasonSemantics.Season.SUMMER;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape.GrapeSeasonSemantics.Season.WINTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrapeSeasonSemanticsTest {
    @Test
    void defaultsMatchForgeSereneSeasonsTags() {
        // datagen/tag/TagBlock: SPRING/SUMMER/AUTUMN/WINTER_CROPS_BLOCK.
        assertEquals(EnumSet.of(SPRING, SUMMER), GRAPEVINE_TRELLIS.defaultSeasons());
        assertEquals(EnumSet.of(SUMMER), GOLD_GRAPEVINE_TRELLIS.defaultSeasons());
        assertEquals(EnumSet.of(WINTER), ICE_GRAPEVINE_TRELLIS.defaultSeasons());
        assertEquals(EnumSet.of(SUMMER, AUTUMN), HANGING_GRAPE.defaultSeasons());
        assertEquals(EnumSet.of(SUMMER), HANGING_GOLD_GRAPE.defaultSeasons());
        assertEquals(EnumSet.of(WINTER), HANGING_ICE_GRAPE.defaultSeasons());
    }

    @Test
    void mapsTrellisBlockIds() {
        assertEquals(GRAPEVINE_TRELLIS,
                GrapeSeasonSemantics.plantForTrellis("kaleidoscope_tavern:grapevine_trellis"));
        assertEquals(GOLD_GRAPEVINE_TRELLIS,
                GrapeSeasonSemantics.plantForTrellis("kaleidoscope_tavern:gold_grapevine_trellis"));
        assertEquals(ICE_GRAPEVINE_TRELLIS,
                GrapeSeasonSemantics.plantForTrellis("kaleidoscope_tavern:ice_grapevine_trellis"));
        // The bare carrier trellis and other blocks are never season-gated.
        assertNull(GrapeSeasonSemantics.plantForTrellis("kaleidoscope_tavern:trellis"));
        assertNull(GrapeSeasonSemantics.plantForTrellis("kaleidoscope_tavern:wild_grapevine"));
    }

    @Test
    void mapsHangingGrapeVarieties() {
        assertEquals(HANGING_GRAPE,
                GrapeSeasonSemantics.plantForVariety(GrapeGrowthSemantics.Variety.NORMAL));
        assertEquals(HANGING_GOLD_GRAPE,
                GrapeSeasonSemantics.plantForVariety(GrapeGrowthSemantics.Variety.GOLD));
        assertEquals(HANGING_ICE_GRAPE,
                GrapeSeasonSemantics.plantForVariety(GrapeGrowthSemantics.Variety.ICE));
    }

    @Test
    void parsesSeasonsCaseInsensitively() {
        assertEquals(EnumSet.of(SPRING, SUMMER, WINTER),
                GrapeSeasonSemantics.parseSeasons(List.of("Spring", "SUMMER", " winter ")));
        assertEquals(EnumSet.noneOf(GrapeSeasonSemantics.Season.class),
                GrapeSeasonSemantics.parseSeasons(List.of()));
    }

    @Test
    void rejectsUnknownSeasonEntries() {
        assertThrows(IllegalArgumentException.class,
                () -> GrapeSeasonSemantics.parseSeasons(List.of("sprin")));
        assertThrows(IllegalArgumentException.class,
                () -> GrapeSeasonSemantics.parseSeasons(java.util.Arrays.asList("spring", null)));
    }

    @Test
    void disabledWorldSeasonNeverRestricts() {
        // CC Season.DISABLE == "no season mod" on Forge: everything grows.
        assertTrue(GrapeSeasonSemantics.allowsGrowth(EnumSet.of(WINTER), "DISABLE"));
        assertTrue(GrapeSeasonSemantics.allowsGrowth(EnumSet.noneOf(GrapeSeasonSemantics.Season.class), "DISABLE"));
        assertTrue(GrapeSeasonSemantics.allowsGrowth(EnumSet.of(WINTER), null));
        assertTrue(GrapeSeasonSemantics.allowsGrowth(EnumSet.of(WINTER), "SOMETHING_NEW"));
    }

    @Test
    void gatesOnSeasonMembership() {
        assertTrue(GrapeSeasonSemantics.allowsGrowth(EnumSet.of(SPRING, SUMMER), "SPRING"));
        assertTrue(GrapeSeasonSemantics.allowsGrowth(EnumSet.of(SPRING, SUMMER), "SUMMER"));
        assertFalse(GrapeSeasonSemantics.allowsGrowth(EnumSet.of(SPRING, SUMMER), "AUTUMN"));
        assertFalse(GrapeSeasonSemantics.allowsGrowth(EnumSet.of(SPRING, SUMMER), "WINTER"));
        // An explicitly empty allow list blocks every real season.
        assertFalse(GrapeSeasonSemantics.allowsGrowth(
                EnumSet.noneOf(GrapeSeasonSemantics.Season.class), "SUMMER"));
    }
}
