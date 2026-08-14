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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native port of validate_pack.py's placed-drink furniture family and model
 * geometry contracts: cardinal bottle direction mapping, sculk ripple
 * directions, forced translucency on pendant/fluid models, vanilla-source
 * bottle loot, vessel element shape, storage cutout, ordered model bounds,
 * potion geometry immutability, ripple plane and opaque decoration classes.
 */
public final class PlacedDrinkSemanticsValidator {
    public static final String NAMESPACE = "kaleidoscope_tavern";
    public static final String CARDINAL_BOTTLE_AXIS_SUFFIX = "_axis_x";
    public static final int CARDINAL_BOTTLE_AXIS_YAW = 180;
    public static final int SCULK_RIPPLE_ELEMENT_INDEX = 12;
    public static final String SCULK_RIPPLE_MODEL_PATH =
            "furniture/placed_drink/" + NAMESPACE + "/block/mixology/sculk_special_ripple";
    public static final String SCULK_RIPPLE_MODEL_ID = NAMESPACE + ":" + SCULK_RIPPLE_MODEL_PATH;
    public static final String SCULK_RIPPLE_RENDER_ID = NAMESPACE + ":_render/sculk_special/ripple";

    private static final Set<String> PENDANT_LAMPS = Set.of(
            "bell_pendant_lamp", "yellow_pendant_lamp", "blue_pendant_lamp");
    private static final Set<String> PRESS_FLUIDS = Set.of(
            "glow_berries_juice", "gold_grape_juice", "grape_juice", "green_grape_juice",
            "ice_grape_juice", "sweet_berries_juice");
    private static final Set<String> BARREL_FLUIDS = Set.of(
            "glow_berries_juice", "gold_grape_juice", "grape_juice", "green_grape_juice",
            "ice_grape_juice", "sweet_berries_juice", "water", "lava");
    private static final Set<String> SIMPLE_BOTTLES = Set.of(
            "water_bottle", "honey_bottle", "dragon_breath_bottle",
            "potion_bottle", "xp_bottle");
    private static final Set<String> EXPECTED_BOTTLE_FURNITURE = Set.of(
            "empty_bottle", "empty_glassware", "signature_cocktail", "mystery_cocktail",
            "white_lady", "emerald", "brass_heart", "godfather", "grasshopper",
            "screwdriver", "mojito", "allium_garden", "depth_charge", "nether_special",
            "bloody_mary", "sculk_special", "molotov", "water_bottle", "honey_bottle",
            "dragon_breath_bottle", "potion_bottle", "xp_bottle", "wine", "champagne",
            "vodka", "brandy", "carignan", "sakura_wine", "plum_wine", "whiskey",
            "ice_wine", "polaris_sweet_white", "honey_wine", "red_queen", "miners_star",
            "rum", "riesling_dry_white", "sunset_glow", "madame_shexiang",
            "sweet_berry_wine", "sherry", "mother_snow", "luminous_bride",
            "glowflower_brew", "sauvignon_blanc_dry_white", "vinegar", "watermelon_juice");
    private static final Set<String> EXPECTED_CONSUMABLE_COCKTAILS = Set.of(
            "signature_cocktail", "mystery_cocktail", "white_lady", "emerald",
            "brass_heart", "godfather", "grasshopper", "screwdriver", "mojito",
            "allium_garden", "depth_charge", "nether_special", "bloody_mary",
            "sculk_special");
    private static final Set<String> EXPECTED_ROTATION_16_VESSELS = union(
            EXPECTED_CONSUMABLE_COCKTAILS, union(Set.of("empty_glassware"), SIMPLE_BOTTLES));
    private static final Set<String> EXPECTED_DIRECTIONLESS_VESSELS = Set.of("molotov");
    private static final Set<String> EXPECTED_CARDINAL_BOTTLE_FURNITURE = Set.copyOf(
            difference(difference(EXPECTED_BOTTLE_FURNITURE, EXPECTED_ROTATION_16_VESSELS),
                    EXPECTED_DIRECTIONLESS_VESSELS));
    private static final List<String> BAR_STOOL_COLORS = List.of(
            "black", "blue", "brown", "cyan", "gray", "green", "light_blue",
            "light_gray", "lime", "magenta", "orange", "pink", "purple", "red",
            "white", "yellow");
    private static final Map<String, List<Integer>> OPAQUE_PLACED_DRINK_ELEMENTS = Map.ofEntries(
            Map.entry("allium_garden", List.of(12, 13)),
            Map.entry("bloody_mary", List.of(1, 10, 11, 12, 13)),
            Map.entry("brass_heart", List.of(11)),
            Map.entry("depth_charge", List.of(8, 9, 10, 11)),
            Map.entry("emerald", List.of(5, 6, 7)),
            Map.entry("godfather", List.of(9, 10, 11, 12, 13)),
            Map.entry("grasshopper", List.of(0, 1, 2, 13)),
            Map.entry("mojito", List.of(0, 10, 11, 12, 13)),
            Map.entry("mystery_cocktail", List.of(12)),
            Map.entry("nether_special", List.of(12, 13, 14, 15)),
            Map.entry("screwdriver", List.of(7, 8, 9)),
            Map.entry("sculk_special", List.of(12, 13, 14)),
            Map.entry("signature_cocktail", List.of(12, 13, 14)),
            Map.entry("white_lady", List.of(10, 11, 12)));

    private final Path projectRoot;
    private final Path packAssetsRoot;

    public PlacedDrinkSemanticsValidator(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.packAssetsRoot = projectRoot.resolve(
                "src/paper/pack/resourcepack/assets");
    }

    public static final class ValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public ValidationException(String message) { super(message); }
    }

    public void validate(JsonObject items, JsonObject renderItems, JsonObject furniture)
            throws IOException {
        validateCardinalBottleDirectionMapping();
        for (String lamp : sorted(PENDANT_LAMPS)) {
            for (String half : List.of("top", "bottom")) {
                assertForcedTranslucency(NAMESPACE + ":block/deco/" + lamp + "/" + half,
                        Set.of("2"), lamp + "/" + half);
            }
        }
        for (String fluid : sorted(PRESS_FLUIDS)) {
            assertForcedTranslucency(NAMESPACE + ":furniture/pressing_fluid/" + fluid,
                    Set.of("fluid"), "pressing_fluid/" + fluid);
        }
        for (String fluid : sorted(BARREL_FLUIDS)) {
            assertForcedTranslucency(NAMESPACE + ":furniture/barrel_fluid/" + fluid,
                    Set.of("fluid"), "barrel_fluid/" + fluid);
        }
        JsonObject sourceItemLoot = new JsonObject();
        JsonObject pool = new JsonObject();
        pool.addProperty("rolls", 1);
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "furniture_item");
        JsonArray entries = new JsonArray();
        entries.add(entry);
        pool.add("entries", entries);
        JsonArray pools = new JsonArray();
        pools.add(pool);
        sourceItemLoot.add("pools", pools);
        for (String bottleId : sorted(SIMPLE_BOTTLES)) {
            JsonObject config = furniture.getAsJsonObject(NAMESPACE + ":" + bottleId);
            if (!sourceItemLoot.equals(config.get("loot"))) {
                throw new ValidationException(
                        bottleId + ": vanilla-source bottle must use CE sourceItem furniture loot");
            }
            if (config.has("settings") && config.getAsJsonObject("settings").has("item")) {
                throw new ValidationException(
                        bottleId + ": vanilla-source bottle must not invent a duplicate CE item");
            }
        }
        Map<String, String> placedDrinkModels = new java.util.LinkedHashMap<>();
        for (String bottleId : sorted(EXPECTED_BOTTLE_FURNITURE)) {
            JsonObject config = furniture.getAsJsonObject(NAMESPACE + ":" + bottleId);
            JsonObject variants = obj(config.get("variants"));
            for (var variantEntry : variants.entrySet()) {
                String variantName = variantEntry.getKey();
                JsonObject variant = variantEntry.getValue().getAsJsonObject();
                JsonArray elements = variant.has("elements") ? variant.getAsJsonArray("elements")
                        : new JsonArray();
                for (JsonElement raw : elements) {
                    JsonObject element = raw.getAsJsonObject();
                    if (element.has("type") && !element.get("type").getAsString().equals("item_display")) {
                        throw new ValidationException(bottleId + "/" + variantName
                                + ": vessel furniture must use item displays");
                    }
                }
                if (bottleId.equals("sculk_special")) {
                    if (elements.size() != 17) {
                        throw new ValidationException(
                                "sculk_special: expected one rotating body and sixteen "
                                        + "world-fixed ripple alternatives");
                    }
                    JsonObject body = elements.get(0).getAsJsonObject();
                    if (body.has("conditions") || body.has("yaw")) {
                        throw new ValidationException(
                                "sculk_special: the glass body must follow native CE sixteen-way yaw");
                    }
                    Set<String> rippleDirections = new LinkedHashSet<>();
                    List<JsonObject> rippleBases = new ArrayList<>();
                    for (int i = 1; i < elements.size(); i++) {
                        JsonObject ripple = elements.get(i).getAsJsonObject();
                        if (!ripple.has("conditions") || ripple.getAsJsonArray("conditions").size() != 1
                                || !ripple.getAsJsonArray("conditions").get(0).getAsJsonObject()
                                        .has("type")
                                || !ripple.getAsJsonArray("conditions").get(0).getAsJsonObject()
                                        .get("type").getAsString().equals("expression")) {
                            throw new ValidationException(
                                    "sculk_special: each fixed ripple alternative needs one "
                                            + "native CE expression condition");
                        }
                        rippleDirections.add(ripple.getAsJsonArray("conditions").get(0)
                                .getAsJsonObject().get("expression").getAsString() + "|"
                                + (ripple.has("yaw") ? ripple.get("yaw").getAsDouble() : "null"));
                        JsonObject base = new JsonObject();
                        for (var e : ripple.entrySet()) {
                            if (!e.getKey().equals("conditions") && !e.getKey().equals("yaw")) {
                                base.add(e.getKey(), e.getValue().deepCopy());
                            }
                        }
                        rippleBases.add(base);
                    }
                    if (!rippleDirections.equals(expectedSculkRippleDirections())) {
                        throw new ValidationException(
                                "sculk_special: ripple yaw alternatives no longer cancel all "
                                        + "sixteen furniture directions");
                    }
                    for (JsonObject base : rippleBases.subList(1, rippleBases.size())) {
                        if (!base.equals(rippleBases.get(0))) {
                            throw new ValidationException(
                                    "sculk_special: fixed ripple alternatives must share one model "
                                            + "and exact display transform");
                        }
                    }
                    if (!rippleBases.get(0).has("item")
                            || !rippleBases.get(0).get("item").getAsString().equals(SCULK_RIPPLE_RENDER_ID)) {
                        throw new ValidationException(
                                "sculk_special: fixed ripple alternatives must share one model "
                                        + "and exact display transform");
                    }
                    if (body.has("item") && body.get("item").getAsString().equals(SCULK_RIPPLE_RENDER_ID)) {
                        throw new ValidationException(
                                "sculk_special: rotating body and fixed ripple need separate models");
                    }
                } else if (EXPECTED_CARDINAL_BOTTLE_FURNITURE.contains(bottleId)) {
                    if (elements.size() != 1) {
                        throw new ValidationException(bottleId + "/" + variantName
                                + ": cardinal bottle furniture must contain exactly one "
                                + "unconditional CE item-display element");
                    }
                    JsonObject element = elements.get(0).getAsJsonObject();
                    if (element.has("conditions")) {
                        throw new ValidationException(bottleId + "/" + variantName
                                + ": cardinal stack variants must not depend on CE furniture "
                                + "context expressions");
                    }
                    boolean axisX = variantName.endsWith(CARDINAL_BOTTLE_AXIS_SUFFIX);
                    String baseName = axisX ? variantName.substring(0,
                            variantName.length() - CARDINAL_BOTTLE_AXIS_SUFFIX.length()) : variantName;
                    String pairedName = axisX ? baseName : variantName + CARDINAL_BOTTLE_AXIS_SUFFIX;
                    if (!variants.has(pairedName)) {
                        throw new ValidationException(bottleId + "/" + variantName
                                + ": missing paired four-way axis variant " + pairedName);
                    }
                    Double expectedYaw = axisX ? (double) CARDINAL_BOTTLE_AXIS_YAW : null;
                    if (!element.has("yaw") != (expectedYaw == null)
                            || (expectedYaw != null
                                && element.get("yaw").getAsDouble() != expectedYaw)) {
                        throw new ValidationException(bottleId + "/" + variantName
                                + ": expected axis compensation yaw " + expectedYaw
                                + ", found " + (element.has("yaw") ? element.get("yaw") : "absent"));
                    }
                    JsonArray pairedElements = variants.getAsJsonObject(pairedName)
                            .getAsJsonArray("elements");
                    if (pairedElements.size() != 1) {
                        throw new ValidationException(bottleId + "/" + pairedName
                                + ": paired axis variant must contain exactly one item display");
                    }
                    JsonObject baseElement = new JsonObject();
                    for (var e : element.entrySet()) if (!e.getKey().equals("yaw")) {
                        baseElement.add(e.getKey(), e.getValue().deepCopy());
                    }
                    JsonObject pairedBase = new JsonObject();
                    for (var e : pairedElements.get(0).getAsJsonObject().entrySet()) {
                        if (!e.getKey().equals("yaw")) pairedBase.add(e.getKey(), e.getValue().deepCopy());
                    }
                    if (!baseElement.equals(pairedBase)) {
                        throw new ValidationException(bottleId + "/" + variantName
                                + ": axis variants must share one render item and exact display transform");
                    }
                } else {
                    if (elements.size() != 1) {
                        throw new ValidationException(bottleId + "/" + variantName
                                + ": CE-native sixteen/fixed rotation must use exactly one "
                                + "item-display element");
                    }
                    JsonObject element = elements.get(0).getAsJsonObject();
                    if (element.has("conditions") || element.has("yaw")) {
                        throw new ValidationException(bottleId + "/" + variantName
                                + ": CE must own the furniture yaw without conditional display "
                                + "copies or element yaw");
                    }
                }
                for (JsonElement raw : elements) {
                    JsonObject element = raw.getAsJsonObject();
                    if (!element.has("type")
                            || !element.get("type").getAsString().equals("item_display")) continue;
                    String renderId = element.get("item").getAsString();
                    JsonObject definition = renderItems.has(renderId)
                            ? renderItems.getAsJsonObject(renderId) : new JsonObject();
                    JsonElement modelPath = definition.has("model")
                            ? definition.getAsJsonObject("model").get("path") : null;
                    if (modelPath == null || !modelPath.isJsonPrimitive()) {
                        throw new ValidationException(bottleId + "/" + variantName
                                + ": displayed drink item " + renderId
                                + " must select a vanilla model path");
                    }
                    placedDrinkModels.put(modelPath.getAsString(), bottleId + "/" + variantName);
                }
            }
        }
        for (var renderEntry : renderItems.entrySet()) {
            String renderId = renderEntry.getKey();
            if (!renderId.startsWith(NAMESPACE + ":_render/storage/")) continue;
            JsonObject definition = renderEntry.getValue().getAsJsonObject();
            JsonElement modelPath = definition.has("model")
                    ? definition.getAsJsonObject("model").get("path") : null;
            if (modelPath != null && modelPath.isJsonPrimitive()) {
                placedDrinkModels.put(modelPath.getAsString(), renderId);
                String path = modelPath.getAsString();
                if (path.contains("/block/brew/drink/")) {
                    JsonObject model = assetJson(path, "models", List.of(packAssetsRoot));
                    if (model == null) {
                        throw new ValidationException(renderId
                                + ": missing cutout storage bottle model " + path);
                    }
                    Set<String> forcedSlots = forcedTranslucentSlots(model);
                    if (!forcedSlots.isEmpty()) {
                        throw new ValidationException(renderId
                                + ": binary-alpha storage bottle slots " + forcedSlots
                                + " must use cutout rendering to avoid flicker");
                    }
                }
            }
        }
        for (var modelEntry : placedDrinkModels.entrySet()) {
            assertOrderedModelBounds(modelEntry.getKey(), modelEntry.getValue());
            assertNoForgeRenderType(modelEntry.getKey(), modelEntry.getValue());
        }
        JsonObject sourcePotion = assetJson(NAMESPACE + ":block/brew/potion_bottle",
                "models", sourceAssetRoots());
        JsonObject migratedPotion = assetJson(
                NAMESPACE + ":furniture/placed_drink/" + NAMESPACE + "/block/brew/potion_bottle",
                "models", List.of(packAssetsRoot));
        if (sourcePotion == null || migratedPotion == null) {
            throw new ValidationException("potion_bottle: missing source or migrated model");
        }
        if (!migratedPotion.get("elements").equals(sourcePotion.get("elements"))) {
            throw new ValidationException(
                    "potion_bottle: Paper migration must not alter the authored geometry or UVs");
        }
        JsonObject rippleRender = renderItems.has(SCULK_RIPPLE_RENDER_ID)
                ? renderItems.getAsJsonObject(SCULK_RIPPLE_RENDER_ID) : new JsonObject();
        if (!rippleRender.has("model")
                || !rippleRender.getAsJsonObject("model").has("path")
                || !rippleRender.getAsJsonObject("model").get("path").getAsString()
                        .equals(SCULK_RIPPLE_MODEL_ID)) {
            throw new ValidationException(
                    "sculk_special: fixed ripple render item is missing its split model");
        }
        JsonObject rippleModel = assetJson(SCULK_RIPPLE_MODEL_ID, "models",
                List.of(packAssetsRoot));
        if (rippleModel == null) {
            throw new ValidationException("sculk_special: missing split ripple model");
        }
        JsonArray rippleGeometry = rippleModel.has("elements")
                ? rippleModel.getAsJsonArray("elements") : new JsonArray();
        if (rippleGeometry.size() != 1
                || !jsonArrayEquals(rippleGeometry.get(0).getAsJsonObject().get("from"),
                        List.of(0.0, 0.1, 0.0))
                || !jsonArrayEquals(rippleGeometry.get(0).getAsJsonObject().get("to"),
                        List.of(16.0, 0.1, 16.0))
                || !rippleGeometry.get(0).getAsJsonObject().has("faces")
                || !rippleGeometry.get(0).getAsJsonObject().getAsJsonObject("faces").keySet()
                        .equals(Set.of("up"))) {
            throw new ValidationException(
                    "sculk_special: fixed ripple must remain the authored 16x16 top plane");
        }
        if (!OPAQUE_PLACED_DRINK_ELEMENTS.keySet().equals(EXPECTED_CONSUMABLE_COCKTAILS)) {
            throw new ValidationException(
                    "Every consumable cocktail must explicitly classify its opaque decorations");
        }
        for (var opaqueEntry : OPAQUE_PLACED_DRINK_ELEMENTS.entrySet()) {
            String drinkId = opaqueEntry.getKey();
            String resourcePath = "furniture/placed_drink/" + NAMESPACE
                    + "/block/mixology/" + drinkId;
            String modelId = NAMESPACE + ":" + resourcePath;
            JsonObject model = assetJson(modelId, "models", List.of(packAssetsRoot));
            if (model == null) {
                throw new ValidationException(drinkId + ": missing private placed-drink model");
            }
            JsonObject textures = model.has("textures") ? model.getAsJsonObject("textures")
                    : new JsonObject();
            String opaqueSprite = NAMESPACE + ":furniture/placed_drink/opaque/" + NAMESPACE
                    + "/block/mixology/" + drinkId;
            if (!textures.has("opaque_detail")
                    || !textures.get("opaque_detail").getAsString().equals(opaqueSprite)) {
                throw new ValidationException(
                        drinkId + ": opaque detail must use its private cutout sprite");
            }
            if (!assetExists(opaqueSprite, "textures", ".png")) {
                throw new ValidationException(drinkId + ": missing opaque detail texture");
            }
            Set<String> forcedSlots = forcedTranslucentSlots(model);
            if (forcedSlots.size() != 1) {
                throw new ValidationException(drinkId
                        + ": glass/liquid must retain one forced-translucent slot");
            }
            JsonArray elements = model.has("elements") ? model.getAsJsonArray("elements")
                    : new JsonArray();
            List<Integer> privateOpaqueIndices = new ArrayList<>();
            for (int index : opaqueEntry.getValue()) {
                if (drinkId.equals("sculk_special") && index == SCULK_RIPPLE_ELEMENT_INDEX) continue;
                int shifted = (drinkId.equals("sculk_special") && index > SCULK_RIPPLE_ELEMENT_INDEX)
                        ? index - 1 : index;
                privateOpaqueIndices.add(shifted);
            }
            List<Integer> missingIndices = new ArrayList<>();
            for (int index : privateOpaqueIndices) {
                if (index >= elements.size()) missingIndices.add(index);
            }
            if (!missingIndices.isEmpty()) {
                throw new ValidationException(drinkId + ": missing opaque model elements "
                        + missingIndices);
            }
            String translucentSlot = forcedSlots.iterator().next();
            int elementIndex = 0;
            for (JsonElement rawElement : elements) {
                JsonObject element = rawElement.getAsJsonObject();
                Set<String> faceTextures = new LinkedHashSet<>();
                if (element.has("faces")) {
                    for (JsonElement rawFace : element.getAsJsonObject("faces").asMap().values()) {
                        JsonObject face = rawFace.getAsJsonObject();
                        if (face.has("texture")) faceTextures.add(face.get("texture").getAsString());
                    }
                }
                if (privateOpaqueIndices.contains(elementIndex)) {
                    if (!faceTextures.equals(Set.of("#opaque_detail"))) {
                        throw new ValidationException(drinkId + ": decoration element "
                                + elementIndex + " is not fully opaque");
                    }
                } else if (!faceTextures.equals(Set.of("#" + translucentSlot))) {
                    throw new ValidationException(drinkId + ": glass/liquid element "
                            + elementIndex + " must remain translucent");
                }
                elementIndex++;
            }
            Path sourceMeta = projectRoot.resolve("src/main/resources/assets/" + NAMESPACE
                    + "/textures/block/mixology/" + drinkId + ".png.mcmeta");
            Path generatedMeta = projectRoot.resolve("src/paper/pack/resourcepack/assets/"
                    + NAMESPACE + "/textures/furniture/placed_drink/opaque/" + NAMESPACE
                    + "/block/mixology/" + drinkId + ".png.mcmeta");
            if (Files.isRegularFile(sourceMeta)
                    && (!Files.isRegularFile(generatedMeta)
                        || !java.util.Arrays.equals(Files.readAllBytes(sourceMeta),
                            Files.readAllBytes(generatedMeta)))) {
                throw new ValidationException(drinkId
                        + ": opaque animated detail must preserve source frames");
            }
        }
        assertNoForgeRenderType(NAMESPACE + ":furniture/bar_stool_body_base",
                "bar_stool_body_base");
        for (String color : BAR_STOOL_COLORS) {
            assertNoForgeRenderType(NAMESPACE + ":furniture/bar_stool_body/" + color,
                    "bar_stool_body/" + color);
        }
    }

    private static void validateCardinalBottleDirectionMapping() {
        Map<String, Integer> playerYaw = Map.of("north", 180, "east", -90, "south", 0, "west", 90);
        Map<String, String> opposite = Map.of("north", "south", "east", "west",
                "south", "north", "west", "east");
        Map<String, Integer> sourceBlockstateYaw = Map.of("north", 0, "east", 90,
                "south", 180, "west", 270);
        Map<String, Integer> actual = new java.util.TreeMap<>();
        Map<String, Integer> expected = new java.util.TreeMap<>();
        for (var entry : playerYaw.entrySet()) {
            String playerFacing = entry.getKey();
            int yaw = entry.getValue();
            int furnitureYaw = Math.round((180 + yaw) / 90.0f) * 90;
            int axis = Math.abs(Math.floorMod(furnitureYaw, 180));
            int elementYaw = (axis <= 45 || axis > 135) ? 0 : 180;
            actual.put(playerFacing, Math.floorMod(180 - (furnitureYaw + elementYaw), 360));
            expected.put(playerFacing, sourceBlockstateYaw.get(opposite.get(playerFacing)));
        }
        if (!actual.equals(expected)) {
            throw new ValidationException("CE bottle direction no longer matches Forge "
                    + "FACING=player.opposite blockstates: actual=" + actual
                    + ", expected=" + expected);
        }
    }

    private static Set<String> expectedSculkRippleDirections() {
        Set<String> directions = new LinkedHashSet<>();
        for (int segment = 0; segment < 16; segment++) {
            double furnitureYaw = segment * 22.5;
            String normalized = formatSix(furnitureYaw);
            String expression = "ABS((((<arg:furniture.yaw> % 360) + 360) % 360) - "
                    + normalized + ") < 0.001";
            String yaw = furnitureYaw == 0 ? "null" : String.valueOf(-furnitureYaw);
            directions.add(expression + "|" + yaw);
            if (Math.floorMod((long) (furnitureYaw + (furnitureYaw == 0 ? 0 : -furnitureYaw)), 360) != 0) {
                throw new ValidationException("sculk_special: ripple yaw " + yaw
                        + " does not cancel " + furnitureYaw);
            }
        }
        return directions;
    }

    private static String formatSix(double value) {
        String fixed = String.format(java.util.Locale.ROOT, "%.6f", value);
        while (fixed.endsWith("0")) fixed = fixed.substring(0, fixed.length() - 1);
        if (fixed.endsWith(".")) fixed = fixed.substring(0, fixed.length() - 1);
        return fixed;
    }

    private void assertForcedTranslucency(String resourceId, Set<String> textureSlots,
                                          String owner) throws IOException {
        JsonObject model = assetJson(resourceId, "models", List.of(packAssetsRoot));
        if (model == null) {
            throw new ValidationException(owner + ": missing generated translucent model " + resourceId);
        }
        if (model.has("render_type")) {
            throw new ValidationException(owner
                    + ": Forge render_type is ignored by the vanilla 26.2 client");
        }
        if (!model.has("textures")) {
            throw new ValidationException(owner + ": generated translucent model has no textures");
        }
        JsonObject textures = model.getAsJsonObject("textures");
        for (String slot : sorted(textureSlots)) {
            JsonElement descriptor = textures.get(slot);
            if (descriptor == null || !descriptor.isJsonObject()
                    || !descriptor.getAsJsonObject().has("sprite")
                    || !descriptor.getAsJsonObject().get("sprite").isJsonPrimitive()
                    || !descriptor.getAsJsonObject().has("force_translucent")
                    || !descriptor.getAsJsonObject().get("force_translucent").getAsBoolean()) {
                throw new ValidationException(owner + ": texture slot '" + slot
                        + "' must use a sprite descriptor with force_translucent=true");
            }
        }
    }

    private void assertNoForgeRenderType(String resourceId, String owner) throws IOException {
        JsonObject model = assetJson(resourceId, "models", List.of(packAssetsRoot));
        if (model == null) {
            throw new ValidationException(owner + ": missing displayed model " + resourceId);
        }
        if (model.has("render_type")) {
            throw new ValidationException(owner + ": " + resourceId
                    + " keeps Forge render_type, which the vanilla 26.2 client ignores "
                    + "and renders opaque");
        }
    }

    private void assertOrderedModelBounds(String resourceId, String owner) throws IOException {
        JsonObject model = assetJson(resourceId, "models", List.of(packAssetsRoot));
        if (model == null) {
            throw new ValidationException(owner + ": missing displayed model " + resourceId);
        }
        if (!model.has("elements")) return;
        int index = 0;
        for (JsonElement rawElement : model.getAsJsonArray("elements")) {
            JsonObject element = rawElement.getAsJsonObject();
            if (!element.has("from") || !element.has("to")) { index++; continue; }
            JsonArray start = element.getAsJsonArray("from");
            JsonArray end = element.getAsJsonArray("to");
            if (start.size() != 3 || end.size() != 3) { index++; continue; }
            String[] axisNames = {"x", "y", "z"};
            for (int axis = 0; axis < 3; axis++) {
                if (start.get(axis).getAsDouble() > end.get(axis).getAsDouble()) {
                    throw new ValidationException(owner + ": " + resourceId + " element "
                            + index + " has descending " + axisNames[axis]
                            + " bounds, which exposes its back face when rendered as furniture");
                }
            }
            index++;
        }
    }

    private Set<String> forcedTranslucentSlots(JsonObject model) {
        Set<String> slots = new LinkedHashSet<>();
        if (!model.has("textures")) return slots;
        for (var entry : model.getAsJsonObject("textures").entrySet()) {
            JsonElement texture = entry.getValue();
            if (texture.isJsonObject() && texture.getAsJsonObject().has("force_translucent")
                    && texture.getAsJsonObject().get("force_translucent").getAsBoolean()) {
                slots.add(entry.getKey());
            }
        }
        return slots;
    }

    private boolean assetExists(String resourceId, String folder, String suffix) {
        int colon = resourceId.indexOf(':');
        String namespace = resourceId.substring(0, colon);
        String path = resourceId.substring(colon + 1);
        Path relative = Path.of(namespace, folder, path + suffix);
        for (Path root : List.of(packAssetsRoot, projectRoot.resolve("src/generated/resources/assets"),
                projectRoot.resolve("src/main/resources/assets"))) {
            if (Files.isRegularFile(root.resolve(relative))) return true;
        }
        return false;
    }

    private JsonObject assetJson(String resourceId, String folder, List<Path> roots)
            throws IOException {
        int colon = resourceId.indexOf(':');
        String namespace = resourceId.substring(0, colon);
        String path = resourceId.substring(colon + 1);
        Path relative = Path.of(namespace, folder, path + ".json");
        for (Path root : roots) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                String text = Files.readString(candidate, StandardCharsets.UTF_8);
                if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
                return JsonParser.parseString(text).getAsJsonObject();
            }
        }
        return null;
    }

    private List<Path> sourceAssetRoots() {
        return List.of(projectRoot.resolve("src/generated/resources/assets"),
                projectRoot.resolve("src/main/resources/assets"));
    }

    private static boolean jsonArrayEquals(JsonElement array, List<Double> values) {
        if (array == null || !array.isJsonArray()) return false;
        JsonArray a = array.getAsJsonArray();
        if (a.size() != values.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).getAsDouble() != values.get(i)) return false;
        }
        return true;
    }

    private static JsonObject obj(JsonElement element) {
        return element == null ? new JsonObject() : element.getAsJsonObject();
    }

    private static List<String> sorted(Set<String> values) {
        List<String> result = new ArrayList<>(values);
        result.sort(String::compareTo);
        return result;
    }

    private static <T> Set<T> union(Set<T> a, Set<T> b) {
        Set<T> result = new LinkedHashSet<>(a);
        result.addAll(b);
        return result;
    }

    private static <T> Set<T> difference(Set<T> a, Set<T> b) {
        Set<T> result = new LinkedHashSet<>(a);
        result.removeAll(b);
        return result;
    }
}
