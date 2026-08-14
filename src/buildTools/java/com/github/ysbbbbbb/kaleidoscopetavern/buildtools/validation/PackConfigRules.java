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
