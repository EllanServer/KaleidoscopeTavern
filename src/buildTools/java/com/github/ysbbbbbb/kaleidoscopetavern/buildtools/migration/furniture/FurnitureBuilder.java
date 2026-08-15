package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.furniture;

import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.image.LegacyImageAndPlacedDrinkStage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native port of the legacy furniture builder (tools/migrate_legacy.py:
 * blockstate_records, record_score, select_record, ensure_render_item,
 * furniture_element, cardinal_bottle_axis_element,
 * sculk_special_furniture_elements, semantic_variant_name,
 * furniture_behaviors, table_furniture_variant_name, furniture_rotation_rule,
 * furniture_rules, furniture_settings, build_furniture,
 * build_wall_pressing_tub_furniture and string_lights_dye_events).
 */
public final class FurnitureBuilder {
    public static final String NAMESPACE = "kaleidoscope_tavern";
    public static final String WALL_PRESSING_TUB_ID = NAMESPACE + ":_internal/wall_pressing_tub";
    public static final String CARDINAL_BOTTLE_AXIS_SUFFIX = "_axis_x";
    public static final int CARDINAL_BOTTLE_AXIS_YAW = 180;
    public static final int SCULK_RIPPLE_ELEMENT_INDEX = 12;
    public static final String SCULK_RIPPLE_MODEL_PATH =
            "furniture/placed_drink/" + NAMESPACE + "/block/mixology/sculk_special_ripple";
    public static final String SCULK_RIPPLE_MODEL_ID = NAMESPACE + ":" + SCULK_RIPPLE_MODEL_PATH;
    public static final String SCULK_RIPPLE_RENDER_ID = NAMESPACE + ":_render/sculk_special/ripple";

    private static final Set<String> BOTTLE_AND_GLASS_ITEMS =
            Set.copyOf(difference(FurnitureBoxes.SMALL_FURNITURE, Set.of("shaker")));
    private static final Set<String> SIXTEEN_WAY_VESSELS =
            Set.copyOf(union(FurnitureBoxes.COCKTAILS, FurnitureBoxes.SIMPLE_BOTTLES));
    private static final Set<String> DIRECTIONLESS_VESSELS = Set.of("molotov");
    private static final Set<String> CARDINAL_BOTTLE_FURNITURE = Set.copyOf(
            difference(BOTTLE_AND_GLASS_ITEMS, union(SIXTEEN_WAY_VESSELS, DIRECTIONLESS_VESSELS)));
    private static final Set<String> IGNORED_SEMANTICS = Set.of(
            "facing", "waterlogged", "powered", "triggered", "rotation", "axis", "half", "face");
    private static final List<String> STRING_LIGHT_DYE_COLORS = List.of(
            "white", "orange", "magenta", "light_blue", "yellow", "lime",
            "pink", "gray", "light_gray", "cyan", "purple", "blue",
            "brown", "green", "red", "black");
    private static final Map<String, Integer> TABLE_FACING_YAW_OFFSETS = Map.of(
            "south", 0, "west", -90, "north", 180, "east", 90);

    private final Path projectRoot;
    private final Path outputRoot;
    private final LegacyImageAndPlacedDrinkStage imageStage;
    private final Set<String> languageKeys;

    public FurnitureBuilder(Path projectRoot, Path outputRoot) throws IOException {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
        this.imageStage = new LegacyImageAndPlacedDrinkStage(this.projectRoot, this.outputRoot);
        Path langFile = this.projectRoot.resolve(
                "src/main/resources/assets/" + NAMESPACE + "/lang/en_us.json");
        JsonObject lang = JsonParser.parseString(Files.readString(langFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
        this.languageKeys = new LinkedHashSet<>(lang.keySet());
    }

    /** Immutable blockstate model tuple: (resource id, x, y, z rotation, uvlock). */
    public record Model(String id, int x, int y, int z, boolean uvlock) {
        String digestInput() {
            return id + "|" + x + "|" + y + "|" + z + "|" + (uvlock ? "True" : "False");
        }
    }

    public record Record(Map<String, String> properties, Model model) {}

    /** Build the furniture map, render items, placement rules and metrics. */
    public FurnitureMigrationStage.Result build(List<String> furnitureIds, Set<String> itemIds)
            throws IOException {
        JsonObject furniture = new JsonObject();
        JsonObject renderItems = new JsonObject();
        LinkedHashMap<String, JsonObject> placement = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> metrics = new LinkedHashMap<>();
        metrics.put("furniture_variants", 0);

        for (String blockId : furnitureIds) {
            List<Record> records = blockstateRecords(blockId);
            JsonObject variants = new JsonObject();

            if (blockId.startsWith("string_lights_")) {
                Model selected = selectRecord(records, Map.of(
                        "facing", "north", "waterlogged", "false")).model();
                JsonObject variant = new JsonObject();
                JsonArray elements = new JsonArray();
                elements.add(furnitureElement(renderItems, blockId, "wall", selected, "wall", "0,0.0625,-0.0625"));
                variant.add("elements", elements);
                variant.add("hitboxes", FurnitureBoxes.jsonArrayOf(
                        FurnitureBoxes.furnitureHitboxes(blockId, "wall", Map.of())));
                variants.add("wall", variant);
            } else if (blockId.endsWith("_sandwich_board")) {
                Model bottom = selectRecord(records, Map.of(
                        "half", "bottom", "rotation", "0", "waterlogged", "false")).model();
                Model top = selectRecord(records, Map.of(
                        "half", "top", "rotation", "0", "waterlogged", "false")).model();
                JsonObject variant = new JsonObject();
                JsonArray elements = new JsonArray();
                elements.add(furnitureElement(renderItems, blockId, "bottom", bottom, "ground", null));
                elements.add(furnitureElement(renderItems, blockId, "top", top, "ground", "0,1,0"));
                variant.add("elements", elements);
                variant.add("hitboxes", FurnitureBoxes.jsonArrayOf(
                        FurnitureBoxes.furnitureHitboxes(blockId, "ground", Map.of())));
                variants.add("ground", variant);
            } else if (FurnitureBoxes.PENDANT_LAMPS.contains(blockId)) {
                Model upper = selectRecord(records, Map.of(
                        "half", "upper", "facing", "north")).model();
                Model lower = selectRecord(records, Map.of(
                        "half", "lower", "facing", "north")).model();
                JsonObject variant = new JsonObject();
                JsonArray elements = new JsonArray();
                elements.add(furnitureElement(renderItems, blockId, "upper", upper, "ceiling", null));
                elements.add(furnitureElement(renderItems, blockId, "lower", lower, "ceiling", "0,-1,0"));
                variant.add("elements", elements);
                variant.add("hitboxes", FurnitureBoxes.jsonArrayOf(
                        FurnitureBoxes.furnitureHitboxes(blockId, "ceiling", Map.of())));
                variants.add("ceiling", variant);
            } else if (blockId.equals("table")) {
                for (String axis : List.of("x", "z")) {
                    for (int position = 0; position < 4; position++) {
                        if (axis.equals("z") && position == 0) continue;
                        Model selected = selectRecord(records, Map.of(
                                "axis", axis, "position", String.valueOf(position),
                                "waterlogged", "false")).model();
                        String baseName = position == 0 ? "ground"
                                : "ground_axis_" + axis + "_position_" + position;
                        JsonObject baseElement = furnitureElement(
                                renderItems, blockId, baseName, selected, "ground", null);
                        List<JsonObject> hitboxes = FurnitureBoxes.furnitureHitboxes(
                                blockId, "ground", Map.of("position", String.valueOf(position)));
                        for (Map.Entry<String, Integer> entry : TABLE_FACING_YAW_OFFSETS.entrySet()) {
                            JsonObject element = baseElement.deepCopy();
                            if (entry.getValue() != 0) element.addProperty("yaw", entry.getValue());
                            String name = tableFurnitureVariantName(baseName, entry.getKey());
                            JsonObject variant = new JsonObject();
                            JsonArray elements = new JsonArray();
                            elements.add(element);
                            variant.add("elements", elements);
                            variant.add("hitboxes", FurnitureBoxes.jsonArrayOf(hitboxes));
                            variants.add(name, variant);
                        }
                    }
                }
            } else if (blockId.equals("barrel")) {
                Model closedModel = new Model(NAMESPACE + ":furniture/barrel_closed", 0, 0, 0, false);
                Model bodyModel = new Model(NAMESPACE + ":furniture/barrel_body", 0, 0, 0, false);
                Model lidModel = new Model(NAMESPACE + ":furniture/barrel_open_lid", 0, 0, 0, false);
                JsonObject closed = furnitureElement(
                        renderItems, blockId, "closed", closedModel, "ground", "0,1,0");
                closed.addProperty("view_range", 2.5);
                // No fixed brightness: the source BarrelBlockEntityRender shades
                // the tub with the block's actual light, so the display must
                // follow the environment (dark cellar = dark barrel).
                JsonObject body = furnitureElement(
                        renderItems, blockId, "open body", bodyModel, "ground", "0,1,0");
                JsonObject lid = furnitureElement(
                        renderItems, blockId, "open lid", lidModel, "ground", "0,2.495967497,0.440264912");
                lid.addProperty("rotation", "72.501658,0,0");
                body.addProperty("view_range", 2.5);
                lid.addProperty("view_range", 2.5);
                JsonObject open = new JsonObject();
                JsonArray openElements = new JsonArray();
                openElements.add(body);
                openElements.add(lid);
                open.add("elements", openElements);
                open.add("hitboxes", FurnitureBoxes.jsonArrayOf(
                        FurnitureBoxes.furnitureHitboxes(blockId, "ground", Map.of())));
                variants.add("ground", open);
                JsonObject closedVariant = new JsonObject();
                JsonArray closedElements = new JsonArray();
                closedElements.add(closed);
                closedVariant.add("elements", closedElements);
                closedVariant.add("hitboxes", FurnitureBoxes.jsonArrayOf(
                        FurnitureBoxes.furnitureHitboxes(blockId, "ground", Map.of())));
                variants.add("ground_closed", closedVariant);
            } else if (blockId.equals("stepladder")) {
                Model bottom = selectRecord(records, Map.of(
                        "facing", "north", "half", "bottom", "waterlogged", "false")).model();
                Model top = selectRecord(records, Map.of(
                        "facing", "north", "half", "top", "waterlogged", "false")).model();
                JsonObject variant = new JsonObject();
                JsonArray elements = new JsonArray();
                elements.add(furnitureElement(renderItems, blockId, "bottom", bottom, "ground", null));
                elements.add(furnitureElement(renderItems, blockId, "top", top, "ground", "0,1,0"));
                variant.add("elements", elements);
                variant.add("hitboxes", FurnitureBoxes.jsonArrayOf(
                        FurnitureBoxes.furnitureHitboxes(blockId, "ground", Map.of())));
                variants.add("ground", variant);
            } else if (FurnitureBoxes.PAINTINGS.contains(blockId)) {
                for (String[] anchorFace : new String[][] {
                        {"ground", "floor"}, {"wall", "wall"}, {"ceiling", "ceiling"}}) {
                    String anchor = anchorFace[0];
                    String face = anchorFace[1];
                    String facing = anchor.equals("wall") ? "south" : "north";
                    Model selected = selectRecord(records, Map.of(
                            "face", face, "facing", facing, "waterlogged", "false")).model();
                    JsonObject variant = new JsonObject();
                    JsonArray elements = new JsonArray();
                    elements.add(furnitureElement(renderItems, blockId, anchor, selected, anchor, null));
                    variant.add("elements", elements);
                    variant.add("hitboxes", FurnitureBoxes.jsonArrayOf(
                            FurnitureBoxes.furnitureHitboxes(blockId, anchor, Map.of())));
                    variants.add(anchor, variant);
                }
            } else {
                String anchor = blockId.equals("glassware_holder") ? "ceiling" : "ground";
                Map<List<Map.Entry<String, String>>, List<Record>> grouped = new LinkedHashMap<>();
                for (Record record : records) {
                    List<Map.Entry<String, String>> semantic = new ArrayList<>();
                    for (Map.Entry<String, String> entry : record.properties().entrySet()) {
                        if (!IGNORED_SEMANTICS.contains(entry.getKey())) {
                            semantic.add(Map.entry(entry.getKey(), entry.getValue()));
                        }
                    }
                    semantic.sort(Map.Entry.comparingByKey());
                    grouped.computeIfAbsent(semantic, ignored -> new ArrayList<>()).add(record);
                }
                List<Map.Entry<List<Map.Entry<String, String>>, List<Record>>> ordered =
                        new ArrayList<>(grouped.entrySet());
                ordered.sort(Comparator
                        .comparing((Map.Entry<List<Map.Entry<String, String>>, List<Record>> entry) -> {
                            Record best = entry.getValue().stream().min(recordComparator()).orElseThrow();
                            int[] score = recordScore(best);
                            return score[0] * 1_000_000 + Math.min(score[1], 999_999);
                        })
                        .thenComparing(entry -> entry.getKey().toString()));
                Set<String> usedNames = new LinkedHashSet<>();
                int index = 0;
                for (Map.Entry<List<Map.Entry<String, String>>, List<Record>> entry : ordered) {
                    List<Map.Entry<String, String>> semantic = entry.getKey();
                    List<Record> candidates = entry.getValue();
                    Record preferred = null;
                    for (Record candidate : candidates) {
                        if ("north".equals(candidate.properties().get("facing"))) {
                            preferred = candidate;
                            break;
                        }
                    }
                    Record selected = (preferred != null ? preferred
                            : candidates.stream().min(recordComparator()).orElseThrow());
                    String name = semanticVariantName(anchor, semantic, index);
                    if (usedNames.contains(name)) name = name + "_" + index;
                    usedNames.add(name);
                    JsonObject element = furnitureElement(
                            renderItems, blockId, name, selected.model(), anchor, null);
                    Map<String, String> semanticMap = new LinkedHashMap<>();
                    for (Map.Entry<String, String> pair : semantic) semanticMap.put(pair.getKey(), pair.getValue());
                    List<JsonObject> hitboxes = FurnitureBoxes.furnitureHitboxes(blockId, anchor, semanticMap);
                    List<JsonObject> elements = new ArrayList<>();
                    if (blockId.equals("sculk_special")) {
                        elements.addAll(sculkSpecialFurnitureElements(renderItems, element));
                    } else {
                        elements.add(element);
                    }
                    JsonObject variant = new JsonObject();
                    variant.add("elements", FurnitureBoxes.jsonArrayOf(elements));
                    variant.add("hitboxes", FurnitureBoxes.jsonArrayOf(hitboxes));
                    variants.add(name, variant);
                    if (CARDINAL_BOTTLE_FURNITURE.contains(blockId)) {
                        JsonObject axisElement = cardinalBottleAxisElement(element);
                        JsonObject axisVariant = new JsonObject();
                        JsonArray axisElements = new JsonArray();
                        axisElements.add(axisElement);
                        axisVariant.add("elements", axisElements);
                        axisVariant.add("hitboxes", FurnitureBoxes.jsonArrayOf(hitboxes));
                        variants.add(name + CARDINAL_BOTTLE_AXIS_SUFFIX, axisVariant);
                    }
                    index++;
                }
            }

            JsonObject config = new JsonObject();
            config.add("settings", furnitureSettings(blockId));
            config.add("variants", variants);
            String fullId = NAMESPACE + ":" + blockId;
            if (itemIds.contains(blockId)) {
                config.getAsJsonObject("settings").addProperty("item", fullId);
                JsonObject lootEntry = new JsonObject();
                lootEntry.addProperty("type", "furniture_item");
                lootEntry.addProperty("item", fullId);
                JsonObject entry = new JsonObject();
                entry.addProperty("type", "furniture_item");
                entry.addProperty("item", fullId);
                JsonObject pool = new JsonObject();
                pool.addProperty("rolls", 1);
                JsonArray entries = new JsonArray();
                entries.add(entry);
                pool.add("entries", entries);
                JsonObject loot = new JsonObject();
                JsonArray pools = new JsonArray();
                pools.add(pool);
                loot.add("pools", pools);
                config.add("loot", loot);
                placement.put(blockId, furnitureRules(blockId, new ArrayList<>(variants.keySet())));
            } else if (FurnitureBoxes.SIMPLE_BOTTLES.contains(blockId)) {
                JsonObject entry = new JsonObject();
                entry.addProperty("type", "furniture_item");
                JsonObject pool = new JsonObject();
                pool.addProperty("rolls", 1);
                JsonArray entries = new JsonArray();
                entries.add(entry);
                pool.add("entries", entries);
                JsonObject loot = new JsonObject();
                JsonArray pools = new JsonArray();
                pools.add(pool);
                loot.add("pools", pools);
                config.add("loot", loot);
            }
            List<JsonObject> behaviors = furnitureBehaviors(blockId, new ArrayList<>(variants.keySet()));
            if (behaviors.size() == 1) {
                config.add("behavior", behaviors.get(0));
            } else if (!behaviors.isEmpty()) {
                config.add("behaviors", FurnitureBoxes.jsonArrayOf(behaviors));
            }
            if (blockId.startsWith("string_lights_")) {
                config.add("events", FurnitureBoxes.jsonArrayOf(
                        stringLightsDyeEvents(blockId.substring("string_lights_".length()))));
            }
            furniture.add(fullId, config);
            metrics.put("furniture_variants",
                    metrics.get("furniture_variants") + variants.size());
        }

        Map.Entry<JsonObject, Integer> wall = buildWallPressingTubFurniture(renderItems);
        furniture.add(WALL_PRESSING_TUB_ID, wall.getKey());
        metrics.put("furniture_variants", metrics.get("furniture_variants") + wall.getValue());

        return new FurnitureMigrationStage.Result(furniture, renderItems, placement, metrics);
    }

    private String renderItemName(String referenceId) {
        String[] parts = referenceId.split("_");
        for (int start = 0; start < parts.length; start++) {
            StringBuilder name = new StringBuilder();
            for (int i = start; i < parts.length; i++) {
                if (i > start) name.append('_');
                name.append(parts[i]);
            }
            String candidateBase = name.toString();
            for (String prefix : List.of("block", "item")) {
                String candidate = prefix + "." + NAMESPACE + "." + candidateBase;
                if (languageKeys.contains(candidate)) return "<!i><lang:" + candidate + ">";
            }
        }
        throw new IllegalArgumentException("No display-name translation for render item " + referenceId);
    }

    private List<Record> blockstateRecords(String blockId) throws IOException {
        Path relative = Path.of("assets", NAMESPACE, "blockstates", blockId + ".json");
        Path path = findFile(relative);
        if (path == null) throw new IOException("No blockstate for " + blockId);
        JsonObject data = readJson(path);
        if (data.has("multipart")) throw new IllegalArgumentException(
                "Multipart blockstate is not supported: " + path);
        JsonElement variantsElement = data.get("variants");
        if (variantsElement == null || !variantsElement.isJsonObject()
                || variantsElement.getAsJsonObject().isEmpty()) {
            throw new IllegalArgumentException("No variants in " + path);
        }
        List<Record> records = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : variantsElement.getAsJsonObject().entrySet()) {
            records.add(new Record(parseVariantKey(entry.getKey()), normalizeModelEntry(entry.getValue())));
        }
        return records;
    }

    private static Map<String, String> parseVariantKey(String key) {
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        if (key.isEmpty()) return properties;
        for (String pair : key.split(",", -1)) {
            int separator = pair.indexOf('=');
            if (separator < 0) throw new IllegalArgumentException("Malformed blockstate variant key: " + key);
            properties.put(pair.substring(0, separator), pair.substring(separator + 1));
        }
        return properties;
    }

    private static Model normalizeModelEntry(JsonElement raw) {
        if (raw.isJsonArray()) {
            JsonArray array = raw.getAsJsonArray();
            if (array.isEmpty()) throw new IllegalArgumentException("Empty weighted blockstate model list");
            raw = array.get(0);
        }
        if (!raw.isJsonObject() || !raw.getAsJsonObject().has("model")) {
            throw new IllegalArgumentException("Unsupported blockstate model: " + raw);
        }
        JsonObject object = raw.getAsJsonObject();
        return new Model(object.get("model").getAsString(),
                object.has("x") ? object.get("x").getAsInt() : 0,
                object.has("y") ? object.get("y").getAsInt() : 0,
                object.has("z") ? object.get("z").getAsInt() : 0,
                object.has("uvlock") && object.get("uvlock").getAsBoolean());
    }

    private static Comparator<Record> recordComparator() {
        return Comparator.comparingInt((Record record) -> recordScore(record)[0])
                .thenComparingInt(record -> recordScore(record)[1]);
    }

    private static int[] recordScore(Record record) {
        Map<String, String> preferred = Map.ofEntries(
                Map.entry("facing", "north"), Map.entry("waterlogged", "false"),
                Map.entry("powered", "false"), Map.entry("triggered", "false"),
                Map.entry("open", "false"), Map.entry("connection", "single"),
                Map.entry("position", "single"), Map.entry("count", "1"),
                Map.entry("rotation", "0"), Map.entry("axis", "x"),
                Map.entry("half", "bottom"), Map.entry("face", "wall"),
                Map.entry("tilt", "false"), Map.entry("waxed", "false"));
        int mismatches = 0;
        for (Map.Entry<String, String> entry : record.properties().entrySet()) {
            if (entry.getKey().equals("position")
                    && (entry.getValue().equals("single") || entry.getValue().equals("0"))) continue;
            if (!entry.getValue().equals(preferred.getOrDefault(entry.getKey(), entry.getValue()))) {
                mismatches++;
            }
        }
        Model model = record.model();
        int rotationCost = Math.abs(model.x()) + Math.abs(model.y()) + Math.abs(model.z());
        return new int[] {mismatches, rotationCost};
    }

    private static Record selectRecord(List<Record> records, Map<String, String> required) {
        List<Record> matches = new ArrayList<>();
        for (Record record : records) {
            boolean all = true;
            for (Map.Entry<String, String> entry : required.entrySet()) {
                if (!entry.getValue().equals(record.properties().get(entry.getKey()))) {
                    all = false;
                    break;
                }
            }
            if (all) matches.add(record);
        }
        if (matches.isEmpty()) throw new IllegalArgumentException(
                "No blockstate variant matches " + required);
        return matches.stream()
                .min(recordComparator().thenComparing(record -> record.model().id()))
                .orElseThrow();
    }

    private String ensureRenderItem(JsonObject renderItems, String blockId, String label, Model model)
            throws IOException {
        String digest;
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-1")
                    .digest(model.digestInput().getBytes(StandardCharsets.UTF_8));
            digest = java.util.HexFormat.of().formatHex(bytes).substring(0, 10);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
        Model displayModel = placedDrinkModel(blockId, model);
        String renderId = NAMESPACE + ":_render/" + blockId + "/" + digest;
        if (!renderItems.has(renderId)) {
            JsonObject item = new JsonObject();
            item.addProperty("material", "paper");
            JsonObject data = new JsonObject();
            data.addProperty("item_name", renderItemName(blockId));
            item.add("data", data);
            JsonObject modelConfig = new JsonObject();
            modelConfig.addProperty("type", "minecraft:model");
            modelConfig.addProperty("path", displayModel.id());
            item.add("model", modelConfig);
            JsonObject settings = new JsonObject();
            JsonArray tags = new JsonArray();
            tags.add(NAMESPACE + ":internal_render_items");
            settings.add("tags", tags);
            item.add("settings", settings);
            renderItems.add(renderId, item);
        }
        return renderId;
    }

    private Model placedDrinkModel(String blockId, Model model) throws IOException {
        LegacyImageAndPlacedDrinkStage.ModelReference migrated = imageStage.migratePlacedDrinkModel(
                blockId, new LegacyImageAndPlacedDrinkStage.ModelReference(
                        model.id(), model.x(), model.y(), model.z(), model.uvlock()));
        if (migrated.resourceId().equals(model.id())) return model;
        return new Model(migrated.resourceId(), model.x(), model.y(), model.z(), model.uvlock());
    }

    private JsonObject furnitureElement(JsonObject renderItems, String blockId, String label,
                                        Model model, String anchor, String translation) throws IOException {
        String renderId = ensureRenderItem(renderItems, blockId, label, model);
        JsonObject element = new JsonObject();
        element.addProperty("type", "item_display");
        element.addProperty("item", renderId);
        element.addProperty("display_transform", "none");
        element.addProperty("shadow_radius", 0);
        element.addProperty("view_range", 1.25);
        boolean correctedWallDepth = anchor.equals("wall") && FurnitureBoxes.PAINTINGS.contains(blockId);
        double[] base = switch (anchor) {
            case "ground" -> new double[] {0.0, 0.5, 0.0};
            case "wall" -> new double[] {0.0, 0.0, correctedWallDepth ? -0.627 : 0.49};
            case "ceiling" -> new double[] {0.0, -0.49, 0.0};
            default -> throw new IllegalArgumentException("Unknown furniture anchor " + anchor);
        };
        if (anchor.equals("wall")) {
            element.addProperty("position", correctedWallDepth ? "0,0,0.19" : "0,0,0.01");
        } else if (anchor.equals("ceiling")) {
            element.addProperty("position", "0,-0.01,0");
        }
        double[] offset = translation == null ? new double[] {0.0, 0.0, 0.0}
                : FurnitureBoxes.parseVector(translation);
        double[] combined = FurnitureBoxes.addVector(base, offset);
        element.addProperty("translation", FurnitureBoxes.vector(combined[0], combined[1], combined[2]));
        if (blockId.equals("potion_bottle") || blockId.equals("signature_cocktail")) {
            JsonArray tintSource = new JsonArray();
            tintSource.add("potion_contents");
            element.add("tint_source", tintSource);
            JsonObject tints = new JsonObject();
            tints.addProperty("type", "minecraft:potion");
            tints.addProperty("default", -13083194);
            JsonArray tintArray = new JsonArray();
            tintArray.add(tints);
            renderItems.getAsJsonObject(renderId).getAsJsonObject("model").add("tints", tintArray);
        }
        if (model.x() != 0 || model.y() != 0 || model.z() != 0) {
            element.addProperty("rotation", model.x() + "," + model.y() + "," + model.z());
        }
        return element;
    }

    private static JsonObject cardinalBottleAxisElement(JsonObject element) {
        JsonObject directional = element.deepCopy();
        directional.addProperty("yaw", CARDINAL_BOTTLE_AXIS_YAW);
        return directional;
    }

    private List<JsonObject> sculkSpecialFurnitureElements(JsonObject renderItems, JsonObject body) {
        if (!renderItems.has(SCULK_RIPPLE_RENDER_ID)) {
            JsonObject item = new JsonObject();
            item.addProperty("material", "paper");
            JsonObject data = new JsonObject();
            data.addProperty("item_name", renderItemName("sculk_special"));
            item.add("data", data);
            JsonObject modelConfig = new JsonObject();
            modelConfig.addProperty("type", "minecraft:model");
            modelConfig.addProperty("path", SCULK_RIPPLE_MODEL_ID);
            item.add("model", modelConfig);
            JsonObject settings = new JsonObject();
            JsonArray tags = new JsonArray();
            tags.add(NAMESPACE + ":internal_render_items");
            settings.add("tags", tags);
            item.add("settings", settings);
            renderItems.add(SCULK_RIPPLE_RENDER_ID, item);
        }
        JsonObject ripple = body.deepCopy();
        ripple.addProperty("item", SCULK_RIPPLE_RENDER_ID);
        ripple.remove("rotation");
        ripple.remove("yaw");
        ripple.remove("conditions");
        List<JsonObject> elements = new ArrayList<>();
        elements.add(body);
        for (int segment = 0; segment < 16; segment++) {
            double furnitureYaw = segment * 22.5;
            String normalized = FurnitureBoxes.number(furnitureYaw);
            JsonObject fixed = ripple.deepCopy();
            JsonObject condition = new JsonObject();
            condition.addProperty("type", "expression");
            condition.addProperty("expression",
                    "ABS((((<arg:furniture.yaw> % 360) + 360) % 360) - " + normalized + ") < 0.001");
            JsonArray conditions = new JsonArray();
            conditions.add(condition);
            fixed.add("conditions", conditions);
            if (furnitureYaw != 0) fixed.addProperty("yaw", -furnitureYaw);
            elements.add(fixed);
        }
        return elements;
    }

    private static String semanticVariantName(String anchor, List<Map.Entry<String, String>> properties,
                                              int index) {
        if (index == 0) return anchor;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : properties) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key.equals("open") && value.equals("true")) parts.add("open");
            else if (key.equals("tilt") && value.equals("true")) parts.add("tilted");
            else if (key.equals("waxed") && value.equals("true")) parts.add("waxed");
            else parts.add(key + "_" + value);
        }
        String suffix = parts.isEmpty() ? "variant_" + index : String.join("_", parts);
        return anchor + "_" + suffix;
    }

    private static List<JsonObject> furnitureBehaviors(String blockId, List<String> variants) {
        List<JsonObject> behaviors = new ArrayList<>();
        boolean usesTavernState = (BOTTLE_AND_GLASS_ITEMS.contains(blockId)
                && variants.contains("ground_count_2"))
                || blockId.equals("barrel")
                || blockId.endsWith("_sandwich_board");
        if (usesTavernState) {
            behaviors.add(obj("type", NAMESPACE + ":state_furniture"));
        }
        List<String> lifecycleChannels = new ArrayList<>();
        if (blockId.endsWith("_sandwich_board")) lifecycleChannels.add("board");
        if (blockId.endsWith("_bar_stool")) lifecycleChannels.add("bar_stool");
        if (blockId.equals("shaker")) lifecycleChannels.add("shaker");
        if (blockId.equals("barrel")) lifecycleChannels.add("barrel");
        if (blockId.equals("empty_bottle")) lifecycleChannels.add("tap_bottle");
        if (blockId.equals("bar_cabinet") || blockId.equals("glass_bar_cabinet")) {
            lifecycleChannels.add("connection");
        }
        for (String channel : lifecycleChannels) {
            behaviors.add(obj("type", NAMESPACE + ":lifecycle_furniture", "channel", channel));
        }
        Integer boardTextMaxLines = null;
        if (blockId.endsWith("_sandwich_board")) boardTextMaxLines = 8;
        if (boardTextMaxLines != null) {
            behaviors.add(obj("type", NAMESPACE + ":board_text_furniture",
                    "max_lines", boardTextMaxLines, "view_range", 0.75));
        }
        String[] animatedVisual = null;
        if (blockId.equals("shaker")) animatedVisual = new String[] {"shaker", "2"};
        else if (blockId.endsWith("_bar_stool")) animatedVisual = new String[] {"bar_stool", "1"};
        if (animatedVisual != null) {
            behaviors.add(obj("type", NAMESPACE + ":animated_item_furniture",
                    "channel", animatedVisual[0], "max_elements", Integer.parseInt(animatedVisual[1]),
                    "view_range", 1.25));
        }
        if (BOTTLE_AND_GLASS_ITEMS.contains(blockId)) {
            behaviors.add(obj("type", NAMESPACE + ":bottle_furniture"));
        }
        if (Set.of("bar_cabinet", "glass_bar_cabinet", "glassware_holder").contains(blockId)) {
            behaviors.add(obj("type", NAMESPACE + ":storage_interaction_furniture"));
        }
        List<String> displaySlotPositions = null;
        double slotWidth = 0;
        double slotHeight = 0;
        if (blockId.equals("bar_cabinet") || blockId.equals("glass_bar_cabinet")) {
            displaySlotPositions = List.of("-0.25,0.5,0", "0.25,0.5,0");
            slotWidth = 0.5;
            slotHeight = 1.0;
        } else if (blockId.equals("glassware_holder")) {
            displaySlotPositions = List.of("-0.25,-0.24,-0.25", "0.25,-0.24,-0.25",
                    "-0.25,-0.24,0.25", "0.25,-0.24,0.25");
            slotWidth = 0.35;
            slotHeight = 0.35;
        }
        if (displaySlotPositions != null) {
            int slotIndex = 0;
            for (String position : displaySlotPositions) {
                JsonObject behavior = new JsonObject();
                behavior.addProperty("type", "display_item_furniture");
                behavior.addProperty("data_key", NAMESPACE + ":display_slot_" + slotIndex);
                JsonObject sounds = new JsonObject();
                sounds.addProperty("put", "minecraft:block.decorated_pot.insert");
                sounds.addProperty("take", "minecraft:block.decorated_pot.insert_fail");
                behavior.add("sounds", sounds);
                behaviors.add(behavior);
                slotIndex++;
            }
        }
        Integer storageVisualSlots = null;
        if (blockId.equals("bar_cabinet") || blockId.equals("glass_bar_cabinet")) storageVisualSlots = 2;
        else if (blockId.equals("glassware_holder")) storageVisualSlots = 4;
        if (storageVisualSlots != null) {
            behaviors.add(obj("type", NAMESPACE + ":storage_visual_furniture",
                    "slots", storageVisualSlots));
        }
        int stationMaxElements = -1;
        double stationViewRange = 0;
        if (blockId.equals("barrel")) { stationMaxElements = 17; stationViewRange = 2.5; }
        if (stationMaxElements >= 0) {
            behaviors.add(obj("type", NAMESPACE + ":station_visual_furniture",
                    "max_elements", stationMaxElements, "view_range", stationViewRange));
        }
        if (Set.of("barrel", "shaker", "empty_glassware").contains(blockId)) {
            behaviors.add(obj("type", NAMESPACE + ":station_interaction_furniture"));
        }
        if (blockId.startsWith("string_lights_")) {
            JsonArray lights = new JsonArray();
            lights.add("0,0,0.5 15");
            behaviors.add(obj("type", "glowing_furniture", "lights", lights));
        } else if (FurnitureBoxes.PENDANT_LAMPS.contains(blockId)) {
            JsonArray lights = new JsonArray();
            lights.add("0,-1,0 13");
            behaviors.add(obj("type", "glowing_furniture", "lights", lights));
        } else if (blockId.equals("glassware_holder")) {
            JsonArray lights = new JsonArray();
            lights.add("0,0,0 8");
            behaviors.add(obj("type", "glowing_furniture", "lights", lights));
        } else if (blockId.equals("molotov")) {
            JsonArray lights = new JsonArray();
            lights.add("0,0,0 14");
            behaviors.add(obj("type", "glowing_furniture", "lights", lights));
        }
        if (blockId.equals("mystery_cocktail")) {
            behaviors.add(obj("type", NAMESPACE + ":ticking_furniture",
                    "channel", "mystery_particle", "chance", 49));
        } else if (blockId.equals("barrel")) {
            behaviors.add(obj("type", NAMESPACE + ":ticking_furniture",
                    "channel", "barrel", "interval", 97, "phase", "identity"));
        }
        return behaviors;
    }

    private static String tableFurnitureVariantName(String base, String facing) {
        return facing.equals("south") ? base : base + "_facing_" + facing;
    }

    private static String furnitureRotationRule(String blockId) {
        if (SIXTEEN_WAY_VESSELS.contains(blockId) || blockId.endsWith("_sandwich_board")) return "sixteen";
        if (DIRECTIONLESS_VESSELS.contains(blockId)) return "north";
        return "four";
    }

    private static JsonObject furnitureRules(String blockId, List<String> variantNames) {
        List<String> anchors = new ArrayList<>();
        for (String name : List.of("ground", "wall", "ceiling")) {
            if (variantNames.contains(name)) anchors.add(name);
        }
        String rotation = furnitureRotationRule(blockId);
        JsonObject rules = new JsonObject();
        for (String anchor : anchors) {
            JsonObject rule = new JsonObject();
            rule.addProperty("rotation", rotation);
            rule.addProperty("alignment", "center");
            rules.add(anchor, rule);
        }
        return rules;
    }

    private static JsonObject furnitureSettings(String blockId) {
        String family;
        if (blockId.startsWith("string_lights_")) family = "chain";
        else if (blockId.endsWith("_sofa")) family = "wool";
        else if (FurnitureBoxes.PENDANT_LAMPS.contains(blockId)) family = "chain";
        else if (blockId.equals("glassware_holder")) family = "metal";
        else if (blockId.equals("shaker")) family = "lantern";
        else if (BOTTLE_AND_GLASS_ITEMS.contains(blockId)) family = "glass";
        else family = "wood";
        boolean instantBreak = BOTTLE_AND_GLASS_ITEMS.contains(blockId) || blockId.equals("shaker");
        JsonObject sounds = new JsonObject();
        for (String action : List.of("break", "place", "hit")) {
            sounds.addProperty(action, "minecraft:block." + family + "." + action);
        }
        if (BOTTLE_AND_GLASS_ITEMS.contains(blockId)) {
            JsonObject place = new JsonObject();
            place.addProperty("id", "minecraft:block.glass.place");
            place.addProperty("volume", 1.0);
            place.addProperty("pitch", 0.8);
            sounds.add("place", place);
        }
        JsonObject result = new JsonObject();
        result.addProperty("hit_times", instantBreak ? 1 : 3);
        result.add("sounds", sounds);
        return result;
    }

    private Map.Entry<JsonObject, Integer> buildWallPressingTubFurniture(JsonObject renderItems)
            throws IOException {
        String publicItem = NAMESPACE + ":pressing_tub";
        JsonObject settings = furnitureSettings("pressing_tub");
        settings.addProperty("item", publicItem);
        JsonObject variant = new JsonObject();
        JsonArray elements = new JsonArray();
        elements.add(furnitureElement(renderItems, "pressing_tub", "active_wall",
                new Model(NAMESPACE + ":block/brew/tilt_pressing_tub", 0, 0, 0, false), "wall", null));
        variant.add("elements", elements);
        variant.add("hitboxes", FurnitureBoxes.jsonArrayOf(pressingTubWallHitboxes()));
        JsonObject variants = new JsonObject();
        variants.add("wall", variant);
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "furniture_item");
        entry.addProperty("item", publicItem);
        JsonObject pool = new JsonObject();
        pool.addProperty("rolls", 1);
        JsonArray entries = new JsonArray();
        entries.add(entry);
        pool.add("entries", entries);
        JsonObject loot = new JsonObject();
        JsonArray pools = new JsonArray();
        pools.add(pool);
        loot.add("pools", pools);
        JsonObject config = new JsonObject();
        config.add("settings", settings);
        config.add("variants", variants);
        config.add("loot", loot);
        List<JsonObject> behaviors = new ArrayList<>();
        behaviors.add(obj("type", NAMESPACE + ":state_furniture"));
        // 源 PressingTubBlockEntityRender 按 count 渲染全部原料（最多 64 个）+ 液体平面。
        behaviors.add(obj("type", NAMESPACE + ":station_visual_furniture",
                "max_elements", 65, "view_range", 1.25));
        behaviors.add(obj("type", NAMESPACE + ":station_interaction_furniture"));
        config.add("behaviors", FurnitureBoxes.jsonArrayOf(behaviors));
        return Map.entry(config, 1);
    }

    private static List<JsonObject> pressingTubWallHitboxes() {
        List<JsonObject> result = new ArrayList<>();
        result.add(FurnitureBoxes.interactionBox(new FurnitureBoxes.Box(0, 0, 0, 16, 16, 16), "wall", null));
        result.add(FurnitureBoxes.shulkerBox(new double[] {-0.25, -0.5, 0.75}, 0.5, 0, null, true, null));
        result.add(FurnitureBoxes.shulkerBox(new double[] {0.25, -0.5, 0.75}, 0.5, 0, null, true, null));
        result.add(FurnitureBoxes.shulkerBox(new double[] {-0.25, -0.25, 0.5}, 0.5, 0, null, true, null));
        result.add(FurnitureBoxes.shulkerBox(new double[] {0.25, -0.25, 0.5}, 0.5, 0, null, true, null));
        result.add(FurnitureBoxes.shulkerBox(new double[] {-0.25, 0, 0.25}, 0.5, 0, null, true, null));
        result.add(FurnitureBoxes.shulkerBox(new double[] {0.25, 0, 0.25}, 0.5, 0, null, true, null));
        return result;
    }

    private static List<JsonObject> stringLightsDyeEvents(String currentColor) {
        List<JsonObject> events = new ArrayList<>();
        for (String color : STRING_LIGHT_DYE_COLORS) {
            if (color.equals(currentColor)) continue;
            String target = NAMESPACE + ":string_lights_" + color;
            JsonObject matchItem = new JsonObject();
            matchItem.addProperty("type", "match_item");
            matchItem.addProperty("item", "minecraft:" + color + "_dye");
            JsonObject hand = new JsonObject();
            hand.addProperty("type", "hand");
            hand.addProperty("hand", "main_hand");
            JsonArray conditions = new JsonArray();
            conditions.add(matchItem);
            conditions.add(hand);
            JsonObject testFlag = new JsonObject();
            testFlag.addProperty("type", "test_flag");
            testFlag.addProperty("flag", "interact");
            JsonArray flagConditions = new JsonArray();
            flagConditions.add(testFlag);
            JsonArray dyeFunctions = new JsonArray();
            dyeFunctions.add(obj("type", "update_interaction_tick"));
            JsonObject setCount = new JsonObject();
            setCount.addProperty("type", "set_count");
            setCount.addProperty("add", true);
            setCount.addProperty("count", -1);
            JsonObject notEquals = new JsonObject();
            notEquals.addProperty("type", "!equals");
            notEquals.addProperty("value1", "<arg:player.gamemode>");
            notEquals.addProperty("value2", "CREATIVE");
            JsonArray setCountConditions = new JsonArray();
            setCountConditions.add(notEquals);
            setCount.add("conditions", setCountConditions);
            dyeFunctions.add(setCount);
            dyeFunctions.add(obj("type", "play_sound", "sound", "minecraft:item.dye.use", "source", "block"));
            dyeFunctions.add(stringLightParticleFunction());
            dyeFunctions.add(obj("type", "swing_hand"));
            dyeFunctions.add(obj("type", "replace_furniture", "furniture", target,
                    "variant", "wall", "drop_loot", false, "play_sound", false));
            JsonObject ruleFirst = new JsonObject();
            ruleFirst.add("conditions", flagConditions);
            ruleFirst.add("functions", dyeFunctions);
            JsonObject ruleSecond = new JsonObject();
            JsonArray secondFunctions = new JsonArray();
            secondFunctions.add(obj("type", "update_interaction_tick"));
            ruleSecond.add("functions", secondFunctions);
            JsonObject ifElse = new JsonObject();
            ifElse.addProperty("type", "if_else");
            JsonArray rules = new JsonArray();
            rules.add(ruleFirst);
            rules.add(ruleSecond);
            ifElse.add("rules", rules);
            JsonArray functions = new JsonArray();
            functions.add(ifElse);
            JsonObject event = new JsonObject();
            event.addProperty("on", "right_click");
            event.add("conditions", conditions);
            event.add("functions", functions);
            events.add(event);
        }
        return events;
    }

    private static JsonObject stringLightParticleFunction() {
        JsonObject particle = new JsonObject();
        particle.addProperty("type", "particle");
        particle.addProperty("particle", "minecraft:happy_villager");
        particle.addProperty("x", "<arg:position.x>");
        particle.addProperty("y", "<arg:position.y>");
        particle.addProperty("z", "<arg:position.z>");
        particle.addProperty("count", 15);
        particle.addProperty("offset_x", 0.5);
        particle.addProperty("offset_y", 0.375);
        particle.addProperty("offset_z", 0.5);
        JsonArray cases = new JsonArray();
        for (String[] entry : new String[][] {
                {"0.0", "<arg:position.x>", "<arg:position.z> + 0.5"},
                {"180.0", "<arg:position.x>", "<arg:position.z> - 0.5"},
                {"90.0", "<arg:position.x> - 0.5", "<arg:position.z>"},
                {"-90.0", "<arg:position.x> + 0.5", "<arg:position.z>"}}) {
            JsonObject caseObject = new JsonObject();
            caseObject.addProperty("when", entry[0]);
            JsonObject p = particle.deepCopy();
            p.addProperty("x", entry[1]);
            p.addProperty("z", entry[2]);
            JsonArray caseFunctions = new JsonArray();
            caseFunctions.add(p);
            caseObject.add("functions", caseFunctions);
            cases.add(caseObject);
        }
        JsonObject fallbackParticle = particle.deepCopy();
        JsonArray fallbackFunctions = new JsonArray();
        fallbackFunctions.add(fallbackParticle);
        JsonObject result = new JsonObject();
        result.addProperty("type", "when");
        result.addProperty("source", "<arg:furniture.yaw>");
        result.add("cases", cases);
        result.add("fallback", fallbackFunctions);
        return result;
    }

    private Path findFile(Path relative) {
        for (Path root : List.of(projectRoot.resolve("src/generated/resources"),
                projectRoot.resolve("src/main/resources"))) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static JsonObject readJson(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static JsonObject obj(Object... values) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < values.length; i += 2) {
            String key = (String) values[i];
            Object value = values[i + 1];
            if (value instanceof JsonElement element) object.add(key, element);
            else if (value instanceof Boolean bool) object.addProperty(key, bool);
            else if (value instanceof Number number) object.addProperty(key, number);
            else object.addProperty(key, String.valueOf(value));
        }
        return object;
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
