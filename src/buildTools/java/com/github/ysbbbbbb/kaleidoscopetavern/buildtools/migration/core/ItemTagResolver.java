package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.core;

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
import java.util.stream.Stream;

/** Loads and recursively resolves archived item tags in deterministic path order. */
public final class ItemTagResolver {
    private static final Map<String, List<String>> FALLBACKS = fallbacks();

    public Map<String, List<String>> loadAndFlatten(List<Path> resourceRoots) {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        for (Path resourceRoot : resourceRoots) loadRoot(resourceRoot, raw);
        FALLBACKS.forEach((key, value) -> raw.putIfAbsent(key, value));
        Map<String, List<String>> memo = new LinkedHashMap<>();
        raw.keySet().stream().sorted().forEach(tag -> resolve(tag, raw, memo, new ArrayList<>()));
        return immutableCopy(memo);
    }

    private static void loadRoot(Path resourceRoot, Map<String, List<String>> tags) {
        Path data = resourceRoot.resolve("data");
        if (!Files.isDirectory(data)) return;
        for (Path namespace : directories(data)) {
            for (String folder : List.of("items", "item")) {
                Path root = namespace.resolve("tags").resolve(folder);
                if (!Files.isDirectory(root)) continue;
                for (Path path : jsonFiles(root)) {
                    String relative = root.relativize(path).toString().replace('\\', '/');
                    String tag = namespace.getFileName() + ":" + relative.substring(0, relative.length() - 5);
                    JsonObject json = JsonFiles.read(path).getAsJsonObject();
                    List<String> values = json.has("replace") && json.get("replace").getAsBoolean()
                            ? new ArrayList<>() : new ArrayList<>(tags.getOrDefault(tag, List.of()));
                    if (json.has("values")) for (JsonElement entry : json.getAsJsonArray("values")) {
                        if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                            values.add(LegacyIds.normalize(entry.getAsString()));
                        } else if (entry.isJsonObject() && entry.getAsJsonObject().has("id")) {
                            values.add(LegacyIds.normalize(entry.getAsJsonObject().get("id").getAsString()));
                        }
                    }
                    tags.put(tag, values);
                }
            }
        }
    }

    private static List<String> resolve(String tag, Map<String, List<String>> raw,
                                        Map<String, List<String>> memo, List<String> stack) {
        if (memo.containsKey(tag)) return memo.get(tag);
        if (stack.contains(tag)) {
            List<String> cycle = new ArrayList<>(stack); cycle.add(tag);
            throw new CoreMigrationException("recursive item tag: " + String.join(" -> ", cycle));
        }
        stack.add(tag);
        Set<String> flattened = new LinkedHashSet<>();
        for (String value : raw.getOrDefault(tag, List.of())) {
            if (value.startsWith("#")) flattened.addAll(resolve(value.substring(1), raw, memo, stack));
            else flattened.add(value);
        }
        stack.removeLast();
        List<String> result = List.copyOf(flattened);
        memo.put(tag, result);
        return result;
    }

    private static List<Path> directories(Path root) {
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString)).toList();
        } catch (IOException error) { throw new CoreMigrationException("cannot list " + root, error); }
    }

    private static List<Path> jsonFiles(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
        } catch (IOException error) { throw new CoreMigrationException("cannot list " + root, error); }
    }

    private static Map<String, List<String>> immutableCopy(Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Map<String, List<String>> fallbacks() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("forge:rods/wooden", List.of("minecraft:stick"));
        result.put("forge:ingots/iron", List.of("minecraft:iron_ingot"));
        result.put("forge:nuggets/iron", List.of("minecraft:iron_nugget"));
        result.put("forge:nuggets/gold", List.of("minecraft:gold_nugget"));
        result.put("forge:gems/diamond", List.of("minecraft:diamond"));
        result.put("forge:glass", List.of("minecraft:glass"));
        result.put("forge:glass_panes", List.of("minecraft:glass_pane"));
        result.put("forge:string", List.of("minecraft:string"));
        result.put("forge:leather", List.of("minecraft:leather"));
        result.put("forge:slimeballs", List.of("minecraft:slime_ball"));
        for (String color : List.of("white","light_gray","gray","black","brown","red","orange","yellow","lime","green","cyan","light_blue","blue","purple","magenta","pink"))
            result.put("forge:dyes/" + color, List.of("minecraft:" + color + "_dye"));
        return java.util.Collections.unmodifiableMap(result);
    }
}
