package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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

/** Ordered parsing and selection for authored block-state variants. */
public final class BlockStateVariants {
    private static final Map<String, String> PROPERTY_DEFAULTS = Map.ofEntries(
            Map.entry("facing", "north"), Map.entry("axis", "y"), Map.entry("table_axis", "x"),
            Map.entry("waterlogged", "false"), Map.entry("powered", "false"),
            Map.entry("triggered", "false"), Map.entry("open", "false"), Map.entry("waxed", "false"),
            Map.entry("age", "0"), Map.entry("count", "1"), Map.entry("rotation", "0"),
            Map.entry("connection", "single"), Map.entry("shape", "straight"),
            Map.entry("position", "0"), Map.entry("half", "bottom"),
            Map.entry("face", "floor"), Map.entry("type", "single"));
    private static final Map<String, String> RECORD_DEFAULTS = Map.ofEntries(
            Map.entry("facing", "north"), Map.entry("waterlogged", "false"),
            Map.entry("powered", "false"), Map.entry("triggered", "false"),
            Map.entry("open", "false"), Map.entry("connection", "single"),
            Map.entry("position", "single"), Map.entry("count", "1"),
            Map.entry("rotation", "0"), Map.entry("axis", "x"), Map.entry("half", "bottom"),
            Map.entry("face", "wall"), Map.entry("tilt", "false"), Map.entry("waxed", "false"));

    private BlockStateVariants() {}

    static LinkedHashMap<String, String> parseVariantKey(String key) {
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        if (key.isEmpty()) return properties;
        for (String pair : key.split(",", -1)) {
            int separator = pair.indexOf('=');
            if (separator < 0) throw new IllegalArgumentException("Malformed blockstate variant key: " + key);
            properties.put(pair.substring(0, separator), pair.substring(separator + 1));
        }
        return properties;
    }

    static JsonObject propertyDefinition(String name, List<String> values) {
        List<String> ordered = new ArrayList<>(new LinkedHashSet<>(values));
        if (ordered.isEmpty()) throw new IllegalArgumentException("Empty values for property " + name);
        String preferred = PROPERTY_DEFAULTS.get(name);
        if ("position".equals(name) && ordered.contains("single")) preferred = "single";
        String defaultValue = preferred != null && ordered.contains(preferred) ? preferred : ordered.getFirst();
        Set<String> distinct = Set.copyOf(ordered);
        JsonObject result = new JsonObject();
        if (Set.of("true", "false").containsAll(distinct)) {
            result.addProperty("type", "boolean"); result.addProperty("default", defaultValue); return result;
        }
        String type = null;
        if ("facing".equals(name) && Set.of("north", "east", "south", "west").containsAll(distinct)) type = "horizontal_direction";
        else if ("shape".equals(name) && Set.of("straight", "inner_left", "inner_right").containsAll(distinct)) type = "sofa_shape";
        else if ("facing".equals(name) && Set.of("north", "east", "south", "west", "up", "down").containsAll(distinct)) type = "direction";
        else if (("axis".equals(name) || "table_axis".equals(name)) && Set.of("x", "y", "z").containsAll(distinct)) type = "axis";
        else if ("face".equals(name) && Set.of("floor", "wall", "ceiling").containsAll(distinct)) type = "anchor_type";
        else if ("half".equals(name) && Set.of("top", "bottom").containsAll(distinct)) type = "single_block_half";
        else if ("half".equals(name) && Set.of("upper", "lower").containsAll(distinct)) type = "double_block_half";
        if (type != null) {
            result.addProperty("type", type); result.addProperty("default", defaultValue); result.add("values", strings(ordered)); return result;
        }
        boolean integers = ordered.stream().allMatch(value -> value.matches("-?\\d+"));
        if (integers) {
            List<Integer> numbers = ordered.stream().map(Integer::parseInt).sorted().toList();
            result.addProperty("type", "int"); result.addProperty("default", Integer.parseInt(defaultValue));
            result.addProperty("range", numbers.getFirst() + "~" + numbers.getLast()); return result;
        }
        result.addProperty("type", "string"); result.addProperty("default", defaultValue); result.add("values", strings(ordered)); return result;
    }

    /** min(records, key=record_score): (mismatches, rotation cost, model id). */
    public static Record minByScore(List<Record> records) {
        return records.stream()
                .min(Comparator.comparingInt(BlockStateVariants::mismatches)
                        .thenComparingInt(record -> record.model().rotationCost())
                        .thenComparing(record -> record.model().model()))
                .orElseThrow();
    }

    public static List<Record> read(Path projectRoot, String blockId) throws IOException {
        Path relative = Path.of("assets", BlockMigrationStage.NAMESPACE, "blockstates", blockId + ".json");
        Path path = firstRegular(projectRoot.resolve("src/generated/resources").resolve(relative),
                projectRoot.resolve("src/main/resources").resolve(relative));
        if (path == null) throw new IOException("No blockstate for " + blockId);
        JsonObject data = BlockMigrationStage.readObjectForHelpers(path);
        if (data.has("multipart")) throw new IllegalArgumentException("Multipart blockstate is not supported: " + path);
        JsonElement variantsElement = data.get("variants");
        if (variantsElement == null || !variantsElement.isJsonObject() || variantsElement.getAsJsonObject().isEmpty())
            throw new IllegalArgumentException("No variants in " + path);
        List<Record> records = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : variantsElement.getAsJsonObject().entrySet())
            records.add(new Record(parseVariantKey(entry.getKey()), normalizeModelEntry(entry.getValue())));
        return List.copyOf(records);
    }

    static Model normalizeModelEntry(JsonElement raw) {
        if (raw.isJsonArray()) {
            JsonArray array = raw.getAsJsonArray();
            if (array.isEmpty()) throw new IllegalArgumentException("Empty weighted blockstate model list");
            raw = array.get(0);
        }
        if (!raw.isJsonObject() || !raw.getAsJsonObject().has("model"))
            throw new IllegalArgumentException("Unsupported blockstate model: " + raw);
        JsonObject object = raw.getAsJsonObject();
        return new Model(object.get("model").getAsString(), integer(object, "x"), integer(object, "y"),
                integer(object, "z"), object.has("uvlock") && object.get("uvlock").getAsBoolean());
    }

    public static Record select(List<Record> records, Map<String, String> required) {
        return records.stream().filter(record -> required.entrySet().stream()
                        .allMatch(entry -> entry.getValue().equals(record.properties().get(entry.getKey()))))
                .min(Comparator.comparingInt(BlockStateVariants::mismatches)
                        .thenComparingInt(record -> record.model().rotationCost())
                        .thenComparing(record -> record.model().model()))
                .orElseThrow(() -> new IllegalArgumentException("No blockstate variant matches " + required));
    }

    private static int mismatches(Record record) {
        int count = 0;
        for (Map.Entry<String, String> entry : record.properties().entrySet()) {
            if ("position".equals(entry.getKey()) && Set.of("single", "0").contains(entry.getValue())) continue;
            if (!entry.getValue().equals(RECORD_DEFAULTS.getOrDefault(entry.getKey(), entry.getValue()))) count++;
        }
        return count;
    }

    private static int integer(JsonObject object, String key) { return object.has(key) ? object.get(key).getAsInt() : 0; }
    private static Path firstRegular(Path... paths) { for (Path path : paths) if (Files.isRegularFile(path)) return path; return null; }
    private static JsonArray strings(List<String> values) { JsonArray array = new JsonArray(); values.forEach(array::add); return array; }

    public record Model(String model, int x, int y, int z, boolean uvlock) {
        int rotationCost() { return Math.abs(x) + Math.abs(y) + Math.abs(z); }
        String digestInput() { return model + "|" + x + "|" + y + "|" + z + "|" + (uvlock ? "True" : "False"); }
    }
    public record Record(LinkedHashMap<String, String> properties, Model model) {}
}
