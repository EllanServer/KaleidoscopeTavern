package com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContentCatalogTest {
    private static ContentCatalog catalog;

    @BeforeAll
    static void loadCatalog() throws IOException {
        catalog = ContentCatalog.load(ContentCatalogTest.class.getClassLoader());
    }

    @Test
    void loadsEveryMigratedGameplayEntry() {
        assertEquals(6, catalog.pressingRecipes().size());
        assertEquals(24, catalog.barrelRecipes().size());
        assertEquals(12, catalog.shakerRecipes().size());
        assertEquals(270, catalog.effectEntryCount());
    }

    @Test
    void resolvesItemAndTagBasedPressingRecipes() {
        assertEquals("kaleidoscope_tavern:glow_berries_juice",
                catalog.pressing("minecraft:glow_berries").orElseThrow().fluid());
        assertEquals("kaleidoscope_tavern:grape_juice",
                catalog.pressing("kaleidoscope_tavern:grape").orElseThrow().fluid());
        assertTrue(catalog.pressing("minecraft:stone").isEmpty());
    }

    @Test
    void matchesBarrelIngredientsWithoutDependingOnInsertionOrder() {
        assertEquals("kaleidoscope_tavern:brandy",
                catalog.barrel("kaleidoscope_tavern:grape_juice", List.of("minecraft:apple"))
                        .orElseThrow().result());
        assertTrue(catalog.mayBeBarrelIngredient(
                "kaleidoscope_tavern:grape_juice", List.of(), "minecraft:sugar"));
        assertFalse(catalog.mayBeBarrelIngredient(
                "minecraft:lava", List.of(), "minecraft:sugar"));
    }

    @Test
    void backtracksAcrossOverlappingCocktailTags() {
        List<String> ingredients = List.of(
                "minecraft:potion",
                "kaleidoscope_tavern:brandy",
                "kaleidoscope_tavern:ice_wine");
        assertEquals("kaleidoscope_tavern:depth_charge",
                catalog.shaker(ingredients).orElseThrow().result());
        assertTrue(catalog.mayBeShakerIngredient(
                List.of("kaleidoscope_tavern:ice_wine"), "kaleidoscope_tavern:brandy"));
        assertTrue(catalog.shakerRecipes().stream().allMatch(recipe -> catalog.isCocktail(recipe.result())));
        assertTrue(catalog.isCocktail("kaleidoscope_tavern:signature_cocktail"));
        assertFalse(catalog.isCocktail("kaleidoscope_tavern:brandy"));
    }

    @Test
    void keepsEffectsSeparatedByAgingLevel() {
        assertFalse(catalog.effects("kaleidoscope_tavern:brandy", 1).isEmpty());
        assertFalse(catalog.effects("kaleidoscope_tavern:brandy", 6).isEmpty());
        assertEquals(0, catalog.effects("kaleidoscope_tavern:carignan", 3).stream()
                .filter(effect -> effect.effect().equals("minecraft:instant_health"))
                .findFirst().orElseThrow().durationTicks());
        assertTrue(catalog.effects("kaleidoscope_tavern:brandy", 0).isEmpty());
        assertTrue(catalog.effects("minecraft:water", 1).isEmpty());
    }
}
