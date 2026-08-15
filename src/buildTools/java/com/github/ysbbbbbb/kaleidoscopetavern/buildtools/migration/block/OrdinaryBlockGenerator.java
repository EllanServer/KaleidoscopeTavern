package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native port of the legacy ordinary block generation path (tools/migrate_legacy.py
 * build_blocks main loop, build_shared_sofa_block, split_hanging_crop_stages and the
 * block event attachments). Chalkboard and pressing_tub need their special builders.
 */
final class OrdinaryBlockGenerator {
    private static final String NAMESPACE = "kaleidoscope_tavern";
    private static final String SHARED_SOFA_ID = NAMESPACE + ":_internal/sofa";
    private static final String SHARED_SOFA_BLOCK = "_internal/sofa";
    private static final String SOFA_CARRIER_STATE = "minecraft:barrier";
    private static final String COPPER_LANTERN_CARRIER_STATE =
            "minecraft:copper_lantern[hanging=false,waterlogged=false]";
    private static final String GRAPE_CARRIER_TYPE = "cave_vines";
    private static final String GRAPE_CARRIER_ID = "kaleidoscope-tavern-wild-grapevine-transparent";
    private static final Set<String> HANGING_GRAPE_CROPS =
            Set.of("grape_crop", "ice_grape_crop", "gold_grape_crop");
    private static final Set<String> TRELLIS_BLOCKS = Set.of(
            "trellis", "grapevine_trellis", "ice_grapevine_trellis", "gold_grapevine_trellis");
    private static final List<String> SOFA_CONNECTIONS = List.of(
            "single", "left", "left_corner", "middle", "right", "right_corner");
    private static final Map<String, Integer> ITEM_DISPLAY_FACING_YAW = Map.of(
            "north", 180, "east", 90, "south", 0, "west", 270);
    private static final Map<String, String> TABLE_ENDPOINT_MODELS = Map.of(
            NAMESPACE + ":block/deco/table/right", NAMESPACE + ":block/deco/table/left",
            NAMESPACE + ":block/deco/table/left", NAMESPACE + ":block/deco/table/right",
            NAMESPACE + ":block/deco/table/right_rot", NAMESPACE + ":block/deco/table/left_rot",
            NAMESPACE + ":block/deco/table/left_rot", NAMESPACE + ":block/deco/table/right_rot");

    private OrdinaryBlockGenerator() {}

    static BlockMigrationStage.Result generate(Path projectRoot, Path outputRoot,
                                               List<String> blockIds, Set<String> itemIds,
                                               Map<String, List<String>> tags) throws IOException {
        JsonObject blocks = new JsonObject();
        JsonObject renderItems = new JsonObject();
        LinkedHashMap<String, Integer> metrics = new LinkedHashMap<>();
        metrics.put("appearances", 0);
        metrics.put("weighted_variants_reduced", 0);
        metrics.put("collidable_trellises", 0);
        RenderItems renderer = new RenderItems(projectRoot);

        // Shared sofa: one active CE tint-source block replaces sixteen colour tables.
        int sofaAppearances = buildSharedSofaBlock(renderItems, renderer, projectRoot);
        blocks.add(SHARED_SOFA_ID, sofaConfig(renderItems, renderer, projectRoot));
        metrics.put("appearances", metrics.get("appearances") + sofaAppearances);

        for (String blockId : blockIds) {
            if (blockId.equals("chalkboard")) {
                SpecialBlocks.ChalkboardResult chalk = SpecialBlocks.buildChalkboard(
                        itemIds.contains(blockId), renderer);
                for (var entry : chalk.renderItems().entrySet()) {
                    renderItems.add(entry.getKey(), entry.getValue().deepCopy());
                }
                blocks.add(NAMESPACE + ":" + blockId, chalk.config());
                metrics.put("appearances", metrics.get("appearances") + chalk.appearanceCount());
                continue;
            }
            if (blockId.equals("pressing_tub")) {
                SpecialBlocks.TubResult tub = SpecialBlocks.buildPressingTub(
                        itemIds.contains(blockId), renderItems, renderer);
                blocks.add(NAMESPACE + ":" + blockId, tub.config());
                metrics.put("appearances", metrics.get("appearances") + tub.appearanceCount());
                continue;
            }
            JsonObject blockstate = readBlockstate(projectRoot, blockId);
            JsonObject variants = blockstate.getAsJsonObject("variants");
            boolean isSofa = BlockBehaviors.isSofaBlock(blockId);

            LinkedHashMap<String, List<String>> propertyValues = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
                for (Map.Entry<String, String> pair : parseVariantKey(entry.getKey()).entrySet()) {
                    for (String option : pair.getValue().split("\\|")) {
                        propertyValues.computeIfAbsent(pair.getKey(), ignored -> new ArrayList<>());
                        List<String> values = propertyValues.get(pair.getKey());
                        if (!values.contains(option)) values.add(option);
                    }
                }
            }
            if (blockId.equals("wild_grapevine")) {
                List<String> ages = new ArrayList<>();
                for (int age = 0; age < 26; age++) ages.add(String.valueOf(age));
                propertyValues.put("age", ages);
                propertyValues.put("sheared", List.of("false", "true"));
            }
            if (blockId.equals("table")) {
                propertyValues.put("table_axis", propertyValues.remove("axis"));
            }
            if (TRELLIS_BLOCKS.contains(blockId)) {
                propertyValues.put("axis", List.of("x", "y", "z"));
            }
            if (BlockBehaviors.FURNITURE_STYLE_BLOCKS.contains(blockId)
                    && !blockId.equals("table")) {
                propertyValues.remove("waterlogged");
            }
            String[] carrier = BlockBehaviors.carrierType(blockId);
            boolean usesWaterloggedCarrier = blockId.equals("tap") || TRELLIS_BLOCKS.contains(blockId);

            LinkedHashMap<Object, String> appearanceNames = new LinkedHashMap<>();
            JsonObject appearances = new JsonObject();
            JsonObject mappedVariants = new JsonObject();

            for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
                String variantKey = entry.getKey();
                JsonElement rawModel = entry.getValue();
                if (rawModel.isJsonArray() && rawModel.getAsJsonArray().size() > 1) {
                    metrics.put("weighted_variants_reduced",
                            metrics.get("weighted_variants_reduced") + 1);
                }
                LinkedHashMap<String, String> variantProperties = parseVariantKey(variantKey);
                if (blockId.equals("table") && variantProperties.containsKey("axis")) {
                    variantProperties.put("table_axis", variantProperties.remove("axis"));
                }
                if (blockId.equals("table")) {
                    variantKey = sortedVariantKey(variantProperties);
                } else if (BlockBehaviors.FURNITURE_STYLE_BLOCKS.contains(blockId)) {
                    variantProperties.remove("waterlogged");
                    variantKey = sortedVariantKey(variantProperties);
                }
                BlockStateVariants.Model model = BlockStateVariants.normalizeModelEntry(rawModel);
                if (blockId.equals("table")) {
                    String swapped = TABLE_ENDPOINT_MODELS.getOrDefault(model.model(), model.model());
                    model = new BlockStateVariants.Model(swapped, model.x(), model.y(), model.z(), model.uvlock());
                }
                if (BlockBehaviors.FURNITURE_STYLE_BLOCKS.contains(blockId)
                        && variantProperties.containsKey("facing")) {
                    model = new BlockStateVariants.Model(model.model(), model.x(),
                            ITEM_DISPLAY_FACING_YAW.get(variantProperties.get("facing")),
                            model.z(), model.uvlock());
                }
                if (blockId.equals("tap")
                        && Set.of("north", "south").contains(variantProperties.get("facing"))) {
                    model = new BlockStateVariants.Model(model.model(), model.x(),
                            (model.y() + 180) % 360, model.z(), model.uvlock());
                }
                String trellisType = TRELLIS_BLOCKS.contains(blockId)
                        ? variantProperties.get("type") : null;
                Object appearanceKey = usesWaterloggedCarrier
                        ? new WaterloggedModelKey(model, variantProperties.get("waterlogged"))
                        : model;
                String appearanceName = appearanceNames.get(appearanceKey);
                if (appearanceName == null) {
                    appearanceName = "appearance_" + appearanceNames.size();
                    appearanceNames.put(appearanceKey, appearanceName);
                    JsonObject appearance = new JsonObject();
                    String renderIdentity;
                    if (isSofa || BlockBehaviors.FURNITURE_STYLE_BLOCKS.contains(blockId)) {
                        renderIdentity = model.model() + "|0|0|0|False";
                    } else if (BlockBehaviors.INCENSE_BLOCKS.contains(blockId)
                            || BlockBehaviors.STORAGE_BLOCKS.contains(blockId)
                            || blockId.equals("tap")) {
                        renderIdentity = model.model();
                    } else {
                        renderIdentity = model.digestInput();
                    }
                    String renderHash = sha1(renderIdentity);
                    String renderPath = "_render/" + blockId + "/" + renderHash;
                    String renderId = NAMESPACE + ":" + renderPath;
                    JsonObject renderItem = new JsonObject();
                    renderItem.addProperty("material", "paper");
                    JsonObject data = new JsonObject();
                    data.addProperty("item_name", renderer.renderItemName(blockId));
                    renderItem.add("data", data);
                    JsonObject renderModel = new JsonObject();
                    renderModel.addProperty("type", "minecraft:model");
                    renderModel.addProperty("path", model.model());
                    renderItem.add("model", renderModel);
                    JsonObject settings = new JsonObject();
                    JsonArray renderTags = new JsonArray();
                    renderTags.add(NAMESPACE + ":internal_render_items");
                    settings.add("tags", renderTags);
                    renderItem.add("settings", settings);
                    renderItems.add(renderId, renderItem);
                    JsonObject rendererConfig = new JsonObject();
                    rendererConfig.addProperty("type", "item_display");
                    rendererConfig.addProperty("item", renderId);
                    rendererConfig.addProperty("display_transform", "none");
                    rendererConfig.addProperty("shadow_radius", 0);
                    rendererConfig.addProperty("view_range", 1.25);
                    if (model.x() != 0 || model.y() != 0 || model.z() != 0) {
                        rendererConfig.addProperty("rotation",
                                model.x() + "," + model.y() + "," + model.z());
                    }
                    if (blockId.equals("tap")) {
                        String facing = variantProperties.get("facing");
                        String waterlogged = variantProperties.get("waterlogged");
                        appearance.addProperty("state", "minecraft:lightning_rod[facing="
                                + facing + ",powered=false,waterlogged=" + waterlogged + "]");
                    } else if (carrier[0].equals("horizontal_lightning_rod")) {
                        appearance.addProperty("state",
                                BlockBehaviors.holderCarrierState(variantProperties.get("facing")));
                    } else if (carrier[0].equals("state") && BlockBehaviors.SHAPED_RACK_BLOCKS.contains(blockId)) {
                        appearance.addProperty("state", carrier[1]);
                    } else if (isSofa || BlockBehaviors.BARRIER_STYLE_BLOCKS.contains(blockId)) {
                        appearance.addProperty("state", SOFA_CARRIER_STATE);
                    } else if (BlockBehaviors.INCENSE_BLOCKS.contains(blockId)) {
                        appearance.addProperty("state", COPPER_LANTERN_CARRIER_STATE);
                    } else if (trellisType != null) {
                        appearance.addProperty("state", BlockBehaviors.trellisCarrierState(
                                trellisType, variantProperties.get("waterlogged")));
                        metrics.put("collidable_trellises", metrics.get("collidable_trellises") + 1);
                    } else if (Set.of("wild_grapevine", "wild_grapevine_plant").contains(blockId)
                            || HANGING_GRAPE_CROPS.contains(blockId)) {
                        JsonObject autoState = new JsonObject();
                        autoState.addProperty("type", GRAPE_CARRIER_TYPE);
                        autoState.addProperty("id", GRAPE_CARRIER_ID);
                        appearance.add("auto_state", autoState);
                    } else {
                        JsonObject autoState = new JsonObject();
                        autoState.addProperty("type", carrier[0]);
                        autoState.addProperty("id", carrier[1]);
                        appearance.add("auto_state", autoState);
                    }
                    if (!(isSofa || BlockBehaviors.BARRIER_STYLE_BLOCKS.contains(blockId))) {
                        appearance.addProperty("transparent", true);
                    }
                    appearance.add("entity_renderer", rendererConfig);
                    appearances.add(appearanceName, appearance);
                }
                if (!propertyValues.isEmpty()) {
                    JsonObject mappedVariant = new JsonObject();
                    mappedVariant.addProperty("appearance", appearanceName);
                    if ("true".equals(variantProperties.get("waterlogged"))) {
                        JsonObject variantSettings = new JsonObject();
                        variantSettings.addProperty("fluid_state", "water");
                        mappedVariant.add("settings", variantSettings);
                    }
                    mappedVariants.add(variantKey, mappedVariant);
                }
            }
            if (blockId.equals("wild_grapevine")) {
                String appearance = appearances.keySet().iterator().next();
                JsonObject synthetic = new JsonObject();
                for (int age = 0; age < 26; age++) {
                    for (boolean sheared : new boolean[] {false, true}) {
                        JsonObject variant = new JsonObject();
                        variant.addProperty("appearance", appearance);
                        synthetic.add("age=" + age + ",sheared=" + sheared, variant);
                    }
                }
                mappedVariants = synthetic;
            }
            metrics.put("appearances", metrics.get("appearances") + appearances.size());
            JsonObject config = new JsonObject();
            if (!propertyValues.isEmpty()) {
                JsonObject states = new JsonObject();
                JsonObject properties = new JsonObject();
                for (Map.Entry<String, List<String>> entry : propertyValues.entrySet()) {
                    properties.add(entry.getKey(),
                            BlockStateVariants.propertyDefinition(entry.getKey(), entry.getValue()));
                }
                states.add("properties", properties);
                states.add("appearances", appearances);
                states.add("variants", mappedVariants);
                config.add("states", states);
            } else {
                config.add("state", appearances.get(appearances.keySet().iterator().next()).deepCopy());
            }
            config.add("settings", BlockBehaviors.blockSettings(blockId, itemIds.contains(blockId)));
            JsonElement behavior = BlockBehaviors.behaviorFor(blockId, propertyValues.keySet(), tags);
            if (behavior != null && behavior.isJsonArray()) config.add("behaviors", behavior);
            else if (behavior != null) config.add("behavior", behavior);
            if (BlockBehaviors.INCENSE_BLOCKS.contains(blockId)) {
                config.add("events", BlockEvents.incenseToggleEvents());
            } else if (blockId.equals("trellis")) {
                config.add("events", BlockEvents.trellisWaxEvents());
            } else if (blockId.endsWith("_grapevine_trellis") || blockId.equals("grapevine_trellis")) {
                config.add("events", BlockEvents.grapevineTrellisShearEvents());
            } else if (blockId.equals("wild_grapevine")) {
                config.add("events", BlockEvents.wildGrapevineShearEvents());
            }
            BlockFinalization.addLoot(config, blockId, itemIds.contains(blockId));
            if (HANGING_GRAPE_CROPS.contains(blockId)) {
                BlockFinalization.addCropStages(blocks, blockId, config);
            } else {
                blocks.add(NAMESPACE + ":" + blockId, config);
            }
        }
        return new BlockMigrationStage.Result(blocks, renderItems, metrics);
    }

    private static int buildSharedSofaBlock(JsonObject renderItems, RenderItems renderer,
                                            Path projectRoot) throws IOException {
        int count = 0;
        for (String connection : SOFA_CONNECTIONS) {
            BlockStateVariants.Model model = selectSofaModel(projectRoot, connection);
            renderer.sofaRenderId(renderItems, connection, model);
            count += 4;
        }
        return count;
    }

    private static BlockStateVariants.Model selectSofaModel(Path projectRoot,
                                                           String connection) throws IOException {
        List<BlockStateVariants.Record> records = BlockStateVariants.read(projectRoot, "white_sofa");
        BlockStateVariants.Record selected = BlockStateVariants.select(records, Map.of(
                "connection", connection, "facing", "north", "waterlogged", "false"));
        return selected.model();
    }

    private static JsonObject sofaConfig(JsonObject renderItems, RenderItems renderer,
                                         Path projectRoot) throws IOException {
        JsonObject appearances = new JsonObject();
        JsonObject variants = new JsonObject();
        for (String connection : List.of(
                "single", "left", "left_corner", "middle", "right", "right_corner")) {
            String renderId = renderer.sofaRenderId(renderItems, connection,
                    selectSofaModel(projectRoot, connection));
            for (String facing : List.of("north", "east", "south", "west")) {
                String appearanceName = connection + "_" + facing;
                JsonObject rendererConfig = new JsonObject();
                rendererConfig.addProperty("type", "item_display");
                rendererConfig.addProperty("item", renderId);
                rendererConfig.addProperty("display_transform", "none");
                rendererConfig.addProperty("shadow_radius", 0);
                rendererConfig.addProperty("view_range", 1.25);
                rendererConfig.addProperty("tint_source", "minecraft:dyed_color");
                int rotation = ITEM_DISPLAY_FACING_YAW.get(facing);
                if (rotation != 0) rendererConfig.addProperty("rotation", "0," + rotation + ",0");
                JsonObject appearance = new JsonObject();
                appearance.addProperty("state", SOFA_CARRIER_STATE);
                appearance.add("entity_renderer", rendererConfig);
                appearances.add(appearanceName, appearance);
                for (String waterlogged : List.of("false", "true")) {
                    JsonObject mappedVariant = obj("appearance", appearanceName);
                    if (waterlogged.equals("true")) {
                        mappedVariant.add("settings",
                                obj("fluid_state", "water"));
                    }
                    variants.add("connection=" + connection + ",facing=" + facing
                            + ",waterlogged=" + waterlogged, mappedVariant);
                }
            }
        }
        JsonObject states = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.add("connection", BlockStateVariants.propertyDefinition(
                "connection", List.of("single", "left", "left_corner", "middle", "right", "right_corner")));
        properties.add("facing", BlockStateVariants.propertyDefinition(
                "facing", List.of("north", "east", "south", "west")));
        properties.add("waterlogged", BlockStateVariants.propertyDefinition(
                "waterlogged", List.of("false", "true")));
        states.add("properties", properties);
        states.add("appearances", appearances);
        states.add("variants", variants);
        JsonObject config = new JsonObject();
        config.add("states", states);
        config.add("settings", BlockBehaviors.sofaSettings(null));
        config.add("behaviors", BlockBehaviors.behaviorFor(
                SHARED_SOFA_BLOCK, Set.of("connection", "facing", "waterlogged"), null));
        return config;
    }

    private static JsonObject readBlockstate(Path projectRoot, String blockId) throws IOException {
        Path relative = Path.of("assets", NAMESPACE, "blockstates", blockId + ".json");
        for (Path root : List.of(projectRoot.resolve("src/generated/resources"),
                projectRoot.resolve("src/main/resources"))) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                JsonObject data = BlockMigrationStage.readObjectForHelpers(candidate);
                if (data.has("multipart")) throw new IllegalArgumentException(
                        "Multipart blockstate is not supported: " + candidate);
                JsonElement variantsElement = data.get("variants");
                if (variantsElement == null || !variantsElement.isJsonObject()
                        || variantsElement.getAsJsonObject().isEmpty()) {
                    throw new IllegalArgumentException("No variants in " + candidate);
                }
                return data;
            }
        }
        throw new java.io.FileNotFoundException("No blockstate for " + blockId);
    }

    private static LinkedHashMap<String, String> parseVariantKey(String key) {
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        if (key.isEmpty()) return properties;
        for (String pair : key.split(",", -1)) {
            int separator = pair.indexOf('=');
            if (separator < 0) throw new IllegalArgumentException("Malformed blockstate variant key: " + key);
            properties.put(pair.substring(0, separator), pair.substring(separator + 1));
        }
        return properties;
    }

    private static String sortedVariantKey(LinkedHashMap<String, String> properties) {
        List<String> sorted = new ArrayList<>(properties.keySet());
        sorted.sort(String::compareTo);
        StringBuilder rebuilt = new StringBuilder();
        for (String name : sorted) {
            if (rebuilt.length() > 0) rebuilt.append(',');
            rebuilt.append(name).append('=').append(properties.get(name));
        }
        return rebuilt.toString();
    }


    private static String sha1(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-1")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes).substring(0, 10);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private record WaterloggedModelKey(BlockStateVariants.Model model, String waterlogged) {}

    private static JsonObject obj(Object... values) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < values.length; i += 2) {
            object.addProperty((String) values[i], String.valueOf(values[i + 1]));
        }
        return object;
    }
}
