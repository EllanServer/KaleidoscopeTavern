package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.orchestrator;

import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.assets.AssetMigrationStage;
import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.block.BlockMigrationStage;
import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.core.CoreDataStage;
import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.core.CoreMigrationException;
import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.data.MigrationDataIO;
import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.furniture.FurnitureMigrationStage;
import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.image.LegacyImageAndPlacedDrinkStage;
import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.items.ItemMigrationStage;
import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.runtime.RuntimeRenderItems;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native top-level migration orchestration with isolated, replaceable stages. */
public final class LegacyMigrationOrchestrator {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private final Path root;
    private final Path outputRoot;
    private final List<NativeMigrationStage> extensionStages;

    public LegacyMigrationOrchestrator(Path root, Path outputRoot, List<NativeMigrationStage> extensionStages) {
        this.root = root.toAbsolutePath().normalize();
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
        this.extensionStages = List.copyOf(extensionStages);
    }

    public Result run() throws Exception {
        prepareOutput();
        CoreDataStage.Result core = new CoreDataStage(root).analyze();
        MigrationContext context = new MigrationContext(root, outputRoot, core);
        LegacyIdClassifier.Classification ids = new LegacyIdClassifier().classify(core.legacyBlockIds());
        writeRecipes(context); writeTags(context); writeCategories(context);
        Path assetsRoot = context.namespaceAssets();
        new AssetMigrationStage(root, assetsRoot).run();
        new LegacyImageAndPlacedDrinkStage(root, assetsRoot).generateEmptyBottleOverrides();
        BlockMigrationStage.Result blocks = new BlockMigrationStage(root, assetsRoot,
                ids.blockIds(), new LinkedHashSet<>(core.itemIds()), core.flattenedTags()).build();
        FurnitureMigrationStage.Result furniture = new FurnitureMigrationStage(root, assetsRoot)
                .build(ids.furnitureIds(), new LinkedHashSet<>(core.itemIds()));
        JsonObject renderItems = new JsonObject();
        for (var entry : blocks.renderItems().entrySet()) renderItems.add(entry.getKey(), entry.getValue().deepCopy());
        for (var entry : furniture.renderItems().entrySet()) renderItems.add(entry.getKey(), entry.getValue().deepCopy());
        new RuntimeRenderItems(root, assetsRoot).add(renderItems);
        Map<String, JsonObject> placement = new LinkedHashMap<>(furniture.placement());
        Set<String> languageKeys = new LinkedHashSet<>(MigrationDataIO.readJson(
                root.resolve("src/main/resources/assets/kaleidoscope_tavern/lang/en_us.json"))
                .getAsJsonObject().keySet());
        Map<String, Map<String, List<String>>> registryTags = new LinkedHashMap<>();
        registryTags.put("block", core.blockTags());
        registryTags.put("entity_type", core.entityTags());
        ItemMigrationStage.Input input = new ItemMigrationStage.Input(core.itemIds(),
                new LinkedHashSet<>(ids.blockIds()), new LinkedHashSet<>(ids.furnitureIds()),
                placement, core.flattenedTags(), registryTags, languageKeys);
        ItemMigrationStage.Result items = new ItemMigrationStage(root, outputRoot).migrate(input);
        writeBlocks(context, blocks);
        writeFurniture(context, furniture);
        writeRenderItems(context, renderItems);
        Map<String, Integer> metrics = metrics(core, ids, blocks, furniture, items, renderItems.size());
        writeReport(context, metrics);
        return new Result(core, ids, metrics, sha256(context.configuration().resolve("recipes.json")));
    }

    private static void writeBlocks(MigrationContext context, BlockMigrationStage.Result blocks)
            throws IOException {
        JsonObject document = new JsonObject(); document.add("blocks", blocks.blocks());
        MigrationDataIO.writeJson(context.configuration().resolve("blocks.json"), document);
    }

    private static void writeFurniture(MigrationContext context, FurnitureMigrationStage.Result furniture)
            throws IOException {
        JsonObject document = new JsonObject(); document.add("furniture", furniture.furniture());
        MigrationDataIO.writeJson(context.configuration().resolve("furniture.json"), document);
    }

    private static void writeRenderItems(MigrationContext context, JsonObject renderItems)
            throws IOException {
        JsonObject document = new JsonObject(); document.add("items", renderItems);
        MigrationDataIO.writeJson(context.configuration().resolve("render-items.json"), document);
    }

    private static void writeRecipes(MigrationContext context) throws IOException {
        JsonObject document = new JsonObject(); document.add("recipes", context.core().standardRecipes());
        MigrationDataIO.writeJson(context.configuration().resolve("recipes.json"), document);
    }

    private static void writeCategories(MigrationContext context) throws IOException {
        JsonArray list = new JsonArray();
        for (String id : context.core().itemIds()) list.add(PREFIX + id);
        JsonObject category = new JsonObject();
        category.addProperty("name", "<!i><dark_aqua>森罗酒馆</dark_aqua>");
        category.addProperty("icon", PREFIX + "wine"); category.addProperty("priority", 10); category.add("list", list);
        JsonObject categories = new JsonObject(); categories.add(PREFIX + "all", category);
        JsonObject document = new JsonObject(); document.add("categories", categories);
        MigrationDataIO.writeJson(context.configuration().resolve("categories.json"), document);
    }

    private static void writeTags(MigrationContext context) throws IOException {
        List<List<?>> itemRows = new ArrayList<>();
        context.core().flattenedTags().keySet().stream().sorted().forEach(tag ->
                context.core().flattenedTags().get(tag).forEach(member -> itemRows.add(List.of(tag, member))));
        MigrationDataIO.writeTsv(context.catalog().resolve("tags.tsv"), List.of("tag", "item"), itemRows);
        List<List<?>> registryRows = new ArrayList<>();
        addRegistryRows(registryRows, "block", context.core().blockTags());
        addRegistryRows(registryRows, "entity_type", context.core().entityTags());
        MigrationDataIO.writeTsv(context.catalog().resolve("registry-tags.tsv"),
                List.of("registry", "tag", "member"), registryRows);
    }

    private static void addRegistryRows(List<List<?>> rows, String registry, Map<String, List<String>> tags) {
        tags.keySet().stream().sorted().forEach(tag ->
                tags.get(tag).forEach(member -> rows.add(List.of(registry, tag, member))));
    }

    private static Map<String, Integer> metrics(CoreDataStage.Result core,
            LegacyIdClassifier.Classification ids, BlockMigrationStage.Result blocks,
            FurnitureMigrationStage.Result furniture, ItemMigrationStage.Result items,
            int renderItemCount) {
        Map<String, Integer> metrics = new LinkedHashMap<>();
        metrics.put("items", core.itemIds().size());
        metrics.put("vanilla_item_extensions", 4);
        metrics.put("blocks", blocks.blocks().size());
        metrics.put("furniture", furniture.furniture().size());
        metrics.put("legacy_placeables", core.legacyBlockIds().size());
        metrics.put("render_items", renderItemCount);
        metrics.put("standard_recipes", core.recipeCount());
        blocks.metrics().forEach(metrics::put);
        furniture.metrics().forEach(metrics::put);
        metrics.put("pressing", items.runtimeMetrics().pressing());
        metrics.put("barrel", items.runtimeMetrics().barrel());
        metrics.put("shaker", items.runtimeMetrics().shaker());
        metrics.put("drink_effect_items", items.runtimeMetrics().drinkEffectItems());
        metrics.put("drink_effect_entries", items.runtimeMetrics().drinkEffectEntries());
        metrics.put("tag_memberships", countAll(core.flattenedTags()));
        metrics.put("registry_tag_memberships", countAll(core.blockTags()) + countAll(core.entityTags()));
        return java.util.Collections.unmodifiableMap(metrics);
    }

    private static int countAll(Map<String,List<String>> tags) {
        return tags.values().stream().mapToInt(List::size).sum();
    }

    private static void writeReport(MigrationContext context, Map<String,Integer> metrics) throws IOException {
        JsonObject report = new JsonObject();
        report.addProperty("source", "KaleidoscopeTavern Forge 1.20.1 data generators");
        report.addProperty("target", "Paper 26.2 + CraftEngine 26.8");
        metrics.forEach(report::addProperty);
        JsonObject document = new JsonObject(); document.add("kaleidoscope_tavern_migration", report);
        MigrationDataIO.writeJson(context.configuration().resolve("migration-report.json"), document);
    }

    private void prepareOutput() throws IOException {
        if (!root.equals(outputRoot)) {
            cleanOutputRoot(outputRoot);
            return;
        }
        // In-project build-tool mode must never erase the checkout. Remove only
        // files owned by the stages currently wired into this orchestrator.
        for (Path file : List.of(
                outputRoot.resolve("src/paper/pack/configuration/recipes.json"),
                outputRoot.resolve("src/paper/pack/configuration/categories.json"),
                outputRoot.resolve("src/paper/pack/configuration/migration-report.json"),
                outputRoot.resolve("src/paper/resources/catalog/tags.tsv"),
                outputRoot.resolve("src/paper/resources/catalog/registry-tags.tsv"),
                outputRoot.resolve("src/paper/pack/resourcepack/assets/kaleidoscope_tavern/models/item/empty_bottle.json"),
                outputRoot.resolve("src/paper/pack/resourcepack/assets/kaleidoscope_tavern/textures/item/empty_bottle.png"),
                outputRoot.resolve("src/paper/pack/resourcepack/assets/kaleidoscope_tavern/textures/block/brew/empty_bottle.png"))) {
            Files.deleteIfExists(file);
        }
    }

    public static void cleanOutputRoot(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        if (normalized.getParent() == null) throw new CoreMigrationException("refusing to clean filesystem root");
        if (Files.exists(normalized)) Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException { if (failure != null) throw failure; Files.delete(dir); return FileVisitResult.CONTINUE; }
        });
        Files.createDirectories(normalized);
    }

    private static String sha256(Path path) throws IOException {
        try { return java.util.HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public record Result(CoreDataStage.Result core, LegacyIdClassifier.Classification ids,
                         Map<String,Integer> metrics, String recipesSha256) {}
}
