package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/** Loads Forge registry tags and recursively expands tag references without reordering members. */
public final class ForgeTagResolver {
    private ForgeTagResolver() {}

    public static Map<String, List<String>> loadItemTags(Path projectRoot, Path outputRoot,
                                                          Map<String, ? extends List<String>> fallbacks,
                                                          UnaryOperator<String> normalizer) throws IOException {
        Map<String, List<String>> tags = load(projectRoot, List.of("items", "item"), false, normalizer);
        for (var entry : fallbacks.entrySet()) tags.putIfAbsent(entry.getKey(), List.copyOf(entry.getValue()));
        return tags;
    }

    public static Map<String, List<String>> loadRegistryTags(Path projectRoot, Path outputRoot,
                                                              List<String> folderNames,
                                                              UnaryOperator<String> normalizer) throws IOException {
        return load(projectRoot, folderNames, true, normalizer);
    }

    private static Map<String, List<String>> load(Path projectRoot, List<String> folders,
                                                   boolean skipOptionalObjects,
                                                   UnaryOperator<String> normalizer) throws IOException {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        for (Path resourceRoot : List.of(projectRoot.resolve("src/main/resources"),
                projectRoot.resolve("src/generated/resources"))) {
            Path dataRoot = resourceRoot.resolve("data");
            if (!Files.isDirectory(dataRoot)) continue;
            for (Path namespace : sortedDirectories(dataRoot)) {
                for (String folder : folders) {
                    Path tagsRoot = namespace.resolve("tags").resolve(folder);
                    if (!Files.isDirectory(tagsRoot)) continue;
                    for (Path path : sortedJsonFiles(tagsRoot)) {
                        String relative = tagsRoot.relativize(path).toString().replace('\\', '/');
                        String tag = namespace.getFileName() + ":" + relative.substring(0, relative.length() - 5);
                        JsonObject data = MigrationDataIO.readJson(path).getAsJsonObject();
                        List<String> values = data.has("replace") && data.get("replace").getAsBoolean()
                                ? new ArrayList<>() : new ArrayList<>(tags.getOrDefault(tag, List.of()));
                        if (data.has("values")) for (JsonElement raw : data.getAsJsonArray("values")) {
                            if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
                                values.add(normalizer.apply(raw.getAsString()));
                            } else if (raw.isJsonObject() && raw.getAsJsonObject().has("id")) {
                                JsonObject object = raw.getAsJsonObject();
                                if (!skipOptionalObjects || !object.has("required") || object.get("required").getAsBoolean()) {
                                    values.add(normalizer.apply(object.get("id").getAsString()));
                                }
                            }
                        }
                        tags.put(tag, List.copyOf(values));
                    }
                }
            }
        }
        return tags;
    }

    public static Map<String, List<String>> flattenTags(Map<String, ? extends List<String>> rawTags) {
        Map<String, List<String>> memo = new LinkedHashMap<>();
        rawTags.keySet().stream().sorted().forEach(tag -> resolve(tag, rawTags, memo, new ArrayList<>()));
        return memo;
    }

    private static List<String> resolve(String tag, Map<String, ? extends List<String>> raw,
                                        Map<String, List<String>> memo, List<String> stack) {
        if (memo.containsKey(tag)) return memo.get(tag);
        if (stack.contains(tag)) {
            List<String> cycle = new ArrayList<>(stack); cycle.add(tag);
            throw new IllegalArgumentException("Recursive item tag: " + String.join(" -> ", cycle));
        }
        List<String> nextStack = new ArrayList<>(stack); nextStack.add(tag);
        Set<String> flattened = new LinkedHashSet<>();
        List<String> rawValues = raw.containsKey(tag) ? raw.get(tag) : List.of();
        for (String value : rawValues) {
            Collection<String> candidates = value.startsWith("#")
                    ? resolve(value.substring(1), raw, memo, nextStack) : List.of(value);
            flattened.addAll(candidates);
        }
        List<String> result = List.copyOf(flattened);
        memo.put(tag, result);
        return result;
    }

    public static String normalizeLegacyResourceId(String id) {
        boolean tag = id.startsWith("#");
        String bare = tag ? id.substring(1) : id;
        String renamed = switch (bare) {
            case "minecraft:chain" -> "minecraft:iron_chain";
            case "minecraft:grass" -> "minecraft:short_grass";
            default -> bare;
        };
        return tag ? "#" + renamed : renamed;
    }

    private static List<Path> sortedDirectories(Path root) throws IOException {
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString)).toList();
        }
    }

    private static List<Path> sortedJsonFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/'))).toList();
        }
    }
}
