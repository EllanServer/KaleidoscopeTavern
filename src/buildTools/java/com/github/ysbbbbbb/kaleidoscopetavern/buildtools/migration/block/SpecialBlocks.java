package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Native ports of build_chalkboard_block and build_pressing_tub_block
 * (tools/migrate_legacy.py). The chalkboard/large/small models are written by
 * the asset stage; only the configuration is produced here.
 */
final class SpecialBlocks {
    private static final String NAMESPACE = "kaleidoscope_tavern";
    private static final String PRESSING_TUB_CARRIER_STATE =
            "minecraft:cut_copper_slab[type=bottom,waterlogged=false]";
    private static final String PRESSING_TUB_CARRIER_WATER_STATE =
            "minecraft:cut_copper_slab[type=bottom,waterlogged=true]";
    private static final java.util.Map<String, Integer> FACING_YAW = java.util.Map.of(
            "north", 180, "east", 90, "south", 0, "west", 270);

    private SpecialBlocks() {}

    static record ChalkboardResult(JsonObject config, JsonObject renderItems, int appearanceCount) {}

    static ChalkboardResult buildChalkboard(boolean hasItem, RenderItems renderer) throws IOException {
        JsonObject renderItems = new JsonObject();
        for (String size : List.of("small", "large")) {
            JsonObject item = new JsonObject();
            item.addProperty("material", "paper");
            JsonObject data = new JsonObject();
            data.addProperty("item_name", renderer.renderItemName("chalkboard"));
            item.add("data", data);
            JsonObject modelConfig = new JsonObject();
            modelConfig.addProperty("type", "minecraft:model");
            modelConfig.addProperty("path", NAMESPACE + ":furniture/chalkboard_" + size);
            item.add("model", modelConfig);
            JsonObject settings = new JsonObject();
            JsonArray tags = new JsonArray();
            tags.add(NAMESPACE + ":internal_render_items");
            settings.add("tags", tags);
            item.add("settings", settings);
            renderItems.add(NAMESPACE + ":_render/chalkboard/" + size, item);
        }
        JsonObject appearances = new JsonObject();
        JsonObject variants = new JsonObject();
        for (String facing : List.of("north", "east", "south", "west")) {
            for (String half : List.of("lower", "upper")) {
                String carrier = BlockBehaviors.chalkboardCarrierState(facing, half);
                String hiddenName = "hidden_" + facing + "_" + half;
                JsonObject hidden = new JsonObject();
                hidden.addProperty("state", carrier);
                hidden.addProperty("transparent", true);
                appearances.add(hiddenName, hidden);
                java.util.Map<String, String> visibleNames = new java.util.LinkedHashMap<>();
                if (half.equals("lower")) {
                    for (String size : List.of("small", "large")) {
                        String appearanceName = size + "_" + facing + "_" + half;
                        JsonObject rendererConfig = new JsonObject();
                        rendererConfig.addProperty("type", "item_display");
                        rendererConfig.addProperty("item", NAMESPACE + ":_render/chalkboard/" + size);
                        rendererConfig.addProperty("display_transform", "none");
                        rendererConfig.addProperty("shadow_radius", 0);
                        rendererConfig.addProperty("view_range", 1.25);
                        int yaw = FACING_YAW.get(facing);
                        if (yaw != 0) rendererConfig.addProperty("rotation", "0," + yaw + ",0");
                        JsonObject appearance = new JsonObject();
                        appearance.addProperty("state", carrier);
                        appearance.addProperty("transparent", true);
                        appearance.add("entity_renderer", rendererConfig);
                        appearances.add(appearanceName, appearance);
                        visibleNames.put(size, appearanceName);
                    }
                }
                for (String waterlogged : List.of("false", "true")) {
                    for (String position : List.of("single", "left", "middle", "right")) {
                        String appearanceName;
                        if (half.equals("lower") && position.equals("single")) {
                            appearanceName = visibleNames.get("small");
                        } else if (half.equals("lower") && position.equals("middle")) {
                            appearanceName = visibleNames.get("large");
                        } else {
                            appearanceName = hiddenName;
                        }
                        String key = "facing=" + facing + ",half=" + half
                                + ",position=" + position + ",waterlogged=" + waterlogged;
                        JsonObject mapped = new JsonObject();
                        mapped.addProperty("appearance", appearanceName);
                        if (waterlogged.equals("true")) {
                            JsonObject mappedSettings = new JsonObject();
                            mappedSettings.addProperty("fluid_state", "water");
                            mapped.add("settings", mappedSettings);
                        }
                        variants.add(key, mapped);
                    }
                }
            }
        }
        JsonObject config = new JsonObject();
        JsonObject states = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.add("facing", horizontalDirection());
        properties.add("half", enumProperty("double_block_half", "lower",
                List.of("lower", "upper")));
        properties.add("position", enumProperty("string", "single",
                List.of("single", "left", "middle", "right")));
        properties.add("waterlogged", booleanProperty());
        states.add("properties", properties);
        states.add("appearances", appearances);
        states.add("variants", variants);
        config.add("states", states);
        config.add("settings", BlockBehaviors.blockSettings("chalkboard", hasItem));
        config.add("behaviors", BlockBehaviors.behaviorFor(
                "chalkboard", Set.of("facing", "half", "position", "waterlogged"), null));
        if (hasItem) {
            JsonArray functions = new JsonArray();
            for (String position : List.of("left", "middle", "right")) {
                JsonObject setCount = new JsonObject();
                setCount.addProperty("type", "set_count");
                setCount.addProperty("count", 3);
                setCount.addProperty("add", false);
                JsonObject condition = new JsonObject();
                condition.addProperty("type", "match_block_property");
                JsonObject props = new JsonObject();
                props.addProperty("position", position);
                condition.add("properties", props);
                JsonArray conditions = new JsonArray();
                conditions.add(condition);
                setCount.add("conditions", conditions);
                functions.add(setCount);
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "item");
            entry.addProperty("item", NAMESPACE + ":chalkboard");
            entry.add("functions", functions);
            JsonObject condition = new JsonObject();
            condition.addProperty("type", "survives_explosion");
            JsonArray conditions = new JsonArray();
            conditions.add(condition);
            JsonObject pool = new JsonObject();
            pool.addProperty("rolls", 1);
            pool.add("conditions", conditions);
            JsonArray entries = new JsonArray();
            entries.add(entry);
            pool.add("entries", entries);
            JsonObject loot = new JsonObject();
            JsonArray pools = new JsonArray();
            pools.add(pool);
            loot.add("pools", pools);
            config.add("loot", loot);
        }
        return new ChalkboardResult(config, renderItems, appearances.size());
    }

    static record TubResult(JsonObject config, int appearanceCount) {}

    static TubResult buildPressingTub(boolean hasItem, JsonObject renderItems, RenderItems renderer) throws IOException {
        BlockStateVariants.Model groundModel = new BlockStateVariants.Model(
                "kaleidoscope_tavern:block/brew/pressing_tub", 0, 0, 0, false);
        String digest = sha1(groundModel.digestInput());
        String groundRender = NAMESPACE + ":_render/pressing_tub/" + digest;
        if (!renderItems.has(groundRender)) {
            JsonObject item = new JsonObject();
            item.addProperty("material", "paper");
            JsonObject data = new JsonObject();
            data.addProperty("item_name", renderer.renderItemName("pressing_tub"));
            item.add("data", data);
            JsonObject modelConfig = new JsonObject();
            modelConfig.addProperty("type", "minecraft:model");
            modelConfig.addProperty("path", groundModel.model());
            item.add("model", modelConfig);
            JsonObject settings = new JsonObject();
            JsonArray tags = new JsonArray();
            tags.add(NAMESPACE + ":internal_render_items");
            settings.add("tags", tags);
            item.add("settings", settings);
            renderItems.add(groundRender, item);
        }
        JsonObject appearances = new JsonObject();
        JsonObject variants = new JsonObject();
        for (String facing : List.of("north", "east", "south", "west")) {
            for (String[] waterloggedCarrier : new String[][] {
                    {"false", PRESSING_TUB_CARRIER_STATE},
                    {"true", PRESSING_TUB_CARRIER_WATER_STATE}}) {
                String waterlogged = waterloggedCarrier[0];
                String carrier = waterloggedCarrier[1];
                String appearanceName = "ground_" + facing + "_" + waterlogged;
                JsonObject rendererConfig = new JsonObject();
                rendererConfig.addProperty("type", "item_display");
                rendererConfig.addProperty("item", groundRender);
                rendererConfig.addProperty("display_transform", "none");
                rendererConfig.addProperty("shadow_radius", 0);
                rendererConfig.addProperty("view_range", 1.25);
                int yaw = FACING_YAW.get(facing);
                if (yaw != 0) rendererConfig.addProperty("rotation", "0," + yaw + ",0");
                JsonObject appearance = new JsonObject();
                appearance.addProperty("state", carrier);
                appearance.addProperty("transparent", true);
                appearance.add("entity_renderer", rendererConfig);
                appearances.add(appearanceName, appearance);
                variants.add("facing=" + facing + ",waterlogged=" + waterlogged,
                        obj("appearance", appearanceName));
            }
        }
        JsonObject config = new JsonObject();
        JsonObject states = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.add("facing", horizontalDirection());
        properties.add("waterlogged", booleanProperty());
        states.add("properties", properties);
        states.add("appearances", appearances);
        states.add("variants", variants);
        config.add("states", states);
        config.add("settings", BlockBehaviors.blockSettings("pressing_tub", hasItem));
        config.add("behaviors", BlockBehaviors.behaviorFor(
                "pressing_tub", Set.of("facing", "waterlogged"), null));
        return new TubResult(config, appearances.size());
    }

    private static JsonObject horizontalDirection() {
        JsonObject result = new JsonObject();
        result.addProperty("type", "horizontal_direction");
        result.addProperty("default", "north");
        JsonArray values = new JsonArray();
        values.add("north");
        values.add("east");
        values.add("south");
        values.add("west");
        result.add("values", values);
        return result;
    }

    private static JsonObject enumProperty(String type, String defaultValue, List<String> values) {
        JsonObject result = new JsonObject();
        result.addProperty("type", type);
        result.addProperty("default", defaultValue);
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        result.add("values", array);
        return result;
    }

    private static JsonObject booleanProperty() {
        JsonObject result = new JsonObject();
        result.addProperty("type", "boolean");
        result.addProperty("default", "false");
        return result;
    }

    private static String sha1(String input) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes).substring(0, 10);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static JsonObject obj(Object... values) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < values.length; i += 2) {
            object.addProperty((String) values[i], String.valueOf(values[i + 1]));
        }
        return object;
    }
}
