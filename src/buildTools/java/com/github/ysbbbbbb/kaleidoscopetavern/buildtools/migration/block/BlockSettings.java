package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;

/** Exact per-block physical and mining settings. */
final class BlockSettings {
    private static final Set<String> STORAGE = Set.of("bar_cabinet", "glass_bar_cabinet", "wine_rack", "tilted_rack", "circular_rack", "holder");
    private static final Set<String> INCENSE = Set.of("incense_stick", "disenchantment_incense", "herbaceous_incense", "repellent_incense", "pungent_incense", "mysterious_incense");
    private static final Set<String> STURDY = Set.of("trellis");
    private static final Set<String> CLIMBABLE = Set.of(
            "wild_grapevine", "wild_grapevine_plant", "grapevine_trellis");
    private static final Set<String> NON_AXE_TRELLISES = Set.of(
            "ice_grapevine_trellis", "gold_grapevine_trellis");
    private static final Set<String> WOODEN_STORAGE = Set.of(
            "cellar_cabinet", "tilted_rack", "circular_rack", "holder");
    private static final Set<String> WOODEN_TRELLISES = Set.of(
            "trellis", "grapevine_trellis", "ice_grapevine_trellis", "gold_grapevine_trellis");
    private BlockSettings() {}

    static JsonObject create(String blockId, boolean hasItem) {
        boolean wild = Set.of("wild_grapevine", "wild_grapevine_plant").contains(blockId);
        boolean crop = blockId.equals("grape_crop") || blockId.endsWith("_grape_crop");
        boolean storage = STORAGE.contains(blockId);
        boolean sofa = blockId.endsWith("_sofa") || blockId.equals("_internal/sofa");
        boolean sturdy = STURDY.contains(blockId) || storage || sofa || Set.of("chalkboard", "pressing_tub", "table", "bar_counter").contains(blockId);
        double hardness = storage ? 2.5 : blockId.equals("table") ? 2.0 : wild || crop || INCENSE.contains(blockId) ? 0.0 : 0.8;
        JsonObject result = new JsonObject(); result.addProperty("hardness", hardness);
        result.addProperty("resistance", blockId.equals("table") ? 3.0 : hardness);
        result.addProperty("push_reaction", sturdy || blockId.equals("tap") ? "NORMAL" : "DESTROY");
        result.addProperty("is_redstone_conductor", false); result.addProperty("is_suffocating", false);
        result.addProperty("is_view_blocking", false); result.addProperty("can_occlude", false); result.addProperty("propagate_skylight", true);
        result.add("sounds", sounds(blockId, wild, crop, sofa));
        JsonArray tags = new JsonArray();
        if (CLIMBABLE.contains(blockId)) tags.add("minecraft:climbable");
        if (blockId.equals("tap") || sofa || Set.of("tilted_rack", "circular_rack", "holder").contains(blockId)) tags.add("minecraft:mineable/pickaxe");
        else if (!wild && !crop && !INCENSE.contains(blockId) && !NON_AXE_TRELLISES.contains(blockId)) tags.add("minecraft:mineable/axe");
        result.add("tags", tags);
        if (storage || Set.of("chalkboard", "pressing_tub").contains(blockId)) { JsonObject destroy = new JsonObject(); destroy.addProperty("template", "internal:destroy_stages"); result.add("destroy_stages", destroy); }
        if (Set.of("chalkboard", "pressing_tub").contains(blockId)) combustible(result, 13, "guitar");
        if (WOODEN_STORAGE.contains(blockId)) { result.addProperty("map_color", 13); burnable(result); }
        if (WOODEN_TRELLISES.contains(blockId)) combustible(result, 13, "guitar");
        if (sofa) { combustible(result, 27, "guitar"); result.addProperty("support_shape", "minecraft:cobweb"); }
        else if (blockId.equals("table")) combustible(result, 13, "bass");
        else if (Set.of("bar_counter", "bar_cabinet", "glass_bar_cabinet").contains(blockId)) combustible(result, blockId.equals("bar_counter") ? 29 : 13, "guitar");
        if (hasItem) result.addProperty("item", BlockMigrationStage.NAMESPACE + ":" + blockId);
        if (blockId.contains("lamp")) result.addProperty("luminance", 15); else if (blockId.equals("circular_rack")) result.addProperty("luminance", 14);
        return result;
    }
    private static JsonObject sounds(String id, boolean wild, boolean crop, boolean sofa) {
        JsonObject sounds = new JsonObject();
        String family = id.equals("tap") ? "metal" : INCENSE.contains(id) ? "decorated_pot" : sofa ? "wool" : "wood";
        for (String action : List.of("break", "step", "place", "hit", "fall")) {
            String sound = wild ? "minecraft:block.cave_vines." + action : crop ? switch (action) {
                case "break" -> "minecraft:block.crop.break"; case "place" -> "minecraft:item.crop.plant"; default -> "minecraft:block.grass." + action;
            } : "minecraft:block." + family + "." + action;
            sounds.addProperty(action, sound);
        }
        return sounds;
    }
    private static void combustible(JsonObject target, int color, String instrument) {
        target.addProperty("map_color", color); target.addProperty("instrument", instrument); burnable(target);
    }
    private static void burnable(JsonObject target) {
        target.addProperty("burnable", true); target.addProperty("burn_chance", 5); target.addProperty("fire_spread_chance", 20);
    }
}
