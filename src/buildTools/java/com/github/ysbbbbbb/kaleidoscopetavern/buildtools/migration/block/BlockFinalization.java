package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic block loot and hanging-crop expansion. */
final class BlockFinalization {
    private static final Map<String, List<String>> ITEMLESS = Map.of(
            "grapevine_trellis", List.of("trellis", "grapevine"),
            "ice_grapevine_trellis", List.of("trellis", "grapevine"),
            "gold_grapevine_trellis", List.of("trellis", "grapevine"),
            "wild_grapevine", List.of("grapevine"),
            "wild_grapevine_plant", List.of("grapevine"));
    private static final Set<String> CROPS = Set.of("grape_crop", "ice_grape_crop", "gold_grape_crop");
    private BlockFinalization() {}

    static void addLoot(JsonObject config, String blockId, boolean hasItem) {
        List<String> drops = hasItem ? List.of(blockId) : ITEMLESS.get(blockId);
        if (drops == null) return;
        JsonArray pools = new JsonArray();
        for (String drop : drops) {
            JsonObject pool = new JsonObject(); pool.addProperty("rolls", 1);
            JsonArray conditions = new JsonArray(); JsonObject survives = new JsonObject(); survives.addProperty("type", "survives_explosion"); conditions.add(survives); pool.add("conditions", conditions);
            JsonArray entries = new JsonArray(); JsonObject item = new JsonObject(); item.addProperty("type", "item"); item.addProperty("item", BlockMigrationStage.NAMESPACE + ":" + drop); entries.add(item); pool.add("entries", entries); pools.add(pool);
        }
        JsonObject loot = new JsonObject(); loot.add("pools", pools); config.add("loot", loot);
    }

    static boolean isHangingCrop(String id) { return CROPS.contains(id); }

    static void addCropStages(JsonObject blocks, String blockId, JsonObject config) {
        if (!config.has("states") || !config.get("states").isJsonObject()) throw new IllegalArgumentException(blockId + ": hanging crop must expose age states");
        JsonObject states = config.getAsJsonObject("states");
        JsonObject properties = states.getAsJsonObject("properties");
        JsonObject age = properties == null ? null : properties.getAsJsonObject("age");
        if (age == null || !age.has("range") || !"0~5".equals(age.get("range").getAsString()))
            throw new IllegalArgumentException(blockId + ": expected legacy age range 0~5, found " + age);
        JsonObject appearances = states.getAsJsonObject("appearances"); JsonObject variants = states.getAsJsonObject("variants");
        for (int point = 0; point < 6; point++) {
            JsonObject variant = variants == null ? null : variants.getAsJsonObject("age=" + point);
            if (variant == null) throw new IllegalArgumentException(blockId + ": missing age=" + point + " visual");
            String name = variant.get("appearance").getAsString(); JsonObject appearance = appearances == null ? null : appearances.getAsJsonObject(name);
            if (appearance == null) throw new IllegalArgumentException(blockId + ": missing appearance " + name);
            JsonObject stage = new JsonObject(); stage.add("state", appearance.deepCopy()); JsonObject settings = config.getAsJsonObject("settings").deepCopy(); if (point > 0) settings.remove("item"); stage.add("settings", settings);
            JsonObject behavior = new JsonObject(); behavior.addProperty("type", BlockMigrationStage.NAMESPACE + ":hanging_grape_crop"); stage.add("behavior", behavior);
            String id = point == 0 ? blockId : "_crop/" + blockId + "/stage_" + point; blocks.add(BlockMigrationStage.NAMESPACE + ":" + id, stage);
        }
    }
}
