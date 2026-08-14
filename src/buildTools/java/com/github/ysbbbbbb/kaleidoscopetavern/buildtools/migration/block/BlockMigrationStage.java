package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.block;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic block-configuration migration stage backed only by archived source assets. */
public final class BlockMigrationStage {
    public static final String NAMESPACE = "kaleidoscope_tavern";

    private final Path projectRoot;
    private final Path outputRoot;
    private final List<String> blockIds;
    private final Set<String> itemIds;
    private final Map<String, List<String>> flattenedTags;

    public BlockMigrationStage(Path projectRoot, Path outputRoot, List<String> blockIds,
                               Set<String> itemIds,
                               Map<String, ? extends List<String>> flattenedTags) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot").toAbsolutePath().normalize();
        this.blockIds = List.copyOf(requireStrings(Objects.requireNonNull(blockIds, "blockIds"), "blockIds"));
        this.itemIds = Collections.unmodifiableSet(new LinkedHashSet<>(requireStrings(
                Objects.requireNonNull(itemIds, "itemIds"), "itemIds")));
        Objects.requireNonNull(flattenedTags, "flattenedTags");
        LinkedHashMap<String, List<String>> tags = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends List<String>> entry : flattenedTags.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "flattenedTags key");
            tags.put(key, List.copyOf(requireStrings(Objects.requireNonNull(entry.getValue(),
                    "flattenedTags[" + key + "]"), "flattenedTags[" + key + "]")));
        }
        this.flattenedTags = Collections.unmodifiableMap(tags);
    }

    /** Build a fresh result exclusively from archived blockstates, models and registry inputs. */
    public Result run() throws IOException {
        return OrdinaryBlockGenerator.generate(projectRoot, outputRoot, blockIds, itemIds, flattenedTags);
    }

    public Result build() throws IOException {
        return run();
    }

    static JsonObject readObjectForHelpers(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Missing JSON input: " + path);
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) throw new IllegalStateException("Expected JSON object in " + path);
            return parsed.getAsJsonObject();
        }
    }

    private static List<String> requireStrings(Iterable<String> values, String owner) {
        ArrayList<String> result = new ArrayList<>();
        for (String value : values) result.add(Objects.requireNonNull(value, owner + " element"));
        return result;
    }

    public record Result(JsonObject blocks, JsonObject renderItems, Map<String, Integer> metrics) {
        public Result {
            Objects.requireNonNull(blocks, "blocks");
            Objects.requireNonNull(renderItems, "renderItems");
            Objects.requireNonNull(metrics, "metrics");
            metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        }
    }
}
