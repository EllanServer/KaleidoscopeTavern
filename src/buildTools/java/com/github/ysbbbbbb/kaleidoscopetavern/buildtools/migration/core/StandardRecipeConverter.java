package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.core;

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
import java.util.Set;
import java.util.stream.Stream;

/** Converts archived shaped/shapeless recipes to CraftEngine recipe objects. */
public final class StandardRecipeConverter {
    private static final String NAMESPACE = "kaleidoscope_tavern";

    public JsonObject convert(Path recipeRoot, Map<String, List<String>> tags) {
        JsonObject converted = new JsonObject();
        for (Path path : directJsonFiles(recipeRoot)) {
            JsonObject source = JsonFiles.read(path).getAsJsonObject();
            String type = source.has("type") ? source.get("type").getAsString() : "";
            int colon = type.lastIndexOf(':');
            if (colon >= 0) type = type.substring(colon + 1);
            String file = path.getFileName().toString();
            String recipeId = NAMESPACE + ":" + file.substring(0, file.length() - 5);
            JsonObject target = switch (type) {
                case "crafting_shaped" -> shaped(source, tags);
                case "crafting_shapeless" -> shapeless(source, tags);
                default -> throw new CoreMigrationException("unsupported standard recipe type " + type + " in " + path);
            };
            converted.add(recipeId, target);
        }
        return converted;
    }

    private static JsonObject shaped(JsonObject source, Map<String, List<String>> tags) {
        JsonObject target = common("shaped", "building", source);
        target.add("pattern", source.get("pattern").deepCopy());
        JsonObject ingredients = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : source.getAsJsonObject("key").entrySet())
            ingredients.add(entry.getKey(), compactIngredient(entry.getValue(), tags));
        target.add("ingredients", ingredients);
        target.add("result", resultEntry(source.get("result")));
        target.addProperty("unlock_on_ingredient_obtained", true);
        return target;
    }

    private static JsonObject shapeless(JsonObject source, Map<String, List<String>> tags) {
        JsonObject target = common("shapeless", "misc", source);
        JsonArray ingredients = new JsonArray();
        for (JsonElement entry : source.getAsJsonArray("ingredients"))
            ingredients.add(compactIngredient(entry, tags));
        target.add("ingredients", ingredients);
        target.add("result", resultEntry(source.get("result")));
        target.addProperty("unlock_on_ingredient_obtained", true);
        return target;
    }

    private static JsonObject common(String type, String category, JsonObject ignored) {
        JsonObject result = new JsonObject();
        result.addProperty("type", type); result.addProperty("category", category); return result;
    }

    private static JsonElement compactIngredient(JsonElement raw, Map<String, List<String>> tags) {
        List<String> values = ingredientValues(raw, tags);
        if (values.size() == 1) return new JsonPrimitive(values.getFirst());
        JsonArray result = new JsonArray(); values.forEach(result::add); return result;
    }

    private static List<String> ingredientValues(JsonElement raw, Map<String, List<String>> tags) {
        Set<String> result = new LinkedHashSet<>();
        collectIngredient(raw, tags, result);
        return List.copyOf(result);
    }

    private static void collectIngredient(JsonElement raw, Map<String, List<String>> tags, Set<String> result) {
        if (raw.isJsonArray()) { for (JsonElement entry : raw.getAsJsonArray()) collectIngredient(entry, tags, result); return; }
        if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
            result.add(LegacyIds.normalize(raw.getAsString())); return;
        }
        if (!raw.isJsonObject()) throw new CoreMigrationException("unsupported ingredient: " + raw);
        JsonObject object = raw.getAsJsonObject();
        for (String key : List.of("item", "id")) if (object.has(key)) {
            result.add(LegacyIds.normalize(object.get(key).getAsString())); return;
        }
        if (object.has("tag")) {
            String tag = object.get("tag").getAsString();
            List<String> members = tags.getOrDefault(tag, List.of());
            if (members.isEmpty() && tag.startsWith("minecraft:")) result.add("#" + tag);
            else if (members.isEmpty()) throw new CoreMigrationException("item tag " + tag + " has no resolvable members");
            else result.addAll(members);
            return;
        }
        throw new CoreMigrationException("unsupported ingredient object: " + raw);
    }

    private static JsonObject resultEntry(JsonElement raw) {
        JsonObject result = new JsonObject();
        if (raw.isJsonPrimitive()) {
            result.addProperty("id", LegacyIds.normalize(raw.getAsString())); result.addProperty("count", 1); return result;
        }
        JsonObject object = raw.getAsJsonObject();
        JsonElement id = object.has("item") ? object.get("item") : object.get("id");
        if (id == null) throw new CoreMigrationException("recipe result has no item id: " + raw);
        result.addProperty("id", LegacyIds.normalize(id.getAsString()));
        result.addProperty("count", object.has("count") ? object.get("count").getAsInt() : 1);
        return result;
    }

    private static List<Path> directJsonFiles(Path root) {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
        } catch (IOException error) { throw new CoreMigrationException("cannot list recipes " + root, error); }
    }
}
