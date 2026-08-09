package com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    void normalizesRenamedVanillaIdsForPaper262() {
        var grassStealthPlants = catalog.blockTag("kaleidoscope_tavern:grass_stealth_plants");
        assertTrue(grassStealthPlants.contains("minecraft:short_grass"));
        assertFalse(grassStealthPlants.contains("minecraft:grass"));
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
    void indexedRecipeLookupsKeepCatalogFirstMatchSemantics() {
        Set<String> pressingInputs = new LinkedHashSet<>();
        for (ContentCatalog.PressingRecipe recipe : catalog.pressingRecipes()) {
            if (recipe.ingredient().kind() == ContentCatalog.SelectorKind.ITEM) {
                pressingInputs.add(recipe.ingredient().value());
            } else {
                pressingInputs.addAll(catalog.tag(recipe.ingredient().value()));
            }
        }
        for (String input : pressingInputs) {
            assertEquals(catalog.pressingRecipes().stream()
                            .filter(recipe -> catalog.selectorMatches(recipe.ingredient(), input))
                            .findFirst(),
                    catalog.pressing(input));
        }
        for (ContentCatalog.PressingRecipe recipe : catalog.pressingRecipes()) {
            assertEquals(catalog.pressingRecipes().stream()
                            .filter(candidate -> candidate.fluid().equals(recipe.fluid()))
                            .findFirst(),
                    catalog.pressingByFluid(recipe.fluid()));
            assertEquals(catalog.pressingRecipes().stream()
                            .filter(candidate -> candidate.bucket().equals(recipe.bucket()))
                            .findFirst(),
                    catalog.pressingByBucket(recipe.bucket()));
        }
        for (ContentCatalog.BarrelRecipe recipe : catalog.barrelRecipes()) {
            assertEquals(catalog.barrelRecipes().stream()
                            .filter(candidate -> candidate.id().equals(recipe.id()))
                            .findFirst(),
                    catalog.barrelById(recipe.id()));
        }
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
    void indexedShakerLookupMatchesReferenceBacktrackingForEveryIngredientTriple() {
        Set<String> ingredientSet = new LinkedHashSet<>(
                catalog.tag("kaleidoscope_tavern:cocktail_ingredient"));
        ingredientSet.add("minecraft:stone");
        List<String> ingredients = ingredientSet.stream().sorted().toList();
        for (String first : ingredients) {
            assertEquals(referenceShaker(List.of(first)),
                    catalog.shaker(List.of(first)));
            assertEquals(referenceMayBeShaker(List.of(first)),
                    catalog.mayBeShakerIngredient(List.of(), first));
            for (String second : ingredients) {
                List<String> pair = List.of(first, second);
                assertEquals(referenceShaker(pair), catalog.shaker(pair));
                assertEquals(referenceMayBeShaker(pair),
                        catalog.mayBeShakerIngredient(List.of(first), second));
                for (String third : ingredients) {
                    List<String> triple = List.of(first, second, third);
                    assertEquals(referenceShaker(triple), catalog.shaker(triple));
                    assertEquals(referenceMayBeShaker(triple),
                            catalog.mayBeShakerIngredient(pair, third));
                }
            }
        }
    }

    @Test
    void indexedBarrelLookupMatchesReferenceForEveryFluidAndIngredientPair() {
        Set<String> ingredientSet = new LinkedHashSet<>();
        Set<String> fluids = new LinkedHashSet<>();
        for (ContentCatalog.BarrelRecipe recipe : catalog.barrelRecipes()) {
            fluids.add(recipe.fluid());
            for (ContentCatalog.Selector selector : recipe.ingredients()) {
                if (selector.kind() == ContentCatalog.SelectorKind.ITEM) {
                    ingredientSet.add(selector.value());
                } else {
                    ingredientSet.addAll(catalog.tag(selector.value()));
                }
            }
        }
        ingredientSet.add("minecraft:stone");
        List<String> ingredients = ingredientSet.stream().sorted().toList();
        for (String fluid : fluids) {
            assertEquals(referenceBarrel(fluid, List.of()), catalog.barrel(fluid, List.of()));
            for (String first : ingredients) {
                assertEquals(referenceBarrel(fluid, List.of(first)),
                        catalog.barrel(fluid, List.of(first)));
                assertEquals(referenceMayBeBarrel(fluid, List.of(first)),
                        catalog.mayBeBarrelIngredient(fluid, List.of(), first));
                for (String second : ingredients) {
                    List<String> pair = List.of(first, second);
                    assertEquals(referenceBarrel(fluid, pair), catalog.barrel(fluid, pair));
                    assertEquals(referenceMayBeBarrel(fluid, pair),
                            catalog.mayBeBarrelIngredient(fluid, List.of(first), second));
                }
            }
        }
        for (String first : ingredients) {
            assertEquals(referenceMayBeBarrel(null, List.of(first)),
                    catalog.mayBeBarrelIngredient(null, List.of(), first));
        }
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

    @Test
    void usesTheForgeChatFormattingIngredientColors() {
        assertEquals(0xFF55FF,
                catalog.cocktailColor("kaleidoscope_tavern:brandy").orElseThrow());
        assertEquals(0xFF55FF,
                catalog.barrelById("kaleidoscope_tavern:brandy")
                        .orElseThrow().tapColor().orElseThrow());
        assertEquals(0xFFFFFF, catalog.cocktailColor("minecraft:potion").orElseThrow());
        assertTrue(catalog.cocktailColor("minecraft:stone").isEmpty());
    }

    private static Optional<ContentCatalog.ShakerRecipe> referenceShaker(List<String> ingredients) {
        return catalog.shakerRecipes().stream()
                .filter(recipe -> recipe.ingredients().size() == ingredients.size())
                .filter(recipe -> referenceMatch(recipe.ingredients(), ingredients))
                .findFirst();
    }

    private static boolean referenceMayBeShaker(List<String> ingredients) {
        return catalog.shakerRecipes().stream()
                .filter(recipe -> recipe.ingredients().size() >= ingredients.size())
                .anyMatch(recipe -> referenceMatch(recipe.ingredients(), ingredients));
    }

    private static Optional<ContentCatalog.BarrelRecipe> referenceBarrel(
            String fluid, List<String> ingredients) {
        return catalog.barrelRecipes().stream()
                .filter(recipe -> recipe.fluid().equals(fluid))
                .filter(recipe -> recipe.ingredients().size() == ingredients.size())
                .filter(recipe -> referenceMatch(recipe.ingredients(), ingredients))
                .findFirst();
    }

    private static boolean referenceMayBeBarrel(String fluid, List<String> ingredients) {
        return catalog.barrelRecipes().stream()
                .filter(recipe -> fluid == null || recipe.fluid().equals(fluid))
                .filter(recipe -> recipe.ingredients().size() >= ingredients.size())
                .anyMatch(recipe -> referenceMatch(recipe.ingredients(), ingredients));
    }

    private static boolean referenceMatch(List<ContentCatalog.Selector> selectors,
                                          List<String> ingredients) {
        return referenceMatch(selectors, ingredients, 0, new boolean[selectors.size()]);
    }

    private static boolean referenceMatch(List<ContentCatalog.Selector> selectors,
                                          List<String> ingredients, int ingredientIndex,
                                          boolean[] used) {
        if (ingredientIndex == ingredients.size()) {
            return true;
        }
        String ingredient = ingredients.get(ingredientIndex);
        for (int selectorIndex = 0; selectorIndex < selectors.size(); selectorIndex++) {
            if (used[selectorIndex]
                    || !catalog.selectorMatches(selectors.get(selectorIndex), ingredient)) {
                continue;
            }
            used[selectorIndex] = true;
            if (referenceMatch(selectors, ingredients, ingredientIndex + 1, used)) {
                return true;
            }
            used[selectorIndex] = false;
        }
        return false;
    }
}
