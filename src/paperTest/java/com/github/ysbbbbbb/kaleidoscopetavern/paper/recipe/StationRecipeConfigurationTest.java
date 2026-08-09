package com.github.ysbbbbbb.kaleidoscopetavern.paper.recipe;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StationRecipeConfigurationTest {
    private static ContentCatalog content;

    @BeforeAll
    static void loadContent() throws IOException {
        content = ContentCatalog.load(
                StationRecipeConfigurationTest.class.getClassLoader());
    }

    @Test
    void installsBundledDefaultsWithoutReplacingOperatorFiles(@TempDir Path directory)
            throws IOException {
        StationRecipeLoader loader = new StationRecipeLoader(
                getClass().getClassLoader(), directory);
        var defaults = loader.load();

        assertEquals(24, defaults.barrelRecipes().size());
        assertEquals(12, defaults.shakerRecipes().size());
        assertTrue(Files.isRegularFile(directory.resolve("barrel.yml")));
        assertTrue(Files.isRegularFile(directory.resolve("shaker.yml")));

        Files.writeString(directory.resolve("shaker.yml"), customShaker("minecraft:stone"),
                StandardCharsets.UTF_8);
        assertEquals("example:stone_cocktail",
                loader.load().shakerRecipes().getFirst().id());
    }

    @Test
    void customRecipesMatchAndInvalidReloadLeavesTheLiveSnapshotUntouched(
            @TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("barrel.yml"), CUSTOM_BARREL,
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("shaker.yml"), customShaker("minecraft:stone"),
                StandardCharsets.UTF_8);
        StationRecipeLoader loader = new StationRecipeLoader(
                getClass().getClassLoader(), directory);
        StationRecipeRegistry registry = new StationRecipeRegistry(content, loader.load());

        assertEquals("kaleidoscope_tavern:brandy",
                registry.barrel("minecraft:water", List.of("minecraft:apple"))
                        .orElseThrow().result());
        assertEquals("kaleidoscope_tavern:brass_heart",
                registry.shaker(List.of("minecraft:dirt", "minecraft:stone"))
                        .orElseThrow().result());
        assertTrue(registry.mayBeShakerIngredient(List.of(), "minecraft:stone"));
        assertFalse(registry.canMixShaker(List.of("minecraft:stone")));
        assertTrue(registry.canMixShaker(List.of("minecraft:dirt", "minecraft:stone")));
        assertTrue(registry.canMixShaker(List.of(
                "minecraft:stone", "minecraft:dirt", "minecraft:stick")));

        Files.writeString(directory.resolve("shaker.yml"), "config-version: 2\nrecipes: []\n",
                StandardCharsets.UTF_8);
        assertThrows(IOException.class, loader::load);
        assertTrue(registry.shaker(List.of("minecraft:stone", "minecraft:dirt")).isPresent());

        Files.writeString(directory.resolve("shaker.yml"), customShaker("minecraft:cobblestone"),
                StandardCharsets.UTF_8);
        registry.replace(loader.load());
        assertFalse(registry.shaker(List.of("minecraft:stone", "minecraft:dirt")).isPresent());
        assertTrue(registry.shaker(List.of("minecraft:cobblestone", "minecraft:dirt")).isPresent());
    }

    private static String customShaker(String firstIngredient) {
        return """
                config-version: 1
                special-results:
                  mystery: "kaleidoscope_tavern:mystery_cocktail"
                  signature: "kaleidoscope_tavern:signature_cocktail"
                recipes:
                  - id: "example:stone_cocktail"
                    result: "kaleidoscope_tavern:brass_heart"
                    ingredients:
                      - "item=%s"
                      - "item=minecraft:dirt"
                """.formatted(firstIngredient);
    }

    private static final String CUSTOM_BARREL = """
            config-version: 1
            fallback:
              id: "example:fallback"
              result: "kaleidoscope_tavern:vinegar"
              unit-ticks: 80
              output: 3
            recipes:
              - id: "example:apple_wine"
                result: "kaleidoscope_tavern:brandy"
                carrier: "item=kaleidoscope_tavern:empty_bottle"
                fluid: "minecraft:water"
                ingredients:
                  - "item=minecraft:apple"
                unit-ticks: 40
            """;
}
