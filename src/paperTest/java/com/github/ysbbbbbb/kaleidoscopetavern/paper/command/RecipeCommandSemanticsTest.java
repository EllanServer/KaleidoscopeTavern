package com.github.ysbbbbbb.kaleidoscopetavern.paper.command;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static com.github.ysbbbbbb.kaleidoscopetavern.paper.command.RecipeCommandSemantics.RecipeType.BARREL;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.command.RecipeCommandSemantics.RecipeType.PRESSING;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.command.RecipeCommandSemantics.RecipeType.SHAKER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeCommandSemanticsTest {
    @Test
    void parsesRecipeTypesCaseInsensitively() {
        assertEquals(BARREL, RecipeCommandSemantics.parseType("barrel").orElseThrow());
        assertEquals(PRESSING, RecipeCommandSemantics.parseType("PRESSING").orElseThrow());
        assertEquals(SHAKER, RecipeCommandSemantics.parseType("ShAkEr").orElseThrow());
        assertTrue(RecipeCommandSemantics.parseType("wine").isEmpty());
    }

    @Test
    void validatesPositivePageNumbersWithoutOverflow() {
        assertEquals(OptionalInt.of(4), RecipeCommandSemantics.parsePage("4"));
        assertTrue(RecipeCommandSemantics.parsePage("0").isEmpty());
        assertTrue(RecipeCommandSemantics.parsePage("-1").isEmpty());
        assertTrue(RecipeCommandSemantics.parsePage("2147483648").isEmpty());
        assertTrue(RecipeCommandSemantics.parsePage("one").isEmpty());
        assertTrue(RecipeCommandSemantics.parsePage(null).isEmpty());
    }

    @Test
    void paginatesEachCatalogAtSixEntriesPerPage() {
        assertEquals(1, RecipeCommandSemantics.pageCount(6));
        assertEquals(2, RecipeCommandSemantics.pageCount(12));
        assertEquals(4, RecipeCommandSemantics.pageCount(24));
        assertEquals(357_913_942, RecipeCommandSemantics.pageCount(Integer.MAX_VALUE));

        RecipeCommandSemantics.PageWindow fourth =
                RecipeCommandSemantics.pageWindow(24, 4).orElseThrow();
        assertEquals(18, fourth.fromInclusive());
        assertEquals(24, fourth.toExclusive());
        assertEquals(4, fourth.totalPages());
        assertTrue(RecipeCommandSemantics.pageWindow(24, 5).isEmpty());
    }

    @Test
    void formatsPerLevelBarrelDuration() {
        assertEquals("0:00", RecipeCommandSemantics.formatTicks(-1));
        assertEquals("2:00", RecipeCommandSemantics.formatTicks(2_400));
    }

    @Test
    void recognizesOnlyCocktailColorTags() {
        assertEquals("light_purple", RecipeCommandSemantics.cocktailColorSuffix(
                "kaleidoscope_tavern:cocktail_ingredient_light_purple").orElseThrow());
        assertTrue(RecipeCommandSemantics.cocktailColorSuffix("forge:fruits/grapes").isEmpty());
        assertTrue(RecipeCommandSemantics.cocktailColorSuffix(
                "kaleidoscope_tavern:cocktail_ingredient_").isEmpty());
    }
}
