package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Converts archived vanilla-shaped recipe JSON into CraftEngine recipe objects. */
public final class StandardRecipeConverter {
    private StandardRecipeConverter() {}

    public static Map<String, JsonObject> convert(Path projectRoot, Path outputRoot, String namespace,
                                                   Map<String, ? extends List<String>> tags) throws IOException {
        Path recipes = projectRoot.resolve("src/generated/resources/data").resolve(namespace).resolve("recipes");
        Map<String, JsonObject> converted = new LinkedHashMap<>();
        if (!Files.isDirectory(recipes)) return converted;
        List<Path> paths;
        try (Stream<Path> stream = Files.list(recipes)) {
            paths = stream.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        for (Path path : paths) {
            JsonObject source = MigrationDataIO.readJson(path).getAsJsonObject();
            String rawType = source.has("type") ? source.get("type").getAsString() : "";
            String type = rawType.substring(rawType.lastIndexOf(':') + 1);
            String file = path.getFileName().toString();
            String id = namespace + ":" + file.substring(0, file.length() - 5);
            JsonObject recipe = new JsonObject();
            if (type.equals("crafting_shaped")) {
                recipe.addProperty("type", "shaped");
                recipe.addProperty("category", "building");
                recipe.add("pattern", source.get("pattern").deepCopy());
                JsonObject ingredients = new JsonObject();
                for (var entry : source.getAsJsonObject("key").entrySet()) {
                    ingredients.add(entry.getKey(), compactIngredient(entry.getValue(), tags));
                }
                recipe.add("ingredients", ingredients);
            } else if (type.equals("crafting_shapeless")) {
                recipe.addProperty("type", "shapeless");
                recipe.addProperty("category", "misc");
                JsonArray ingredients = new JsonArray();
                for (JsonElement entry : source.getAsJsonArray("ingredients")) {
                    ingredients.add(compactIngredient(entry, tags));
                }
                recipe.add("ingredients", ingredients);
            } else {
                throw new IllegalArgumentException("Unsupported standard recipe type " + type + " in " + path);
            }
            recipe.add("result", resultEntry(source.get("result")));
            recipe.addProperty("unlock_on_ingredient_obtained", true);
            converted.put(id, recipe);
        }
        return converted;
    }

    public static List<String> ingredientValues(JsonElement raw, Map<String, ? extends List<String>> tags) {
        if (raw.isJsonArray()) {
            LinkedHashSet<String> merged = new LinkedHashSet<>();
            for (JsonElement entry : raw.getAsJsonArray()) merged.addAll(ingredientValues(entry, tags));
            return List.copyOf(merged);
        }
        if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
            return List.of(ForgeTagResolver.normalizeLegacyResourceId(raw.getAsString()));
        }
        if (!raw.isJsonObject()) throw new IllegalArgumentException("Unsupported ingredient: " + raw);
        JsonObject object = raw.getAsJsonObject();
        if (object.has("item")) return List.of(ForgeTagResolver.normalizeLegacyResourceId(object.get("item").getAsString()));
        if (object.has("id")) return List.of(ForgeTagResolver.normalizeLegacyResourceId(object.get("id").getAsString()));
        if (object.has("tag")) {
            String tag = object.get("tag").getAsString();
            List<String> values = tags.containsKey(tag) ? List.copyOf(tags.get(tag)) : List.of();
            if (values.isEmpty() && tag.startsWith("minecraft:")) return List.of("#" + tag);
            if (values.isEmpty()) throw new IllegalArgumentException("Item tag " + tag + " has no resolvable members");
            return values;
        }
        throw new IllegalArgumentException("Unsupported ingredient object: " + raw);
    }

    public static JsonElement compactIngredient(JsonElement raw, Map<String, ? extends List<String>> tags) {
        List<String> values = ingredientValues(raw, tags);
        if (values.size() == 1) return new JsonPrimitive(values.getFirst());
        JsonArray array = new JsonArray(); values.forEach(array::add); return array;
    }

    public static JsonObject resultEntry(JsonElement raw) {
        JsonObject result = new JsonObject();
        if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
            result.addProperty("id", ForgeTagResolver.normalizeLegacyResourceId(raw.getAsString()));
            result.addProperty("count", 1);
            return result;
        }
        JsonObject object = raw.getAsJsonObject();
        JsonElement item = object.has("item") ? object.get("item") : object.get("id");
        if (item == null || item.isJsonNull() || item.getAsString().isEmpty()) {
            throw new IllegalArgumentException("Recipe result has no item id: " + raw);
        }
        result.addProperty("id", ForgeTagResolver.normalizeLegacyResourceId(item.getAsString()));
        result.addProperty("count", object.has("count") ? object.get("count").getAsInt() : 1);
        return result;
    }
}
