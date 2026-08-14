package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.core;

import com.google.gson.JsonObject;
import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.data.ForgeTagResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Orchestrates the first independently verifiable native migration stage. */
public final class CoreDataStage {
    private static final String INIT = "src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/init";
    private static final String GENERATED = "src/generated/resources";
    private static final String MAIN_RESOURCES = "src/main/resources";
    private final Path root;

    public CoreDataStage(Path root) { this.root = root.toAbsolutePath().normalize(); }

    /** Reads archived inputs only and returns an immutable semantic result. */
    public Result analyze() {
        RegistryScanner scanner = new RegistryScanner();
        List<String> items = scanner.scan(root.resolve(INIT).resolve("ModItems.java"), "ITEMS");
        List<String> blocks = scanner.scan(root.resolve(INIT).resolve("ModBlocks.java"), "BLOCKS");
        List<Path> resources = List.of(root.resolve(MAIN_RESOURCES), root.resolve(GENERATED));
        Map<String, List<String>> tags = new ItemTagResolver().loadAndFlatten(resources);
        Path recipes = root.resolve(GENERATED).resolve(
                "data/kaleidoscope_tavern/recipes");
        JsonObject standardRecipes = new StandardRecipeConverter().convert(recipes, tags);
        try {
            Map<String, List<String>> blockTags = ForgeTagResolver.loadRegistryTags(
                    root, root, List.of("blocks", "block"), LegacyIds::normalize);
            Map<String, List<String>> entityTags = ForgeTagResolver.loadRegistryTags(
                    root, root, List.of("entity_types", "entity_type"), LegacyIds::normalize);
            return new Result(items, blocks, tags, blockTags, entityTags, standardRecipes);
        } catch (IOException error) {
            throw new CoreMigrationException("cannot load registry tags", error);
        }
    }

    /** Writes only the stage-owned recipe file beneath an explicitly supplied output root. */
    public Result generate(Path outputRoot) {
        Result result = analyze();
        Path normalizedRoot = outputRoot.toAbsolutePath().normalize();
        if (normalizedRoot.equals(root)) {
            throw new CoreMigrationException(
                    "refusing to overwrite checked-in generated output; use a distinct temporary output root");
        }
        Path output = normalizedRoot.resolve(
                "src/paper/pack/configuration/recipes.json");
        JsonObject document = new JsonObject();
        document.add("recipes", result.standardRecipes().deepCopy());
        JsonFiles.write(output, document);
        return result;
    }

    public record Result(List<String> itemIds, List<String> legacyBlockIds,
                         Map<String, List<String>> flattenedTags,
                         Map<String, List<String>> blockTags,
                         Map<String, List<String>> entityTags, JsonObject standardRecipes) {
        public Result {
            itemIds = List.copyOf(itemIds);
            legacyBlockIds = List.copyOf(legacyBlockIds);
            flattenedTags = immutableMap(flattenedTags);
            blockTags = immutableMap(blockTags);
            entityTags = immutableMap(entityTags);
            standardRecipes = standardRecipes.deepCopy();
        }
        @Override public JsonObject standardRecipes() { return standardRecipes.deepCopy(); }
        public int recipeCount() { return standardRecipes.size(); }
        private static Map<String, List<String>> immutableMap(Map<String, List<String>> source) {
            java.util.LinkedHashMap<String, List<String>> result = new java.util.LinkedHashMap<>();
            source.forEach((key, value) -> result.put(key, List.copyOf(value)));
            return java.util.Collections.unmodifiableMap(result);
        }
    }
}
