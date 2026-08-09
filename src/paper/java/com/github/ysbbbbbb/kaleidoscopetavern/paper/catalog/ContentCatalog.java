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
import java.util.OptionalInt;
import java.util.Set;

/** Immutable, validated view of the Forge data maps migrated into runtime TSV catalogs. */
public final class ContentCatalog {
    private static final Map<String, Integer> COCKTAIL_COLORS = Map.ofEntries(
            Map.entry("black", 0x000000), Map.entry("dark_blue", 0x0000AA),
            Map.entry("dark_green", 0x00AA00), Map.entry("dark_aqua", 0x00AAAA),
            Map.entry("dark_red", 0xAA0000), Map.entry("dark_purple", 0xAA00AA),
            Map.entry("gold", 0xFFAA00), Map.entry("gray", 0xAAAAAA),
            Map.entry("dark_gray", 0x555555), Map.entry("blue", 0x5555FF),
            Map.entry("green", 0x55FF55), Map.entry("aqua", 0x55FFFF),
            Map.entry("red", 0xFF5555), Map.entry("light_purple", 0xFF55FF),
            Map.entry("yellow", 0xFFFF55), Map.entry("white", 0xFFFFFF));

    private final Map<String, Set<String>> tags;
    private final List<PressingRecipe> pressingRecipes;
    private final List<BarrelRecipe> barrelRecipes;
    private final List<ShakerRecipe> shakerRecipes;
    private final Map<String, Map<Integer, List<EffectSpec>>> effects;
    private final Map<String, Set<String>> blockTags;
    private final Map<String, Set<String>> entityTypeTags;
    private final Map<String, PressingRecipe> pressingByIngredient;
    private final Map<String, PressingRecipe> pressingByFluid;
    private final Map<String, PressingRecipe> pressingByBucket;
    private final Map<String, BarrelRecipe> barrelById;
    private final Map<String, Map<IngredientKey, BarrelRecipe>> barrelByIngredients;
    private final Map<String, Set<IngredientKey>> barrelPartialByFluid;
    private final Set<IngredientKey> barrelPartialAnyFluid;
    private final Map<IngredientKey, ShakerRecipe> shakerByIngredients;
    private final Set<IngredientKey> shakerPartial;
    private final Set<String> cocktailItems;
    private final Map<String, Integer> cocktailColors;

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

        Map<String, PressingRecipe> ingredientIndex = new LinkedHashMap<>();
        Map<String, PressingRecipe> fluidIndex = new LinkedHashMap<>();
        Map<String, PressingRecipe> bucketIndex = new LinkedHashMap<>();
        for (PressingRecipe recipe : this.pressingRecipes) {
            fluidIndex.putIfAbsent(recipe.fluid(), recipe);
            bucketIndex.putIfAbsent(recipe.bucket(), recipe);
            if (recipe.ingredient().kind() == SelectorKind.ITEM) {
                ingredientIndex.putIfAbsent(recipe.ingredient().value(), recipe);
            } else {
                this.tags.getOrDefault(recipe.ingredient().value(), Set.of())
                        .forEach(item -> ingredientIndex.putIfAbsent(item, recipe));
            }
        }
        this.pressingByIngredient = Collections.unmodifiableMap(ingredientIndex);
        this.pressingByFluid = Collections.unmodifiableMap(fluidIndex);
        this.pressingByBucket = Collections.unmodifiableMap(bucketIndex);

        Map<String, BarrelRecipe> barrelIndex = new LinkedHashMap<>();
        this.barrelRecipes.forEach(recipe -> barrelIndex.putIfAbsent(recipe.id(), recipe));
        this.barrelById = Collections.unmodifiableMap(barrelIndex);

        Map<String, Map<IngredientKey, BarrelRecipe>> barrelIngredients = new LinkedHashMap<>();
        Map<String, Set<IngredientKey>> barrelPartials = new LinkedHashMap<>();
        Set<IngredientKey> allBarrelPartials = new LinkedHashSet<>();
        for (BarrelRecipe recipe : this.barrelRecipes) {
            Map<IngredientKey, BarrelRecipe> exact = barrelIngredients.computeIfAbsent(
                    recipe.fluid(), ignored -> new LinkedHashMap<>());
            ingredientKeys(recipe.ingredients(), false).forEach(
                    key -> exact.putIfAbsent(key, recipe));
            Set<IngredientKey> partial = barrelPartials.computeIfAbsent(
                    recipe.fluid(), ignored -> new LinkedHashSet<>());
            Set<IngredientKey> recipePartials = ingredientKeys(recipe.ingredients(), true);
            partial.addAll(recipePartials);
            allBarrelPartials.addAll(recipePartials);
        }
        barrelIngredients.replaceAll((fluid, index) -> Collections.unmodifiableMap(index));
        barrelPartials.replaceAll((fluid, index) -> Collections.unmodifiableSet(index));
        this.barrelByIngredients = Collections.unmodifiableMap(barrelIngredients);
        this.barrelPartialByFluid = Collections.unmodifiableMap(barrelPartials);
        this.barrelPartialAnyFluid = Collections.unmodifiableSet(allBarrelPartials);

        Map<IngredientKey, ShakerRecipe> shakerIngredients = new LinkedHashMap<>();
        Set<IngredientKey> shakerPartials = new LinkedHashSet<>();
        for (ShakerRecipe recipe : this.shakerRecipes) {
            ingredientKeys(recipe.ingredients(), false).forEach(
                    key -> shakerIngredients.putIfAbsent(key, recipe));
            shakerPartials.addAll(ingredientKeys(recipe.ingredients(), true));
        }
        this.shakerByIngredients = Collections.unmodifiableMap(shakerIngredients);
        this.shakerPartial = Collections.unmodifiableSet(shakerPartials);

        Set<String> cocktails = new LinkedHashSet<>();
        cocktails.add("kaleidoscope_tavern:signature_cocktail");
        cocktails.add("kaleidoscope_tavern:mystery_cocktail");
        this.shakerRecipes.forEach(recipe -> cocktails.add(recipe.result()));
        this.cocktailItems = Set.copyOf(cocktails);

        Map<String, Integer> colorIndex = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : COCKTAIL_COLORS.entrySet()) {
            this.tags.getOrDefault("kaleidoscope_tavern:cocktail_ingredient_" + entry.getKey(), Set.of())
                    .forEach(item -> colorIndex.putIfAbsent(item, entry.getValue()));
        }
        this.cocktailColors = Collections.unmodifiableMap(colorIndex);
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
                    selectors(row.getOrDefault("ingredients", "")),
                    optionalRgb(row, "tap_color")));
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
        return Optional.ofNullable(pressingByIngredient.get(itemId));
    }

    public Optional<PressingRecipe> pressingByFluid(String fluid) {
        return Optional.ofNullable(pressingByFluid.get(fluid));
    }

    public Optional<PressingRecipe> pressingByBucket(String bucket) {
        return Optional.ofNullable(pressingByBucket.get(bucket));
    }

    public Optional<BarrelRecipe> barrel(String fluid, List<String> ingredients) {
        Map<IngredientKey, BarrelRecipe> byIngredients = barrelByIngredients.get(fluid);
        return byIngredients == null
                ? Optional.empty()
                : Optional.ofNullable(byIngredients.get(IngredientKey.of(ingredients)));
    }

    public Optional<BarrelRecipe> barrelById(String recipeId) {
        return Optional.ofNullable(barrelById.get(recipeId));
    }

    public boolean mayBeBarrelIngredient(String fluid, List<String> current, String candidate) {
        List<String> proposed = new ArrayList<>(current);
        proposed.add(candidate);
        IngredientKey key = IngredientKey.of(proposed);
        if (fluid == null) {
            return barrelPartialAnyFluid.contains(key);
        }
        return barrelPartialByFluid.getOrDefault(fluid, Set.of()).contains(key);
    }

    public Optional<ShakerRecipe> shaker(List<String> ingredients) {
        return Optional.ofNullable(shakerByIngredients.get(IngredientKey.of(ingredients)));
    }

    public boolean mayBeShakerIngredient(List<String> current, String candidate) {
        List<String> proposed = new ArrayList<>(current);
        proposed.add(candidate);
        return shakerPartial.contains(IngredientKey.of(proposed));
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

    public Set<String> drinkItems() {
        return effects.keySet();
    }

    public boolean isCocktail(String itemId) {
        return cocktailItems.contains(itemId);
    }

    public Set<String> cocktailItems() {
        return cocktailItems;
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

    public OptionalInt cocktailColor(String itemId) {
        Integer color = cocktailColors.get(itemId);
        return color == null ? OptionalInt.empty() : OptionalInt.of(color);
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

    private Set<IngredientKey> ingredientKeys(List<Selector> selectors, boolean includePartial) {
        if (selectors.size() >= Integer.SIZE - 1) {
            throw new IllegalArgumentException("Too many station recipe ingredients: " + selectors.size());
        }
        Set<IngredientKey> result = new LinkedHashSet<>();
        int fullMask = (1 << selectors.size()) - 1;
        int firstMask = includePartial ? 0 : fullMask;
        for (int mask = firstMask; mask <= fullMask; mask++) {
            expandIngredientKey(selectors, mask, 0, new ArrayList<>(), result);
        }
        return result;
    }

    private void expandIngredientKey(List<Selector> selectors, int selectedMask, int index,
                                     List<String> ingredients, Set<IngredientKey> result) {
        if (index == selectors.size()) {
            result.add(IngredientKey.of(ingredients));
            return;
        }
        if ((selectedMask & 1 << index) == 0) {
            expandIngredientKey(selectors, selectedMask, index + 1, ingredients, result);
            return;
        }
        Selector selector = selectors.get(index);
        Set<String> members = selector.kind() == SelectorKind.ITEM
                ? Set.of(selector.value())
                : tags.getOrDefault(selector.value(), Set.of());
        for (String member : members) {
            ingredients.add(member);
            expandIngredientKey(selectors, selectedMask, index + 1, ingredients, result);
            ingredients.removeLast();
        }
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

    private static OptionalInt optionalRgb(Map<String, String> row, String key)
            throws IOException {
        String encoded = row.get(key);
        if (encoded == null || encoded.isBlank()) {
            return OptionalInt.empty();
        }
        if (!encoded.matches("#[0-9a-fA-F]{6}")) {
            throw new IOException(key + " must use #RRGGBB format");
        }
        return OptionalInt.of(Integer.parseInt(encoded.substring(1), 16));
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

    public record BarrelRecipe(String id, String result, Selector carrier, String fluid,
                               int unitTicks, List<Selector> ingredients,
                               OptionalInt tapColor) {
        public BarrelRecipe(String id, String result, Selector carrier, String fluid,
                            int unitTicks, List<Selector> ingredients) {
            this(id, result, carrier, fluid, unitTicks, ingredients, OptionalInt.empty());
        }

        public BarrelRecipe {
            ingredients = List.copyOf(ingredients);
            tapColor = Objects.requireNonNull(tapColor, "tapColor");
        }
    }

    public record ShakerRecipe(String id, String result, List<Selector> ingredients) {
    }

    public record EffectSpec(String effect, int durationTicks, int amplifier, double probability) {
    }

    private record IngredientKey(List<String> ingredients) {
        private static IngredientKey of(List<String> ingredients) {
            List<String> canonical = new ArrayList<>(ingredients);
            canonical.sort(String::compareTo);
            return new IngredientKey(List.copyOf(canonical));
        }
    }
}
