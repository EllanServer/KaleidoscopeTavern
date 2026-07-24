package com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable, validated view of the Forge data maps migrated into runtime TSV catalogs. */
public final class ContentCatalog {
    private final Map<String, Set<String>> tags;
    private final List<PressingRecipe> pressingRecipes;
    private final List<BarrelRecipe> barrelRecipes;
    private final List<ShakerRecipe> shakerRecipes;
    private final Map<String, Map<Integer, List<EffectSpec>>> effects;
    private final Map<String, Set<String>> blockTags;
    private final Map<String, Set<String>> entityTypeTags;

    private ContentCatalog(Map<String, Set<String>> tags,
                           List<PressingRecipe> pressingRecipes,
                           List<BarrelRecipe> barrelRecipes,
                           List<ShakerRecipe> shakerRecipes,
                           Map<String, Map<Integer, List<EffectSpec>>> effects,
                           Map<String, Set<String>> blockTags,
                           Map<String, Set<String>> entityTypeTags) {
        this.tags = tags;
        this.pressingRecipes = List.copyOf(pressingRecipes);
        this.barrelRecipes = List.copyOf(barrelRecipes);
        this.shakerRecipes = List.copyOf(shakerRecipes);
        this.effects = effects;
        this.blockTags = blockTags;
        this.entityTypeTags = entityTypeTags;
    }

    public static ContentCatalog load(ClassLoader loader) throws IOException {
        Map<String, Set<String>> tags = loadTags(loader);
        List<PressingRecipe> pressing = new ArrayList<>();
        for (Map<String, String> row : readTsv(loader, "catalog/pressing.tsv")) {
            pressing.add(new PressingRecipe(
                    required(row, "recipe"),
                    Selector.parse(required(row, "ingredient")),
                    required(row, "fluid"),
                    positiveInt(row, "amount"),
                    required(row, "bucket")));
        }

        List<BarrelRecipe> barrel = new ArrayList<>();
        for (Map<String, String> row : readTsv(loader, "catalog/barrel.tsv")) {
            barrel.add(new BarrelRecipe(
                    required(row, "recipe"),
                    required(row, "result"),
                    Selector.parse(required(row, "carrier")),
                    required(row, "fluid"),
                    positiveInt(row, "unit_ticks"),
                    selectors(row.getOrDefault("ingredients", ""))));
        }

        List<ShakerRecipe> shaker = new ArrayList<>();
        for (Map<String, String> row : readTsv(loader, "catalog/shaker.tsv")) {
            shaker.add(new ShakerRecipe(
                    required(row, "recipe"),
                    required(row, "result"),
                    selectors(required(row, "ingredients"))));
        }

        Map<String, Map<Integer, List<EffectSpec>>> effects = new LinkedHashMap<>();
        for (Map<String, String> row : readTsv(loader, "catalog/drink-effects.tsv")) {
            String item = required(row, "item");
            int level = positiveInt(row, "level");
            EffectSpec spec = new EffectSpec(
                    required(row, "effect"),
                    nonNegativeInt(row, "duration_ticks"),
                    nonNegativeInt(row, "amplifier"),
                    probability(row, "probability"));
            effects.computeIfAbsent(item, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(level, ignored -> new ArrayList<>())
                    .add(spec);
        }
        effects.replaceAll((item, levels) -> {
            levels.replaceAll((level, specs) -> List.copyOf(specs));
            return Collections.unmodifiableMap(levels);
        });

        Map<String, Set<String>> blockTags = new LinkedHashMap<>();
        Map<String, Set<String>> entityTags = new LinkedHashMap<>();
        for (Map<String, String> row : readTsv(loader, "catalog/registry-tags.tsv")) {
            Map<String, Set<String>> target = switch (required(row, "registry")) {
                case "block" -> blockTags;
                case "entity_type" -> entityTags;
                default -> throw new IOException("Unknown registry tag type " + row.get("registry"));
            };
            target.computeIfAbsent(required(row, "tag"), ignored -> new LinkedHashSet<>())
                    .add(required(row, "member"));
        }

        return new ContentCatalog(
                immutableTags(tags),
                pressing,
                barrel,
                shaker,
                Collections.unmodifiableMap(effects),
                immutableTags(blockTags),
                immutableTags(entityTags));
    }

    public Optional<PressingRecipe> pressing(String itemId) {
        return pressingRecipes.stream().filter(recipe -> matches(recipe.ingredient(), itemId)).findFirst();
    }

    public Optional<PressingRecipe> pressingByFluid(String fluid) {
        return pressingRecipes.stream().filter(recipe -> recipe.fluid().equals(fluid)).findFirst();
    }

    public Optional<PressingRecipe> pressingByBucket(String bucket) {
        return pressingRecipes.stream().filter(recipe -> recipe.bucket().equals(bucket)).findFirst();
    }

    public Optional<BarrelRecipe> barrel(String fluid, List<String> ingredients) {
        return barrelRecipes.stream()
                .filter(recipe -> recipe.fluid().equals(fluid))
                .filter(recipe -> exactMatch(recipe.ingredients(), ingredients))
                .findFirst();
    }

    public Optional<BarrelRecipe> barrelById(String recipeId) {
        return barrelRecipes.stream().filter(recipe -> recipe.id().equals(recipeId)).findFirst();
    }

    public boolean mayBeBarrelIngredient(String fluid, List<String> current, String candidate) {
        List<String> proposed = new ArrayList<>(current);
        proposed.add(candidate);
        return barrelRecipes.stream()
                .filter(recipe -> fluid == null || recipe.fluid().equals(fluid))
                .anyMatch(recipe -> partialMatch(recipe.ingredients(), proposed));
    }

    public Optional<ShakerRecipe> shaker(List<String> ingredients) {
        return shakerRecipes.stream()
                .filter(recipe -> exactMatch(recipe.ingredients(), ingredients))
                .findFirst();
    }

    public boolean mayBeShakerIngredient(List<String> current, String candidate) {
        List<String> proposed = new ArrayList<>(current);
        proposed.add(candidate);
        return shakerRecipes.stream().anyMatch(recipe -> partialMatch(recipe.ingredients(), proposed));
    }

    public List<EffectSpec> effects(String itemId, int level) {
        Map<Integer, List<EffectSpec>> byLevel = effects.get(itemId);
        if (byLevel == null) {
            return List.of();
        }
        return byLevel.getOrDefault(level, List.of());
    }

    public boolean hasDrinkEffects(String itemId) {
        return effects.containsKey(itemId);
    }

    public boolean isCocktail(String itemId) {
        return itemId.equals("kaleidoscope_tavern:signature_cocktail")
                || itemId.equals("kaleidoscope_tavern:mystery_cocktail")
                || shakerRecipes.stream().anyMatch(recipe -> recipe.result().equals(itemId));
    }

    public Set<String> tag(String tagId) {
        return tags.getOrDefault(tagId, Set.of());
    }

    public Set<String> blockTag(String tagId) {
        return blockTags.getOrDefault(tagId, Set.of());
    }

    public Set<String> entityTypeTag(String tagId) {
        return entityTypeTags.getOrDefault(tagId, Set.of());
    }

    public boolean selectorMatches(Selector selector, String itemId) {
        return matches(selector, itemId);
    }

    public int cocktailColor(String itemId) {
        Map<String, Integer> colors = Map.ofEntries(
                Map.entry("black", 0x1D1D21), Map.entry("blue", 0x3C44AA),
                Map.entry("brown", 0x835432), Map.entry("cyan", 0x169C9C),
                Map.entry("gray", 0x474F52), Map.entry("green", 0x5E7C16),
                Map.entry("light_blue", 0x3AB3DA), Map.entry("light_gray", 0x9D9D97),
                Map.entry("light_purple", 0xC74EBD), Map.entry("lime", 0x80C71F),
                Map.entry("orange", 0xF9801D), Map.entry("pink", 0xF38BAA),
                Map.entry("purple", 0x8932B8), Map.entry("red", 0xB02E26),
                Map.entry("white", 0xF9FFFE), Map.entry("yellow", 0xFED83D),
                Map.entry("gold", 0xF6C344));
        for (Map.Entry<String, Integer> entry : colors.entrySet()) {
            if (tag("kaleidoscope_tavern:cocktail_ingredient_" + entry.getKey()).contains(itemId)) {
                return entry.getValue();
            }
        }
        return 0xA349A4;
    }

    public List<PressingRecipe> pressingRecipes() {
        return pressingRecipes;
    }

    public List<BarrelRecipe> barrelRecipes() {
        return barrelRecipes;
    }

    public List<ShakerRecipe> shakerRecipes() {
        return shakerRecipes;
    }

    public int effectEntryCount() {
        return effects.values().stream()
                .flatMap(levels -> levels.values().stream())
                .mapToInt(List::size)
                .sum();
    }

    private boolean matches(Selector selector, String item) {
        return selector.kind() == SelectorKind.ITEM
                ? selector.value().equals(item)
                : tag(selector.value()).contains(item);
    }

    private boolean exactMatch(List<Selector> selectors, List<String> items) {
        return selectors.size() == items.size() && matchBacktracking(selectors, items, 0, new boolean[selectors.size()]);
    }

    private boolean partialMatch(List<Selector> selectors, List<String> items) {
        return selectors.size() >= items.size() && matchBacktrackingForItems(selectors, items, 0, new boolean[selectors.size()]);
    }

    private boolean matchBacktracking(List<Selector> selectors, List<String> items, int itemIndex, boolean[] used) {
        if (itemIndex == items.size()) {
            return true;
        }
        String item = items.get(itemIndex);
        for (int selectorIndex = 0; selectorIndex < selectors.size(); selectorIndex++) {
            if (!used[selectorIndex] && matches(selectors.get(selectorIndex), item)) {
                used[selectorIndex] = true;
                if (matchBacktracking(selectors, items, itemIndex + 1, used)) {
                    return true;
                }
                used[selectorIndex] = false;
            }
        }
        return false;
    }

    private boolean matchBacktrackingForItems(List<Selector> selectors, List<String> items, int itemIndex,
                                              boolean[] used) {
        return matchBacktracking(selectors, items, itemIndex, used);
    }

    private static List<Selector> selectors(String cell) throws IOException {
        if (cell.isBlank()) {
            return List.of();
        }
        List<Selector> result = new ArrayList<>();
        for (String part : cell.split(";")) {
            result.add(Selector.parse(part));
        }
        return List.copyOf(result);
    }

    private static Map<String, Set<String>> loadTags(ClassLoader loader) throws IOException {
        Map<String, Set<String>> tags = new LinkedHashMap<>();
        for (Map<String, String> row : readTsv(loader, "catalog/tags.tsv")) {
            tags.computeIfAbsent(required(row, "tag"), ignored -> new LinkedHashSet<>())
                    .add(required(row, "item"));
        }
        return tags;
    }

    private static Map<String, Set<String>> immutableTags(Map<String, Set<String>> tags) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        tags.forEach((key, values) -> result.put(key, Set.copyOf(values)));
        return Collections.unmodifiableMap(result);
    }

    private static List<Map<String, String>> readTsv(ClassLoader loader, String resource) throws IOException {
        InputStream stream = loader.getResourceAsStream(resource);
        if (stream == null) {
            throw new IOException("Missing catalog resource " + resource);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw new IOException("Empty catalog resource " + resource);
            }
            String[] headers = headerLine.split("\\t", -1);
            List<Map<String, String>> rows = new ArrayList<>();
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cells = line.split("\\t", -1);
                if (cells.length != headers.length) {
                    throw new IOException(resource + ':' + lineNumber + " has " + cells.length
                            + " cells; expected " + headers.length);
                }
                Map<String, String> row = new HashMap<>();
                for (int index = 0; index < headers.length; index++) {
                    row.put(headers[index], cells[index]);
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private static String required(Map<String, String> row, String key) throws IOException {
        String value = row.get(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing required catalog value " + key);
        }
        return value;
    }

    private static int positiveInt(Map<String, String> row, String key) throws IOException {
        int value = parseInt(row, key);
        if (value <= 0) {
            throw new IOException(key + " must be positive");
        }
        return value;
    }

    private static int nonNegativeInt(Map<String, String> row, String key) throws IOException {
        int value = parseInt(row, key);
        if (value < 0) {
            throw new IOException(key + " must not be negative");
        }
        return value;
    }

    private static int parseInt(Map<String, String> row, String key) throws IOException {
        try {
            return Integer.parseInt(required(row, key));
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid integer in " + key, exception);
        }
    }

    private static double probability(Map<String, String> row, String key) throws IOException {
        try {
            double value = Double.parseDouble(required(row, key));
            if (value < 0 || value > 1) {
                throw new IOException(key + " must be between 0 and 1");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid decimal in " + key, exception);
        }
    }

    public enum SelectorKind {
        ITEM,
        TAG
    }

    public record Selector(SelectorKind kind, String value) {
        public Selector {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
            if (value.isBlank()) {
                throw new IllegalArgumentException("Selector value cannot be blank");
            }
        }

        public static Selector parse(String encoded) throws IOException {
            int separator = encoded.indexOf('=');
            if (separator < 1 || separator == encoded.length() - 1) {
                throw new IOException("Invalid selector " + encoded);
            }
            String kind = encoded.substring(0, separator);
            String value = encoded.substring(separator + 1);
            return switch (kind) {
                case "item" -> new Selector(SelectorKind.ITEM, value);
                case "tag" -> new Selector(SelectorKind.TAG, value);
                default -> throw new IOException("Unknown selector kind " + kind);
            };
        }
    }

    public record PressingRecipe(String id, Selector ingredient, String fluid, int amount, String bucket) {
    }

    public record BarrelRecipe(String id, String result, Selector carrier, String fluid, int unitTicks,
                               List<Selector> ingredients) {
    }

    public record ShakerRecipe(String id, String result, List<Selector> ingredients) {
    }

    public record EffectSpec(String effect, int durationTicks, int amplifier, double probability) {
    }
}
