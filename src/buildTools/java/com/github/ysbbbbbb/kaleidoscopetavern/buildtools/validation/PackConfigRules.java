package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
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

/**
 * Native port of validate_pack.py's deep configuration contracts: worldgen
 * anchoring, material examples, table/bar-counter/sofa state products, wall
 * pressing-tub, paintings, render helpers, shaker model equality, placement
 * rules and cross-file hand-off tokens.
 */
public final class PackConfigRules {
    public static final String NAMESPACE = "kaleidoscope_tavern";
    public static final String WALL_PRESSING_TUB_ID = NAMESPACE + ":_internal/wall_pressing_tub";
    public static final String SHARED_SOFA_ID = NAMESPACE + ":_internal/sofa";

    private final Path projectRoot;
    private final Path packAssetsRoot;
    private final Path generatedAssetsRoot;
    private final Path mainAssetsRoot;

    public PackConfigRules(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.packAssetsRoot = projectRoot.resolve("src/paper/pack/resourcepack/assets");
        this.generatedAssetsRoot = projectRoot.resolve("src/generated/resources/assets");
        this.mainAssetsRoot = projectRoot.resolve("src/main/resources/assets");
    }

    public static final class ValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public ValidationException(String message) { super(message); }
    }

    private static final List<String> FURNITURE_COLORS = List.of(
            "black", "blue", "brown", "cyan", "gray", "green", "light_blue",
            "light_gray", "lime", "magenta", "orange", "pink", "purple", "red",
            "white", "yellow");
    private static final Map<String, String> SOFA_DYE_COLORS = Map.ofEntries(
            Map.entry("white", "249,255,254"), Map.entry("orange", "249,128,29"),
            Map.entry("magenta", "199,78,189"), Map.entry("light_blue", "58,179,218"),
            Map.entry("yellow", "254,216,61"), Map.entry("lime", "128,199,31"),
            Map.entry("pink", "243,139,170"), Map.entry("gray", "71,79,82"),
            Map.entry("light_gray", "157,157,151"), Map.entry("cyan", "22,156,156"),
            Map.entry("purple", "137,50,184"), Map.entry("blue", "60,68,170"),
            Map.entry("brown", "131,84,50"), Map.entry("green", "94,124,22"),
            Map.entry("red", "176,46,38"), Map.entry("black", "29,29,33"));
    private static final List<String> SOFA_CONNECTIONS = List.of(
            "single", "left", "left_corner", "middle", "right", "right_corner");
    private static final Map<String, String> FACING_ROTATIONS = new LinkedHashMap<>();
    static {
        FACING_ROTATIONS.put("north", "0,180,0");
        FACING_ROTATIONS.put("east", "0,90,0");
        FACING_ROTATIONS.put("south", null);
        FACING_ROTATIONS.put("west", "0,270,0");
    }

    public void validate(JsonObject items, JsonObject renderItems, JsonObject blocks,
                         JsonObject furniture) throws IOException {
        validateWorldgen();
        validateFiles();
        validateIncense(items, blocks);
        validateConfigItems(items, renderItems, blocks, furniture);
        validateRemaining(items, renderItems, blocks, furniture);
        validateDrinks(items, renderItems, blocks, furniture);
        validateStringLightsAndEvents(items, renderItems, blocks, furniture);
        validateChalkboard(items, renderItems, blocks);
        validateHudAndCustomCrops(items, blocks, furniture);
        JsonObject worldgen = readConfig("worldgen.json").getAsJsonObject("placed_features")
                .getAsJsonObject(NAMESPACE + ":wild_grapevine");
        validateWorldgenFeature(worldgen);
        if (Files.exists(projectRoot.resolve(
                "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/game/WorldgenService.java"))) {
            throw new ValidationException("WorldgenService must stay deleted; CE features own worldgen");
        }
        validateMaterialExamples(furniture);
        validateTable(items, renderItems, blocks, furniture);
        validateBoardAndPendant(furniture);
        validatePressingTub(furniture);
        validatePaintings(items, furniture);
        validateSofa(items, renderItems, blocks, furniture);
        validateBarCounter(items, renderItems, blocks, furniture);
        validateStorage(items, renderItems, blocks, furniture);
        validateFurnitureBehaviors(furniture);
        JsonObject bottle = furniture.getAsJsonObject(NAMESPACE + ":empty_bottle")
                .getAsJsonObject("variants").getAsJsonObject("ground");
        JsonObject bottleHitbox = bottle.getAsJsonArray("hitboxes").get(0).getAsJsonObject();
        if (bottleHitbox.get("width").getAsDouble() != 0.375
                || bottleHitbox.get("height").getAsDouble() != 0.875) {
            throw new ValidationException("Bottle hitboxes must retain the 6x14x6 source VoxelShape");
        }
        validateShakerModel();
        int storageHelpers = 0;
        int fluidHelpers = 0;
        int barrelFluidHelpers = 0;
        for (String renderId : renderItems.keySet()) {
            if (renderId.startsWith(NAMESPACE + ":_render/storage/")) storageHelpers++;
            else if (renderId.startsWith(NAMESPACE + ":_render/pressing_fluid/")) fluidHelpers++;
            else if (renderId.startsWith(NAMESPACE + ":_render/barrel_fluid/")) barrelFluidHelpers++;
        }
        if (storageHelpers != 33 || fluidHelpers != 6 || barrelFluidHelpers != 8) {
            throw new ValidationException("Storage/pressing runtime visual helper set is incomplete");
        }
        JsonObject potionHelper = renderItems.getAsJsonObject(NAMESPACE + ":_render/storage/potion_bottle");
        JsonArray potionTints = new JsonArray();
        JsonObject potionTint = new JsonObject();
        potionTint.addProperty("type", "minecraft:potion");
        potionTint.addProperty("default", -13083194);
        potionTints.add(potionTint);
        if (!potionTints.equals(potionHelper.getAsJsonObject("model").getAsJsonArray("tints"))) {
            throw new ValidationException("Stored potion block models must preserve potion_contents tint");
        }
        JsonObject waterHelper = renderItems.getAsJsonObject(NAMESPACE + ":_render/barrel_fluid/water");
        JsonArray waterTints = new JsonArray();
        JsonObject waterTint = new JsonObject();
        waterTint.addProperty("type", "minecraft:constant");
        waterTint.addProperty("value", 0x3F76E4);
        waterTints.add(waterTint);
        if (!waterTints.equals(waterHelper.getAsJsonObject("model").getAsJsonArray("tints"))) {
            throw new ValidationException("Open-barrel water surface must retain its source fluid tint");
        }
        Map<String, Map<String, String>> expectedRules = new LinkedHashMap<>();
        expectedRules.put(NAMESPACE + ":empty_bottle",
                Map.of("rotation", "four", "alignment", "center"));
        expectedRules.put(NAMESPACE + ":base_sandwich_board",
                Map.of("rotation", "sixteen", "alignment", "center"));
        for (var ruleEntry : expectedRules.entrySet()) {
            JsonObject actual = items.getAsJsonObject(ruleEntry.getKey()).getAsJsonObject("behavior")
                    .getAsJsonObject("rules").getAsJsonObject("ground");
            if (!actual.get("rotation").getAsString().equals(ruleEntry.getValue().get("rotation"))
                    || !actual.get("alignment").getAsString().equals(ruleEntry.getValue().get("alignment"))) {
                throw new ValidationException(ruleEntry.getKey()
                        + ": placement rule drifted from Forge BlockItem semantics");
            }
        }
    }

    private void validateWorldgen() throws IOException {
        JsonObject worldgen = readConfig("worldgen.json");
        JsonObject chain = worldgen.getAsJsonObject("configured_features")
                .getAsJsonObject(NAMESPACE + ":wild_grapevine_chain");
        JsonObject chainConfig = chain.getAsJsonObject("config");
        JsonArray layers = chainConfig.getAsJsonArray("layers");
        if (!chain.get("type").getAsString().equals("minecraft:block_column")
                || !chainConfig.get("direction").getAsString().equals("down")
                || layers.size() != 2
                || !layers.get(0).getAsJsonObject().get("height").equals(uniformHeight(0, 6))
                || !layers.get(0).getAsJsonObject().getAsJsonObject("provider")
                        .getAsJsonObject("state").get("Name").getAsString()
                        .equals(NAMESPACE + ":wild_grapevine_plant")
                || layers.get(1).getAsJsonObject().get("height").getAsInt() != 1
                || !layers.get(1).getAsJsonObject().getAsJsonObject("provider")
                        .getAsJsonObject("state").get("Name").getAsString()
                        .equals(NAMESPACE + ":wild_grapevine")) {
            throw new ValidationException("Wild grapevine feature must hang body segments above a head");
        }
    }

    private static JsonObject uniformHeight(int minInclusive, int maxInclusive) {
        JsonObject height = new JsonObject();
        height.addProperty("type", "minecraft:uniform");
        height.addProperty("min_inclusive", minInclusive);
        height.addProperty("max_inclusive", maxInclusive);
        return height;
    }

    private static void validateWorldgenFeature(JsonObject placedFeature) {
        JsonArray placement = placedFeature.getAsJsonArray("placement");
        Map<String, JsonObject> placements = new LinkedHashMap<>();
        for (JsonElement raw : placement) {
            JsonObject entry = raw.getAsJsonObject();
            placements.put(entry.get("type").getAsString(), entry);
        }
        for (String required : List.of("minecraft:rarity_filter", "minecraft:count",
                "minecraft:in_square", "minecraft:heightmap", "minecraft:environment_scan",
                "minecraft:block_predicate_filter")) {
            if (!placements.containsKey(required)) {
                throw new ValidationException("Wild grapevine placed feature is missing " + required);
            }
        }
        JsonObject environmentScan = placements.get("minecraft:environment_scan");
        JsonObject targetCondition = environmentScan.has("target_condition")
                ? environmentScan.getAsJsonObject("target_condition") : new JsonObject();
        JsonArray targetPredicates = targetCondition.has("predicates")
                ? targetCondition.getAsJsonArray("predicates") : new JsonArray();
        JsonObject expectedAir = new JsonObject();
        expectedAir.addProperty("type", "minecraft:matching_blocks");
        expectedAir.addProperty("blocks", "minecraft:air");
        JsonObject expectedLeaves = new JsonObject();
        expectedLeaves.addProperty("type", "minecraft:matching_blocks");
        JsonArray offset = new JsonArray();
        offset.add(0); offset.add(1); offset.add(0);
        expectedLeaves.add("offset", offset);
        JsonArray leaves = new JsonArray();
        leaves.add("minecraft:oak_leaves");
        leaves.add("minecraft:birch_leaves");
        expectedLeaves.add("blocks", leaves);
        if (!environmentScan.get("direction_of_search").getAsString().equals("down")
                || !targetCondition.get("type").getAsString().equals("minecraft:all_of")
                || !targetPredicates.contains(expectedAir)
                || !targetPredicates.contains(expectedLeaves)) {
            throw new ValidationException(
                    "Wild grapevine worldgen must anchor the head/body chain directly below oak or birch leaves");
        }
    }

    private void validateFiles() throws IOException {
        String hudSource = readText(projectRoot.resolve("src/paper/java/com/github/ysbbbbbb"
                + "/kaleidoscopetavern/paper/integration/EffectHudPlaceholder.java"));
        for (String token : List.of("return \"kaleidoscopetavern\";", "\"effect_hud\"", "\"effect_count\"")) {
            if (!hudSource.contains(token)) {
                throw new ValidationException(
                        "EffectHudPlaceholder must keep the documented placeholder API: " + token);
            }
        }
        String nameplatesSnippet = readText(projectRoot.resolve(
                "src/paper/customnameplates/bossbar-tavern-effects.yml"));
        for (String token : List.of("%kaleidoscopetavern_effect_hud%",
                "%kaleidoscopetavern_effect_count%", "'!equals':")) {
            if (!nameplatesSnippet.contains(token)) {
                throw new ValidationException(
                        "CustomNameplates reference bossbar config is missing " + token);
            }
        }
        String paperPluginYml = readText(projectRoot.resolve("src/paper/resources/plugin.yml"));
        if (!paperPluginYml.contains("softdepend: [PlaceholderAPI, CustomNameplates]")) {
            throw new ValidationException(
                    "plugin.yml must soft-depend on PlaceholderAPI and CustomNameplates for load order");
        }
        String pluginConfig = readText(projectRoot.resolve("src/paper/resources/config.yml"));
        if (!pluginConfig.contains("mode: auto") || !pluginConfig.contains("effect-hud:")) {
            throw new ValidationException(
                    "config.yml must document the effect-hud mode switch and default to auto");
        }
        if (!pluginConfig.contains("style: corner") || !pluginConfig.contains("gui-half-width: 240")) {
            throw new ValidationException(
                    "config.yml must default the effect HUD to the vanilla-position corner style");
        }
        String pluginSource = readText(projectRoot.resolve("src/paper/java/com/github/ysbbbbbb"
                + "/kaleidoscopetavern/paper/KaleidoscopeTavernPlugin.java"));
        for (String expectedToken : List.of("EXPECTED_ITEMS = 571",
                "EXPECTED_BLOCKS = 44", "EXPECTED_FURNITURE = 116")) {
            if (!pluginSource.contains(expectedToken)) {
                throw new ValidationException(
                        "Runtime CE content count guard is stale: " + expectedToken);
            }
        }
    }

    private void validateMaterialExamples(JsonObject furniture) {
        Map<String, int[]> materialExamples = new LinkedHashMap<>();
        materialExamples.put("bell_pendant_lamp", new int[] {0, 3});
        materialExamples.put("glassware_holder", new int[] {1, 3});
        materialExamples.put("shaker", new int[] {2, 1});
        materialExamples.put("empty_bottle", new int[] {3, 1});
        materialExamples.put("white_lady", new int[] {3, 1});
        materialExamples.put("wine", new int[] {3, 1});
        materialExamples.put("molotov", new int[] {3, 1});
        materialExamples.put("barrel", new int[] {4, 3});
        String[] families = {"chain", "metal", "lantern", "glass", "wood"};
        for (var entry : materialExamples.entrySet()) {
            String furnitureId = entry.getKey();
            int familyIndex = entry.getValue()[0];
            int hitTimes = entry.getValue()[1];
            JsonObject settings = furniture.getAsJsonObject(NAMESPACE + ":" + furnitureId)
                    .getAsJsonObject("settings");
            Map<String, JsonElement> expectedSounds = new LinkedHashMap<>();
            for (String action : List.of("break", "place", "hit")) {
                expectedSounds.put(action, new JsonPrimitive(
                        "minecraft:block." + families[familyIndex] + "." + action));
            }
            if (familyIndex == 3) {
                JsonObject place = new JsonObject();
                place.addProperty("id", "minecraft:block.glass.place");
                place.addProperty("volume", 1.0);
                place.addProperty("pitch", 0.8);
                expectedSounds.put("place", place);
            }
            JsonObject sounds = settings.getAsJsonObject("sounds");
            if (settings.get("hit_times").getAsInt() != hitTimes
                    || !sounds.equals(jsonObjectOf(expectedSounds))) {
                throw new ValidationException(furnitureId + ": source material/break behavior drifted");
            }
        }
    }

    private static JsonObject cornerTopology() {
        JsonObject outputs = new JsonObject();
        outputs.addProperty("none", "single");
        outputs.addProperty("left", "right");
        outputs.addProperty("right", "left");
        outputs.addProperty("both", "middle");
        outputs.addProperty("front_left", "right_corner");
        outputs.addProperty("front_left_with_right", "left");
        outputs.addProperty("front_right", "left_corner");
        outputs.addProperty("front_right_with_left", "right");
        JsonObject compatibility = new JsonObject();
        JsonArray leftPerp = new JsonArray();
        leftPerp.add("single"); leftPerp.add("right"); leftPerp.add("right_corner");
        compatibility.add("left_perpendicular", leftPerp);
        JsonArray rightPerp = new JsonArray();
        rightPerp.add("single"); rightPerp.add("left"); rightPerp.add("left_corner");
        compatibility.add("right_perpendicular", rightPerp);
        compatibility.addProperty("front_left_excluded", "left_corner");
        compatibility.addProperty("front_right_excluded", "right_corner");
        JsonObject topology = new JsonObject();
        topology.add("outputs", outputs);
        topology.add("compatibility", compatibility);
        return topology;
    }

    private void validateTable(JsonObject items, JsonObject renderItems, JsonObject blocks,
                               JsonObject furniture) {
        String tableId = NAMESPACE + ":table";
        JsonObject tableBlock = blocks.getAsJsonObject(tableId);
        JsonObject expectedTableBehavior = new JsonObject();
        expectedTableBehavior.addProperty("type", NAMESPACE + ":connected_block");
        expectedTableBehavior.addProperty("mode", "table");
        JsonArray connects = new JsonArray();
        connects.add(tableId);
        expectedTableBehavior.add("connects", connects);
        expectedTableBehavior.addProperty("axis_property", "table_axis");
        expectedTableBehavior.addProperty("state_property", "position");
        JsonObject topology = new JsonObject();
        topology.addProperty("default_axis", "z");
        topology.addProperty("perpendicular_to_player", true);
        topology.addProperty("allow_cross_axis_singles", true);
        JsonObject outputs = new JsonObject();
        outputs.addProperty("none", 0);
        outputs.addProperty("positive", 1);
        outputs.addProperty("negative", 3);
        outputs.addProperty("both", 2);
        topology.add("outputs", outputs);
        expectedTableBehavior.add("topology", topology);
        if (!expectedTableBehavior.equals(tableBlock.get("behavior"))) {
            throw new ValidationException(
                    "Table must keep every family-specific topology value in CE config");
        }
        JsonObject tableItemBehavior = new JsonObject();
        tableItemBehavior.addProperty("type", "block_item");
        tableItemBehavior.addProperty("block", tableId);
        if (!tableItemBehavior.equals(items.getAsJsonObject(tableId).get("behavior"))) {
            throw new ValidationException("Table item placement must be native CE block_item");
        }
        JsonObject tableStates = tableBlock.getAsJsonObject("states");
        JsonObject expectedProperties = new JsonObject();
        JsonObject tableAxis = new JsonObject();
        tableAxis.addProperty("type", "axis");
        tableAxis.addProperty("default", "x");
        JsonArray axisValues = new JsonArray();
        axisValues.add("x");
        axisValues.add("z");
        tableAxis.add("values", axisValues);
        expectedProperties.add("table_axis", tableAxis);
        JsonObject position = new JsonObject();
        position.addProperty("type", "int");
        position.addProperty("default", 0);
        position.addProperty("range", "0~3");
        expectedProperties.add("position", position);
        if (!expectedProperties.equals(tableStates.getAsJsonObject("properties"))) {
            throw new ValidationException("Furniture-style table state properties drifted");
        }
        Map<String, String> expectedTableModels = new LinkedHashMap<>();
        String[][] tableModelSpecs = {
                {"x", "0", "single"}, {"x", "1", "left"}, {"x", "2", "middle"}, {"x", "3", "right"},
                {"z", "0", "single"}, {"z", "1", "left_rot"}, {"z", "2", "middle_rot"}, {"z", "3", "right_rot"}};
        for (String[] spec : tableModelSpecs) {
            expectedTableModels.put(spec[0] + "|" + spec[1],
                    NAMESPACE + ":block/deco/table/" + spec[2]);
        }
        JsonObject tableVariants = tableStates.getAsJsonObject("variants");
        Set<String> expectedTableKeys = new LinkedHashSet<>();
        for (String axis : List.of("x", "z")) {
            for (int positionValue = 0; positionValue < 4; positionValue++) {
                expectedTableKeys.add("position=" + positionValue + ",table_axis=" + axis);
            }
        }
        if (!tableVariants.keySet().equals(expectedTableKeys)) {
            throw new ValidationException("Table must expose exactly eight axis/endpoint states");
        }
        Map<String, Set<String>> tableRenderIds = new LinkedHashMap<>();
        JsonObject tableAppearances = tableStates.getAsJsonObject("appearances");
        for (var variantEntry : tableVariants.entrySet()) {
            String[] props = new String[2];
            for (String part : variantEntry.getKey().split(",")) {
                String[] pair = part.split("=", 2);
                if (pair[0].equals("table_axis")) props[0] = pair[1];
                else if (pair[0].equals("position")) props[1] = pair[1];
            }
            String axis = props[0];
            String positionValue = props[1];
            JsonObject appearance = tableAppearances.getAsJsonObject(
                    variantEntry.getValue().getAsJsonObject().get("appearance").getAsString());
            if (!appearance.get("state").getAsString().equals("minecraft:barrier")
                    || appearance.has("auto_state")
                    || hasNonNull(appearance, "transparent")) {
                throw new ValidationException("table/" + variantEntry.getKey()
                        + ": must use CE sofa-style barrier rendering");
            }
            JsonObject renderer = appearance.getAsJsonObject("entity_renderer");
            String renderId = renderer.get("item").getAsString();
            tableRenderIds.computeIfAbsent(axis + "|" + positionValue, k -> new LinkedHashSet<>())
                    .add(renderId);
            String expectedModel = expectedTableModels.get(axis + "|" + positionValue);
            if (!renderItems.getAsJsonObject(renderId).getAsJsonObject("model")
                    .get("path").getAsString().equals(expectedModel)) {
                throw new ValidationException("table/" + variantEntry.getKey() + ": source model drifted");
            }
            if (!variantEntry.getValue().equals(obj("appearance",
                    variantEntry.getValue().getAsJsonObject().get("appearance").getAsString()))) {
                throw new ValidationException("table/" + variantEntry.getKey() + ": unexpected state settings");
            }
        }
        for (Set<String> ids : tableRenderIds.values()) {
            if (ids.size() != 1) {
                throw new ValidationException("Table states must share their seven render helpers");
            }
        }
        Set<String> distinctTableRenders = new LinkedHashSet<>();
        for (Set<String> ids : tableRenderIds.values()) distinctTableRenders.addAll(ids);
        if (distinctTableRenders.size() != 7) {
            throw new ValidationException("Table must retain exactly seven authored source models");
        }
        if (furniture.has(tableId)) {
            throw new ValidationException("Block-backed table must not retain a furniture definition");
        }
    }

    private void validateBoardAndPendant(JsonObject furniture) throws IOException {
        JsonArray boardElements = furniture.getAsJsonObject(NAMESPACE + ":base_sandwich_board")
                .getAsJsonObject("variants").getAsJsonObject("ground").getAsJsonArray("elements");
        List<String> boardTranslations = new ArrayList<>();
        for (JsonElement raw : boardElements) {
            boardTranslations.add(raw.getAsJsonObject().get("translation").getAsString());
        }
        if (!boardTranslations.equals(List.of("0,0.5,0", "0,1.5,0"))) {
            throw new ValidationException("Two-block sandwich-board model halves are vertically misaligned");
        }
        JsonArray pendantElements = furniture.getAsJsonObject(NAMESPACE + ":bell_pendant_lamp")
                .getAsJsonObject("variants").getAsJsonObject("ceiling").getAsJsonArray("elements");
        List<String> pendantTranslations = new ArrayList<>();
        for (JsonElement raw : pendantElements) {
            pendantTranslations.add(raw.getAsJsonObject().get("translation").getAsString());
        }
        if (!pendantTranslations.equals(List.of("0,-0.49,0", "0,-1.49,0"))) {
            throw new ValidationException("Ceiling pendant model halves are vertically misaligned");
        }
        for (String pendantId : List.of("bell_pendant_lamp", "blue_pendant_lamp", "yellow_pendant_lamp")) {
            for (String half : List.of("top", "bottom")) {
                JsonObject model = assetJson(NAMESPACE + ":block/deco/" + pendantId + "/" + half, "models");
                String particle = model == null || !model.has("textures")
                        ? null : model.getAsJsonObject("textures").get("particle").getAsString();
                if (!"minecraft:block/iron_chain".equals(particle)) {
                    throw new ValidationException(pendantId + "/" + half
                            + ": Paper 26.2 requires the iron_chain particle texture");
                }
                if (model != null && model.has("elements")) {
                    int elementIndex = 0;
                    for (JsonElement rawElement : model.getAsJsonArray("elements")) {
                        JsonObject element = rawElement.getAsJsonObject();
                        if (!element.has("faces")) { elementIndex++; continue; }
                        for (var faceEntry : element.getAsJsonObject("faces").entrySet()) {
                            JsonObject face = faceEntry.getValue().getAsJsonObject();
                            if (face.has("uv")) {
                                JsonArray uv = face.getAsJsonArray("uv");
                                if (uv.size() == 4 && (uv.get(0).getAsDouble() == uv.get(2).getAsDouble()
                                        || uv.get(1).getAsDouble() == uv.get(3).getAsDouble())) {
                                    throw new ValidationException(pendantId + "/" + half
                                            + ": element " + elementIndex + " " + faceEntry.getKey()
                                            + " has a degenerate UV, which causes translucent "
                                            + "ItemDisplay texture noise");
                                }
                            }
                        }
                        elementIndex++;
                    }
                }
            }
        }
    }

    private void validatePressingTub(JsonObject furniture) {
        String pressingTubId = NAMESPACE + ":pressing_tub";
        if (furniture.has(pressingTubId)) {
            throw new ValidationException("Ground pressing tub must exist only as a CE block");
        }
        if (!furniture.has(WALL_PRESSING_TUB_ID)) {
            throw new ValidationException("The non-pressable wall tub must be a private native CE furniture");
        }
        JsonObject wallTub = furniture.getAsJsonObject(WALL_PRESSING_TUB_ID);
        if (!wallTub.getAsJsonObject("settings").get("item").getAsString().equals(pressingTubId)) {
            throw new ValidationException("Wall pressing-tub furniture must map back to the public tub item");
        }
        if (!wallTub.getAsJsonObject("variants").keySet().equals(Set.of("wall"))) {
            throw new ValidationException("The active wall tub must expose no ground fallback variant");
        }
        JsonObject expectedWallLoot = new JsonObject();
        JsonArray pools = new JsonArray();
        JsonObject pool = new JsonObject();
        pool.addProperty("rolls", 1);
        JsonArray entries = new JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "furniture_item");
        entry.addProperty("item", pressingTubId);
        entries.add(entry);
        pool.add("entries", entries);
        pools.add(pool);
        expectedWallLoot.add("pools", pools);
        if (!expectedWallLoot.equals(wallTub.get("loot"))) {
            throw new ValidationException("Wall pressing-tub item drops must be CE-configured");
        }
        JsonArray expectedWallBehaviors = new JsonArray();
        JsonObject stateBehavior = new JsonObject();
        stateBehavior.addProperty("type", NAMESPACE + ":state_furniture");
        expectedWallBehaviors.add(stateBehavior);
        JsonObject visualBehavior = new JsonObject();
        visualBehavior.addProperty("type", NAMESPACE + ":station_visual_furniture");
        visualBehavior.addProperty("max_elements", 17);
        visualBehavior.addProperty("view_range", 1.25);
        expectedWallBehaviors.add(visualBehavior);
        JsonObject interactionBehavior = new JsonObject();
        interactionBehavior.addProperty("type", NAMESPACE + ":station_interaction_furniture");
        expectedWallBehaviors.add(interactionBehavior);
        if (!expectedWallBehaviors.equals(wallTub.get("behaviors"))) {
            throw new ValidationException("Wall pressing-tub runtime-sized state must use the shared generic "
                    + "furniture adapters, not a dedicated placement/lifecycle behavior");
        }
    }

    private void validatePaintings(JsonObject items, JsonObject furniture) {
        List<String> paintings = new ArrayList<>();
        for (String itemId : items.keySet()) {
            if (itemId.endsWith("_painting")) paintings.add(itemId);
        }
        if (paintings.size() != 14) {
            throw new ValidationException("Expected 14 paintings, found " + paintings.size());
        }
        for (String paintingId : paintings) {
            JsonObject behavior = items.getAsJsonObject(paintingId).getAsJsonObject("behavior");
            JsonObject wall = furniture.getAsJsonObject(paintingId).getAsJsonObject("variants")
                    .getAsJsonObject("wall");
            if (!behavior.getAsJsonObject("rules").keySet().equals(Set.of("ground", "wall", "ceiling"))
                    || !behavior.has("ignore_placer")
                    || !behavior.get("ignore_placer").getAsBoolean()) {
                throw new ValidationException(paintingId + ": wall/ceiling placement rules are incomplete");
            }
            for (JsonElement rawHitbox : wall.getAsJsonArray("hitboxes")) {
                JsonObject hitbox = rawHitbox.getAsJsonObject();
                if (!hitbox.has("blocks_building")
                        || hitbox.get("blocks_building").getAsBoolean()) {
                    throw new ValidationException(paintingId + ": square wall hitbox must not block placement");
                }
            }
            JsonObject wallElement = wall.getAsJsonArray("elements").get(0).getAsJsonObject();
            if (!wallElement.get("position").getAsString().equals("0,0,0.19")
                    || !wallElement.get("translation").getAsString().equals("0,0,-0.627")) {
                throw new ValidationException(paintingId + ": wall display depth drifted");
            }
        }
    }

    private void validateSofa(JsonObject items, JsonObject renderItems, JsonObject blocks,
                              JsonObject furniture) throws IOException {
        JsonObject sharedBlock = blocks.getAsJsonObject(SHARED_SOFA_ID);
        JsonArray expectedSharedBehaviors = new JsonArray();
        JsonObject connected = new JsonObject();
        connected.addProperty("type", NAMESPACE + ":connected_block");
        connected.addProperty("mode", "corner");
        JsonArray connects = new JsonArray();
        connects.add(SHARED_SOFA_ID);
        connected.add("connects", connects);
        connected.addProperty("state_property", "connection");
        connected.add("topology", cornerTopology());
        expectedSharedBehaviors.add(connected);
        JsonObject seat = new JsonObject();
        seat.addProperty("type", "seat_block");
        JsonArray seats = new JsonArray();
        seats.add("0,-0.1,0 180");
        seat.add("seats", seats);
        expectedSharedBehaviors.add(seat);
        JsonObject tintSource = new JsonObject();
        tintSource.addProperty("type", "tint_source_block");
        tintSource.addProperty("drop_item", true);
        expectedSharedBehaviors.add(tintSource);
        if (!expectedSharedBehaviors.equals(sharedBlock.get("behaviors"))) {
            throw new ValidationException("Shared sofa must delegate colour/drop/seat ownership to CE");
        }
        JsonObject sharedStates = sharedBlock.getAsJsonObject("states");
        JsonObject expectedSofaProperties = new JsonObject();
        JsonObject connection = new JsonObject();
        connection.addProperty("type", "string");
        connection.addProperty("default", "single");
        JsonArray connectionValues = new JsonArray();
        SOFA_CONNECTIONS.forEach(connectionValues::add);
        connection.add("values", connectionValues);
        expectedSofaProperties.add("connection", connection);
        JsonObject facing = new JsonObject();
        facing.addProperty("type", "horizontal_direction");
        facing.addProperty("default", "north");
        JsonArray facingValues = new JsonArray();
        facingValues.add("north");
        facingValues.add("east");
        facingValues.add("south");
        facingValues.add("west");
        facing.add("values", facingValues);
        expectedSofaProperties.add("facing", facing);
        if (!expectedSofaProperties.equals(sharedStates.getAsJsonObject("properties"))) {
            throw new ValidationException("Shared sofa state product drifted");
        }
        Set<String> expectedSharedKeys = new LinkedHashSet<>();
        for (String connectionName : SOFA_CONNECTIONS) {
            for (String facingName : List.of("north", "east", "south", "west")) {
                expectedSharedKeys.add("connection=" + connectionName + ",facing=" + facingName);
            }
        }
        JsonObject sharedVariants = sharedStates.getAsJsonObject("variants");
        if (!sharedVariants.keySet().equals(expectedSharedKeys)) {
            throw new ValidationException("Shared sofa must expose exactly 24 active states");
        }
        Map<String, Set<String>> tintRenderIds = new LinkedHashMap<>();
        JsonObject sharedAppearances = sharedStates.getAsJsonObject("appearances");
        for (var variantEntry : sharedVariants.entrySet()) {
            String connectionName = null;
            String facingName = null;
            for (String part : variantEntry.getKey().split(",")) {
                String[] pair = part.split("=", 2);
                if (pair[0].equals("connection")) connectionName = pair[1];
                else if (pair[0].equals("facing")) facingName = pair[1];
            }
            JsonObject appearance = sharedAppearances.getAsJsonObject(
                    variantEntry.getValue().getAsJsonObject().get("appearance").getAsString());
            if (!appearance.get("state").getAsString().equals("minecraft:barrier")) {
                throw new ValidationException("Shared sofa/" + variantEntry.getKey() + ": carrier drifted");
            }
            JsonObject renderer = appearance.getAsJsonObject("entity_renderer");
            String expectedRotation = FACING_ROTATIONS.get(facingName);
            JsonElement actualRotation = renderer.get("rotation");
            if ((expectedRotation == null) ? actualRotation != null && !actualRotation.isJsonNull()
                    : actualRotation == null || actualRotation.isJsonNull()
                        || !actualRotation.getAsString().equals(expectedRotation)) {
                throw new ValidationException("Shared sofa/" + variantEntry.getKey() + ": rotation drifted");
            }
            if (!renderer.get("tint_source").getAsString().equals("minecraft:dyed_color")) {
                throw new ValidationException("Shared sofa/" + variantEntry.getKey() + ": tint source missing");
            }
            String renderId = renderer.get("item").getAsString();
            tintRenderIds.computeIfAbsent(connectionName, k -> new LinkedHashSet<>()).add(renderId);
            JsonObject renderModel = renderItems.getAsJsonObject(renderId).getAsJsonObject("model");
            JsonArray expectedTints = new JsonArray();
            JsonObject dyeTint = new JsonObject();
            dyeTint.addProperty("type", "minecraft:dye");
            dyeTint.addProperty("default", 16_777_215);
            expectedTints.add(dyeTint);
            if (!renderModel.get("path").getAsString().equals(
                    NAMESPACE + ":block/deco/sofa/tint/" + connectionName)
                    || !expectedTints.equals(renderModel.getAsJsonArray("tints"))) {
                throw new ValidationException("Shared sofa/" + variantEntry.getKey()
                        + ": tintable render model drifted");
            }
        }
        if (!tintRenderIds.keySet().equals(new LinkedHashSet<>(SOFA_CONNECTIONS))) {
            throw new ValidationException("Shared sofa must use six tintable render items");
        }
        for (Set<String> ids : tintRenderIds.values()) {
            if (ids.size() != 1) {
                throw new ValidationException("Shared sofa must use six tintable render items");
            }
        }
        Path tintModelRoot = packAssetsRoot.resolve(NAMESPACE + "/models/block/deco/sofa/tint");
        for (String connectionName : SOFA_CONNECTIONS) {
            JsonObject wrapper = readJson(tintModelRoot.resolve(connectionName + ".json"));
            if (!wrapper.get("parent").getAsString().equals(
                    NAMESPACE + ":block/deco/sofa/tint/base/" + connectionName)) {
                throw new ValidationException("Tint sofa " + connectionName + ": wrapper drifted");
            }
            JsonObject base = readJson(tintModelRoot.resolve("base/" + connectionName + ".json"));
            int tintedFaces = 0;
            if (base.has("elements")) {
                for (JsonElement rawElement : base.getAsJsonArray("elements")) {
                    JsonObject element = rawElement.getAsJsonObject();
                    if (!element.has("faces")) continue;
                    for (JsonElement rawFace : element.getAsJsonObject("faces").asMap().values()) {
                        JsonObject face = rawFace.getAsJsonObject();
                        if (face.has("texture") && face.get("texture").getAsString().equals("#texture")) {
                            tintedFaces++;
                            if (!face.has("tintindex") || face.get("tintindex").getAsInt() != 0) {
                                throw new ValidationException("Tint sofa " + connectionName
                                        + ": every upholstery face needs tintindex 0");
                            }
                        }
                    }
                }
            }
            if (tintedFaces == 0) {
                throw new ValidationException("Tint sofa " + connectionName
                        + ": every upholstery face needs tintindex 0");
            }
        }
        for (String color : FURNITURE_COLORS) {
            String sofaName = color + "_sofa";
            String sofaId = NAMESPACE + ":" + sofaName;
            JsonObject item = items.getAsJsonObject(sofaId);
            JsonObject expectedBehavior = new JsonObject();
            expectedBehavior.addProperty("type", "block_item");
            expectedBehavior.addProperty("block", SHARED_SOFA_ID);
            if (!expectedBehavior.equals(item.get("behavior"))) {
                throw new ValidationException(sofaName + ": public item must place the shared sofa");
            }
            if (!item.getAsJsonObject("data").get("dyed_color").getAsString()
                    .equals(SOFA_DYE_COLORS.get(color))) {
                throw new ValidationException(sofaName + ": fixed dye colour drifted");
            }
            if (blocks.has(sofaId) || furniture.has(sofaId)) {
                throw new ValidationException(sofaName + ": obsolete block/furniture definition was restored");
            }
        }
        if (sharedVariants.size() != 24) {
            throw new ValidationException("Sofa family must use exactly 24 shared states, found "
                    + sharedVariants.size());
        }
    }

    private void validateBarCounter(JsonObject items, JsonObject renderItems, JsonObject blocks,
                                    JsonObject furniture) {
        String counterId = NAMESPACE + ":bar_counter";
        JsonObject counter = blocks.getAsJsonObject(counterId);
        JsonObject expectedCounterBehavior = new JsonObject();
        expectedCounterBehavior.addProperty("type", NAMESPACE + ":connected_block");
        expectedCounterBehavior.addProperty("mode", "corner");
        JsonArray connects = new JsonArray();
        connects.add(counterId);
        expectedCounterBehavior.add("connects", connects);
        expectedCounterBehavior.addProperty("state_property", "connection");
        expectedCounterBehavior.add("topology", cornerTopology());
        if (!expectedCounterBehavior.equals(counter.get("behavior"))) {
            throw new ValidationException("Bar counter topology/output ownership must stay in CE config");
        }
        JsonObject counterItemBehavior = new JsonObject();
        counterItemBehavior.addProperty("type", "block_item");
        counterItemBehavior.addProperty("block", counterId);
        if (!counterItemBehavior.equals(items.getAsJsonObject(counterId).get("behavior"))) {
            throw new ValidationException("Bar counter placement must use native CE block_item");
        }
        JsonObject counterStates = counter.getAsJsonObject("states");
        Set<String> expectedCounterKeys = new LinkedHashSet<>();
        for (String connectionName : SOFA_CONNECTIONS) {
            for (String facingName : List.of("east", "north", "south", "west")) {
                expectedCounterKeys.add("connection=" + connectionName + ",facing=" + facingName);
            }
        }
        JsonObject counterVariants = counterStates.getAsJsonObject("variants");
        if (!counterVariants.keySet().equals(expectedCounterKeys)) {
            throw new ValidationException("Bar counter must retain all 24 source states");
        }
        Map<String, Set<String>> counterRenderIds = new LinkedHashMap<>();
        JsonObject counterAppearances = counterStates.getAsJsonObject("appearances");
        for (var variantEntry : counterVariants.entrySet()) {
            String connectionName = null;
            String facingName = null;
            for (String part : variantEntry.getKey().split(",")) {
                String[] pair = part.split("=", 2);
                if (pair[0].equals("connection")) connectionName = pair[1];
                else if (pair[0].equals("facing")) facingName = pair[1];
            }
            JsonObject appearance = counterAppearances.getAsJsonObject(
                    variantEntry.getValue().getAsJsonObject().get("appearance").getAsString());
            if (!appearance.get("state").getAsString().equals("minecraft:barrier")
                    || appearance.has("auto_state")
                    || hasNonNull(appearance, "transparent")) {
                throw new ValidationException("bar_counter/" + variantEntry.getKey()
                        + ": must use CE sofa-style barrier rendering");
            }
            JsonObject renderer = appearance.getAsJsonObject("entity_renderer");
            String expectedRotation = FACING_ROTATIONS.get(facingName);
            JsonElement actualRotation = renderer.get("rotation");
            if ((expectedRotation == null) ? actualRotation != null && !actualRotation.isJsonNull()
                    : actualRotation == null || actualRotation.isJsonNull()
                        || !actualRotation.getAsString().equals(expectedRotation)) {
                throw new ValidationException("bar_counter/" + variantEntry.getKey() + ": rotation drifted");
            }
            String renderId = renderer.get("item").getAsString();
            counterRenderIds.computeIfAbsent(connectionName, k -> new LinkedHashSet<>()).add(renderId);
            String expectedModel = NAMESPACE + ":block/deco/bar_counter/" + connectionName;
            if (!renderItems.getAsJsonObject(renderId).getAsJsonObject("model")
                    .get("path").getAsString().equals(expectedModel)) {
                throw new ValidationException("bar_counter/" + variantEntry.getKey() + ": source model drifted");
            }
        }
        for (Set<String> ids : counterRenderIds.values()) {
            if (ids.size() != 1) {
                throw new ValidationException("Bar counter facings must share six render items");
            }
        }
        if (furniture.has(counterId)) {
            throw new ValidationException("Block-backed bar counter must not retain a furniture definition");
        }
    }


    private void validateStorage(JsonObject items, JsonObject renderItems, JsonObject blocks,
                                 JsonObject furniture) {
        Map<String, String[]> storageSpecs = Map.ofEntries(
                Map.entry("bar_cabinet", new String[] {"2", null, "minecraft:barrier"}),
                Map.entry("glass_bar_cabinet", new String[] {"2", null, "minecraft:barrier"}),
                Map.entry("cellar_cabinet", new String[] {"9", "cellar_cabinet_blocklist", "minecraft:barrier"}),
                Map.entry("tilted_rack", new String[] {"3", "tilted_rack_blocklist", "cactus"}),
                Map.entry("circular_rack", new String[] {"6", "circular_rack_blocklist",
                        "minecraft:cave_vines[age=1,berries=true]"}),
                Map.entry("holder", new String[] {"1", "holder_blocklist", "horizontal_lightning_rod"}));
        JsonObject baseOrientations = new JsonObject();
        String[][] orientationSpecs = {
                {"north", "0", "0", "1-x", "z"},
                {"east", "-90", "-90", "1-z", "1-x"},
                {"south", "180", "180", "x", "1-z"},
                {"west", "90", "90", "z", "x"}};
        for (String[] spec : orientationSpecs) {
            JsonObject orientation = new JsonObject();
            orientation.addProperty("position_yaw", Integer.parseInt(spec[1]));
            orientation.addProperty("model_yaw", Integer.parseInt(spec[2]));
            orientation.addProperty("local_x", spec[3]);
            orientation.addProperty("local_z", spec[4]);
            orientation.addProperty("reverse_slots", false);
            baseOrientations.add(spec[0], orientation);
        }
        Map<String, String> storageFacingRotations = new LinkedHashMap<>();
        storageFacingRotations.put("east", "0,90,0");
        storageFacingRotations.put("north", "0,180,0");
        storageFacingRotations.put("south", null);
        storageFacingRotations.put("west", "0,270,0");
        for (var specEntry : storageSpecs.entrySet()) {
            String storageId = specEntry.getKey();
            int slotCount = Integer.parseInt(specEntry.getValue()[0]);
            String blocklist = specEntry.getValue()[1];
            String carrierType = specEntry.getValue()[2];
            String fullId = NAMESPACE + ":" + storageId;
            boolean twoSlotCabinet = storageId.equals("bar_cabinet") || storageId.equals("glass_bar_cabinet");
            boolean configuredCabinet = twoSlotCabinet || storageId.equals("cellar_cabinet");
            boolean pitchedStorage = storageId.equals("cellar_cabinet") || storageId.equals("tilted_rack")
                    || storageId.equals("holder");
            if (furniture.has(fullId)) {
                throw new ValidationException(storageId + ": block-backed storage must not remain furniture");
            }
            JsonObject definition = blocks.getAsJsonObject(fullId);
            JsonElement actualBehaviorElement = definition.get(configuredCabinet ? "behaviors" : "behavior");
            JsonObject configuredStorage;
            if (configuredCabinet) {
                if (!actualBehaviorElement.isJsonArray()
                        || actualBehaviorElement.getAsJsonArray().size() != 2) {
                    throw new ValidationException(
                            storageId + ": connected storage must compose topology + storage");
                }
                JsonObject topology = actualBehaviorElement.getAsJsonArray().get(0).getAsJsonObject();
                JsonObject expectedTopology = new JsonObject();
                expectedTopology.addProperty("type", NAMESPACE + ":connected_block");
                expectedTopology.addProperty("mode", "linear");
                JsonArray connects = new JsonArray();
                connects.add(fullId);
                expectedTopology.add("connects", connects);
                expectedTopology.addProperty("state_property", "position");
                JsonObject outputs = new JsonObject();
                outputs.addProperty("none", "single");
                outputs.addProperty("left", "right");
                outputs.addProperty("right", "left");
                outputs.addProperty("both", "middle");
                JsonObject topologyConfig = new JsonObject();
                topologyConfig.add("outputs", outputs);
                expectedTopology.add("topology", topologyConfig);
                if (!expectedTopology.equals(topology)) {
                    throw new ValidationException(
                            storageId + ": linear connection values must live in CE config");
                }
                configuredStorage = actualBehaviorElement.getAsJsonArray().get(1).getAsJsonObject();
            } else {
                if (!actualBehaviorElement.isJsonObject()) {
                    throw new ValidationException(storageId + ": missing configured storage behavior");
                }
                configuredStorage = actualBehaviorElement.getAsJsonObject();
            }
            if (!configuredStorage.get("type").getAsString().equals(NAMESPACE + ":storage")
                    || !configuredStorage.get("data_key").getAsString()
                            .equals(NAMESPACE + ":storage_" + storageId)
                    || !configuredStorage.get("render_item_prefix").getAsString()
                            .equals(NAMESPACE + ":_render/storage/")
                    || configuredStorage.get("view_range").getAsDouble() != 1.25
                    || configuredStorage.getAsJsonArray("slots").size() != slotCount) {
                throw new ValidationException(storageId + ": generic multi-slot storage config drifted");
            }
            JsonObject expectedOrientations = new JsonObject();
            for (var orientationEntry : baseOrientations.entrySet()) {
                JsonObject copy = orientationEntry.getValue().getAsJsonObject().deepCopy();
                if (twoSlotCabinet && (orientationEntry.getKey().equals("east")
                        || orientationEntry.getKey().equals("west"))) {
                    copy.addProperty("reverse_slots", true);
                }
                if (pitchedStorage && (orientationEntry.getKey().equals("east")
                        || orientationEntry.getKey().equals("west"))) {
                    copy.addProperty("model_yaw", orientationEntry.getKey().equals("east") ? 90 : 270);
                }
                expectedOrientations.add(orientationEntry.getKey(), copy);
            }
            JsonObject orientations = configuredStorage.getAsJsonObject("orientations");
            if (!expectedOrientations.equals(orientations)) {
                throw new ValidationException(
                        storageId + ": source-space click/model orientation drifted");
            }
            for (var orientationEntry : orientations.entrySet()) {
                String facing = orientationEntry.getKey();
                JsonObject orientation = orientationEntry.getValue().getAsJsonObject();
                int expectedOffset = (pitchedStorage && (facing.equals("east") || facing.equals("west")))
                        ? 180 : 0;
                int actualOffset = Math.floorMod(
                        orientation.get("model_yaw").getAsInt() - orientation.get("position_yaw").getAsInt(),
                        360);
                if (actualOffset != expectedOffset) {
                    throw new ValidationException(storageId + "/" + facing
                            + ": packet model yaw offset must be " + expectedOffset
                            + ", found " + actualOffset);
                }
            }
            JsonObject selector = configuredStorage.getAsJsonObject("selector");
            String expectedSelectorType = switch (storageId) {
                case "bar_cabinet", "glass_bar_cabinet", "tilted_rack" -> "split";
                case "cellar_cabinet" -> "grid";
                case "circular_rack" -> "radial";
                default -> "single";
            };
            if (!selector.get("type").getAsString().equals(expectedSelectorType)) {
                throw new ValidationException(storageId + ": click selector is not config-owned");
            }
            JsonObject interaction = configuredStorage.getAsJsonObject("interaction");
            if (!interaction.has("allowed_items") || interaction.getAsJsonArray("allowed_items").isEmpty()
                    || !interaction.has("consume_in_creative")
                    || !interaction.has("sounds")) {
                throw new ValidationException(storageId + ": item rules/sounds must live in CE configuration");
            }
            if (blocklist != null && !interaction.has("blocked_items")) {
                throw new ValidationException(
                        storageId + ": configured blocklist key was not flattened into the behavior");
            }
            if (twoSlotCabinet && (!interaction.has("exclusive_items")
                    || interaction.get("exclusive_slot").getAsInt() != 0
                    || !interaction.get("fallback_take").getAsBoolean()
                    || !interaction.get("fallback_put").getAsBoolean())) {
                throw new ValidationException(storageId + ": irregular two-slot behavior must be config-owned");
            }
            if (!twoSlotCabinet && !configuredStorage.has("launch")) {
                throw new ValidationException(storageId + ": redstone launch parameters must be config-owned");
            }
            if (storageId.equals("circular_rack")) {
                JsonObject particle = configuredStorage.getAsJsonObject("particle");
                if (particle.get("alternate_min_x").getAsDouble() != 0.625
                        || particle.get("alternate_max_x").getAsDouble() != 0.875
                        || particle.get("alternate_min_z").getAsDouble() != 0.625
                        || particle.get("alternate_max_z").getAsDouble() != 0.875) {
                    throw new ValidationException(
                            "Circular-rack edge particle ranges must remain in CE config");
                }
            }
            JsonObject properties = definition.getAsJsonObject("states").getAsJsonObject("properties");
            Set<String> expectedProperties = twoSlotCabinet ? Set.of("facing", "position")
                    : storageId.equals("cellar_cabinet") ? Set.of("facing", "powered", "position")
                    : Set.of("facing", "powered");
            if (!properties.keySet().equals(expectedProperties)) {
                throw new ValidationException(storageId + ": storage state properties drifted: "
                        + properties.keySet());
            }
            if (expectedProperties.contains("position")) {
                JsonObject position = properties.getAsJsonObject("position");
                if (!position.get("default").getAsString().equals("single")
                        || !new LinkedHashSet<>(position.getAsJsonArray("values").asList().stream()
                                .map(JsonElement::getAsString).toList())
                                .equals(Set.of("single", "left", "middle", "right"))) {
                    throw new ValidationException(
                            storageId + ": connected cabinet position property drifted");
                }
            }
            JsonObject states = definition.getAsJsonObject("states");
            JsonObject appearances = states.getAsJsonObject("appearances");
            JsonObject variants = states.getAsJsonObject("variants");
            int expectedAppearances = (twoSlotCabinet || storageId.equals("cellar_cabinet")) ? 16 : 4;
            int expectedVariants = twoSlotCabinet ? 16
                    : storageId.equals("cellar_cabinet") ? 32 : 8;
            if (appearances.size() != expectedAppearances || variants.size() != expectedVariants) {
                throw new ValidationException(storageId + ": storage appearance/state coverage drifted: "
                        + appearances.size() + "/" + variants.size());
            }
            Set<String> renderIds = new LinkedHashSet<>();
            for (JsonElement rawAppearance : appearances.asMap().values()) {
                JsonObject appearance = rawAppearance.getAsJsonObject();
                if (carrierType.equals("minecraft:barrier")) {
                    if (!appearance.get("state").getAsString().equals(carrierType)
                            || appearance.has("auto_state")
                            || hasNonNull(appearance, "transparent")) {
                        throw new ValidationException(
                                storageId + ": must use CE sofa-style barrier rendering");
                    }
                } else if (carrierType.equals("horizontal_lightning_rod")) {
                    Set<String> holderStates = new LinkedHashSet<>();
                    for (String facing : List.of("north", "east", "south", "west")) {
                        holderStates.add("minecraft:lightning_rod[facing=" + facing
                                + ",powered=false,waterlogged=false]");
                    }
                    if (!holderStates.contains(appearance.get("state").getAsString())
                            || appearance.has("auto_state")
                            || !appearance.get("transparent").getAsBoolean()) {
                        throw new ValidationException(storageId
                                + ": expected a transparent released horizontal lightning-rod carrier");
                    }
                } else if (carrierType.startsWith("minecraft:")) {
                    if (!appearance.get("state").getAsString().equals(carrierType)
                            || appearance.has("auto_state")
                            || !appearance.get("transparent").getAsBoolean()) {
                        throw new ValidationException(storageId
                                + ": expected transparent released carrier state " + carrierType);
                    }
                } else {
                    JsonObject expectedAutoState = new JsonObject();
                    expectedAutoState.addProperty("type", carrierType);
                    expectedAutoState.addProperty("id", "kaleidoscope-tavern-tilted-rack-transparent");
                    if (!expectedAutoState.equals(appearance.get("auto_state"))
                            || appearance.has("state")
                            || !appearance.get("transparent").getAsBoolean()) {
                        throw new ValidationException(storageId + ": expected transparent "
                                + carrierType + " carrier");
                    }
                }
                JsonObject renderer = appearance.getAsJsonObject("entity_renderer");
                if (!renderer.get("type").getAsString().equals("item_display")) {
                    throw new ValidationException(
                            storageId + ": authored model must use an ItemDisplay renderer");
                }
                renderIds.add(renderer.get("item").getAsString());
            }
            int expectedRenderItems = (twoSlotCabinet || storageId.equals("cellar_cabinet")) ? 4 : 1;
            if (renderIds.size() != expectedRenderItems) {
                throw new ValidationException(storageId + ": expected " + expectedRenderItems
                        + " shared base render items, found " + renderIds);
            }
            String poweredSuffix = expectedProperties.contains("powered") ? ",powered=false" : "";
            String positionSuffix = expectedProperties.contains("position") ? ",position=single" : "";
            for (var rotationEntry : storageFacingRotations.entrySet()) {
                String facing = rotationEntry.getKey();
                String variantKey = "facing=" + facing + positionSuffix + poweredSuffix;
                JsonElement variant = variants.get(variantKey);
                if (variant == null) {
                    throw new ValidationException(storageId + ": missing canonical state " + variantKey);
                }
                JsonObject appearance = appearances.getAsJsonObject(
                        variant.getAsJsonObject().get("appearance").getAsString());
                JsonElement actualRotation = appearance.getAsJsonObject("entity_renderer").get("rotation");
                String expectedRotation = rotationEntry.getValue();
                if ((expectedRotation == null) ? actualRotation != null && !actualRotation.isJsonNull()
                        : actualRotation == null || actualRotation.isJsonNull()
                            || !actualRotation.getAsString().equals(expectedRotation)) {
                    throw new ValidationException(storageId + ": " + facing
                            + " model rotation drifted: " + actualRotation);
                }
                if (storageId.equals("holder") && !appearance.get("state").getAsString()
                        .equals("minecraft:lightning_rod[facing=" + facing
                                + ",powered=false,waterlogged=false]")) {
                    throw new ValidationException("holder: " + facing
                            + " carrier must rotate horizontally with the block state");
                }
            }
            JsonObject settings = definition.getAsJsonObject("settings");
            String expectedMiningTag = configuredCabinet ? "minecraft:mineable/axe" : "minecraft:mineable/pickaxe";
            JsonArray expectedTags = new JsonArray();
            expectedTags.add(expectedMiningTag);
            JsonObject expectedDestroyStages = new JsonObject();
            expectedDestroyStages.addProperty("template", "internal:destroy_stages");
            if (settings.get("hardness").getAsDouble() != 2.5
                    || settings.get("resistance").getAsDouble() != 2.5
                    || !settings.get("push_reaction").getAsString().equals("NORMAL")
                    || !expectedTags.equals(settings.getAsJsonArray("tags"))
                    || !expectedDestroyStages.equals(settings.get("destroy_stages"))) {
                throw new ValidationException(storageId
                        + ": source mining settings or CE destroy stages drifted");
            }
            if (storageId.equals("circular_rack")) {
                if (!settings.has("luminance") || settings.get("luminance").getAsInt() != 14) {
                    throw new ValidationException(storageId + ": source luminance drifted");
                }
            } else if (settings.has("luminance") && !settings.get("luminance").isJsonNull()) {
                throw new ValidationException(storageId + ": source luminance drifted");
            }
            if (twoSlotCabinet) {
                if (settings.get("map_color").getAsInt() != 13
                        || !settings.get("instrument").getAsString().equals("guitar")
                        || !settings.get("burnable").getAsBoolean()
                        || settings.get("burn_chance").getAsInt() != 5
                        || settings.get("fire_spread_chance").getAsInt() != 20) {
                    throw new ValidationException(storageId + ": wood cabinet settings drifted");
                }
            }
            JsonObject expectedItemBehavior = new JsonObject();
            expectedItemBehavior.addProperty("type", "block_item");
            expectedItemBehavior.addProperty("block", fullId);
            if (!expectedItemBehavior.equals(items.getAsJsonObject(fullId).get("behavior"))) {
                throw new ValidationException(storageId + ": placement must use CE's native block_item");
            }
        }
    }

    private void validateShakerModel() throws IOException {
        JsonObject paperModel = assetJson(NAMESPACE + ":item/shaker_3d", "models");
        JsonObject sourceModel = null;
        for (Path root : List.of(generatedAssetsRoot, mainAssetsRoot)) {
            Path candidate = root.resolve(NAMESPACE + "/models/item/shaker_3d.json");
            if (Files.isRegularFile(candidate)) {
                sourceModel = readJson(candidate);
                break;
            }
        }
        if (paperModel == null || sourceModel == null) {
            throw new ValidationException("shaker_3d: missing source or migrated model");
        }
        JsonObject stripped = new JsonObject();
        for (var entry : sourceModel.entrySet()) {
            if (!entry.getKey().equals("groups")) stripped.add(entry.getKey(), entry.getValue());
        }
        if (!stripped.equals(paperModel)
                || paperModel.getAsJsonArray("elements").size() != 5
                || !paperModel.getAsJsonObject("display").keySet().equals(Set.of(
                        "thirdperson_righthand", "thirdperson_lefthand",
                        "firstperson_righthand", "firstperson_lefthand",
                        "ground", "head"))) {
            throw new ValidationException(
                    "Shaker held/ground/head model must retain the authored 3D geometry and transforms");
        }
    }


    private static final List<String> SANDWICH_BOARDS = List.of(
            "base", "grass", "allium", "azure_bluet", "cornflower", "orchid",
            "peony", "pink_petals", "pitcher_plant", "poppy", "sunflower",
            "torchflower", "tulip", "wither_rose");
    private static final List<String> WINES = List.of(
            "wine", "champagne", "vodka", "brandy", "carignan", "sakura_wine",
            "plum_wine", "whiskey", "ice_wine", "polaris_sweet_white",
            "honey_wine", "red_queen", "miners_star", "rum",
            "riesling_dry_white", "sunset_glow", "madame_shexiang",
            "sweet_berry_wine", "sherry", "mother_snow", "luminous_bride",
            "glowflower_brew", "sauvignon_blanc_dry_white", "vinegar");

    private static List<String> allBehaviorTypes(JsonObject definition) {
        List<String> types = new ArrayList<>();
        if (definition.has("behaviors")) {
            for (JsonElement raw : definition.getAsJsonArray("behaviors")) {
                types.add(raw.getAsJsonObject().get("type").getAsString());
            }
        }
        if (definition.has("behavior")) {
            types.add(definition.getAsJsonObject("behavior").get("type").getAsString());
        }
        return types;
    }

    private void validateFurnitureBehaviors(JsonObject furniture) {
        Set<String> expectedStateIds = new LinkedHashSet<>();
        SANDWICH_BOARDS.forEach(b -> expectedStateIds.add(NAMESPACE + ":" + b + "_sandwich_board"));
        expectedStateIds.add(NAMESPACE + ":barrel");
        expectedStateIds.add(NAMESPACE + ":watermelon_juice");
        WINES.forEach(w -> expectedStateIds.add(NAMESPACE + ":" + w));
        expectedStateIds.add(WALL_PRESSING_TUB_ID);
        Map<String, Integer> stateIndex = new LinkedHashMap<>();
        Map<String, JsonElement> stateBehavior = new LinkedHashMap<>();
        for (var entry : furniture.entrySet()) {
            JsonObject definition = entry.getValue().getAsJsonObject();
            List<String> types = allBehaviorTypes(definition);
            int index = -1;
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i).equals(NAMESPACE + ":state_furniture")) {
                    if (index != -1) {
                        throw new ValidationException(entry.getKey() + ": duplicate state_furniture behaviors");
                    }
                    index = i;
                }
            }
            if (index != -1) {
                JsonElement behavior = behaviorAt(definition, index);
                stateIndex.put(entry.getKey(), index);
                stateBehavior.put(entry.getKey(), behavior);
            }
        }
        if (!stateIndex.keySet().equals(expectedStateIds)) {
            Set<String> missing = new LinkedHashSet<>(expectedStateIds);
            missing.removeAll(stateIndex.keySet());
            Set<String> unexpected = new LinkedHashSet<>(stateIndex.keySet());
            unexpected.removeAll(expectedStateIds);
            throw new ValidationException("State furniture coverage drift: missing="
                    + missing + ", unexpected=" + unexpected);
        }
        JsonObject plainState = new JsonObject();
        plainState.addProperty("type", NAMESPACE + ":state_furniture");
        for (var entry : stateIndex.entrySet()) {
            if (entry.getValue() != 0 || !plainState.equals(stateBehavior.get(entry.getKey()))) {
                throw new ValidationException(
                        entry.getKey() + ": state_furniture must be the exact index-zero behavior");
            }
        }
        Map<String, List<String>> expectedLifecycle = new LinkedHashMap<>();
        SANDWICH_BOARDS.forEach(b -> expectedLifecycle.put(b + "_sandwich_board", List.of("board")));
        expectedLifecycle.put("shaker", List.of("shaker"));
        expectedLifecycle.put("barrel", List.of("barrel"));
        expectedLifecycle.put("empty_bottle", List.of("tap_bottle"));
        for (String color : FURNITURE_COLORS) {
            expectedLifecycle.put(color + "_bar_stool", List.of("bar_stool"));
        }
        Map<String, List<JsonElement>> lifecycleBehaviors = new LinkedHashMap<>();
        for (var entry : furniture.entrySet()) {
            JsonObject definition = entry.getValue().getAsJsonObject();
            List<String> types = allBehaviorTypes(definition);
            List<JsonElement> matches = new ArrayList<>();
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i).equals(NAMESPACE + ":lifecycle_furniture")) {
                    matches.add(behaviorAt(definition, i));
                }
            }
            if (!matches.isEmpty()) {
                lifecycleBehaviors.put(entry.getKey(), matches);
            }
        }
        Set<String> expectedLifecycleIds = new LinkedHashSet<>();
        expectedLifecycle.keySet().forEach(id -> expectedLifecycleIds.add(NAMESPACE + ":" + id));
        if (!lifecycleBehaviors.keySet().equals(expectedLifecycleIds)) {
            Set<String> missing = new LinkedHashSet<>(expectedLifecycleIds);
            missing.removeAll(lifecycleBehaviors.keySet());
            Set<String> unexpected = new LinkedHashSet<>(lifecycleBehaviors.keySet());
            unexpected.removeAll(expectedLifecycleIds);
            throw new ValidationException("Lifecycle furniture coverage drift: missing="
                    + missing + ", unexpected=" + unexpected);
        }
        for (var entry : expectedLifecycle.entrySet()) {
            String furnitureId = NAMESPACE + ":" + entry.getKey();
            JsonObject definition = furniture.getAsJsonObject(furnitureId);
            List<String> types = allBehaviorTypes(definition);
            int expectedStart = expectedStateIds.contains(furnitureId) ? 1 : 0;
            List<JsonElement> actual = lifecycleBehaviors.get(furnitureId);
            for (int i = 0; i < entry.getValue().size(); i++) {
                int actualIndex = types.indexOf(NAMESPACE + ":lifecycle_furniture");
                if (actualIndex == -1) {
                    throw new ValidationException(furnitureId + ": lifecycle order drifted");
                }
                JsonObject expectedBehavior = new JsonObject();
                expectedBehavior.addProperty("type", NAMESPACE + ":lifecycle_furniture");
                expectedBehavior.addProperty("channel", entry.getValue().get(i));
                if (actualIndex != expectedStart + i || !expectedBehavior.equals(actual.get(i))) {
                    throw new ValidationException(furnitureId + ": lifecycle order drifted: indices="
                            + actualIndex + ", behaviors=" + actual);
                }
                types.set(actualIndex, "__consumed__");
            }
        }
        Map<String, Integer> expectedBoardText = new LinkedHashMap<>();
        SANDWICH_BOARDS.forEach(b -> expectedBoardText.put(b + "_sandwich_board", 8));
        Map<String, JsonElement> boardTextBehavior = new LinkedHashMap<>();
        for (var entry : furniture.entrySet()) {
            JsonObject definition = entry.getValue().getAsJsonObject();
            List<String> types = allBehaviorTypes(definition);
            int index = -1;
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i).equals(NAMESPACE + ":board_text_furniture")) {
                    if (index != -1) {
                        throw new ValidationException(entry.getKey() + ": duplicate board_text_furniture behaviors");
                    }
                    index = i;
                }
            }
            if (index != -1) {
                boardTextBehavior.put(entry.getKey(), new com.google.gson.JsonArray());
                boardTextBehavior.get(entry.getKey()).getAsJsonArray().add(index);
                boardTextBehavior.get(entry.getKey()).getAsJsonArray().add(behaviorAt(definition, index));
            }
        }
        Set<String> expectedBoardIds = new LinkedHashSet<>();
        expectedBoardText.keySet().forEach(id -> expectedBoardIds.add(NAMESPACE + ":" + id));
        if (!boardTextBehavior.keySet().equals(expectedBoardIds)) {
            Set<String> missing = new LinkedHashSet<>(expectedBoardIds);
            missing.removeAll(boardTextBehavior.keySet());
            Set<String> unexpected = new LinkedHashSet<>(boardTextBehavior.keySet());
            unexpected.removeAll(expectedBoardIds);
            throw new ValidationException("Board text furniture coverage drift: missing="
                    + missing + ", unexpected=" + unexpected);
        }
        for (var entry : expectedBoardText.entrySet()) {
            String furnitureId = NAMESPACE + ":" + entry.getKey();
            JsonArray packed = boardTextBehavior.get(furnitureId).getAsJsonArray();
            JsonObject expectedBehavior = new JsonObject();
            expectedBehavior.addProperty("type", NAMESPACE + ":board_text_furniture");
            expectedBehavior.addProperty("max_lines", entry.getValue());
            expectedBehavior.addProperty("view_range", 0.75);
            if (packed.get(0).getAsInt() != 2 || !expectedBehavior.equals(packed.get(1))) {
                throw new ValidationException(furnitureId + ": board_text_furniture order/config drifted: "
                        + "index=" + packed.get(0) + ", behavior=" + packed.get(1));
            }
        }
        Map<String, JsonElement> animatedBehavior = new LinkedHashMap<>();
        for (var entry : furniture.entrySet()) {
            JsonObject definition = entry.getValue().getAsJsonObject();
            List<String> types = allBehaviorTypes(definition);
            int index = -1;
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i).equals(NAMESPACE + ":animated_item_furniture")) {
                    if (index != -1) {
                        throw new ValidationException(
                                entry.getKey() + ": duplicate animated_item_furniture behaviors");
                    }
                    index = i;
                }
            }
            if (index != -1) {
                JsonArray packed = new JsonArray();
                packed.add(index);
                packed.add(behaviorAt(definition, index));
                animatedBehavior.put(entry.getKey(), packed);
            }
        }
        Set<String> expectedAnimatedIds = new LinkedHashSet<>();
        expectedAnimatedIds.add(NAMESPACE + ":shaker");
        for (String color : FURNITURE_COLORS) {
            expectedAnimatedIds.add(NAMESPACE + ":" + color + "_bar_stool");
        }
        if (!animatedBehavior.keySet().equals(expectedAnimatedIds)) {
            Set<String> missing = new LinkedHashSet<>(expectedAnimatedIds);
            missing.removeAll(animatedBehavior.keySet());
            Set<String> unexpected = new LinkedHashSet<>(animatedBehavior.keySet());
            unexpected.removeAll(expectedAnimatedIds);
            throw new ValidationException("Animated visual furniture coverage drift: missing="
                    + missing + ", unexpected=" + unexpected);
        }
        for (String furnitureId : expectedAnimatedIds) {
            String bareId = furnitureId.substring(NAMESPACE.length() + 1);
            JsonArray packed = animatedBehavior.get(furnitureId).getAsJsonArray();
            JsonObject expectedBehavior = new JsonObject();
            expectedBehavior.addProperty("type", NAMESPACE + ":animated_item_furniture");
            expectedBehavior.addProperty("channel",
                    bareId.equals("shaker") ? "shaker" : "bar_stool");
            expectedBehavior.addProperty("max_elements",
                    bareId.equals("shaker") ? 2 : 1);
            expectedBehavior.addProperty("view_range", 1.25);
            if (packed.get(0).getAsInt() != 1 || !expectedBehavior.equals(packed.get(1))) {
                throw new ValidationException(furnitureId + ": animated_item_furniture order/config drifted: "
                        + "index=" + packed.get(0) + ", behavior=" + packed.get(1));
            }
        }
        Set<String> expectedBottleIds = new LinkedHashSet<>();
        List.of("empty_bottle", "empty_glassware", "signature_cocktail", "mystery_cocktail",
                "white_lady", "emerald", "brass_heart", "godfather", "grasshopper",
                "screwdriver", "mojito", "allium_garden", "depth_charge", "nether_special",
                "bloody_mary", "sculk_special", "molotov", "water_bottle", "honey_bottle",
                "dragon_breath_bottle", "potion_bottle", "xp_bottle")
                .forEach(id -> expectedBottleIds.add(NAMESPACE + ":" + id));
        WINES.forEach(w -> expectedBottleIds.add(NAMESPACE + ":" + w));
        expectedBottleIds.add(NAMESPACE + ":watermelon_juice");
        Map<String, JsonElement> bottleBehavior = new LinkedHashMap<>();
        for (var entry : furniture.entrySet()) {
            JsonObject definition = entry.getValue().getAsJsonObject();
            List<String> types = allBehaviorTypes(definition);
            int index = -1;
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i).equals(NAMESPACE + ":bottle_furniture")) {
                    if (index != -1) {
                        throw new ValidationException(entry.getKey() + ": duplicate bottle_furniture behaviors");
                    }
                    index = i;
                }
            }
            if (index != -1) {
                JsonArray packed = new JsonArray();
                packed.add(index);
                packed.add(behaviorAt(definition, index));
                bottleBehavior.put(entry.getKey(), packed);
            }
        }
        if (!bottleBehavior.keySet().equals(expectedBottleIds)) {
            Set<String> missing = new LinkedHashSet<>(expectedBottleIds);
            missing.removeAll(bottleBehavior.keySet());
            Set<String> unexpected = new LinkedHashSet<>(bottleBehavior.keySet());
            unexpected.removeAll(expectedBottleIds);
            throw new ValidationException("Bottle CE interaction coverage drift: missing="
                    + missing + ", unexpected=" + unexpected);
        }
        JsonObject plainBottle = new JsonObject();
        plainBottle.addProperty("type", NAMESPACE + ":bottle_furniture");
        for (String bottleId : expectedBottleIds) {
            String bareId = bottleId.substring(NAMESPACE.length() + 1);
            JsonArray packed = bottleBehavior.get(bottleId).getAsJsonArray();
            int expectedIndex = (expectedStateIds.contains(bottleId) ? 1 : 0)
                    + (expectedLifecycle.containsKey(bareId) ? expectedLifecycle.get(bareId).size() : 0);
            if (packed.get(0).getAsInt() != expectedIndex || !plainBottle.equals(packed.get(1))) {
                throw new ValidationException(bottleId + ": bottle_furniture order/config drifted: "
                        + "index=" + packed.get(0) + ", behavior=" + packed.get(1));
            }
        }
        Map<String, JsonElement> stationVisuals = new LinkedHashMap<>();
        JsonObject barrelVisual = new JsonObject();
        barrelVisual.addProperty("type", NAMESPACE + ":station_visual_furniture");
        barrelVisual.addProperty("max_elements", 17);
        barrelVisual.addProperty("view_range", 2.5);
        JsonObject wallTubVisual = new JsonObject();
        wallTubVisual.addProperty("type", NAMESPACE + ":station_visual_furniture");
        wallTubVisual.addProperty("max_elements", 17);
        wallTubVisual.addProperty("view_range", 1.25);
        stationVisuals.put(NAMESPACE + ":barrel", barrelVisual);
        stationVisuals.put(WALL_PRESSING_TUB_ID, wallTubVisual);
        for (var entry : stationVisuals.entrySet()) {
            JsonObject definition = furniture.getAsJsonObject(entry.getKey());
            List<String> types = allBehaviorTypes(definition);
            List<JsonElement> visualBehaviors = new ArrayList<>();
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i).equals(NAMESPACE + ":station_visual_furniture")) {
                    visualBehaviors.add(behaviorAt(definition, i));
                }
            }
            JsonArray expectedVisuals = new JsonArray();
            expectedVisuals.add(entry.getValue());
            if (visualBehaviors.size() != 1 || !expectedVisuals.get(0).equals(visualBehaviors.get(0))) {
                throw new ValidationException(entry.getKey() + ": CE virtual station visual coverage drifted");
            }
            int expectedIndex = entry.getKey().equals(NAMESPACE + ":barrel") ? 2 : 1;
            if (types.indexOf(NAMESPACE + ":station_visual_furniture") != expectedIndex) {
                throw new ValidationException(entry.getKey() + ": station visual controller order drifted");
            }
        }
        Map<String, Integer> expectedStationInteractions = new LinkedHashMap<>();
        expectedStationInteractions.put(NAMESPACE + ":barrel", 3);
        expectedStationInteractions.put(NAMESPACE + ":shaker", 2);
        expectedStationInteractions.put(NAMESPACE + ":empty_glassware", 1);
        expectedStationInteractions.put(WALL_PRESSING_TUB_ID, 2);
        Map<String, JsonElement> stationInteractionBehavior = new LinkedHashMap<>();
        for (var entry : furniture.entrySet()) {
            JsonObject definition = entry.getValue().getAsJsonObject();
            List<String> types = allBehaviorTypes(definition);
            int index = -1;
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i).equals(NAMESPACE + ":station_interaction_furniture")) {
                    if (index != -1) {
                        throw new ValidationException(
                                entry.getKey() + ": duplicate station_interaction_furniture behaviors");
                    }
                    index = i;
                }
            }
            if (index != -1) {
                JsonArray packed = new JsonArray();
                packed.add(index);
                packed.add(behaviorAt(definition, index));
                stationInteractionBehavior.put(entry.getKey(), packed);
            }
        }
        if (!stationInteractionBehavior.keySet().equals(expectedStationInteractions.keySet())) {
            Set<String> missing = new LinkedHashSet<>(expectedStationInteractions.keySet());
            missing.removeAll(stationInteractionBehavior.keySet());
            Set<String> unexpected = new LinkedHashSet<>(stationInteractionBehavior.keySet());
            unexpected.removeAll(expectedStationInteractions.keySet());
            throw new ValidationException("Station CE interaction coverage drift: missing="
                    + missing + ", unexpected=" + unexpected);
        }
        JsonObject plainStationInteraction = new JsonObject();
        plainStationInteraction.addProperty("type", NAMESPACE + ":station_interaction_furniture");
        for (var entry : expectedStationInteractions.entrySet()) {
            JsonArray packed = stationInteractionBehavior.get(entry.getKey()).getAsJsonArray();
            if (packed.get(0).getAsInt() != entry.getValue()
                    || !plainStationInteraction.equals(packed.get(1))) {
                throw new ValidationException(entry.getKey() + ": station interaction order/config drifted: "
                        + "index=" + packed.get(0) + ", behavior=" + packed.get(1));
            }
        }
        for (var entry : furniture.entrySet()) {
            if (allBehaviorTypes(entry.getValue().getAsJsonObject())
                    .contains(NAMESPACE + ":redstone_furniture")) {
                throw new ValidationException(entry.getKey()
                        + ": storage launchers are CE blocks; redstone_furniture must not be generated");
            }
        }
        Map<String, List<JsonElement>> tickingBehavior = new LinkedHashMap<>();
        for (var entry : furniture.entrySet()) {
            JsonObject definition = entry.getValue().getAsJsonObject();
            List<String> types = allBehaviorTypes(definition);
            List<JsonElement> matches = new ArrayList<>();
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i).equals(NAMESPACE + ":ticking_furniture")) {
                    matches.add(behaviorAt(definition, i));
                }
            }
            if (!matches.isEmpty()) {
                tickingBehavior.put(entry.getKey(), matches);
            }
        }
        Map<String, List<JsonObject>> expectedTicking = new LinkedHashMap<>();
        JsonObject mysterySchedule = new JsonObject();
        mysterySchedule.addProperty("channel", "mystery_particle");
        mysterySchedule.addProperty("chance", 49);
        expectedTicking.put("mystery_cocktail", List.of(mysterySchedule));
        JsonObject barrelSchedule = new JsonObject();
        barrelSchedule.addProperty("channel", "barrel");
        barrelSchedule.addProperty("interval", 97);
        barrelSchedule.addProperty("phase", "identity");
        expectedTicking.put("barrel", List.of(barrelSchedule));
        Set<String> expectedTickingIds = new LinkedHashSet<>();
        expectedTicking.keySet().forEach(id -> expectedTickingIds.add(NAMESPACE + ":" + id));
        if (!tickingBehavior.keySet().equals(expectedTickingIds)) {
            Set<String> missing = new LinkedHashSet<>(expectedTickingIds);
            missing.removeAll(tickingBehavior.keySet());
            Set<String> unexpected = new LinkedHashSet<>(tickingBehavior.keySet());
            unexpected.removeAll(expectedTickingIds);
            throw new ValidationException("Ticking furniture coverage drift: missing="
                    + missing + ", unexpected=" + unexpected);
        }
        for (var entry : expectedTicking.entrySet()) {
            String furnitureId = NAMESPACE + ":" + entry.getKey();
            List<JsonElement> actual = tickingBehavior.get(furnitureId);
            List<JsonElement> expected = new ArrayList<>();
            for (JsonObject schedule : entry.getValue()) {
                JsonObject behavior = new JsonObject();
                behavior.addProperty("type", NAMESPACE + ":ticking_furniture");
                for (var scheduleEntry : schedule.entrySet()) {
                    behavior.add(scheduleEntry.getKey(), scheduleEntry.getValue());
                }
                expected.add(behavior);
            }
            if (!expected.equals(actual)) {
                throw new ValidationException(furnitureId + ": ticking behaviors must be exactly " + expected);
            }
        }
        Map<String, JsonElement> storageInteractionBehavior = new LinkedHashMap<>();
        for (var entry : furniture.entrySet()) {
            JsonObject definition = entry.getValue().getAsJsonObject();
            List<String> types = allBehaviorTypes(definition);
            int index = -1;
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i).equals(NAMESPACE + ":storage_interaction_furniture")) {
                    if (index != -1) {
                        throw new ValidationException(
                                entry.getKey() + ": duplicate storage_interaction_furniture behaviors");
                    }
                    index = i;
                }
            }
            if (index != -1) {
                JsonArray packed = new JsonArray();
                packed.add(index);
                packed.add(behaviorAt(definition, index));
                storageInteractionBehavior.put(entry.getKey(), packed);
            }
        }
        Set<String> expectedStorageInteractionIds = Set.of(NAMESPACE + ":glassware_holder");
        if (!storageInteractionBehavior.keySet().equals(expectedStorageInteractionIds)) {
            Set<String> missing = new LinkedHashSet<>(expectedStorageInteractionIds);
            missing.removeAll(storageInteractionBehavior.keySet());
            Set<String> unexpected = new LinkedHashSet<>(storageInteractionBehavior.keySet());
            unexpected.removeAll(expectedStorageInteractionIds);
            throw new ValidationException("Storage CE interaction coverage drift: missing="
                    + missing + ", unexpected=" + unexpected);
        }
        for (String storageId : expectedStorageInteractionIds) {
            JsonObject definition = furniture.getAsJsonObject(storageId);
            List<String> types = allBehaviorTypes(definition);
            List<JsonElement> displayBehaviors = new ArrayList<>();
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i).equals("display_item_furniture")) {
                    displayBehaviors.add(behaviorAt(definition, i));
                }
            }
            List<JsonElement> expectedDisplay = new ArrayList<>();
            for (int slot = 0; slot < 4; slot++) {
                JsonObject behavior = new JsonObject();
                behavior.addProperty("type", "display_item_furniture");
                behavior.addProperty("data_key", NAMESPACE + ":display_slot_" + slot);
                JsonObject sounds = new JsonObject();
                sounds.addProperty("put", "minecraft:block.decorated_pot.insert");
                sounds.addProperty("take", "minecraft:block.decorated_pot.insert_fail");
                behavior.add("sounds", sounds);
                expectedDisplay.add(behavior);
            }
            if (!expectedDisplay.equals(displayBehaviors)) {
                throw new ValidationException(
                        storageId + ": native CE controllers must own storage without duplicate sprites");
            }
            List<Integer> displayIndices = new ArrayList<>();
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i).equals("display_item_furniture")) displayIndices.add(i);
            }
            JsonArray packed = storageInteractionBehavior.get(storageId).getAsJsonArray();
            JsonObject plainStorageInteraction = new JsonObject();
            plainStorageInteraction.addProperty("type", NAMESPACE + ":storage_interaction_furniture");
            if (displayIndices.isEmpty() || packed.get(0).getAsInt() != displayIndices.get(0) - 1
                    || !plainStorageInteraction.equals(packed.get(1))) {
                throw new ValidationException(
                        storageId + ": CE storage interaction must immediately precede native slots");
            }
            List<JsonElement> visualBehaviors = new ArrayList<>();
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i).equals(NAMESPACE + ":storage_visual_furniture")) {
                    visualBehaviors.add(behaviorAt(definition, i));
                }
            }
            JsonObject expectedVisual = new JsonObject();
            expectedVisual.addProperty("type", NAMESPACE + ":storage_visual_furniture");
            expectedVisual.addProperty("slots", 4);
            if (visualBehaviors.size() != 1 || !expectedVisual.equals(visualBehaviors.get(0))) {
                throw new ValidationException(storageId + ": CE virtual storage visual coverage drifted");
            }
            int visualIndex = types.indexOf(NAMESPACE + ":storage_visual_furniture");
            if (displayIndices.isEmpty() || visualIndex != displayIndices.get(displayIndices.size() - 1) + 1) {
                throw new ValidationException(
                        storageId + ": storage visual controller must follow its native slot controllers");
            }
        }
    }

    private static JsonElement behaviorAt(JsonObject definition, int index) {
        if (definition.has("behaviors")) {
            JsonArray behaviors = definition.getAsJsonArray("behaviors");
            if (index < behaviors.size()) return behaviors.get(index);
            index -= behaviors.size();
        }
        return definition.getAsJsonObject("behavior");
    }

    private void validateIncense(JsonObject items, JsonObject blocks) {
        Map<String, String[]> incenseSpecs = Map.ofEntries(
                Map.entry("sakura_incense", new String[] {"CHERRY_LEAVES", "CHERRY_LEAVES", "-2.0", "16.0"}),
                Map.entry("pine_incense", new String[] {"SMOKE", "CAMPFIRE_COSY_SMOKE", "-2.0", "16.0"}),
                Map.entry("ginkgo_incense", new String[] {"WAX_OFF", "COMPOSTER", "-2.0", "16.0"}),
                Map.entry("spore_incense", new String[] {"SPORE_BLOSSOM_AIR", "SPORE_BLOSSOM_AIR", "-2.0", "16.0"}),
                Map.entry("catnip_incense", new String[] {"HAPPY_VILLAGER", "HAPPY_VILLAGER", "-2.0", "16.0"}),
                Map.entry("snow_incense", new String[] {"SNOWFLAKE", "SNOWFLAKE", "-2.0", "16.0"}),
                Map.entry("butterfly_incense", new String[] {"GLOW", "GLOW", "-2.0", "16.0"}),
                Map.entry("firefly_incense", new String[] {"FIREFLY", "FIREFLY", "-0.67", "5.33"}));
        JsonArray expectedEvents = incenseEvents();
        for (var specEntry : incenseSpecs.entrySet()) {
            String incenseId = NAMESPACE + ":" + specEntry.getKey();
            String[] particleSpec = specEntry.getValue();
            JsonObject definition = blocks.getAsJsonObject(incenseId);
            JsonObject states = definition.getAsJsonObject("states");
            JsonObject properties = states.getAsJsonObject("properties");
            if (!properties.keySet().equals(Set.of("facing", "open", "powered"))
                    || !properties.getAsJsonObject("facing").get("type").getAsString()
                            .equals("horizontal_direction")) {
                throw new ValidationException(incenseId + ": facing/open/powered state schema drifted");
            }
            JsonObject openProperty = new JsonObject();
            openProperty.addProperty("type", "boolean");
            openProperty.addProperty("default", "false");
            JsonObject poweredProperty = new JsonObject();
            poweredProperty.addProperty("type", "boolean");
            poweredProperty.addProperty("default", "false");
            if (!openProperty.equals(properties.get("open"))
                    || !poweredProperty.equals(properties.get("powered"))) {
                throw new ValidationException(incenseId + ": facing/open/powered state schema drifted");
            }
            JsonObject appearances = states.getAsJsonObject("appearances");
            JsonObject variants = states.getAsJsonObject("variants");
            if (appearances.size() != 8 || variants.size() != 16) {
                throw new ValidationException(incenseId
                        + ": expected 8 visual appearances and 16 state variants");
            }
            Set<String> renderHelpers = new LinkedHashSet<>();
            for (JsonElement rawAppearance : appearances.asMap().values()) {
                JsonObject appearance = rawAppearance.getAsJsonObject();
                JsonObject renderer = appearance.getAsJsonObject("entity_renderer");
                if (!appearance.get("state").getAsString()
                        .equals("minecraft:copper_lantern[hanging=false,waterlogged=false]")
                        || !appearance.get("transparent").getAsBoolean()
                        || !renderer.get("type").getAsString().equals("item_display")) {
                    throw new ValidationException(incenseId
                            + ": must use the released standing copper-lantern carrier");
                }
                renderHelpers.add(renderer.get("item").getAsString());
            }
            if (renderHelpers.size() != 2 || renderHelpers.contains(null)) {
                throw new ValidationException(incenseId
                        + ": closed/open directions must share exactly two render items");
            }
            JsonObject expectedBehavior = new JsonObject();
            expectedBehavior.addProperty("type", NAMESPACE + ":incense");
            expectedBehavior.addProperty("small_particle", particleSpec[0]);
            expectedBehavior.addProperty("large_particle", particleSpec[1]);
            expectedBehavior.addProperty("large_particle_y_offset", Double.parseDouble(particleSpec[2]));
            expectedBehavior.addProperty("large_particle_y_range", Double.parseDouble(particleSpec[3]));
            if (!expectedBehavior.equals(definition.get("behavior"))) {
                throw new ValidationException(incenseId + ": incense CE behavior config drifted");
            }
            if (!expectedEvents.equals(definition.get("events"))) {
                throw new ValidationException(incenseId + ": manual toggle/protection must use native CE events");
            }
            JsonObject settings = definition.getAsJsonObject("settings");
            Map<String, JsonElement> expectedSounds = new LinkedHashMap<>();
            for (String action : List.of("break", "step", "place", "hit", "fall")) {
                expectedSounds.put(action, new JsonPrimitive("minecraft:block.decorated_pot." + action));
            }
            if (settings.get("hardness").getAsDouble() != 0.0
                    || settings.get("resistance").getAsDouble() != 0.0
                    || !jsonObjectOf(expectedSounds).equals(settings.get("sounds"))
                    || settings.has("luminance")) {
                throw new ValidationException(incenseId
                        + ": source instant-break, sound or non-luminous settings drifted");
            }
            JsonObject expectedItemBehavior = new JsonObject();
            expectedItemBehavior.addProperty("type", "block_item");
            expectedItemBehavior.addProperty("block", incenseId);
            if (!expectedItemBehavior.equals(items.getAsJsonObject(incenseId).get("behavior"))) {
                throw new ValidationException(incenseId + ": item must place the CE block directly");
            }
        }
    }

    private static JsonArray incenseEvents() {
        JsonObject ordinaryUse = new JsonObject();
        ordinaryUse.addProperty("type", "any_of");
        JsonArray terms = new JsonArray();
        JsonObject notSneaking = new JsonObject();
        notSneaking.addProperty("type", "!equals");
        notSneaking.addProperty("value1", "<arg:player.is_sneaking>");
        notSneaking.addProperty("value2", "true");
        terms.add(notSneaking);
        JsonObject emptyHands = new JsonObject();
        emptyHands.addProperty("type", "all_of");
        JsonArray handTerms = new JsonArray();
        for (String hand : List.of("main_hand", "off_hand")) {
            JsonObject equals = new JsonObject();
            equals.addProperty("type", "equals");
            equals.addProperty("value1", "<arg:player." + hand + "_item.count>");
            equals.addProperty("value2", "0");
            handTerms.add(equals);
        }
        emptyHands.add("terms", handTerms);
        terms.add(emptyHands);
        ordinaryUse.add("terms", terms);
        JsonArray events = new JsonArray();
        String[][] toggles = {
                {"false", "true", "minecraft:block.stone_button.click_on"},
                {"true", "false", "minecraft:block.stone_button.click_off"}};
        for (String[] toggle : toggles) {
            JsonObject event = new JsonObject();
            event.addProperty("on", "right_click");
            JsonArray conditions = new JsonArray();
            conditions.add(ordinaryUse);
            JsonObject matchProperty = new JsonObject();
            matchProperty.addProperty("type", "match_block_property");
            JsonObject properties = new JsonObject();
            properties.addProperty("open", toggle[0]);
            matchProperty.add("properties", properties);
            conditions.add(matchProperty);
            JsonObject testFlag = new JsonObject();
            testFlag.addProperty("type", "test_flag");
            testFlag.addProperty("flag", "interact");
            conditions.add(testFlag);
            event.add("conditions", conditions);
            JsonArray functions = new JsonArray();
            JsonObject updateInteraction = new JsonObject();
            updateInteraction.addProperty("type", "update_interaction_tick");
            functions.add(updateInteraction);
            JsonObject updateProperty = new JsonObject();
            updateProperty.addProperty("type", "update_block_property");
            JsonObject openProperties = new JsonObject();
            openProperties.addProperty("open", toggle[1]);
            updateProperty.add("properties", openProperties);
            updateProperty.addProperty("update_flags", 2);
            functions.add(updateProperty);
            JsonObject playSound = new JsonObject();
            playSound.addProperty("type", "play_sound");
            playSound.addProperty("sound", toggle[2]);
            playSound.addProperty("source", "block");
            functions.add(playSound);
            JsonObject swingHand = new JsonObject();
            swingHand.addProperty("type", "swing_hand");
            functions.add(swingHand);
            JsonObject cancelEvent = new JsonObject();
            cancelEvent.addProperty("type", "cancel_event");
            functions.add(cancelEvent);
            event.add("functions", functions);
            events.add(event);
        }
        JsonObject blockedEvent = new JsonObject();
        blockedEvent.addProperty("on", "right_click");
        JsonArray blockedConditions = new JsonArray();
        blockedConditions.add(ordinaryUse);
        JsonObject notTestFlag = new JsonObject();
        notTestFlag.addProperty("type", "!test_flag");
        notTestFlag.addProperty("flag", "interact");
        blockedConditions.add(notTestFlag);
        blockedEvent.add("conditions", blockedConditions);
        JsonArray blockedFunctions = new JsonArray();
        JsonObject updateInteraction2 = new JsonObject();
        updateInteraction2.addProperty("type", "update_interaction_tick");
        blockedFunctions.add(updateInteraction2);
        JsonObject cancelEvent2 = new JsonObject();
        cancelEvent2.addProperty("type", "cancel_event");
        blockedFunctions.add(cancelEvent2);
        blockedEvent.add("functions", blockedFunctions);
        events.add(blockedEvent);
        return events;
    }


    private void validateConfigItems(JsonObject items, JsonObject renderItems, JsonObject blocks,
                                     JsonObject furniture) throws IOException {
        JsonArray grapevineBehaviors = items.getAsJsonObject(NAMESPACE + ":grapevine")
                .getAsJsonArray("behaviors");
        if (grapevineBehaviors.size() != 3
                || !grapevineBehaviors.get(0).getAsJsonObject().get("type").getAsString()
                        .equals(NAMESPACE + ":grapevine_item")
                || !grapevineBehaviors.get(1).getAsJsonObject().get("type").getAsString()
                        .equals("block_item")
                || !grapevineBehaviors.get(2).getAsJsonObject().get("type").getAsString()
                        .equals("compostable_item")) {
            throw new ValidationException("Grapevine must run its CE trellis interaction "
                    + "before wild placement and composting");
        }
        if (!grapevineBehaviors.get(1).getAsJsonObject().get("block").getAsString()
                .equals(NAMESPACE + ":wild_grapevine")) {
            throw new ValidationException("Grapevine's CE block-item fallback must place wild_grapevine");
        }
        JsonArray shakerBehaviors = items.getAsJsonObject(NAMESPACE + ":shaker")
                .getAsJsonArray("behaviors");
        if (shakerBehaviors.size() != 2
                || !shakerBehaviors.get(0).getAsJsonObject().get("type").getAsString()
                        .equals(NAMESPACE + ":shaker_item")
                || !shakerBehaviors.get(1).getAsJsonObject().get("type").getAsString()
                        .equals(NAMESPACE + ":sneak_place_drink")
                || !shakerBehaviors.get(1).getAsJsonObject().get("furniture").getAsString()
                        .equals(NAMESPACE + ":shaker")) {
            throw new ValidationException("Shaker must keep only its portable-use callback "
                    + "and delegate sneak placement through the generic CE furniture adapter");
        }
        JsonObject shakerRules = shakerBehaviors.get(1).getAsJsonObject().getAsJsonObject("rules");
        JsonObject expectedShakerRule = new JsonObject();
        expectedShakerRule.addProperty("rotation", "four");
        expectedShakerRule.addProperty("alignment", "center");
        JsonObject ground = new JsonObject();
        ground.add("ground", expectedShakerRule);
        if (!ground.equals(shakerRules)) {
            throw new ValidationException("Shaker sneak-placement rule drifted");
        }
        String tapId = NAMESPACE + ":tap";
        JsonObject tapItemBehavior = new JsonObject();
        tapItemBehavior.addProperty("type", "block_item");
        tapItemBehavior.addProperty("block", tapId);
        if (!tapItemBehavior.equals(items.getAsJsonObject(tapId).get("behavior"))) {
            throw new ValidationException("Tap installation must be owned by CE's native block_item behavior");
        }
        if (furniture.has(tapId)) {
            throw new ValidationException("Tap must not retain a duplicate CE furniture definition");
        }
        JsonObject tapBlock = blocks.getAsJsonObject(tapId);
        JsonObject tapStates = tapBlock.getAsJsonObject("states");
        JsonObject tapProperties = tapStates.getAsJsonObject("properties");
        if (!tapProperties.keySet().equals(Set.of("facing", "open", "triggered", "waterlogged"))) {
            throw new ValidationException("Tap CE block properties drifted: " + tapProperties.keySet());
        }
        JsonObject tapBehavior = new JsonObject();
        tapBehavior.addProperty("type", NAMESPACE + ":tap");
        if (!tapBehavior.equals(tapBlock.get("behavior"))) {
            throw new ValidationException("Tap must use the Tavern CE block behavior");
        }
        JsonObject tapAppearances = tapStates.getAsJsonObject("appearances");
        JsonObject tapVariants = tapStates.getAsJsonObject("variants");
        if (tapAppearances.size() != 16 || tapVariants.size() != 32) {
            throw new ValidationException("Tap needs 4 facing x 2 open x 2 waterlogged appearances "
                    + "and 32 complete states; found " + tapAppearances.size() + "/" + tapVariants.size());
        }
        Set<String> tapRenderItems = new LinkedHashSet<>();
        for (var variantEntry : tapVariants.entrySet()) {
            String[] props = new String[2];
            for (String part : variantEntry.getKey().split(",")) {
                String[] pair = part.split("=", 2);
                if (pair[0].equals("facing")) props[0] = pair[1];
                else if (pair[0].equals("waterlogged")) props[1] = pair[1];
            }
            JsonObject mapped = variantEntry.getValue().getAsJsonObject();
            JsonObject appearance = tapAppearances.getAsJsonObject(
                    mapped.get("appearance").getAsString());
            String expectedCarrier = "minecraft:lightning_rod[facing=" + props[0]
                    + ",powered=false,waterlogged=" + props[1] + "]";
            if (!appearance.get("state").getAsString().equals(expectedCarrier)) {
                throw new ValidationException("Tap " + variantEntry.getKey()
                        + " carrier must preserve facing/waterlogging");
            }
            if (props[1].equals("true")) {
                JsonObject expectedSettings = new JsonObject();
                expectedSettings.addProperty("fluid_state", "water");
                if (!expectedSettings.equals(mapped.get("settings"))) {
                    throw new ValidationException("Tap " + variantEntry.getKey()
                            + " must preserve CE's server-side fluid state");
                }
            } else if (mapped.has("settings") && !mapped.get("settings").isJsonNull()) {
                throw new ValidationException("Tap " + variantEntry.getKey()
                        + " must preserve CE's server-side fluid state");
            }
            JsonObject renderer = appearance.getAsJsonObject("entity_renderer");
            String renderItem = renderer.get("item").getAsString();
            tapRenderItems.add(renderItem);
            String openValue = null;
            for (String part : variantEntry.getKey().split(",")) {
                String[] pair = part.split("=", 2);
                if (pair[0].equals("open")) openValue = pair[1];
            }
            String expectedModel = openValue.equals("true")
                    ? NAMESPACE + ":block/brew/tap/open" : NAMESPACE + ":block/brew/tap/close";
            if (!renderer.get("type").getAsString().equals("item_display")
                    || !renderItems.getAsJsonObject(renderItem).getAsJsonObject("model")
                            .get("path").getAsString().equals(expectedModel)) {
                throw new ValidationException("Tap " + variantEntry.getKey()
                        + " must render its authored open/closed model");
            }
            JsonElement actualRotation = renderer.get("rotation");
            String expectedRotation = switch (props[0]) {
                case "north" -> "0,180,0";
                case "east" -> "0,90,0";
                case "west" -> "0,270,0";
                default -> null;
            };
            if ((expectedRotation == null) ? actualRotation != null && !actualRotation.isJsonNull()
                    : actualRotation == null || actualRotation.isJsonNull()
                        || !actualRotation.getAsString().equals(expectedRotation)) {
                throw new ValidationException("Tap " + variantEntry.getKey()
                        + " north/south visual mapping drifted");
            }
        }
        if (tapRenderItems.size() != 2) {
            throw new ValidationException("Tap must reuse exactly two private render items, found "
                    + tapRenderItems);
        }
        for (String facing : List.of("north", "south", "west", "east")) {
            for (String waterlogged : List.of("false", "true")) {
                String closedKey = "facing=" + facing + ",open=false,triggered=false,waterlogged=" + waterlogged;
                String openKey = "facing=" + facing + ",open=true,triggered=false,waterlogged=" + waterlogged;
                JsonObject closed = tapAppearances.getAsJsonObject(
                        tapVariants.getAsJsonObject(closedKey).get("appearance").getAsString());
                JsonObject opened = tapAppearances.getAsJsonObject(
                        tapVariants.getAsJsonObject(openKey).get("appearance").getAsString());
                if (!closed.get("state").getAsString().equals(opened.get("state").getAsString())) {
                    throw new ValidationException("Tap open/closed collision carrier changed for "
                            + "facing=" + facing + ",triggered=false,waterlogged=" + waterlogged);
                }
                for (String openValue : List.of("false", "true")) {
                    String untriggered = "facing=" + facing + ",open=" + openValue
                            + ",triggered=false,waterlogged=" + waterlogged;
                    String triggered = untriggered.replace("triggered=false", "triggered=true");
                    if (!tapVariants.get(untriggered).equals(tapVariants.get(triggered))) {
                        throw new ValidationException("Tap triggered is a server edge latch "
                                + "and must not change rendering");
                    }
                }
            }
        }
        JsonObject tapSettings = tapBlock.getAsJsonObject("settings");
        JsonArray expectedTapTags = new JsonArray();
        expectedTapTags.add("minecraft:mineable/pickaxe");
        if (tapSettings.get("hardness").getAsDouble() != 0.8
                || !tapSettings.get("push_reaction").getAsString().equals("NORMAL")
                || !expectedTapTags.equals(tapSettings.getAsJsonArray("tags"))) {
            throw new ValidationException("Tap CE block must retain the source metal settings");
        }
        JsonObject tapSounds = tapSettings.getAsJsonObject("sounds");
        for (String action : List.of("break", "step", "place", "hit", "fall")) {
            if (!tapSounds.get(action).getAsString().equals("minecraft:block.metal." + action)) {
                throw new ValidationException("Tap CE block must retain the source metal settings");
            }
        }
        for (var variantEntry : furniture.getAsJsonObject(NAMESPACE + ":barrel")
                .getAsJsonObject("variants").entrySet()) {
            for (JsonElement rawHitbox : variantEntry.getValue().getAsJsonObject()
                    .getAsJsonArray("hitboxes")) {
                JsonObject hitbox = rawHitbox.getAsJsonObject();
                if (!hitbox.has("can_use_item_on")
                        || !hitbox.get("can_use_item_on").getAsBoolean()) {
                    throw new ValidationException("Barrel " + variantEntry.getKey()
                            + " hitboxes must allow CE furniture-item placement");
                }
            }
        }
        String tapDefaultText = readText(projectRoot.resolve("src/paper/resources/visuals/tap.yml"));
        for (String token : List.of("water: \"water\"", "lava: \"lava\"", "honey: \"honey\"",
                "dragon-breath: \"obsidian-tear\"",
                "TODO: 等 Minecraft/Paper 提供原生红色或可着色的滴落液体粒子后",
                "watermelon: \"water\"")) {
            if (!tapDefaultText.contains(token)) {
                throw new ValidationException("Bundled tap.yml is missing direct output " + token);
            }
        }
        Path shakerTextureRoot = packAssetsRoot.resolve(
                NAMESPACE + "/textures/font/shaker");
        Map<String, int[]> expectedShakerSizes = new LinkedHashMap<>();
        expectedShakerSizes.put("bar.png", new int[] {181, 18});
        expectedShakerSizes.put("pointer.png", new int[] {11, 14});
        for (var sizeEntry : expectedShakerSizes.entrySet()) {
            int[] actual = pngDimensions(shakerTextureRoot.resolve(sizeEntry.getKey()));
            if (actual[0] != sizeEntry.getValue()[0] || actual[1] != sizeEntry.getValue()[1]) {
                throw new ValidationException("Shaker HUD " + sizeEntry.getKey()
                        + " must preserve source pixels via even-height padding; expected "
                        + sizeEntry.getValue()[0] + "x" + sizeEntry.getValue()[1] + ", got "
                        + actual[0] + "x" + actual[1]);
            }
        }
        validatePlaceableCoverage(blocks, furniture);
    }

    private static int[] pngDimensions(Path path) throws IOException {
        byte[] head = Files.readAllBytes(path);
        if (head.length < 24 || head[0] != (byte) 0x89 || head[1] != 0x50) {
            throw new ValidationException(path + ": not a PNG file");
        }
        return new int[] {
                ((head[16] & 0xFF) << 24) | ((head[17] & 0xFF) << 16)
                        | ((head[18] & 0xFF) << 8) | (head[19] & 0xFF),
                ((head[20] & 0xFF) << 24) | ((head[21] & 0xFF) << 16)
                        | ((head[22] & 0xFF) << 8) | (head[23] & 0xFF)};
    }

    private void validatePlaceableCoverage(JsonObject blocks, JsonObject furniture) throws IOException {
        Set<String> derivedCropStages = new LinkedHashSet<>();
        for (String blockId : blocks.keySet()) {
            if (blockId.startsWith(NAMESPACE + ":_crop/")) derivedCropStages.add(blockId);
        }
        Set<String> represented = new LinkedHashSet<>();
        for (String blockId : blocks.keySet()) {
            if (!derivedCropStages.contains(blockId) && !blockId.equals(SHARED_SOFA_ID)) {
                represented.add(blockId);
            }
        }
        for (String furnitureId : furniture.keySet()) {
            if (!furnitureId.equals(WALL_PRESSING_TUB_ID)) represented.add(furnitureId);
        }
        for (String color : FURNITURE_COLORS) {
            represented.add(NAMESPACE + ":" + color + "_sofa");
        }
        Set<String> sourcePlaceables = new LinkedHashSet<>();
        String modBlocks = readText(projectRoot.resolve("src/main/java/com/github/ysbbbbbb/"
                + "kaleidoscopetavern/init/ModBlocks.java"));
        Pattern register = Pattern.compile("BLOCKS\\.register\\(\"([a-z0-9_]+)\"");
        Matcher matcher = register.matcher(modBlocks);
        while (matcher.find()) {
            sourcePlaceables.add(NAMESPACE + ":" + matcher.group(1));
        }
        if (!represented.equals(sourcePlaceables)) {
            Set<String> missing = new LinkedHashSet<>(sourcePlaceables);
            missing.removeAll(represented);
            Set<String> unexpected = new LinkedHashSet<>(represented);
            unexpected.removeAll(sourcePlaceables);
            throw new ValidationException("Source-to-CE placeable coverage drift: missing="
                    + missing + ", unexpected=" + unexpected);
        }
    }


    private static final List<String> SIMPLE_BOTTLES = List.of(
            "water_bottle", "honey_bottle", "dragon_breath_bottle",
            "potion_bottle", "xp_bottle");
    private static final Set<String> EXPECTED_CONSUMABLE_COCKTAILS = Set.of(
            "signature_cocktail", "mystery_cocktail", "white_lady", "emerald",
            "brass_heart", "godfather", "grasshopper", "screwdriver", "mojito",
            "allium_garden", "depth_charge", "nether_special", "bloody_mary",
            "sculk_special");
    private static final Set<String> EXPECTED_ROTATION_16_VESSELS = new LinkedHashSet<>();
    private static final Set<String> EXPECTED_DIRECTIONLESS_VESSELS = Set.of("molotov");
    private static final List<String> CUSTOM_EFFECT_ICON_IDS = List.of(
            "slightly_tipsy", "high_heels", "grass_stealth", "vision",
            "bloody_mary", "ardent_heat", "long_reach", "tomb_raider",
            "xp_drain", "upside_down", "zenith", "shriek_attack");
    static {
        EXPECTED_ROTATION_16_VESSELS.addAll(EXPECTED_CONSUMABLE_COCKTAILS);
        EXPECTED_ROTATION_16_VESSELS.add("empty_glassware");
        EXPECTED_ROTATION_16_VESSELS.addAll(SIMPLE_BOTTLES);
    }

    private static String vesselRotation(String itemId) {
        String path = itemId.substring(itemId.indexOf(':') + 1);
        if (EXPECTED_ROTATION_16_VESSELS.contains(path)) return "sixteen";
        if (EXPECTED_DIRECTIONLESS_VESSELS.contains(path)) return "north";
        return "four";
    }

    private void validateRemaining(JsonObject items, JsonObject renderItems, JsonObject blocks,
                                   JsonObject furniture) throws IOException {
        Set<String> geometryless = new LinkedHashSet<>();
        Path generatedAssets = projectRoot.resolve("src/generated/resources/assets");
        Path mainAssets = projectRoot.resolve("src/main/resources/assets");
        String modBlocks = readText(projectRoot.resolve("src/main/java/com/github/ysbbbbbb/"
                + "kaleidoscopetavern/init/ModBlocks.java"));
        Matcher idMatcher = Pattern.compile("BLOCKS\\.register\\(\"([a-z0-9_]+)\"")
                .matcher(modBlocks);
        while (idMatcher.find()) {
            String blockId = idMatcher.group(1);
            JsonObject blockstate = assetJson(NAMESPACE + ":" + blockId, "blockstates");
            if (blockstate == null) {
                throw new ValidationException("Source block " + blockId + " has no blockstate");
            }
            Set<String> references = new LinkedHashSet<>();
            if (blockstate.has("variants")) {
                for (JsonElement raw : blockstate.getAsJsonObject("variants").asMap().values()) {
                    collectModelPaths(raw, references);
                }
            }
            if (blockstate.has("multipart")) {
                for (JsonElement part : blockstate.getAsJsonArray("multipart")) {
                    if (part.isJsonObject() && part.getAsJsonObject().has("apply")) {
                        collectModelPaths(part.getAsJsonObject().get("apply"), references);
                    }
                }
            }
            if (!references.isEmpty()) {
                boolean anyGeometry = false;
                for (String model : references) {
                    if (modelHasGeometry(model, new LinkedHashSet<>())) {
                        anyGeometry = true;
                        break;
                    }
                }
                if (!anyGeometry) geometryless.add(blockId);
            }
        }
        Set<String> expectedGeometryless = Set.of("barrel", "chalkboard", "shaker");
        if (!geometryless.equals(expectedGeometryless)) {
            throw new ValidationException("Source particle-only model set changed: found="
                    + geometryless);
        }
        for (String blockId : expectedGeometryless) {
            if (blockId.equals("chalkboard")) continue;
            JsonObject definition = furniture.getAsJsonObject(NAMESPACE + ":" + blockId);
            for (var variantEntry : definition.getAsJsonObject("variants").entrySet()) {
                JsonObject variant = variantEntry.getValue().getAsJsonObject();
                if (!variant.has("elements") || variant.getAsJsonArray("elements").isEmpty()) {
                    throw new ValidationException(blockId + "/" + variantEntry.getKey()
                            + ": particle-only source has no replacement");
                }
                String renderId = variant.getAsJsonArray("elements").get(0).getAsJsonObject()
                        .get("item").getAsString();
                String model = renderItems.getAsJsonObject(renderId).getAsJsonObject("model")
                        .get("path").getAsString();
                if (blockId.equals("shaker")) {
                    if (!model.equals(NAMESPACE + ":block/mixology/shaker")) {
                        throw new ValidationException(
                                "Shaker CE anchor must remain the invisible source block model");
                    }
                    continue;
                }
                JsonObject parsed = assetJson(model, "models");
                if (parsed == null || !parsed.has("elements")
                        || parsed.getAsJsonArray("elements").isEmpty()) {
                    throw new ValidationException(blockId + "/" + variantEntry.getKey()
                            + ": particle-only source still maps to invisible model " + model);
                }
            }
        }
        JsonObject stepladder = furniture.getAsJsonObject(NAMESPACE + ":stepladder");
        JsonObject stepladderVariants = stepladder.getAsJsonObject("variants");
        if (!stepladderVariants.keySet().equals(Set.of("ground"))) {
            throw new ValidationException("Stepladder must expose only its ground variant, found "
                    + stepladderVariants.keySet());
        }
        JsonObject stepladderGround = stepladderVariants.getAsJsonObject("ground");
        JsonArray stepladderElements = stepladderGround.getAsJsonArray("elements");
        if (stepladderElements.size() != 2) {
            throw new ValidationException("Stepladder must keep exactly two ItemDisplay halves");
        }
        for (JsonElement raw : stepladderElements) {
            if (!raw.getAsJsonObject().get("type").getAsString().equals("item_display")) {
                throw new ValidationException("Stepladder must keep exactly two ItemDisplay halves");
            }
        }
        JsonArray stepladderHitboxes = stepladderGround.getAsJsonArray("hitboxes");
        if (stepladderHitboxes.size() != 4) {
            throw new ValidationException("Stepladder must use four physical shulker hitboxes");
        }
        Set<String> actualStepladder = new LinkedHashSet<>();
        for (JsonElement raw : stepladderHitboxes) {
            JsonObject h = raw.getAsJsonObject();
            actualStepladder.add(String.join("|",
                    h.get("position").getAsString(),
                    h.has("scale") ? String.valueOf(h.get("scale").getAsDouble()) : "1.0",
                    h.has("peek") ? String.valueOf(h.get("peek").getAsInt()) : "0",
                    h.has("direction") ? h.get("direction").getAsString() : "up",
                    String.valueOf(h.has("blocks_building") && h.get("blocks_building").getAsBoolean()),
                    String.valueOf(h.has("invisible") && h.get("invisible").getAsBoolean())));
        }
        Set<String> expectedStepladder = Set.of(
                "0,0,0|0.75|0|up|true|false",
                "0,0.75,-0.25|0.625|25|north|false|false",
                "-0.25,1.5,-0.25|0.4|35|up|false|false",
                "0.25,1.5,-0.25|0.4|35|up|false|false");
        if (!actualStepladder.equals(expectedStepladder)) {
            throw new ValidationException("Stepladder hitboxes must retain the server-tested "
                    + "compact layout: found=" + actualStepladder);
        }
        for (String blockId : List.of("trellis", "grapevine_trellis",
                "ice_grapevine_trellis", "gold_grapevine_trellis")) {
            String fullId = NAMESPACE + ":" + blockId;
            JsonObject definition = blocks.getAsJsonObject(fullId);
            JsonObject states = definition.getAsJsonObject("states");
            JsonObject expectedAxis = new JsonObject();
            expectedAxis.addProperty("type", "axis");
            expectedAxis.addProperty("default", "y");
            JsonArray axisValues = new JsonArray();
            axisValues.add("x"); axisValues.add("y"); axisValues.add("z");
            expectedAxis.add("values", axisValues);
            if (!expectedAxis.equals(states.getAsJsonObject("properties").get("axis"))) {
                throw new ValidationException(
                        blockId + ": CE must own clicked-face placement through native axis state");
            }
            JsonObject appearances = states.getAsJsonObject("appearances");
            for (var appearanceEntry : appearances.entrySet()) {
                JsonObject appearance = appearanceEntry.getValue().getAsJsonObject();
                if (!appearance.getAsJsonObject("entity_renderer").get("type").getAsString()
                        .equals("item_display")) {
                    throw new ValidationException(
                            blockId + "/" + appearanceEntry.getKey()
                            + " must keep its authored item-display model");
                }
                String state = appearance.get("state").getAsString();
                if (!state.startsWith("minecraft:lightning_rod[")) {
                    throw new ValidationException(blockId + "/" + appearanceEntry.getKey()
                            + ": every trellis shape needs a colliding lightning-rod carrier");
                }
                if (!state.contains("powered=false") || !state.contains("waterlogged=")) {
                    throw new ValidationException(blockId + "/" + appearanceEntry.getKey()
                            + ": trellis carrier must remain unpowered and water-aware");
                }
            }
            for (var variantEntry : states.getAsJsonObject("variants").entrySet()) {
                String waterlogged = null;
                for (String part : variantEntry.getKey().split(",")) {
                    String[] pair = part.split("=", 2);
                    if (pair[0].equals("waterlogged")) waterlogged = pair[1];
                }
                JsonObject appearance = appearances.getAsJsonObject(variantEntry.getValue()
                        .getAsJsonObject().get("appearance").getAsString());
                if (!appearance.get("state").getAsString().contains("waterlogged=" + waterlogged)) {
                    throw new ValidationException(blockId + "/" + variantEntry.getKey()
                            + ": carrier lost its waterlogged state");
                }
                JsonElement settings = variantEntry.getValue().getAsJsonObject().get("settings");
                if (waterlogged.equals("true")) {
                    JsonObject expectedSettings = new JsonObject();
                    expectedSettings.addProperty("fluid_state", "water");
                    if (!expectedSettings.equals(settings)) {
                        throw new ValidationException(blockId + "/" + variantEntry.getKey()
                                + ": CE fluid state does not match waterlogged");
                    }
                } else if (settings != null && !settings.isJsonNull()) {
                    throw new ValidationException(blockId + "/" + variantEntry.getKey()
                            + ": CE fluid state does not match waterlogged");
                }
            }
        }
        int collidableTrellises = 0;
        for (String blockId : List.of("trellis", "grapevine_trellis",
                "ice_grapevine_trellis", "gold_grapevine_trellis")) {
            collidableTrellises += blocks.getAsJsonObject(NAMESPACE + ":" + blockId)
                    .getAsJsonObject("states").getAsJsonObject("appearances").size();
        }
        if (collidableTrellises != 74) {
            throw new ValidationException("Expected 74 dry/waterlogged trellis appearances, found "
                    + collidableTrellises);
        }
        for (String blockId : List.of("trellis", "grapevine_trellis",
                "ice_grapevine_trellis", "gold_grapevine_trellis")) {
            JsonObject settings = blocks.getAsJsonObject(NAMESPACE + ":" + blockId)
                    .getAsJsonObject("settings");
            if (settings.get("hardness").getAsDouble() != 0.8
                    || settings.get("resistance").getAsDouble() != 0.8
                    || !settings.getAsJsonObject("sounds").get("break").getAsString()
                            .equals("minecraft:block.wood.break")) {
                throw new ValidationException(blockId + ": source trellis hardness must remain 0.8");
            }
        }
        if (!blocks.getAsJsonObject(NAMESPACE + ":trellis").getAsJsonObject("settings")
                .get("push_reaction").getAsString().equals("NORMAL")) {
            throw new ValidationException("Plain trellis must retain the source default piston reaction");
        }
        for (String blockId : List.of("grapevine_trellis", "ice_grapevine_trellis",
                "gold_grapevine_trellis")) {
            JsonObject expectedBehavior = new JsonObject();
            expectedBehavior.addProperty("type", NAMESPACE + ":trellis");
            expectedBehavior.addProperty("spread_chance", 0.25);
            if (!expectedBehavior.equals(blocks.getAsJsonObject(NAMESPACE + ":" + blockId)
                    .get("behavior"))) {
                throw new ValidationException(blockId
                        + ": growth must have one source-compatible owner");
            }
        }
        JsonObject wildBehavior = new JsonObject();
        wildBehavior.addProperty("type", NAMESPACE + ":wild_grapevine");
        wildBehavior.addProperty("body", NAMESPACE + ":wild_grapevine_plant");
        wildBehavior.addProperty("direction", "down");
        wildBehavior.addProperty("grow_speed", 0.15);
        if (!wildBehavior.equals(blocks.getAsJsonObject(NAMESPACE + ":wild_grapevine")
                .get("behavior"))) {
            throw new ValidationException(
                    "Wild grapevine must wrap CE lifecycle and shearing in one behavior");
        }
        JsonObject wildBodyBehavior = new JsonObject();
        wildBodyBehavior.addProperty("type", NAMESPACE + ":wild_grapevine");
        wildBodyBehavior.addProperty("head", NAMESPACE + ":wild_grapevine");
        wildBodyBehavior.addProperty("direction", "down");
        JsonObject boneMeal = new JsonObject();
        boneMeal.addProperty("behavior", "grow");
        boneMeal.addProperty("grow_blocks", 1);
        wildBodyBehavior.add("bone_meal", boneMeal);
        if (!wildBodyBehavior.equals(blocks.getAsJsonObject(NAMESPACE + ":wild_grapevine_plant")
                .get("behavior"))) {
            throw new ValidationException("Wild grapevine body must delegate native bone meal to its head");
        }
        JsonObject wildHead = blocks.getAsJsonObject(NAMESPACE + ":wild_grapevine");
        if (wildHead.getAsJsonObject("behavior").has("max_height")) {
            throw new ValidationException("Wild grapevine must not retain the invented 16-block growth cap");
        }
        JsonObject wildHeadAppearances = wildHead.getAsJsonObject("states")
                .getAsJsonObject("appearances");
        if (wildHeadAppearances.size() != 1) {
            throw new ValidationException("Wild grapevine head must keep one shared authored appearance");
        }
        JsonElement wildHeadAppearance = wildHeadAppearances.asMap().values().iterator().next();
        JsonElement wildBodyAppearance = blocks.getAsJsonObject(NAMESPACE + ":wild_grapevine_plant")
                .get("state");
        JsonObject expectedWildCarrier = new JsonObject();
        expectedWildCarrier.addProperty("type", "cave_vines");
        expectedWildCarrier.addProperty("id", "kaleidoscope-tavern-wild-grapevine-transparent");
        for (JsonElement appearance : List.of(wildHeadAppearance, wildBodyAppearance)) {
            JsonObject a = appearance.getAsJsonObject();
            if (!expectedWildCarrier.equals(a.get("auto_state"))
                    || a.has("state")
                    || !a.get("transparent").getAsBoolean()
                    || !renderItems.has(a.getAsJsonObject("entity_renderer").get("item").getAsString())) {
                throw new ValidationException(
                        "Wild grapevine must share CE's cave-vines auto-state carrier");
            }
            if (a.toString().contains("weeping_vines")) {
                throw new ValidationException(
                        "Wild grapevine must not reserve vanilla weeping-vine texture states");
            }
        }
        for (String crop : List.of("grape_crop", "ice_grape_crop", "gold_grape_crop")) {
            for (int point = 0; point < 6; point++) {
                String cropId = point == 0 ? NAMESPACE + ":" + crop
                        : NAMESPACE + ":_crop/" + crop + "/stage_" + point;
                JsonObject appearance = blocks.getAsJsonObject(cropId).getAsJsonObject("state");
                if (!expectedWildCarrier.equals(appearance.get("auto_state"))
                        || appearance.has("state")
                        || !appearance.get("transparent").getAsBoolean()
                        || !renderItems.has(appearance.getAsJsonObject("entity_renderer")
                                .get("item").getAsString())) {
                    throw new ValidationException(
                            cropId + ": hanging crop must share CE's cave-vines carrier");
                }
            }
        }
        JsonObject wildSettings = wildHead.getAsJsonObject("settings");
        if (wildSettings.get("hardness").getAsDouble() != 0
                || wildSettings.get("resistance").getAsDouble() != 0
                || !wildSettings.getAsJsonObject("sounds").get("break").getAsString()
                        .equals("minecraft:block.cave_vines.break")) {
            throw new ValidationException(
                    "Wild grapevine must retain instant break and cave-vine sounds");
        }
        JsonObject enUs = readJson(projectRoot.resolve("src/main/resources/assets/"
                + NAMESPACE + "/lang/en_us.json"));
        JsonObject zhCn = readJson(projectRoot.resolve("src/main/resources/assets/"
                + NAMESPACE + "/lang/zh_cn.json"));
        Set<String> languageKeys = enUs.keySet();
        Set<String> chineseKeys = zhCn.keySet();
        for (JsonElement value : zhCn.asMap().values()) {
            if (value.isJsonPrimitive() && value.getAsString().contains("\uFFFD")) {
                throw new ValidationException("zh_cn.json contains a Unicode replacement character");
            }
        }
        for (var itemEntry : items.entrySet()) {
            String fullItemId = itemEntry.getKey();
            if (!fullItemId.startsWith(NAMESPACE + ":")) continue;
            String itemId = fullItemId.substring(NAMESPACE.length() + 1);
            JsonObject item = itemEntry.getValue().getAsJsonObject();
            String rawName = item.has("data") && item.getAsJsonObject("data").has("item_name")
                    ? item.getAsJsonObject("data").get("item_name").getAsString() : "";
            Matcher langMatcher = Pattern.compile("^<!i><lang:([^>]+)>$").matcher(rawName);
            if (!langMatcher.matches()) {
                throw new ValidationException(fullItemId + ": malformed translatable item_name " + rawName);
            }
            String actualKey = langMatcher.group(1);
            if (!languageKeys.contains(actualKey)) {
                throw new ValidationException(fullItemId + ": missing item-name translation " + actualKey);
            }
            if (!chineseKeys.contains(actualKey)) {
                throw new ValidationException(fullItemId + ": missing Chinese item-name translation " + actualKey);
            }
            String expectedKey;
            if (itemId.endsWith("_sandwich_board")) {
                expectedKey = "block." + NAMESPACE + ".sandwich_board";
            } else if (itemId.endsWith("_painting")) {
                expectedKey = "block." + NAMESPACE + ".painting";
                JsonArray lore = item.getAsJsonObject("data").getAsJsonArray("lore");
                String tooltip = "<!i><gray><lang:tooltip." + NAMESPACE + "." + itemId + ">";
                boolean found = false;
                for (JsonElement line : lore) {
                    if (line.getAsString().contains(tooltip)) found = true;
                }
                if (!found) {
                    throw new ValidationException(fullItemId + ": painting variant tooltip is missing");
                }
            } else {
                String blockKey = "block." + NAMESPACE + "." + itemId;
                String itemKey = "item." + NAMESPACE + "." + itemId;
                expectedKey = languageKeys.contains(blockKey) ? blockKey : itemKey;
            }
            if (!actualKey.equals(expectedKey)) {
                throw new ValidationException(fullItemId + ": expected source description id "
                        + expectedKey + ", found " + actualKey);
            }
        }
    }

    private boolean modelHasGeometry(String resourceId, Set<String> seen) throws IOException {
        if (seen.contains(resourceId)) return false;
        JsonObject model = assetJson(resourceId, "models");
        if (model == null) {
            return resourceId.startsWith("minecraft:");
        }
        if (model.has("elements") && model.getAsJsonArray("elements").size() > 0) {
            return true;
        }
        if (!model.has("parent")) return false;
        seen.add(resourceId);
        return modelHasGeometry(model.get("parent").getAsString(), seen);
    }

    private static void collectModelPaths(JsonElement value, Set<String> out) {
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) collectModelPaths(child, out);
        } else if (value.isJsonObject()) {
            JsonElement model = value.getAsJsonObject().get("model");
            if (model != null && model.isJsonPrimitive()) out.add(model.getAsString());
        }
    }


    private static final Set<String> EFFECTLESS_DRINKS = Set.of(NAMESPACE + ":watermelon_juice");
    private static final int SHAKER_USE_FRAMES = 16;
    private static final double SHAKER_USE_PERIOD_TICKS = Math.PI * 2 / 1.5;

    private static double[] shakerXTransform(double rotationDegrees, double translationY) {
        double angle = Math.toRadians(rotationDegrees);
        double cosine = Math.round(Math.cos(angle) * 1e8) / 1e8;
        double sine = Math.round(Math.sin(angle) * 1e8) / 1e8;
        return new double[] {1.0, 0.0, 0.0, 0.0,
                0.0, cosine, -sine, Math.round(translationY * 1e8) / 1e8,
                0.0, sine, cosine, 0.0,
                0.0, 0.0, 0.0, 1.0};
    }

    /**
     * First-person: the use_cycle range dispatch tilts the shaker -15°
     * about X and bobs it ±0.15 blocks, mirroring ShakerAnimation's
     * applyForgeHandTransform while the shaker is being shaken. The 26.2
     * client applies the transformation after the display transform.
     */
    private static JsonObject expectedShakerUseCycle() {
        JsonArray entries = new JsonArray();
        for (int index = 0; index < SHAKER_USE_FRAMES; index++) {
            double cycle = SHAKER_USE_PERIOD_TICKS * index / SHAKER_USE_FRAMES;
            double wave = Math.sin(-cycle * 1.5);
            JsonObject entry = new JsonObject();
            entry.addProperty("threshold", Math.round(cycle * 1e6) / 1e6);
            JsonObject model = new JsonObject();
            model.addProperty("type", "minecraft:model");
            model.addProperty("path", NAMESPACE + ":item/shaker_3d");
            JsonArray array = new JsonArray();
            for (double v : shakerXTransform(-15.0, -wave * 0.15)) array.add(v);
            model.add("transformation", array);
            entry.add("model", model);
            entries.add(entry);
        }
        JsonObject range = new JsonObject();
        range.addProperty("type", "minecraft:range_dispatch");
        range.addProperty("property", "use_cycle");
        range.addProperty("source", Math.round(SHAKER_USE_PERIOD_TICKS * 1e6) / 1e6);
        range.add("entries", entries);
        JsonObject fallback = entries.get(0).getAsJsonObject().getAsJsonObject("model").deepCopy();
        range.add("fallback", fallback);
        return range;
    }

    private void validateDrinks(JsonObject items, JsonObject renderItems, JsonObject blocks,
                                JsonObject furniture) throws IOException {
        Path catalogs = projectRoot.resolve("src/paper/resources/catalog");
        List<String[]> effectRows = tsvRows(catalogs.resolve("drink-effects.tsv"));
        if (effectRows.stream().map(row -> row[0]).distinct().count() != 37) {
            throw new ValidationException("Expected drink effects for 37 items");
        }
        Set<String> effectDrinkIds = new LinkedHashSet<>();
        for (String[] row : effectRows) effectDrinkIds.add(row[0]);
        Set<String> unexpectedEffects = new LinkedHashSet<>(effectDrinkIds);
        unexpectedEffects.retainAll(EFFECTLESS_DRINKS);
        if (!unexpectedEffects.isEmpty()) {
            throw new ValidationException(
                    "Effectless drinks unexpectedly declare drink effects: " + unexpectedEffects);
        }
        Set<String> drinkIds = new LinkedHashSet<>(effectDrinkIds);
        drinkIds.addAll(EFFECTLESS_DRINKS);
        drinkIds.add(NAMESPACE + ":signature_cocktail");
        JsonObject expectedConsumable = new JsonObject();
        expectedConsumable.addProperty("consume_seconds", 1.6);
        expectedConsumable.addProperty("animation", "drink");
        expectedConsumable.addProperty("sound", "minecraft:entity.generic.drink");
        expectedConsumable.addProperty("has_consume_particles", false);
        for (String itemId : drinkIds) {
            JsonObject item = items.getAsJsonObject(itemId);
            List<JsonElement> itemBehaviors = behaviorsOf(item);
            Set<String> behaviorTypes = new LinkedHashSet<>();
            for (JsonElement behavior : itemBehaviors) {
                behaviorTypes.add(behavior.getAsJsonObject().get("type").getAsString());
            }
            if (!item.get("material").getAsString().equals("potion")
                    || !behaviorTypes.equals(Set.of(NAMESPACE + ":sneak_place_drink"))) {
                throw new ValidationException(itemId
                        + ": drinks must remain consumable items with CE-owned sneak placement");
            }
            JsonObject expectedPlacement = new JsonObject();
            expectedPlacement.addProperty("type", NAMESPACE + ":sneak_place_drink");
            expectedPlacement.addProperty("furniture", itemId);
            JsonObject ground = new JsonObject();
            JsonObject groundRule = new JsonObject();
            groundRule.addProperty("rotation", vesselRotation(itemId));
            groundRule.addProperty("alignment", "center");
            ground.add("ground", groundRule);
            expectedPlacement.add("rules", ground);
            if (!itemBehaviors.equals(List.of(expectedPlacement))) {
                throw new ValidationException(itemId
                        + ": CE drink placement target/rules drifted from the released "
                        + "vessel placement semantics");
            }
            JsonObject components = item.getAsJsonObject("data").getAsJsonObject("components");
            if (components.get("minecraft:max_stack_size").getAsInt() != 16) {
                throw new ValidationException(itemId + ": bottle/glassware stack size must remain 16");
            }
            if (!expectedConsumable.equals(components.get("minecraft:consumable"))
                    || !expectedConsumable.equals(nestedObject(item, "client_bound_data", "components")
                            .get("minecraft:consumable"))) {
                throw new ValidationException(itemId
                        + ": CE must own the server active-use contract and expose the same "
                        + "drink animation component to observer clients");
            }
            JsonObject data = item.getAsJsonObject("data");
            if (!data.get("custom_name").getAsString().equals(data.get("item_name").getAsString())) {
                throw new ValidationException(itemId
                        + ": potion drinks require custom_name because PotionItem "
                        + "ignores item_name when deriving its hover title");
            }
            JsonArray hideTooltip = data.getAsJsonArray("hide_tooltip");
            if (hideTooltip.size() != 1
                    || !hideTooltip.get(0).getAsString().equals("minecraft:potion_contents")) {
                throw new ValidationException(itemId
                        + ": drinks must hide only the vanilla potion_contents tooltip");
            }
            JsonElement potionContents = components.get("minecraft:potion_contents");
            if (!potionContents.isJsonObject()
                    || !potionContents.getAsJsonObject().get("potion").getAsString()
                            .equals("minecraft:mundane")
                    || !new LinkedHashSet<>(potionContents.getAsJsonObject().keySet())
                            .equals(potionContents.getAsJsonObject().has("custom_color")
                                    ? Set.of("potion", "custom_color") : Set.of("potion"))
                    || (potionContents.getAsJsonObject().has("custom_color")
                        && !potionContents.getAsJsonObject().get("custom_color").isJsonPrimitive())) {
                throw new ValidationException(itemId
                        + ": drink potion_contents must use the effectless mundane base "
                        + "to prevent PotionItem water-on-dirt conversion");
            }
        }
        Map<String, String[][]> vanillaRoutes = Map.ofEntries(
                Map.entry("minecraft:potion", new String[][] {
                        {"water", "water_bottle", "bottle-placement.water"},
                        {"potion", "potion_bottle", "bottle-placement.potion"}}),
                Map.entry("minecraft:honey_bottle", new String[][] {
                        {"honey", "honey_bottle", "bottle-placement.honey"}}),
                Map.entry("minecraft:dragon_breath", new String[][] {
                        {"dragon_breath", "dragon_breath_bottle", "bottle-placement.dragon-breath"}}),
                Map.entry("minecraft:experience_bottle", new String[][] {
                        {"experience", "xp_bottle", "bottle-placement.experience"}}));
        for (var routeEntry : vanillaRoutes.entrySet()) {
            JsonObject placements = new JsonObject();
            for (String[] route : routeEntry.getValue()) {
                JsonObject placement = new JsonObject();
                placement.addProperty("furniture", NAMESPACE + ":" + route[1]);
                JsonObject ground = new JsonObject();
                JsonObject groundRule = new JsonObject();
                groundRule.addProperty("rotation", vesselRotation(route[1]));
                groundRule.addProperty("alignment", "center");
                ground.add("ground", groundRule);
                placement.add("rules", ground);
                placement.addProperty("config", route[2]);
                placements.add(route[0], placement);
            }
            JsonObject behavior = new JsonObject();
            behavior.addProperty("type", NAMESPACE + ":sneak_place_vanilla_bottle");
            behavior.add("placements", placements);
            JsonObject expectedExtension = new JsonObject();
            expectedExtension.add("behavior", behavior);
            if (!expectedExtension.equals(items.getAsJsonObject(routeEntry.getKey()))) {
                throw new ValidationException(routeEntry.getKey()
                        + ": CE vanilla bottle placement routing drifted");
            }
        }
        Set<String> publicVesselIds = new LinkedHashSet<>();
        for (String vesselId : EXPECTED_ROTATION_16_VESSELS) {
            publicVesselIds.add(NAMESPACE + ":" + vesselId);
        }
        publicVesselIds.add(NAMESPACE + ":shaker");
        for (String itemId : publicVesselIds) {
            if (!items.has(itemId)) continue;
            JsonObject item = items.getAsJsonObject(itemId);
            List<JsonElement> placementBehaviors = new ArrayList<>();
            for (JsonElement behavior : behaviorsOf(item)) {
                JsonObject b = behavior.getAsJsonObject();
                if (b.has("furniture") && b.get("furniture").getAsString().equals(itemId)) {
                    placementBehaviors.add(behavior);
                }
            }
            JsonObject expectedPlacement = new JsonObject();
            expectedPlacement.addProperty("type", NAMESPACE + ":sneak_place_drink");
            expectedPlacement.addProperty("furniture", itemId);
            JsonObject ground = new JsonObject();
            JsonObject groundRule = new JsonObject();
            groundRule.addProperty("rotation", vesselRotation(itemId));
            groundRule.addProperty("alignment", "center");
            ground.add("ground", groundRule);
            expectedPlacement.add("rules", ground);
            if (!placementBehaviors.equals(List.of(expectedPlacement))) {
                throw new ValidationException(itemId
                        + ": portable vessels must delegate sneak placement through "
                        + "the generic native-CE furniture adapter");
            }
        }
        for (String itemId : EFFECTLESS_DRINKS) {
            String replacement = items.getAsJsonObject(itemId).getAsJsonObject("settings")
                    .get("consume_replacement").getAsString();
            if (!replacement.equals(NAMESPACE + ":empty_bottle")) {
                throw new ValidationException(itemId
                        + ": effectless bottle drinks must return empty_bottle after consumption");
            }
        }
        Path shakerTsv = projectRoot.resolve("src/paper/resources/catalog/shaker.tsv");
        Set<String> fixedCocktails = new LinkedHashSet<>();
        for (String[] row : tsvRows(shakerTsv)) fixedCocktails.add(row[1]);
        fixedCocktails.add(NAMESPACE + ":mystery_cocktail");
        for (String itemId : fixedCocktails) {
            JsonArray lore = items.getAsJsonObject(itemId).getAsJsonObject("data").getAsJsonArray("lore");
            Set<String> expectedEffects = new LinkedHashSet<>();
            for (String[] row : effectRows) {
                if (row[0].equals(itemId) && row[1].equals("1")) {
                    expectedEffects.add("effect." + row[2].replace(":", "."));
                }
            }
            if (lore.isEmpty()) {
                throw new ValidationException(itemId + ": fixed cocktail creative preview is missing real effect lore");
            }
            for (String effect : expectedEffects) {
                boolean found = false;
                for (JsonElement line : lore) {
                    if (line.getAsString().contains(effect)) found = true;
                }
                if (!found) {
                    throw new ValidationException(itemId + ": fixed cocktail creative preview is missing real effect lore");
                }
            }
            for (JsonElement line : lore) {
                if (!line.getAsString().contains("<insert:kaleidoscope_tavern_managed_lore>")) {
                    throw new ValidationException(itemId + ": generated cocktail lore must carry the managed insertion marker");
                }
            }
        }
        Set<String> legacyAttributeKeys = Set.of("attribute.name.generic.step_height",
                "attribute.name.player.block_interaction_range",
                "attribute.name.player.entity_interaction_range");
        for (JsonElement item : items.asMap().values()) {
            JsonArray lore = nestedObject(item.getAsJsonObject(), "data").has("lore")
                    ? item.getAsJsonObject().getAsJsonObject("data").getAsJsonArray("lore") : new JsonArray();
            for (JsonElement line : lore) {
                for (String key : legacyAttributeKeys) {
                    if (line.getAsString().contains(key)) {
                        throw new ValidationException("Drink lore still contains pre-26.2 attribute translation keys");
                    }
                }
            }
        }
        Map<String, Set<String>> expectedAttributeLore = Map.ofEntries(
                Map.entry(NAMESPACE + ":white_lady", Set.of("attribute.name.step_height")),
                Map.entry(NAMESPACE + ":emerald", Set.of("attribute.name.block_interaction_range",
                        "attribute.name.entity_interaction_range")));
        for (var attributeEntry : expectedAttributeLore.entrySet()) {
            JsonArray lore = items.getAsJsonObject(attributeEntry.getKey())
                    .getAsJsonObject("data").getAsJsonArray("lore");
            Set<String> missing = new LinkedHashSet<>();
            for (String key : attributeEntry.getValue()) {
                boolean found = false;
                for (JsonElement line : lore) {
                    if (line.getAsString().contains(key)) found = true;
                }
                if (!found) missing.add(key);
            }
            if (!missing.isEmpty()) {
                throw new ValidationException(attributeEntry.getKey()
                        + ": missing canonical 26.2 attribute lore keys " + missing);
            }
        }

        Set<String> cocktailIds = new LinkedHashSet<>();
        for (String[] row : tsvRows(catalogs.resolve("shaker.tsv"))) cocktailIds.add(row[1]);
        cocktailIds.add(NAMESPACE + ":mystery_cocktail");
        cocktailIds.add(NAMESPACE + ":signature_cocktail");
        for (String itemId : drinkIds) {
            String replacement = items.getAsJsonObject(itemId).getAsJsonObject("settings")
                    .get("consume_replacement").getAsString();
            String expected = cocktailIds.contains(itemId)
                    ? NAMESPACE + ":empty_glassware" : NAMESPACE + ":empty_bottle";
            if (!replacement.equals(expected)) {
                throw new ValidationException(itemId + ": consume_replacement must be "
                        + expected + ", got " + replacement);
            }
        }
        JsonObject emptyGlassware = items.getAsJsonObject(NAMESPACE + ":empty_glassware");
        if (nestedObject(emptyGlassware, "settings").has("consume_replacement")) {
            throw new ValidationException(
                    "empty_glassware must not return itself as a consume replacement");
        }
        for (String itemId : items.keySet()) {
            if (!itemId.endsWith("_bucket")) continue;
            JsonObject item = items.getAsJsonObject(itemId);
            JsonObject settings = item.getAsJsonObject("settings");
            if (!item.get("material").getAsString().equals("milk_bucket")
                    || item.getAsJsonObject("data").getAsJsonObject("components")
                            .get("minecraft:max_stack_size").getAsInt() != 16
                    || !settings.get("consume_replacement").getAsString().equals("minecraft:bucket")
                    || !settings.get("craft_remainder").getAsString().equals("minecraft:bucket")) {
                throw new ValidationException(itemId
                        + ": juice buckets must remain stackable drinkable items");
            }
        }
        for (String grapeId : List.of("grape", "ice_grape", "gold_grape", "green_grape")) {
            JsonObject grape = items.getAsJsonObject(NAMESPACE + ":" + grapeId);
            JsonObject expectedFood = new JsonObject();
            expectedFood.addProperty("nutrition", 2);
            expectedFood.addProperty("saturation", 2.0);
            expectedFood.addProperty("can_always_eat", true);
            JsonObject expectedConsumableEat = new JsonObject();
            expectedConsumableEat.addProperty("consume_seconds", 1.6);
            expectedConsumableEat.addProperty("animation", "eat");
            if (!grape.get("material").getAsString().equals("paper")
                    || !expectedFood.equals(grape.getAsJsonObject("data").get("food"))
                    || !expectedConsumableEat.equals(grape.getAsJsonObject("data")
                            .getAsJsonObject("components").get("minecraft:consumable"))
                    || !expectedConsumableEat.equals(nestedObject(grape, "client_bound_data", "components")
                            .get("minecraft:consumable"))) {
                throw new ValidationException(grapeId
                        + ": grapes must stay non-placeable plain food");
            }
        }
        Set<String> placeableMaterials = Set.of("sweet_berries", "glow_berries", "cocoa_beans",
                "wheat_seeds", "melon_seeds", "pumpkin_seeds", "beetroot_seeds",
                "torchflower_seeds", "pitcher_pod", "nether_wart", "bamboo", "sugar_cane",
                "kelp", "sea_pickle", "redstone", "string", "carrot", "potato", "chorus_fruit");
        for (var itemEntry : items.entrySet()) {
            JsonObject item = itemEntry.getValue().getAsJsonObject();
            if (item.has("material") && placeableMaterials.contains(item.get("material").getAsString())) {
                throw new ValidationException(itemEntry.getKey() + ": base material "
                        + item.get("material").getAsString()
                        + " leaks the vanilla block-placement path");
            }
        }
        JsonObject molotov = items.getAsJsonObject(NAMESPACE + ":molotov");
        JsonObject molotovComponents = molotov.getAsJsonObject("data").getAsJsonObject("components");
        JsonObject expectedMolotovConsumable = new JsonObject();
        expectedMolotovConsumable.addProperty("consume_seconds", 3600.0);
        expectedMolotovConsumable.addProperty("animation", "trident");
        expectedMolotovConsumable.addProperty("has_consume_particles", false);
        if (!molotov.get("material").getAsString().equals("paper")
                || molotovComponents.get("minecraft:max_stack_size").getAsInt() != 16
                || !expectedMolotovConsumable.equals(molotovComponents.get("minecraft:consumable"))
                || !expectedMolotovConsumable.equals(nestedObject(molotov, "client_bound_data", "components")
                        .get("minecraft:consumable"))) {
            throw new ValidationException(
                    "Molotov must retain its 72,000-tick trident charge instead of instant splash-potion use");
        }
        JsonObject molotovModel = molotov.getAsJsonObject("model");
        if (!molotovModel.get("property").getAsString().equals("minecraft:using_item")
                || !molotovModel.getAsJsonObject("on_true").get("path").getAsString()
                        .equals(NAMESPACE + ":item/molotov_charging")) {
            throw new ValidationException(
                    "Molotov must swap to the charging display model while using_item is true");
        }
        if (assetJson(NAMESPACE + ":item/molotov_charging", "models") == null) {
            throw new ValidationException("Missing generated molotov charging display model");
        }
        JsonObject shakerItem = items.getAsJsonObject(NAMESPACE + ":shaker");
        JsonObject shakerComponents = shakerItem.getAsJsonObject("data").getAsJsonObject("components");
        JsonObject expectedShakerConsumable = new JsonObject();
        expectedShakerConsumable.addProperty("consume_seconds", 3600.0);
        expectedShakerConsumable.addProperty("animation", "spear");
        expectedShakerConsumable.addProperty("has_consume_particles", false);
        JsonObject expectedShakerKinetic = new JsonObject();
        expectedShakerKinetic.addProperty("contact_cooldown_ticks", 10);
        expectedShakerKinetic.addProperty("delay_ticks", 0);
        JsonObject dismount = new JsonObject();
        dismount.addProperty("max_duration_ticks", 20);
        JsonObject knockback = new JsonObject();
        knockback.addProperty("max_duration_ticks", 40);
        JsonObject damage = new JsonObject();
        damage.addProperty("max_duration_ticks", 200);
        expectedShakerKinetic.add("dismount_conditions", dismount);
        expectedShakerKinetic.add("knockback_conditions", knockback);
        expectedShakerKinetic.add("damage_conditions", damage);
        expectedShakerKinetic.addProperty("forward_movement", 0.0);
        expectedShakerKinetic.addProperty("damage_multiplier", 0.0);
        if (!shakerItem.get("material").getAsString().equals("paper")
                || shakerComponents.get("minecraft:max_stack_size").getAsInt() != 1
                || !expectedShakerConsumable.equals(shakerComponents.get("minecraft:consumable"))
                || !expectedShakerConsumable.equals(nestedObject(shakerItem, "client_bound_data", "components")
                        .get("minecraft:consumable"))
                || !expectedShakerKinetic.equals(shakerComponents.get("minecraft:kinetic_weapon"))
                || !expectedShakerKinetic.equals(nestedObject(shakerItem, "client_bound_data", "components")
                        .get("minecraft:kinetic_weapon"))) {
            throw new ValidationException(
                    "Shaker must retain active-use timing and its kinetic_weapon sway for the client-driven arm wave");
        }
        JsonObject shakerModel = shakerItem.getAsJsonObject("model");
        if (!shakerModel.get("type").getAsString().equals("minecraft:select")
                || !shakerModel.get("property").getAsString().equals("display_context")) {
            throw new ValidationException("Shaker must use the 2D icon only in GUI/FIXED display contexts");
        }
        JsonObject iconCase = shakerModel.getAsJsonArray("cases").get(0).getAsJsonObject();
        JsonArray iconWhen = new JsonArray();
        iconWhen.add("gui");
        iconWhen.add("fixed");
        JsonObject iconModel = new JsonObject();
        iconModel.addProperty("type", "minecraft:model");
        iconModel.addProperty("path", NAMESPACE + ":item/shaker");
        JsonObject expectedIconCase = new JsonObject();
        expectedIconCase.add("when", iconWhen);
        expectedIconCase.add("model", iconModel);
        if (shakerModel.getAsJsonArray("cases").size() != 1 || !expectedIconCase.equals(iconCase)) {
            throw new ValidationException("Shaker must use the 2D icon only in GUI/FIXED display contexts");
        }
        JsonObject useCondition = shakerModel.getAsJsonObject("fallback");
        if (!useCondition.get("type").getAsString().equals("minecraft:condition")
                || !useCondition.get("property").getAsString().equals("minecraft:using_item")) {
            throw new ValidationException("Shaker held motion must use 26.2 item-model transformations");
        }
        JsonObject offFalse = new JsonObject();
        offFalse.addProperty("type", "minecraft:model");
        offFalse.addProperty("path", NAMESPACE + ":item/shaker_3d");
        JsonObject expectedUseModel = new JsonObject();
        expectedUseModel.addProperty("type", "minecraft:select");
        expectedUseModel.addProperty("property", "display_context");
        JsonArray useCases = new JsonArray();
        JsonObject firstPerson = new JsonObject();
        JsonArray fpWhen = new JsonArray();
        fpWhen.add("firstperson_lefthand");
        fpWhen.add("firstperson_righthand");
        firstPerson.add("when", fpWhen);
        firstPerson.add("model", expectedShakerUseCycle());
        useCases.add(firstPerson);
        JsonObject fallbackModel = new JsonObject();
        fallbackModel.addProperty("type", "minecraft:model");
        fallbackModel.addProperty("path", NAMESPACE + ":item/shaker_3d");
        JsonObject thirdPerson = new JsonObject();
        JsonArray tpWhen = new JsonArray();
        tpWhen.add("thirdperson_lefthand");
        tpWhen.add("thirdperson_righthand");
        thirdPerson.add("when", tpWhen);
        thirdPerson.add("model", fallbackModel.deepCopy());
        useCases.add(thirdPerson);
        expectedUseModel.add("cases", useCases);
        expectedUseModel.add("fallback", fallbackModel);
        if (!offFalse.equals(useCondition.get("on_false"))
                || !expectedUseModel.equals(useCondition.get("on_true"))) {
            throw new ValidationException("Shaker held motion must use 26.2 item-model transformations");
        }
        for (int index = 0; index < SHAKER_USE_FRAMES; index++) {
            String frame = String.format("%02d", index);
            if (assetJson(NAMESPACE + ":item/shaker_shaking/frame_" + frame, "models") != null) {
                throw new ValidationException("Shaker must not generate legacy per-frame model files");
            }
        }
        JsonObject barrel = furniture.getAsJsonObject(NAMESPACE + ":barrel");
        JsonObject barrelVariants = barrel.getAsJsonObject("variants");
        if (!barrelVariants.keySet().equals(Set.of("ground", "ground_closed"))) {
            throw new ValidationException(
                    "The source barrel must place open through CE's native ground variant");
        }
        JsonObject expectedBarrelHitbox = new JsonObject();
        expectedBarrelHitbox.addProperty("type", "happy_ghast");
        expectedBarrelHitbox.addProperty("position", "0,0,0");
        expectedBarrelHitbox.addProperty("scale", 0.75);
        expectedBarrelHitbox.addProperty("hard_collision", true);
        expectedBarrelHitbox.addProperty("can_use_item_on", true);
        expectedBarrelHitbox.addProperty("can_be_hit_by_projectile", true);
        expectedBarrelHitbox.addProperty("blocks_building", true);
        JsonArray barrelHitboxList = new JsonArray();
        barrelHitboxList.add(expectedBarrelHitbox);
        for (var variantEntry : barrelVariants.entrySet()) {
            if (!barrelHitboxList.equals(variantEntry.getValue().getAsJsonObject()
                    .getAsJsonArray("hitboxes"))) {
                throw new ValidationException("The barrel must use one CE happy-ghast collider "
                        + "with the exact 3x3x3 footprint");
            }
        }
        JsonObject closedBarrel = barrelVariants.getAsJsonObject("ground_closed");
        JsonObject openBarrel = barrelVariants.getAsJsonObject("ground");
        JsonElement closedElement = closedBarrel.getAsJsonArray("elements").get(0);
        JsonElement openBody = openBarrel.getAsJsonArray("elements").get(0);
        JsonElement openLid = openBarrel.getAsJsonArray("elements").get(1);
        List<String> barrelModels = List.of(
                renderItems.getAsJsonObject(closedElement.getAsJsonObject().get("item").getAsString())
                        .getAsJsonObject("model").get("path").getAsString(),
                renderItems.getAsJsonObject(openBody.getAsJsonObject().get("item").getAsString())
                        .getAsJsonObject("model").get("path").getAsString(),
                renderItems.getAsJsonObject(openLid.getAsJsonObject().get("item").getAsString())
                        .getAsJsonObject("model").get("path").getAsString());
        if (!barrelModels.equals(List.of(NAMESPACE + ":furniture/barrel_closed",
                NAMESPACE + ":furniture/barrel_body", NAMESPACE + ":furniture/barrel_open_lid"))) {
            throw new ValidationException("Barrel must use the exact source entity body/lid geometry");
        }
        List<String> barrelTransforms = List.of(
                closedElement.getAsJsonObject().get("translation").getAsString(),
                openBody.getAsJsonObject().get("translation").getAsString(),
                openLid.getAsJsonObject().get("translation").getAsString(),
                openLid.getAsJsonObject().get("rotation").getAsString());
        if (!barrelTransforms.equals(List.of("0,1.5,0", "0,1.5,0", "0,3,0.5", "72.5,0,0"))) {
            throw new ValidationException("Barrel body/lid pivot no longer matches BarrelModel");
        }
        JsonObject stool = furniture.getAsJsonObject(NAMESPACE + ":white_bar_stool")
                .getAsJsonObject("variants").getAsJsonObject("ground");
        JsonObject stoolHitbox = stool.getAsJsonArray("hitboxes").get(0).getAsJsonObject();
        if (stoolHitbox.get("height").getAsDouble() != 1.3125) {
            throw new ValidationException("Bar-stool hitbox must retain the Forge VoxelShape height");
        }
        JsonArray expectedSeats = new JsonArray();
        expectedSeats.add("0,0.3375,0 0");
        if (!expectedSeats.equals(stoolHitbox.getAsJsonArray("seats"))) {
            throw new ValidationException("Bar-stool seat must rest on the 15/16 cushion, not float above it");
        }
        String stoolRenderId = stool.getAsJsonArray("elements").get(0).getAsJsonObject()
                .get("item").getAsString();
        if (!renderItems.getAsJsonObject(stoolRenderId).getAsJsonObject("model").get("path")
                .getAsString().equals(NAMESPACE + ":block/deco/bar_stool/white")) {
            throw new ValidationException("Bar-stool furniture must keep the static source pedestal model");
        }
        int stoolBodyHelpers = 0;
        for (String renderId : renderItems.keySet()) {
            if (renderId.startsWith(NAMESPACE + ":_render/bar_stool_body/")) stoolBodyHelpers++;
        }
        if (stoolBodyHelpers != 16) {
            throw new ValidationException("Every source dye color needs a dynamic bar-stool body model");
        }
        JsonObject stoolBodyModel = assetJson(NAMESPACE + ":furniture/bar_stool_body_base", "models");
        if (stoolBodyModel == null || stoolBodyModel.getAsJsonArray("elements").size() != 4) {
            throw new ValidationException("Bar-stool seat/back/arms must remain a four-cuboid dynamic body");
        }
        JsonObject shakerFurniture = furniture.getAsJsonObject(NAMESPACE + ":shaker")
                .getAsJsonObject("variants").getAsJsonObject("ground");
        JsonObject shakerAnchor = shakerFurniture.getAsJsonArray("elements").get(0).getAsJsonObject();
        if (!renderItems.getAsJsonObject(shakerAnchor.get("item").getAsString())
                .getAsJsonObject("model").get("path").getAsString()
                .equals(NAMESPACE + ":block/mixology/shaker")) {
            throw new ValidationException("Shaker CE anchor must remain the invisible source block model");
        }
        if (shakerAnchor.has("position")) {
            throw new ValidationException("Shaker anchor must not expand culling bounds to hide its source model");
        }
        for (String part : List.of("base", "lid")) {
            JsonObject helper = renderItems.getAsJsonObject(NAMESPACE + ":_render/shaker_" + part);
            String expectedPath = part.equals("base") ? NAMESPACE + ":furniture/shaker_base"
                    : NAMESPACE + ":furniture/shaker_lid";
            if (!helper.getAsJsonObject("model").get("path").getAsString().equals(expectedPath)) {
                throw new ValidationException("Animated shaker body/lid helper items are incomplete");
            }
        }
        JsonObject shakerBase = assetJson(NAMESPACE + ":furniture/shaker_base", "models");
        JsonObject shakerLid = assetJson(NAMESPACE + ":furniture/shaker_lid", "models");
        if (shakerBase == null || shakerBase.getAsJsonArray("elements").size() != 2
                || shakerLid == null || shakerLid.getAsJsonArray("elements").size() != 3) {
            throw new ValidationException(
                    "ShakerModel must remain split as 2 root + 3 animated lid cuboids");
        }
        JsonObject shakerItemModel = assetJson(NAMESPACE + ":item/shaker", "models");
        JsonObject expectedShakerItemModel = new JsonObject();
        expectedShakerItemModel.addProperty("parent", "minecraft:item/generated");
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", NAMESPACE + ":item/shaker");
        expectedShakerItemModel.add("textures", textures);
        if (!expectedShakerItemModel.equals(shakerItemModel)) {
            throw new ValidationException(
                    "Shaker inventory model must remain vanilla-compatible instead of using Forge loaders");
        }
    }


    private static JsonObject nestedObject(JsonObject object, String... keys) {
        JsonObject current = object;
        for (String key : keys) {
            if (current == null || !current.has(key) || current.get(key).isJsonNull()) return new JsonObject();
            if (!current.get(key).isJsonObject()) return new JsonObject();
            current = current.getAsJsonObject(key);
        }
        return current == null ? new JsonObject() : current;
    }

    private static List<JsonElement> behaviorsOf(JsonObject item) {
        if (item.has("behaviors")) {
            List<JsonElement> result = new ArrayList<>();
            for (JsonElement raw : item.getAsJsonArray("behaviors")) result.add(raw);
            return result;
        }
        if (item.has("behavior")) return List.of(item.get("behavior"));
        return List.of();
    }

    private static List<String[]> tsvRows(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new ValidationException(path.getFileName() + " is empty");
        }
        int width = lines.get(0).split("\t", -1).length;
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) continue;
            String[] row = line.split("\t", -1);
            if (row.length != width) {
                throw new ValidationException(path.getFileName() + " contains a malformed row");
            }
            rows.add(row);
        }
        return rows;
    }


    private void validateStringLightsAndEvents(JsonObject items, JsonObject renderItems,
                                                JsonObject blocks, JsonObject furniture) throws IOException {
        List<String> dyeColors = List.of("white", "orange", "magenta", "light_blue", "yellow",
                "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue",
                "brown", "green", "red", "black");
        List<JsonObject> expectedHitboxes = new ArrayList<>();
        for (String x : List.of("-0.3125", "0", "0.3125")) {
            JsonObject hitbox = new JsonObject();
            hitbox.addProperty("type", "interaction");
            hitbox.addProperty("position", x + ",-0.25,0.1875");
            hitbox.addProperty("width", 0.375);
            hitbox.addProperty("height", 0.75);
            hitbox.addProperty("can_use_item_on", true);
            hitbox.addProperty("can_be_hit_by_projectile", true);
            hitbox.addProperty("interactive", true);
            hitbox.addProperty("blocks_building", true);
            expectedHitboxes.add(hitbox);
        }
        Map<String, JsonElement> expectedStringSounds = new LinkedHashMap<>();
        for (String action : List.of("break", "place", "hit")) {
            expectedStringSounds.put(action,
                    new JsonPrimitive("minecraft:block.chain." + action));
        }
        List<String> allStringColors = new ArrayList<>();
        allStringColors.add("colorless");
        allStringColors.addAll(dyeColors);
        for (String color : allStringColors) {
            String fullId = NAMESPACE + ":string_lights_" + color;
            if (blocks.has(fullId)) {
                throw new ValidationException("String lights must not retain CE custom-block definitions");
            }
            if (!furniture.has(fullId)) {
                throw new ValidationException(fullId + ": missing CE furniture");
            }
            JsonObject lights = furniture.getAsJsonObject(fullId);
            JsonObject expectedSettings = new JsonObject();
            expectedSettings.addProperty("hit_times", 3);
            expectedSettings.add("sounds", jsonObjectOf(expectedStringSounds));
            expectedSettings.addProperty("item", fullId);
            if (!expectedSettings.equals(lights.get("settings"))) {
                throw new ValidationException(fullId + ": chain furniture settings drifted");
            }
            JsonObject variants = lights.getAsJsonObject("variants");
            if (!variants.keySet().equals(Set.of("wall"))) {
                throw new ValidationException(fullId + ": must expose only CE's native wall anchor");
            }
            JsonObject wall = variants.getAsJsonObject("wall");
            JsonArray elements = wall.getAsJsonArray("elements");
            if (elements.size() != 1) {
                throw new ValidationException(fullId + ": thin three-part wall interaction shape drifted");
            }
            JsonArray hitboxList = new JsonArray();
            for (JsonObject h : expectedHitboxes) hitboxList.add(h);
            if (!hitboxList.equals(wall.getAsJsonArray("hitboxes"))) {
                throw new ValidationException(fullId + ": thin three-part wall interaction shape drifted");
            }
            JsonObject element = elements.get(0).getAsJsonObject();
            String renderId = element.get("item").getAsString();
            if (!element.get("type").getAsString().equals("item_display")
                    || !element.get("display_transform").getAsString().equals("none")
                    || !element.get("position").getAsString().equals("0,0,0.01")
                    || !element.get("translation").getAsString().equals("0,0.0625,0.4275")
                    || element.has("rotation") || element.has("scale")) {
                throw new ValidationException(fullId + ": wall item-display transform drifted");
            }
            String expectedModel = NAMESPACE + ":block/deco/string_lights/" + color;
            if (!Files.isRegularFile(projectRoot.resolve("src/main/resources/assets/"
                    + NAMESPACE + "/models/block/deco/string_lights/" + color + ".json"))) {
                throw new ValidationException(fullId + ": archived source model is missing");
            }
            JsonObject model = renderItems.getAsJsonObject(renderId).getAsJsonObject("model");
            if (!model.get("type").getAsString().equals("minecraft:model")
                    || !model.get("path").getAsString().equals(expectedModel)) {
                throw new ValidationException(fullId + ": furniture must render its archived source model");
            }
            JsonObject expectedLoot = new JsonObject();
            JsonArray pools = new JsonArray();
            JsonObject pool = new JsonObject();
            pool.addProperty("rolls", 1);
            JsonArray entries = new JsonArray();
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "furniture_item");
            entry.addProperty("item", fullId);
            entries.add(entry);
            pool.add("entries", entries);
            pools.add(pool);
            expectedLoot.add("pools", pools);
            if (!expectedLoot.equals(lights.get("loot"))) {
                throw new ValidationException(fullId + ": CE native furniture loot drifted");
            }
            JsonObject expectedBehavior = new JsonObject();
            expectedBehavior.addProperty("type", "glowing_furniture");
            JsonArray lightPoints = new JsonArray();
            lightPoints.add("0,0,0.5 15");
            expectedBehavior.add("lights", lightPoints);
            if (!expectedBehavior.equals(lights.get("behavior")) || lights.has("behaviors")) {
                throw new ValidationException(fullId + ": light must use only CE glowing_furniture");
            }
            JsonObject expectedItemBehavior = new JsonObject();
            expectedItemBehavior.addProperty("type", "furniture_item");
            expectedItemBehavior.addProperty("furniture", fullId);
            JsonObject wallRules = new JsonObject();
            JsonObject wallRule = new JsonObject();
            wallRule.addProperty("rotation", "four");
            wallRule.addProperty("alignment", "center");
            wallRules.add("wall", wallRule);
            expectedItemBehavior.add("rules", wallRules);
            expectedItemBehavior.addProperty("ignore_placer", true);
            if (!expectedItemBehavior.equals(items.getAsJsonObject(fullId).get("behavior"))) {
                throw new ValidationException(fullId + ": placement must use native CE furniture_item");
            }
            Set<String> expectedDyes = new LinkedHashSet<>(dyeColors);
            expectedDyes.remove(color);
            JsonArray dyeEvents = lights.getAsJsonArray("events");
            if (dyeEvents.size() != expectedDyes.size()) {
                throw new ValidationException(fullId + ": expected " + expectedDyes.size()
                        + " CE dye events");
            }
            Set<String> seenDyes = new LinkedHashSet<>();
            for (JsonElement rawEvent : dyeEvents) {
                JsonObject dyeEvent = rawEvent.getAsJsonObject();
                JsonArray conditions = dyeEvent.getAsJsonArray("conditions");
                JsonObject handCondition = new JsonObject();
                handCondition.addProperty("type", "hand");
                handCondition.addProperty("hand", "main_hand");
                if (!dyeEvent.get("on").getAsString().equals("right_click")
                        || conditions.size() != 2
                        || !conditions.get(0).getAsJsonObject().get("type").getAsString()
                                .equals("match_item")
                        || !handCondition.equals(conditions.get(1))) {
                    throw new ValidationException(fullId + ": CE dye event conditions drifted");
                }
                String dyeItem = conditions.get(0).getAsJsonObject().get("item").getAsString();
                Matcher dyeMatcher = Pattern.compile("^minecraft:(.+)_dye$").matcher(dyeItem);
                if (!dyeMatcher.matches() || !expectedDyes.contains(dyeMatcher.group(1))) {
                    throw new ValidationException(fullId + ": unexpected dye event " + dyeItem);
                }
                String dye = dyeMatcher.group(1);
                if (!seenDyes.add(dye)) {
                    throw new ValidationException(fullId + ": duplicate CE event for " + dyeItem);
                }
                JsonArray eventFunctions = dyeEvent.getAsJsonArray("functions");
                if (eventFunctions.size() != 1
                        || !eventFunctions.get(0).getAsJsonObject().get("type").getAsString()
                                .equals("if_else")) {
                    throw new ValidationException(fullId + ": protection must use CE if_else");
                }
                JsonArray rules = eventFunctions.get(0).getAsJsonObject().getAsJsonArray("rules");
                if (rules.size() != 2) {
                    throw new ValidationException(fullId + ": CE dye protection branches drifted");
                }
                JsonObject allowed = rules.get(0).getAsJsonObject();
                JsonObject denied = rules.get(1).getAsJsonObject();
                JsonArray flagCondition = new JsonArray();
                JsonObject testFlag = new JsonObject();
                testFlag.addProperty("type", "test_flag");
                testFlag.addProperty("flag", "interact");
                flagCondition.add(testFlag);
                if (!flagCondition.equals(allowed.get("conditions"))) {
                    throw new ValidationException(fullId + ": dye event must use CE interact protection");
                }
                JsonArray functions = allowed.getAsJsonArray("functions");
                List<String> functionTypes = new ArrayList<>();
                for (JsonElement f : functions) functionTypes.add(f.getAsJsonObject().get("type").getAsString());
                if (!functionTypes.equals(List.of("update_interaction_tick", "set_count", "play_sound",
                        "when", "swing_hand", "replace_furniture"))) {
                    throw new ValidationException(fullId + ": native dye function sequence drifted");
                }
                JsonObject expectedSetCount = new JsonObject();
                expectedSetCount.addProperty("type", "set_count");
                expectedSetCount.addProperty("add", true);
                expectedSetCount.addProperty("count", -1);
                JsonArray notCreativeConditions = new JsonArray();
                JsonObject notCreative = new JsonObject();
                notCreative.addProperty("type", "!equals");
                notCreative.addProperty("value1", "<arg:player.gamemode>");
                notCreative.addProperty("value2", "CREATIVE");
                notCreativeConditions.add(notCreative);
                expectedSetCount.add("conditions", notCreativeConditions);
                if (!expectedSetCount.equals(functions.get(1))) {
                    throw new ValidationException(fullId + ": CE must consume dye outside creative mode");
                }
                JsonObject expectedSound = new JsonObject();
                expectedSound.addProperty("type", "play_sound");
                expectedSound.addProperty("sound", "minecraft:item.dye.use");
                expectedSound.addProperty("source", "block");
                if (!expectedSound.equals(functions.get(2))) {
                    throw new ValidationException(fullId + ": CE dye sound drifted");
                }
                JsonObject particleSwitch = functions.get(3).getAsJsonObject();
                JsonArray cases = particleSwitch.getAsJsonArray("cases");
                Map<String, String[]> particlePositions = Map.ofEntries(
                        Map.entry("0.0", new String[] {"<arg:position.x>", "<arg:position.z> + 0.5"}),
                        Map.entry("180.0", new String[] {"<arg:position.x>", "<arg:position.z> - 0.5"}),
                        Map.entry("90.0", new String[] {"<arg:position.x> - 0.5", "<arg:position.z>"}),
                        Map.entry("-90.0", new String[] {"<arg:position.x> + 0.5", "<arg:position.z>"}));
                if (!particleSwitch.get("source").getAsString().equals("<arg:furniture.yaw>")
                        || cases.size() != 4) {
                    throw new ValidationException(fullId + ": wall-relative CE particles drifted");
                }
                for (JsonElement rawCase : cases) {
                    JsonObject particleCase = rawCase.getAsJsonObject();
                    String yaw = String.valueOf(particleCase.get("when").getAsDouble());
                    String[] position = particlePositions.get(yaw);
                    JsonArray caseFunctions = particleCase.getAsJsonArray("functions");
                    if (position == null || caseFunctions.size() != 1
                            || !validateDyeParticle(caseFunctions.get(0).getAsJsonObject(),
                                    position[0], position[1])) {
                        throw new ValidationException(fullId + ": invalid particle case for yaw " + yaw);
                    }
                }
                JsonArray fallback = particleSwitch.getAsJsonArray("fallback");
                if (fallback.size() != 1 || !validateDyeParticle(
                        fallback.get(0).getAsJsonObject(), "<arg:position.x>", "<arg:position.z>")) {
                    throw new ValidationException(fullId + ": CE particle fallback drifted");
                }
                JsonObject expectedReplace = new JsonObject();
                expectedReplace.addProperty("type", "replace_furniture");
                expectedReplace.addProperty("furniture", NAMESPACE + ":string_lights_" + dye);
                expectedReplace.addProperty("variant", "wall");
                expectedReplace.addProperty("drop_loot", false);
                expectedReplace.addProperty("play_sound", false);
                if (!expectedReplace.equals(functions.get(5))) {
                    throw new ValidationException(fullId + ": CE replacement target drifted for " + dye);
                }
                JsonObject expectedDenied = new JsonObject();
                JsonArray deniedFunctions = new JsonArray();
                JsonObject updateInteraction = new JsonObject();
                updateInteraction.addProperty("type", "update_interaction_tick");
                deniedFunctions.add(updateInteraction);
                expectedDenied.add("functions", deniedFunctions);
                if (!expectedDenied.equals(denied)) {
                    throw new ValidationException(fullId + ": denied dye click must remain claimed");
                }
            }
            if (!seenDyes.equals(expectedDyes)) {
                throw new ValidationException(fullId + ": CE dye coverage is incomplete");
            }
        }
        for (String stalePath : List.of(
                "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/game/"
                        + "furniture/StringLightsFurnitureBehavior.java",
                "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/game/"
                        + "furniture/StringLightsSemantics.java",
                "src/paperTest/java/com/github/ysbbbbbb/kaleidoscopetavern/"
                        + "paper/game/furniture/StringLightsFurnitureBehaviorTest.java",
                "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/game/"
                        + "block/StringLightsBlockBehavior.java")) {
            if (Files.exists(projectRoot.resolve(stalePath))) {
                throw new ValidationException(
                        "String-light placement, glow and dye interactions must remain entirely native CE");
            }
        }
        JsonArray trellisEvents = blocks.getAsJsonObject(NAMESPACE + ":trellis")
                .getAsJsonArray("events");
        if (trellisEvents.size() != 2) {
            throw new ValidationException("trellis: expected exactly wax-on plus wax-off events");
        }
        String[][] waxSpecs = {
                {"minecraft:honeycomb", "false", "true", "minecraft:item.honeycomb.wax_on"},
                {"minecraft:.+_axe", "true", "false", "minecraft:item.axe.wax_off"}};
        for (int i = 0; i < 2; i++) {
            JsonObject entry = trellisEvents.get(i).getAsJsonObject();
            Map<String, JsonObject> conditions = new LinkedHashMap<>();
            Map<String, JsonObject> functions = new LinkedHashMap<>();
            for (JsonElement raw : entry.getAsJsonArray("conditions")) {
                JsonObject c = raw.getAsJsonObject();
                conditions.put(c.get("type").getAsString(), c);
            }
            for (JsonElement raw : entry.getAsJsonArray("functions")) {
                JsonObject f = raw.getAsJsonObject();
                functions.put(f.get("type").getAsString(), f);
            }
            JsonObject waxedBefore = new JsonObject();
            waxedBefore.addProperty("waxed", waxSpecs[i][1]);
            JsonObject waxedAfter = new JsonObject();
            waxedAfter.addProperty("waxed", waxSpecs[i][2]);
            if (!entry.get("on").getAsString().equals("right_click")
                    || !conditions.get("match_item").get("item").getAsString().equals(waxSpecs[i][0])
                    || !waxedBefore.equals(conditions.get("match_block_property")
                            .getAsJsonObject("properties"))
                    || !conditions.containsKey("hand")
                    || !waxedAfter.equals(functions.get("update_block_property")
                            .getAsJsonObject("properties"))
                    || !functions.get("play_sound").get("sound").getAsString().equals(waxSpecs[i][3])
                    || !functions.containsKey("particle")
                    || !functions.containsKey("cancel_event")
                    || functions.containsKey("set_count")
                    || functions.containsKey("damage_item")) {
                throw new ValidationException("trellis: wax event drift for " + waxSpecs[i][0]);
            }
        }
        JsonObject waxOffFirstCondition = trellisEvents.get(1).getAsJsonObject()
                .getAsJsonArray("conditions").get(0).getAsJsonObject();
        if (!waxOffFirstCondition.has("regex")
                || !waxOffFirstCondition.get("regex").getAsBoolean()) {
            throw new ValidationException("trellis: wax-off must match every axe via regex");
        }
        for (String blockId : List.of("grapevine_trellis", "ice_grapevine_trellis",
                "gold_grapevine_trellis")) {
            JsonArray vineEvents = blocks.getAsJsonObject(NAMESPACE + ":" + blockId)
                    .getAsJsonArray("events");
            if (vineEvents.size() != 1) {
                throw new ValidationException(blockId + ": expected exactly one shear event");
            }
            JsonObject shear = vineEvents.get(0).getAsJsonObject();
            Map<String, JsonObject> conditions = new LinkedHashMap<>();
            Map<String, JsonObject> functions = new LinkedHashMap<>();
            for (JsonElement raw : shear.getAsJsonArray("conditions")) {
                JsonObject c = raw.getAsJsonObject();
                conditions.put(c.get("type").getAsString(), c);
            }
            for (JsonElement raw : shear.getAsJsonArray("functions")) {
                JsonObject f = raw.getAsJsonObject();
                functions.put(f.get("type").getAsString(), f);
            }
            JsonObject loot = functions.get("drop_loot").getAsJsonObject()
                    .getAsJsonObject("loot");
            JsonArray pools = loot.getAsJsonArray("pools");
            JsonArray entries = pools.size() == 1 ? pools.get(0).getAsJsonObject()
                    .getAsJsonArray("entries") : new JsonArray();
            if (!shear.get("on").getAsString().equals("right_click")
                    || !conditions.keySet().equals(Set.of("match_item"))
                    || !conditions.get("match_item").get("item").getAsString()
                            .equals("minecraft:shears")
                    || !functions.get("transform_block").get("block").getAsString()
                            .equals(NAMESPACE + ":trellis")
                    || entries.size() != 1
                    || !entries.get(0).getAsJsonObject().get("type").getAsString().equals("item")
                    || !entries.get(0).getAsJsonObject().get("item").getAsString()
                            .equals(NAMESPACE + ":grapevine")
                    || functions.get("damage_item").get("amount").getAsInt() != 1
                    || !functions.get("play_sound").get("sound").getAsString()
                            .equals("minecraft:block.beehive.shear")
                    || !functions.get("play_sound").get("target").getAsString().equals("self")
                    || !functions.containsKey("swing_hand")
                    || !functions.containsKey("cancel_event")) {
                throw new ValidationException(blockId + ": shear event drift");
            }
        }
        JsonArray wildEvents = blocks.getAsJsonObject(NAMESPACE + ":wild_grapevine")
                .getAsJsonArray("events");
        if (wildEvents.size() != 2) {
            throw new ValidationException(
                    "wild_grapevine: expected active-shear plus already-sheared consume events");
        }
        JsonObject shear = wildEvents.get(0).getAsJsonObject();
        Map<String, JsonObject> shearConditions = new LinkedHashMap<>();
        Map<String, JsonObject> shearFunctions = new LinkedHashMap<>();
        for (JsonElement raw : shear.getAsJsonArray("conditions")) {
            JsonObject c = raw.getAsJsonObject();
            shearConditions.put(c.get("type").getAsString(), c);
        }
        for (JsonElement raw : shear.getAsJsonArray("functions")) {
            JsonObject f = raw.getAsJsonObject();
            shearFunctions.put(f.get("type").getAsString(), f);
        }
        JsonObject shearedFalse = new JsonObject();
        shearedFalse.addProperty("sheared", "false");
        JsonObject shearedTrue = new JsonObject();
        shearedTrue.addProperty("sheared", "true");
        if (!shear.get("on").getAsString().equals("right_click")
                || !shearConditions.get("match_item").get("item").getAsString()
                        .equals("minecraft:shears")
                || !shearedFalse.equals(shearConditions.get("match_block_property")
                        .getAsJsonObject("properties"))
                || shearConditions.containsKey("hand")
                || !shearedTrue.equals(shearFunctions.get("update_block_property")
                        .getAsJsonObject("properties"))
                || !shearFunctions.get("play_sound").get("sound").getAsString()
                        .equals("minecraft:entity.sheep.shear")
                || !shearFunctions.get("play_sound").get("target").getAsString().equals("self")
                || !shearFunctions.containsKey("damage_item")
                || !shearFunctions.containsKey("swing_hand")
                || !shearFunctions.containsKey("cancel_event")) {
            throw new ValidationException("wild_grapevine: shear event drift");
        }
        JsonObject consumed = wildEvents.get(1).getAsJsonObject();
        Map<String, JsonObject> consumedConditions = new LinkedHashMap<>();
        for (JsonElement raw : consumed.getAsJsonArray("conditions")) {
            JsonObject c = raw.getAsJsonObject();
            consumedConditions.put(c.get("type").getAsString(), c);
        }
        JsonArray consumedFunctions = new JsonArray();
        JsonObject cancelEvent = new JsonObject();
        cancelEvent.addProperty("type", "cancel_event");
        consumedFunctions.add(cancelEvent);
        JsonObject consumedMatch = consumedConditions.containsKey("match_item")
                ? consumedConditions.get("match_item") : new JsonObject();
        JsonObject consumedSheared = consumedConditions.containsKey("match_block_property")
                ? consumedConditions.get("match_block_property").getAsJsonObject("properties")
                : new JsonObject();
        if (!consumed.get("on").getAsString().equals("right_click")
                || !consumedMatch.get("item").getAsString().equals("minecraft:shears")
                || !shearedTrue.equals(consumedSheared)
                || !consumedFunctions.equals(consumed.getAsJsonArray("functions"))) {
            throw new ValidationException("wild_grapevine: already-sheared click must only be consumed");
        }
        for (String furnitureId : furniture.keySet()) {
            if (furnitureId.endsWith("_incense")) {
                throw new ValidationException("Incense must not retain CE furniture definitions");
            }
        }
    }

    private static boolean validateDyeParticle(JsonObject particle, String x, String z) {
        JsonObject expected = new JsonObject();
        expected.addProperty("type", "particle");
        expected.addProperty("particle", "minecraft:happy_villager");
        expected.addProperty("x", x);
        expected.addProperty("y", "<arg:position.y>");
        expected.addProperty("z", z);
        expected.addProperty("count", 15);
        expected.addProperty("offset_x", 0.5);
        expected.addProperty("offset_y", 0.375);
        expected.addProperty("offset_z", 0.5);
        return expected.equals(particle);
    }


    private void validateChalkboard(JsonObject items, JsonObject renderItems, JsonObject blocks) throws IOException {
        String chalkboardId = NAMESPACE + ":chalkboard";
        JsonObject chalkboard = blocks.getAsJsonObject(chalkboardId);
        JsonObject chalkboardStates = chalkboard.getAsJsonObject("states");
        JsonObject expectedProperties = new JsonObject();
        JsonObject facingProp = new JsonObject();
        facingProp.addProperty("type", "horizontal_direction");
        facingProp.addProperty("default", "north");
        JsonArray facingValues = new JsonArray();
        facingValues.add("north"); facingValues.add("east");
        facingValues.add("south"); facingValues.add("west");
        facingProp.add("values", facingValues);
        expectedProperties.add("facing", facingProp);
        JsonObject halfProp = new JsonObject();
        halfProp.addProperty("type", "double_block_half");
        halfProp.addProperty("default", "lower");
        JsonArray halfValues = new JsonArray();
        halfValues.add("lower"); halfValues.add("upper");
        halfProp.add("values", halfValues);
        expectedProperties.add("half", halfProp);
        JsonObject positionProp = new JsonObject();
        positionProp.addProperty("type", "string");
        positionProp.addProperty("default", "single");
        JsonArray positionValues = new JsonArray();
        positionValues.add("single"); positionValues.add("left");
        positionValues.add("middle"); positionValues.add("right");
        positionProp.add("values", positionValues);
        expectedProperties.add("position", positionProp);
        JsonObject waterloggedProp = new JsonObject();
        waterloggedProp.addProperty("type", "boolean");
        waterloggedProp.addProperty("default", "false");
        expectedProperties.add("waterlogged", waterloggedProp);
        if (!expectedProperties.equals(chalkboardStates.getAsJsonObject("properties"))) {
            throw new ValidationException("Chalkboard CE state schema drifted");
        }
        JsonObject chalkboardVariants = chalkboardStates.getAsJsonObject("variants");
        Set<String> expectedChalkboardKeys = new LinkedHashSet<>();
        for (String facing : List.of("north", "east", "south", "west")) {
            for (String half : List.of("lower", "upper")) {
                for (String position : List.of("single", "left", "middle", "right")) {
                    for (String waterlogged : List.of("false", "true")) {
                        expectedChalkboardKeys.add("facing=" + facing + ",half=" + half
                                + ",position=" + position + ",waterlogged=" + waterlogged);
                    }
                }
            }
        }
        if (!chalkboardVariants.keySet().equals(expectedChalkboardKeys)) {
            throw new ValidationException("Chalkboard must expose all 64 facing/half/position/fluid states");
        }
        JsonObject chalkboardAppearances = chalkboardStates.getAsJsonObject("appearances");
        Map<String, Integer> facingYaw = new LinkedHashMap<>();
        facingYaw.put("north", 180);
        facingYaw.put("east", 90);
        facingYaw.put("west", 270);
        Map<String, String> expectedPanelEdge = Map.of(
                "north", "south", "east", "west", "south", "north", "west", "east");
        Map<String, String> expectedFrontDirection = Map.of(
                "north", "north", "east", "east", "south", "south", "west", "west");
        Set<String> referencedAppearances = new LinkedHashSet<>();
        for (var variantEntry : chalkboardVariants.entrySet()) {
            String facing = null;
            String half = null;
            String position = null;
            String waterlogged = null;
            for (String part : variantEntry.getKey().split(",")) {
                String[] pair = part.split("=", 2);
                switch (pair[0]) {
                    case "facing" -> facing = pair[1];
                    case "half" -> half = pair[1];
                    case "position" -> position = pair[1];
                    case "waterlogged" -> waterlogged = pair[1];
                }
            }
            String visibleSize = half.equals("lower") && position.equals("single") ? "small"
                    : half.equals("lower") && position.equals("middle") ? "large" : null;
            String appearanceName = visibleSize != null
                    ? visibleSize + "_" + facing + "_" + half
                    : "hidden_" + facing + "_" + half;
            JsonObject variant = variantEntry.getValue().getAsJsonObject();
            if (!variant.get("appearance").getAsString().equals(appearanceName)) {
                throw new ValidationException("Chalkboard " + variantEntry.getKey()
                        + " maps to the wrong renderer");
            }
            JsonElement expectedSettings = waterlogged.equals("true")
                    ? obj("fluid_state", "water") : null;
            if (waterlogged.equals("true")) {
                if (!expectedSettings.equals(variant.get("settings"))) {
                    throw new ValidationException("Chalkboard " + variantEntry.getKey()
                            + " fluid state drifted");
                }
            } else if (variant.has("settings") && !variant.get("settings").isJsonNull()) {
                throw new ValidationException("Chalkboard " + variantEntry.getKey()
                        + " fluid state drifted");
            }
            JsonObject appearance = chalkboardAppearances.getAsJsonObject(appearanceName);
            referencedAppearances.add(appearanceName);
            String expectedCarrier = "minecraft:iron_door[facing=" + facing + ",half=" + half
                    + ",hinge=left,open=false,powered=true]";
            if (!appearance.get("state").getAsString().equals(expectedCarrier)
                    || !appearance.get("transparent").getAsBoolean()) {
                throw new ValidationException("Chalkboard " + variantEntry.getKey()
                        + " must use its released closed-door carrier");
            }
            JsonElement renderer = appearance.get("entity_renderer");
            if (visibleSize == null) {
                if (renderer != null && !renderer.isJsonNull()) {
                    throw new ValidationException("Chalkboard side/upper cell " + variantEntry.getKey()
                            + " must stay visually hidden");
                }
                continue;
            }
            JsonObject expectedRenderer = new JsonObject();
            expectedRenderer.addProperty("type", "item_display");
            expectedRenderer.addProperty("item", NAMESPACE + ":_render/chalkboard/" + visibleSize);
            expectedRenderer.addProperty("display_transform", "none");
            expectedRenderer.addProperty("shadow_radius", 0);
            expectedRenderer.addProperty("view_range", 1.25);
            Integer yawValue = facingYaw.get(facing);
            if (yawValue != null) {
                expectedRenderer.addProperty("rotation", "0," + yawValue + ",0");
            }
            if (!expectedRenderer.equals(renderer)) {
                throw new ValidationException("Chalkboard " + variantEntry.getKey()
                        + " model renderer drifted");
            }
            int yaw = yawValue == null ? 0 : yawValue;
            if (!rotatedCardinal(0, 1, yaw).equals(expectedPanelEdge.get(facing))
                    || !rotatedCardinal(0, -1, yaw).equals(expectedFrontDirection.get(facing))) {
                throw new ValidationException("Chalkboard " + facing
                        + " model must share its door edge and look outward");
            }
        }
        if (!referencedAppearances.equals(chalkboardAppearances.keySet())
                || chalkboardAppearances.size() != 16) {
            throw new ValidationException("Chalkboard appearance set contains stale or missing states");
        }
        JsonArray expectedChalkboardBehaviors = new JsonArray();
        JsonObject doubleHigh = new JsonObject();
        doubleHigh.addProperty("type", "double_high_block");
        expectedChalkboardBehaviors.add(doubleHigh);
        JsonObject chalkboardBehavior = new JsonObject();
        chalkboardBehavior.addProperty("type", NAMESPACE + ":chalkboard");
        expectedChalkboardBehaviors.add(chalkboardBehavior);
        if (!expectedChalkboardBehaviors.equals(chalkboard.get("behaviors"))) {
            throw new ValidationException(
                    "Chalkboard vertical lifecycle must remain CE-native and precede Tavern merge/text");
        }
        JsonObject settings = chalkboard.getAsJsonObject("settings");
        JsonArray expectedTags = new JsonArray();
        expectedTags.add("minecraft:mineable/axe");
        JsonObject expectedDestroyStages = new JsonObject();
        expectedDestroyStages.addProperty("template", "internal:destroy_stages");
        if (!settings.get("item").getAsString().equals(chalkboardId)
                || settings.get("hardness").getAsDouble() != 0.8
                || settings.get("resistance").getAsDouble() != 0.8
                || !settings.get("push_reaction").getAsString().equals("NORMAL")
                || !expectedTags.equals(settings.getAsJsonArray("tags"))
                || !expectedDestroyStages.equals(settings.get("destroy_stages"))
                || settings.get("map_color").getAsInt() != 13
                || !settings.get("instrument").getAsString().equals("guitar")
                || !settings.get("burnable").getAsBoolean()
                || settings.get("burn_chance").getAsInt() != 5
                || settings.get("fire_spread_chance").getAsInt() != 20) {
            throw new ValidationException("Chalkboard survival mining settings drifted");
        }
        JsonArray expectedCountFunctions = new JsonArray();
        for (String position : List.of("left", "middle", "right")) {
            JsonObject function = new JsonObject();
            function.addProperty("type", "set_count");
            function.addProperty("count", 3);
            function.addProperty("add", false);
            JsonArray conditions = new JsonArray();
            JsonObject condition = new JsonObject();
            condition.addProperty("type", "match_block_property");
            JsonObject properties = new JsonObject();
            properties.addProperty("position", position);
            condition.add("properties", properties);
            conditions.add(condition);
            function.add("conditions", conditions);
            expectedCountFunctions.add(function);
        }
        JsonObject expectedLoot = new JsonObject();
        JsonArray pools = new JsonArray();
        JsonObject pool = new JsonObject();
        pool.addProperty("rolls", 1);
        JsonArray poolConditions = new JsonArray();
        JsonObject survives = new JsonObject();
        survives.addProperty("type", "survives_explosion");
        poolConditions.add(survives);
        pool.add("conditions", poolConditions);
        JsonArray entries = new JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "item");
        entry.addProperty("item", chalkboardId);
        entry.add("functions", expectedCountFunctions);
        entries.add(entry);
        pool.add("entries", entries);
        pools.add(pool);
        expectedLoot.add("pools", pools);
        if (!expectedLoot.equals(chalkboard.get("loot"))) {
            throw new ValidationException(
                    "CE must own chalkboard drops and return three items for any merged cell");
        }
        JsonObject expectedItemBehavior = new JsonObject();
        expectedItemBehavior.addProperty("type", "double_high_block_item");
        expectedItemBehavior.addProperty("block", chalkboardId);
        if (!expectedItemBehavior.equals(items.getAsJsonObject(chalkboardId).get("behavior"))) {
            throw new ValidationException(
                    "Chalkboard placement must use CE's native double_high_block_item");
        }
        Map<String, JsonObject> expectedChalkboardModels = new LinkedHashMap<>();
        JsonObject smallModel = new JsonObject();
        JsonArray smallFrom = new JsonArray();
        smallFrom.add(0); smallFrom.add(2); smallFrom.add(15);
        JsonArray smallTo = new JsonArray();
        smallTo.add(16); smallTo.add(30); smallTo.add(16);
        smallModel.add("from", smallFrom);
        smallModel.add("to", smallTo);
        smallModel.addProperty("texture", NAMESPACE + ":entity/deco/small_chalkboard");
        JsonObject smallFaces = new JsonObject();
        smallFaces.add("down", doubleArray(0.25, 0, 4.25, 0.25));
        smallFaces.add("up", doubleArray(4.25, 0, 8.25, 0.25));
        smallFaces.add("west", doubleArray(0, 0.25, 0.25, 7.25));
        smallFaces.add("north", doubleArray(0.25, 0.25, 4.25, 7.25));
        smallFaces.add("east", doubleArray(4.25, 0.25, 4.5, 7.25));
        smallFaces.add("south", doubleArray(4.5, 0.25, 8.5, 7.25));
        smallModel.add("faces", smallFaces);
        expectedChalkboardModels.put("small", smallModel);
        JsonObject largeModel = new JsonObject();
        JsonArray largeFrom = new JsonArray();
        largeFrom.add(-16); largeFrom.add(2); largeFrom.add(15);
        JsonArray largeTo = new JsonArray();
        largeTo.add(32); largeTo.add(30); largeTo.add(16);
        largeModel.add("from", largeFrom);
        largeModel.add("to", largeTo);
        largeModel.addProperty("texture", NAMESPACE + ":entity/deco/large_chalkboard");
        JsonObject largeFaces = new JsonObject();
        largeFaces.add("down", doubleArray(0.125, 0, 6.125, 0.25));
        largeFaces.add("up", doubleArray(6.125, 0, 12.125, 0.25));
        largeFaces.add("west", doubleArray(0, 0.25, 0.125, 7.25));
        largeFaces.add("north", doubleArray(0.125, 0.25, 6.125, 7.25));
        largeFaces.add("east", doubleArray(6.125, 0.25, 6.25, 7.25));
        largeFaces.add("south", doubleArray(6.25, 0.25, 12.25, 7.25));
        largeModel.add("faces", largeFaces);
        expectedChalkboardModels.put("large", largeModel);
        for (var modelEntry : expectedChalkboardModels.entrySet()) {
            String size = modelEntry.getKey();
            String helperId = NAMESPACE + ":_render/chalkboard/" + size;
            if (!renderItems.getAsJsonObject(helperId).getAsJsonObject("model").get("path")
                    .getAsString().equals(NAMESPACE + ":furniture/chalkboard_" + size)) {
                throw new ValidationException("Chalkboard " + size + " render helper drifted");
            }
            JsonObject model = assetJson(NAMESPACE + ":furniture/chalkboard_" + size, "models");
            if (model == null || !model.has("elements")
                    || model.getAsJsonArray("elements").size() != 1) {
                throw new ValidationException("Chalkboard " + size
                        + " must contain exactly one source cuboid");
            }
            JsonObject element = model.getAsJsonArray("elements").get(0).getAsJsonObject();
            JsonObject actualFaces = new JsonObject();
            for (var faceEntry : element.getAsJsonObject("faces").entrySet()) {
                JsonObject face = faceEntry.getValue().getAsJsonObject();
                if (face.has("texture") && face.get("texture").getAsString().equals("#board")) {
                    actualFaces.add(faceEntry.getKey(), face.get("uv"));
                }
            }
            JsonObject expected = modelEntry.getValue();
            if (!expected.getAsJsonArray("from").equals(element.getAsJsonArray("from"))
                    || !expected.getAsJsonArray("to").equals(element.getAsJsonArray("to"))
                    || !expected.get("texture").getAsString().equals(
                            model.getAsJsonObject("textures").get("board").getAsString())
                    || !expected.getAsJsonObject("faces").equals(actualFaces)) {
                throw new ValidationException("Chalkboard " + size
                        + " geometry/UV no longer matches its archived entity cube");
            }
        }
        JsonObject trellisSettings = blocks.getAsJsonObject(NAMESPACE + ":trellis")
                .getAsJsonObject("settings");
        if (trellisSettings.has("support_shape")) {
            throw new ValidationException("Trellis must not expose a full-cube support/occlusion shape");
        }
    }

    private static String rotatedCardinal(int x, int z, int yaw) {
        double angle = Math.toRadians(yaw + 180);
        int rotatedX = (int) Math.round(x * Math.cos(angle) + z * Math.sin(angle));
        int rotatedZ = (int) Math.round(-x * Math.sin(angle) + z * Math.cos(angle));
        if (rotatedX == 0 && rotatedZ == -1) return "north";
        if (rotatedX == 1 && rotatedZ == 0) return "east";
        if (rotatedX == 0 && rotatedZ == 1) return "south";
        if (rotatedX == -1 && rotatedZ == 0) return "west";
        throw new ValidationException("Unmapped rotated cardinal " + rotatedX + "," + rotatedZ);
    }

    private static JsonArray doubleArray(double... values) {
        JsonArray array = new JsonArray();
        for (double value : values) array.add(value);
        return array;
    }


    private void validateHudAndCustomCrops(JsonObject items, JsonObject blocks,
                                           JsonObject furniture) throws IOException {
        List<Integer> hudOffsetPowers = List.of(1, 2, 4, 8, 16, 32, 64, 128, 256);
        JsonArray expectedShakerHudProviders = new JsonArray();
        JsonObject spaceProvider = new JsonObject();
        spaceProvider.addProperty("type", "space");
        JsonObject advances = new JsonObject();
        for (int index = 0; index < hudOffsetPowers.size(); index++) {
            int power = hudOffsetPowers.get(index);
            advances.addProperty(String.valueOf((char) (0xE410 + index)), power / 2.0);
            advances.addProperty(String.valueOf((char) (0xE420 + index)), -power / 2.0);
        }
        spaceProvider.add("advances", advances);
        expectedShakerHudProviders.add(spaceProvider);
        expectedShakerHudProviders.add(fontBitmap(NAMESPACE + ":font/shaker/bar.png", 0, 9, 0xE400));
        expectedShakerHudProviders.add(fontBitmap(NAMESPACE + ":font/shaker/pointer.png", 3, 7, 0xE401));
        expectedShakerHudProviders.add(fontBitmap(NAMESPACE + ":gui/rhombus.png", 3, 8, 0xE402));
        JsonObject shakerHudFont = assetJson(NAMESPACE + ":shaker_hud", "font");
        if (shakerHudFont == null || !expectedShakerHudProviders.equals(
                shakerHudFont.getAsJsonArray("providers"))) {
            throw new ValidationException(
                    "Shaker HUD font must preserve the source bar, pointer and ingredient layout");
        }
        if (!assetExists(NAMESPACE + ":font/shaker/bar", "textures", ".png")
                || !assetExists(NAMESPACE + ":font/shaker/pointer", "textures", ".png")
                || !assetExists(NAMESPACE + ":gui/rhombus", "textures", ".png")) {
            throw new ValidationException("Missing generated shaker HUD texture");
        }
        JsonArray expectedEffectProviders = new JsonArray();
        for (int index = 0; index < CUSTOM_EFFECT_ICON_IDS.size(); index++) {
            expectedEffectProviders.add(fontBitmap(NAMESPACE + ":mob_effect/"
                    + CUSTOM_EFFECT_ICON_IDS.get(index) + ".png", 8, 9, 0xE100 + index));
        }
        JsonObject customEffectFont = assetJson(NAMESPACE + ":custom_effects", "font");
        if (customEffectFont == null || !expectedEffectProviders.equals(
                customEffectFont.getAsJsonArray("providers"))) {
            throw new ValidationException(
                    "Custom drink-effect HUD font must map all archived icons deterministically");
        }
        for (String effectId : CUSTOM_EFFECT_ICON_IDS) {
            if (!assetExists(NAMESPACE + ":mob_effect/" + effectId, "textures", ".png")) {
                throw new ValidationException("Missing custom drink-effect HUD icon: " + effectId);
            }
        }
        JsonArray expectedHudProviders = new JsonArray();
        JsonObject hudSpace = new JsonObject();
        hudSpace.addProperty("type", "space");
        JsonObject hudAdvances = new JsonObject();
        for (int index = 0; index < hudOffsetPowers.size(); index++) {
            int power = hudOffsetPowers.get(index);
            hudAdvances.addProperty(String.valueOf((char) (0xE300 + index)), power);
            hudAdvances.addProperty(String.valueOf((char) (0xE310 + index)), -power);
        }
        hudSpace.add("advances", hudAdvances);
        expectedHudProviders.add(hudSpace);
        int[][] frameSpecs = {{0xE320, 9, 0xE330, 6}, {0xE321, -16, 0xE340, -19}};
        for (int[] spec : frameSpecs) {
            JsonObject background = new JsonObject();
            background.addProperty("type", "bitmap");
            background.addProperty("file", "minecraft:gui/sprites/hud/effect_background.png");
            background.addProperty("ascent", spec[1]);
            background.addProperty("height", 24);
            JsonArray bgChars = new JsonArray();
            bgChars.add(String.valueOf((char) spec[0]));
            background.add("chars", bgChars);
            expectedHudProviders.add(background);
            for (int index = 0; index < CUSTOM_EFFECT_ICON_IDS.size(); index++) {
                expectedHudProviders.add(fontBitmap(NAMESPACE + ":font/hud_effect/"
                        + CUSTOM_EFFECT_ICON_IDS.get(index) + ".png", spec[3], 18, spec[2] + index));
            }
        }
        JsonObject hudFont = assetJson(NAMESPACE + ":custom_effects_hud", "font");
        if (hudFont == null || !expectedHudProviders.equals(hudFont.getAsJsonArray("providers"))) {
            throw new ValidationException(
                    "Corner HUD font must keep the deterministic space/frame/icon glyph layout");
        }
        for (String effectId : CUSTOM_EFFECT_ICON_IDS) {
            if (!assetExists(NAMESPACE + ":font/hud_effect/" + effectId, "textures", ".png")) {
                throw new ValidationException("Missing padded corner HUD icon: " + effectId);
            }
        }
        for (String sprite : List.of("yellow_background", "yellow_progress")) {
            if (!Files.isRegularFile(packAssetsRoot.resolve("minecraft/textures/gui/sprites/"
                    + "boss_bar/" + sprite + ".png"))) {
                throw new ValidationException(
                        "Corner HUD needs the transparent YELLOW boss bar sprite: " + sprite);
            }
        }
        String modEffectsSource = readText(projectRoot.resolve("src/main/java/com/github/"
                + "ysbbbbbb/kaleidoscopetavern/init/ModEffects.java"));
        Set<String> neutralEffects = new LinkedHashSet<>();
        if (modEffectsSource.contains("SLIGHTLY_TIPSY = EFFECTS.register(\"slightly_tipsy\", "
                + "() -> new BaseEffect(MobEffectCategory.NEUTRAL")) {
            neutralEffects.add("slightly_tipsy");
        }
        Path forgeEffectRoot = projectRoot.resolve("src/main/java/com/github/ysbbbbbb/"
                + "kaleidoscopetavern/effect");
        try (var stream = Files.list(forgeEffectRoot)) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String body = readText(path);
                String name = path.getFileName().toString();
                if (body.contains("MobEffectCategory.HARMFUL")) {
                    throw new ValidationException(name
                            + ": harmful category is new; update the corner HUD row split");
                }
                if (body.contains("MobEffectCategory.NEUTRAL") && !name.equals("BaseEffect.java")) {
                    String stem = name.substring(0, name.length() - "Effect.java".length());
                    String snake = stem.replaceAll("(?<!^)(?=[A-Z])", "_").toLowerCase();
                    neutralEffects.add(snake);
                }
            }
        }
        if (!neutralEffects.equals(Set.of("slightly_tipsy", "upside_down"))) {
            throw new ValidationException("Corner HUD row-two set drifted from the Forge registrations: "
                    + neutralEffects);
        }
        String hudSemanticsSource = readText(projectRoot.resolve("src/paper/java/com/github/"
                + "ysbbbbbb/kaleidoscopetavern/paper/game/effect/CustomEffectHudSemantics.java"));
        Matcher colorMatcher = Pattern.compile(
                "EFFECTS\\.register\\(\"(\\w+)\",[^\\n]*?0x([0-9A-Fa-f]{6})\\)")
                .matcher(modEffectsSource);
        Map<String, String> registeredColors = new LinkedHashMap<>();
        while (colorMatcher.find()) {
            registeredColors.put(colorMatcher.group(1), colorMatcher.group(2).toUpperCase());
        }
        if (!registeredColors.keySet().equals(new LinkedHashSet<>(CUSTOM_EFFECT_ICON_IDS))) {
            throw new ValidationException(
                    "ModEffects colour extraction drifted: " + registeredColors.keySet());
        }
        for (var colorEntry : registeredColors.entrySet()) {
            String entry = "Map.entry(\"" + NAMESPACE + ":" + colorEntry.getKey()
                    + "\", 0x" + colorEntry.getValue() + ")";
            if (!hudSemanticsSource.contains(entry)) {
                throw new ValidationException(
                        "CustomEffectHudSemantics colour table is missing " + entry);
            }
        }
        String row2 = hudSemanticsSource.split("HUD_ROW2_EFFECTS")[1].split(";")[0];
        for (String row2Effect : List.of("slightly_tipsy", "upside_down")) {
            if (!row2.contains("\"" + NAMESPACE + ":" + row2Effect + "\"")) {
                throw new ValidationException(
                        "CustomEffectHudSemantics.HUD_ROW2_EFFECTS must contain " + row2Effect);
            }
        }
        String customCropsText = readText(projectRoot.resolve(
                "src/paper/customcrops/contents/crops/kaleidoscope_tavern.yml"));
        Map<String, List<String>> cropModels = new LinkedHashMap<>();
        for (String crop : List.of("grape_crop", "ice_grape_crop", "gold_grape_crop")) {
            List<String> stageIds = new ArrayList<>();
            stageIds.add(NAMESPACE + ":" + crop);
            for (int point = 1; point < 6; point++) {
                stageIds.add(NAMESPACE + ":_crop/" + crop + "/stage_" + point);
            }
            cropModels.put("kaleidoscope_tavern_" + crop.substring(0, crop.indexOf("_crop")), stageIds);
        }
        for (var cropEntry : cropModels.entrySet()) {
            for (String stageId : cropEntry.getValue()) {
                JsonObject block = blocks.getAsJsonObject(stageId);
                JsonElement behavior = block.get("behavior");
                List<JsonElement> behaviors = behavior.isJsonArray()
                        ? behavior.getAsJsonArray().asList() : List.of(behavior);
                Set<String> behaviorTypes = new LinkedHashSet<>();
                for (JsonElement b : behaviors) {
                    if (b.isJsonObject()) behaviorTypes.add(b.getAsJsonObject().get("type").getAsString());
                }
                if (!behaviorTypes.equals(Set.of(NAMESPACE + ":hanging_grape_crop"))) {
                    throw new ValidationException(stageId + ": hanging crop survival guard is missing");
                }
                if (block.has("states")) {
                    throw new ValidationException(
                            stageId + ": CustomCrops stages must be addressable by block id");
                }
                JsonObject settings = block.getAsJsonObject("settings");
                if (settings.get("hardness").getAsDouble() != 0
                        || settings.get("resistance").getAsDouble() != 0
                        || !settings.getAsJsonObject("sounds").get("break").getAsString()
                                .equals("minecraft:block.crop.break")
                        || !settings.getAsJsonObject("sounds").get("place").getAsString()
                                .equals("minecraft:item.crop.plant")) {
                    throw new ValidationException(stageId + ": crop material semantics drifted");
                }
            }
        }
        Matcher cropMatcher = Pattern.compile("(?m)^([a-z0-9_]+):$").matcher(customCropsText);
        Set<String> configuredCrops = new LinkedHashSet<>();
        while (cropMatcher.find()) configuredCrops.add(cropMatcher.group(1));
        if (!configuredCrops.containsAll(cropModels.keySet())) {
            throw new ValidationException("Managed CustomCrops file is missing a grape crop definition");
        }
        if (countPrefix(customCropsText, "  custom-bone-meal:") != 3) {
            throw new ValidationException("Every managed grape crop must delegate bone meal to CustomCrops");
        }
        Matcher boneMealMatcher = Pattern.compile(
                "(?ms)^  custom-bone-meal:\\r?\\n(?:(?!^[a-z0-9_]+:).)*").matcher(customCropsText);
        int boneMealSections = 0;
        while (boneMealMatcher.find()) {
            boneMealSections++;
            if (!boneMealMatcher.group().contains("type: swing-hand")) {
                throw new ValidationException("Every managed grape bone-meal action must swing the player's hand");
            }
        }
        if (boneMealSections != 3) {
            throw new ValidationException("Every managed grape crop must delegate bone meal to CustomCrops");
        }
        Map<String, String> expectedSeasons = Map.of(
                "kaleidoscope_tavern_grape", "value: [Summer, Autumn]",
                "kaleidoscope_tavern_ice_grape", "value: [Winter]",
                "kaleidoscope_tavern_gold_grape", "value: [Summer]");
        Map<String, String> cropSections = new LinkedHashMap<>();
        for (String cropId : cropModels.keySet()) {
            Matcher sectionMatcher = Pattern.compile(
                    "(?ms)^" + Pattern.quote(cropId) + ":\\r?\\n(?:(?!^[a-z0-9_]+:).)*")
                    .matcher(customCropsText);
            if (!sectionMatcher.find()) {
                throw new ValidationException(cropId + ": managed CustomCrops section is missing");
            }
            cropSections.put(cropId, sectionMatcher.group());
        }
        for (var sectionEntry : cropSections.entrySet()) {
            String section = sectionEntry.getValue();
            for (String token : List.of("ignore-random-tick: false", "ignore-scheduled-tick: false",
                    "grow-conditions:", expectedSeasons.get(sectionEntry.getKey()),
                    "value: 0.125", "value: 0.142857142857")) {
                if (!section.contains(token)) {
                    throw new ValidationException(sectionEntry.getKey()
                            + ": CustomCrops must own ticking, seasons and growth rolls; missing=" + token);
                }
            }
        }
        for (String cropId : List.of("kaleidoscope_tavern_ice_grape",
                "kaleidoscope_tavern_gold_grape")) {
            String section = cropSections.get(cropId);
            List<String> required = new ArrayList<>();
            if (cropId.equals("kaleidoscope_tavern_ice_grape")) {
                required.addAll(List.of("favored_two_points:", "favored_one_point:",
                        "type: temperature", "value: '-10~0'", "value: minecraft:snowy_beach"));
            } else {
                required.addAll(List.of("favored_two_points:", "favored_one_point:",
                        "type: temperature", "value: '2~10'"));
            }
            required.addAll(List.of("value: 0.366666666667", "value: 0.578947368421"));
            for (String token : required) {
                if (!section.contains(token)) {
                    throw new ValidationException(cropId
                            + ": CustomCrops favored-climate growth is incomplete; missing=" + token);
                }
            }
        }
        String pluginConfigText = readText(projectRoot.resolve("src/paper/resources/config.yml"));
        if (Pattern.compile("(?m)^  hanging-(?:gold-|ice-)?grape:").matcher(pluginConfigText).find()) {
            throw new ValidationException(
                    "Hanging grape seasons must live only in the managed CustomCrops crop file");
        }
    }

    private static JsonObject fontBitmap(String file, int ascent, int height, int charCode) {
        JsonObject provider = new JsonObject();
        provider.addProperty("type", "bitmap");
        provider.addProperty("file", file);
        provider.addProperty("ascent", ascent);
        provider.addProperty("height", height);
        JsonArray chars = new JsonArray();
        chars.add(String.valueOf((char) charCode));
        provider.add("chars", chars);
        return provider;
    }

    private boolean assetExists(String resourceId, String folder, String suffix) {
        int colon = resourceId.indexOf(':');
        String namespace = resourceId.substring(0, colon);
        String path = resourceId.substring(colon + 1);
        Path relative = Path.of(namespace, folder, path + suffix);
        for (Path root : List.of(packAssetsRoot, generatedAssetsRoot, mainAssetsRoot)) {
            if (Files.isRegularFile(root.resolve(relative))) return true;
        }
        return false;
    }

    private static int countPrefix(String text, String prefix) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(prefix, index)) >= 0) {
            count++;
            index += prefix.length();
        }
        return count;
    }

    private static boolean hasNonNull(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull();
    }

    private static JsonObject obj(String key, String value) {
        JsonObject object = new JsonObject();
        object.addProperty(key, value);
        return object;
    }

    private static JsonObject jsonObjectOf(Map<String, JsonElement> values) {
        JsonObject object = new JsonObject();
        values.forEach(object::add);
        return object;
    }

    private JsonObject readConfig(String name) throws IOException {
        return readJson(projectRoot.resolve("src/paper/pack/configuration").resolve(name));
    }

    private JsonObject assetJson(String resourceId, String folder) throws IOException {
        int colon = resourceId.indexOf(':');
        String namespace = resourceId.substring(0, colon);
        String path = resourceId.substring(colon + 1);
        Path relative = Path.of(namespace, folder, path + ".json");
        for (Path root : List.of(packAssetsRoot, generatedAssetsRoot, mainAssetsRoot)) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) return readJson(candidate);
        }
        return null;
    }

    private static String readText(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        return text;
    }

    private static JsonObject readJson(Path path) throws IOException {
        return JsonParser.parseString(readText(path)).getAsJsonObject();
    }
}
