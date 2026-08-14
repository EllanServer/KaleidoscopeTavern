package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Closed-world checks proving that archived Forge state and renderer semantics have Paper owners. */
public final class SourceParityValidator {
    private static final String NAMESPACE = "kaleidoscope_tavern";
    private static final Pattern REGISTRY_ID = Pattern.compile("BLOCKS\\.register\\(\"([a-z0-9_]+)\"");
    private static final Map<String, String> STATE_OWNERS = Map.ofEntries(
            Map.entry("age", "CustomCrops stage blocks"),
            Map.entry("axis", "CE native placement except table source axis, mapped to table_axis for ground placement"),
            Map.entry("connection", "ConnectedBlockBehavior sofa/counter topology plus migration furniture variants"),
            Map.entry("count", "BottleFurnitureService"),
            Map.entry("face", "CE ground/wall/ceiling placement rules"),
            Map.entry("facing", "CE chalkboard/tap/storage state plus native wall and four-way/sixteen-way furniture rotation"),
            Map.entry("half", "CE native double-high chalkboard plus composite multi-element furniture variants"),
            Map.entry("open", "CE incense/tap block state"),
            Map.entry("position", "CE chalkboard/storage state plus ConnectedBlockBehavior table topology"),
            Map.entry("powered", "CE incense/storage block redstone edge state"),
            Map.entry("rotation", "CE sixteen-way sandwich-board rotation"),
            Map.entry("tilt", "ground/wall pressing-tub placement variants"),
            Map.entry("triggered", "CE TapBlockBehavior redstone edge latch"),
            Map.entry("type", "CE trellis state variants"),
            Map.entry("waterlogged", "CE chalkboard/tap/trellis state plus water-preserving glowing string-light furniture"),
            Map.entry("waxed", "CE trellis state variants plus blocks.json wax events"));

    private static final Map<String, Evidence> RENDERER_COVERAGE = rendererCoverage();

    private SourceParityValidator() {}

    public static Result validate(Path root) throws IOException {
        Path sourceAssets = root.resolve("src/generated/resources/assets");
        Path fallbackAssets = root.resolve("src/main/resources/assets");
        List<String> blockIds = sourceRegistryIds(root.resolve(
                "src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/init/ModBlocks.java"));
        require(blockIds.size() == new LinkedHashSet<>(blockIds).size(),
                "ModBlocks contains duplicate source block registrations");
        Set<String> properties = sourceStateProperties(blockIds, sourceAssets, fallbackAssets);
        require(properties.equals(STATE_OWNERS.keySet()),
                "Source blockstate ownership drifted: expected " + STATE_OWNERS.keySet() + ", found " + properties);

        Path rendererRoot = root.resolve(
                "src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/client/render/block");
        Set<String> rendererFiles = javaBasenames(rendererRoot);
        require(rendererFiles.equals(RENDERER_COVERAGE.keySet()),
                "Source block-entity renderer coverage drifted: expected " + RENDERER_COVERAGE.keySet()
                        + ", found " + rendererFiles);
        Path gamePackage = root.resolve(
                "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/game");
        for (Map.Entry<String, Evidence> entry : RENDERER_COVERAGE.entrySet()) {
            Evidence evidence = entry.getValue();
            Path owner = gamePackage.resolve(evidence.relativePath());
            require(Files.isRegularFile(owner), entry.getKey() + ": missing Paper owner " + evidence.relativePath());
            String text = readText(owner);
            require(text.contains(evidence.token()), entry.getKey() + ": Paper owner "
                    + evidence.relativePath() + " lacks evidence token " + quote(evidence.token()));
        }
        return new Result(blockIds.size(), properties.size(), rendererFiles.size(), RENDERER_COVERAGE.size());
    }

    private static List<String> sourceRegistryIds(Path source) throws IOException {
        require(Files.isRegularFile(source), "Missing archived block registry " + source);
        Matcher matcher = REGISTRY_ID.matcher(readText(source));
        List<String> result = new ArrayList<>();
        while (matcher.find()) result.add(matcher.group(1));
        require(!result.isEmpty(), "Archived block registry contains no BLOCKS.register declarations");
        return List.copyOf(result);
    }

    private static Set<String> sourceStateProperties(
            List<String> blockIds, Path generatedAssets, Path mainAssets) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        for (String blockId : blockIds) {
            Path relative = Path.of(NAMESPACE, "blockstates", blockId + ".json");
            Path file = Files.isRegularFile(generatedAssets.resolve(relative))
                    ? generatedAssets.resolve(relative) : mainAssets.resolve(relative);
            require(Files.isRegularFile(file), "Source block " + blockId + " has no blockstate");
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(readText(file));
            } catch (RuntimeException error) {
                throw new ValidationException(file + ": invalid JSON", error);
            }
            require(parsed.isJsonObject(), file + ": blockstate root must be an object");
            JsonObject object = parsed.getAsJsonObject();
            JsonElement variants = object.get("variants");
            if (variants != null && variants.isJsonObject()) {
                for (String selector : variants.getAsJsonObject().keySet()) {
                    for (String assignment : selector.split(",")) {
                        int equals = assignment.indexOf('=');
                        if (equals >= 0) result.add(assignment.substring(0, equals));
                    }
                }
            }
            JsonElement multipart = object.get("multipart");
            if (multipart != null && multipart.isJsonArray()) {
                for (JsonElement part : multipart.getAsJsonArray()) {
                    if (part.isJsonObject()) collectWhen(part.getAsJsonObject().get("when"), result);
                }
            }
        }
        return Set.copyOf(result);
    }

    private static void collectWhen(JsonElement value, Set<String> result) {
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            for (JsonElement child : array) collectWhen(child, result);
        } else if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                if (entry.getKey().equals("OR") || entry.getKey().equals("AND")) {
                    collectWhen(entry.getValue(), result);
                } else {
                    result.add(entry.getKey());
                }
            }
        }
    }

    private static Set<String> javaBasenames(Path directory) throws IOException {
        require(Files.isDirectory(directory), "Missing archived renderer directory " + directory);
        try (Stream<Path> paths = Files.list(directory)) {
            Set<String> result = new LinkedHashSet<>();
            paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".java") && !name.equals("package-info.java"))
                    .sorted().forEach(result::add);
            return Set.copyOf(result);
        }
    }

    private static Map<String, Evidence> rendererCoverage() {
        Map<String, Evidence> result = new LinkedHashMap<>();
        result.put("BarCabinetBlockEntityRender.java", new Evidence("storage/StorageBlockConfig.java", "record SlotVisual"));
        result.put("BarrelBlockEntityRender.java", new Evidence("station/StationService.java", "BarrelSemantics"));
        result.put("BarStoolBlockEntityRender.java", new Evidence("decor/BarStoolVisualService.java", "getBodyYaw"));
        result.put("CellarCabinetBlockEntityRender.java", new Evidence("storage/StorageBlockConfig.java", "record Orientation"));
        result.put("ChalkboardBlockEntityRender.java", new Evidence("board/ChalkboardBlockBehavior.java", "class BlockTextElement"));
        result.put("CircularRackBlockEntityRender.java", new Evidence("storage/StorageBlockConfig.java", "record SlotVisual"));
        result.put("GlasswareHolderBlockEntityRender.java", new Evidence("storage/DisplayStorageService.java", "StorageSemantics"));
        result.put("HolderBlockEntityRender.java", new Evidence("storage/StorageBlockConfig.java", "record SlotVisual"));
        result.put("PressingTubBlockEntityRender.java", new Evidence("pressing/PressingTubVisualFactory.java", "visuals"));
        result.put("SandwichBlockEntityRender.java", new Evidence("board/BoardTextService.java", "sandwich"));
        result.put("ShakerBlockEntityRender.java", new Evidence("shaker/ShakerVisualService.java", "ShakerAnimationSemantics"));
        result.put("StorageBlockEntityRender.java", new Evidence("storage/StorageBlockBehavior.java", "renderPosition("));
        result.put("TextBlockEntityRender.java", new Evidence("board/BoardTextService.java", "boardVisuals"));
        result.put("TiltedRackBlockEntityRender.java", new Evidence("storage/StorageBlockConfig.java", "record SlotVisual"));
        return Map.copyOf(result);
    }

    private static String readText(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static String quote(String text) { return "'" + text + "'"; }

    private static void require(boolean condition, String message) {
        if (!condition) throw new ValidationException(message);
    }

    private record Evidence(String relativePath, String token) {}

    public record Result(int sourceBlocks, int stateProperties, int sourceRenderers, int rendererOwners) {}

    public static final class ValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ValidationException(String message) { super(message); }
        public ValidationException(String message, Throwable cause) { super(message, cause); }
    }
}
