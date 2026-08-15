package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native port of the legacy block behavior/settings layer (tools/migrate_legacy.py:
 * carrier_type, trellis/holder/chalkboard carrier states, configured_sound,
 * storage_orientations, render_stack_visual, storage_slot_visuals,
 * storage_selector, storage_interaction, storage_launch, storage_behavior,
 * corner/linear/table connection behaviors, behavior_for and block_settings).
 */
public final class BlockBehaviors {
    public static final String NAMESPACE = "kaleidoscope_tavern";
    public static final String SHARED_SOFA_BLOCK = "_internal/sofa";
    public static final String SOFA_CARRIER_STATE = "minecraft:barrier";
    public static final String CIRCULAR_RACK_CARRIER_STATE =
            "minecraft:cave_vines[age=1,berries=true]";

    public static final Map<String, String[]> INCENSE_SPECS = Map.ofEntries(
            Map.entry("sakura_incense", new String[] {"CHERRY_LEAVES", "CHERRY_LEAVES", "-2.0", "16.0"}),
            Map.entry("pine_incense", new String[] {"SMOKE", "CAMPFIRE_COSY_SMOKE", "-2.0", "16.0"}),
            Map.entry("ginkgo_incense", new String[] {"WAX_OFF", "COMPOSTER", "-2.0", "16.0"}),
            Map.entry("spore_incense", new String[] {"SPORE_BLOSSOM_AIR", "SPORE_BLOSSOM_AIR", "-2.0", "16.0"}),
            Map.entry("catnip_incense", new String[] {"HAPPY_VILLAGER", "HAPPY_VILLAGER", "-2.0", "16.0"}),
            Map.entry("snow_incense", new String[] {"SNOWFLAKE", "SNOWFLAKE", "-2.0", "16.0"}),
            Map.entry("butterfly_incense", new String[] {"GLOW", "GLOW", "-2.0", "16.0"}),
            Map.entry("firefly_incense", new String[] {"FIREFLY", "FIREFLY", "-0.67", "5.33"}));
    public static final Set<String> INCENSE_BLOCKS = Set.copyOf(INCENSE_SPECS.keySet());

    public static final Map<String, Integer> STORAGE_BLOCK_SPECS = Map.of(
            "bar_cabinet", 2, "glass_bar_cabinet", 2, "cellar_cabinet", 9,
            "tilted_rack", 3, "circular_rack", 6, "holder", 1);
    public static final Set<String> STORAGE_BLOCKS = Set.copyOf(STORAGE_BLOCK_SPECS.keySet());
    public static final Set<String> CONNECTED_STORAGE_BLOCKS =
            Set.of("bar_cabinet", "glass_bar_cabinet", "cellar_cabinet");
    public static final Set<String> CONNECTED_GRID_BLOCKS = Set.of("bar_counter", "table");
    public static final Set<String> FURNITURE_STYLE_BLOCKS = union(
            CONNECTED_GRID_BLOCKS, STORAGE_BLOCKS);
    public static final Set<String> SHAPED_RACK_BLOCKS = Set.of(
            "tilted_rack", "circular_rack", "holder");
    public static final Set<String> BARRIER_STYLE_BLOCKS = Set.copyOf(
            difference(FURNITURE_STYLE_BLOCKS, SHAPED_RACK_BLOCKS));
    public static final Set<String> STURDY_BLOCKS = Set.of("trellis");
    public static final Set<String> CLIMBABLE_BLOCKS = Set.of(
            "wild_grapevine", "wild_grapevine_plant", "grapevine_trellis");
    public static final Set<String> NON_AXE_TRELLISES = Set.of(
            "ice_grapevine_trellis", "gold_grapevine_trellis");
    public static final Set<String> WOODEN_STORAGE_BLOCKS = Set.of(
            "cellar_cabinet", "tilted_rack", "circular_rack", "holder");
    public static final Set<String> WOODEN_TRELLISES = Set.of(
            "trellis", "grapevine_trellis", "ice_grapevine_trellis", "gold_grapevine_trellis");
    public static final List<String> SOFA_CONNECT_IDS = List.of(NAMESPACE + ":" + SHARED_SOFA_BLOCK);
    public static final Set<String> SOFA_COLORS = Set.of(
            "white", "orange", "magenta", "light_blue", "yellow", "lime",
            "pink", "gray", "light_gray", "cyan", "purple", "blue",
            "brown", "green", "red", "black");
    public static final Set<String> COCKTAILS = Set.of(
            "empty_glassware", "signature_cocktail", "mystery_cocktail", "white_lady",
            "emerald", "brass_heart", "godfather", "grasshopper", "screwdriver",
            "mojito", "allium_garden", "depth_charge", "nether_special", "bloody_mary",
            "sculk_special");
    public static final Set<String> BOTTLE_AND_GLASS_ITEMS = Set.copyOf(difference(
            com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.furniture.FurnitureBoxes.SMALL_FURNITURE,
            Set.of("shaker")));
    public static final Set<String> CABINET_BOTTLES = Set.copyOf(difference(
            BOTTLE_AND_GLASS_ITEMS, COCKTAILS));
    public static final List<String> STORAGE_ALLOWED_ITEMS = sortedFull(
            difference(CABINET_BOTTLES, Set.of("potion_bottle")));
    public static final List<String> STORAGE_PROJECTILE_ITEMS = sortedFull(difference(
            CABINET_BOTTLES, Set.of("empty_bottle", "water_bottle", "honey_bottle",
            "dragon_breath_bottle", "potion_bottle", "xp_bottle")));
    public static final List<String> STORAGE_PROJECTILE_SOUND_ITEMS = new ArrayList<>(
            STORAGE_PROJECTILE_ITEMS.stream().filter(item -> !item.equals(NAMESPACE + ":molotov")).toList());

    private BlockBehaviors() {}

    public static boolean isSofaBlock(String blockId) {
        if (blockId.equals(SHARED_SOFA_BLOCK)) return true;
        if (!blockId.endsWith("_sofa")) return false;
        return SOFA_COLORS.contains(blockId.substring(0, blockId.length() - 5));
    }

    public static String[] carrierType(String blockId) {
        if (blockId.equals("tilted_rack")) {
            return new String[] {"cactus", "kaleidoscope-tavern-tilted-rack-transparent"};
        }
        if (blockId.equals("circular_rack")) return new String[] {"state", CIRCULAR_RACK_CARRIER_STATE};
        if (blockId.equals("holder")) return new String[] {"horizontal_lightning_rod", "kaleidoscope-tavern-holder"};
        if (FURNITURE_STYLE_BLOCKS.contains(blockId)) return new String[] {"state", SOFA_CARRIER_STATE};
        return new String[] {"higher_tripwire", "kaleidoscope-tavern-decor-transparent"};
    }

    public static String trellisCarrierState(String trellisType, String waterlogged) {
        String facing = switch (trellisType) {
            case "single", "cross_north_south", "cross_east_west", "six_direction" -> "up";
            case "north_south", "cross_up_down" -> "north";
            case "east_west" -> "east";
            default -> throw new IllegalArgumentException("Unknown trellis type " + trellisType);
        };
        return "minecraft:lightning_rod[facing=" + facing + ",powered=false,waterlogged="
                + waterlogged + "]";
    }

    public static String holderCarrierState(String facing) {
        if (!Set.of("north", "east", "south", "west").contains(facing)) {
            throw new IllegalArgumentException("Unsupported holder facing: " + facing);
        }
        return "minecraft:lightning_rod[facing=" + facing + ",powered=false,waterlogged=false]";
    }

    public static String chalkboardCarrierState(String facing, String half) {
        return "minecraft:iron_door[facing=" + facing + ",half=" + half
                + ",hinge=left,open=false,powered=true]";
    }

    public static JsonObject configuredSound(String sound) {
        return configuredSound(sound, 1, 1, 1, 1);
    }

    /** Exact int/float parity with Python configured_sound arguments. */
    public static JsonObject configuredSound(String sound, Object volumeMin, Object volumeMax,
                                             Object pitchMin, Object pitchMax) {
        JsonObject result = new JsonObject();
        result.addProperty("id", sound);
        addExact(result, "volume_min", volumeMin);
        addExact(result, "volume_max", volumeMax);
        addExact(result, "pitch_min", pitchMin);
        addExact(result, "pitch_max", pitchMax);
        return result;
    }

    /** Writes the exact Python value type: Integer stays 1, Double stays 1.0. */
    public static void addExact(JsonObject target, String key, Object value) {
        if (value instanceof Integer integer) target.addProperty(key, integer);
        else if (value instanceof Number number) target.addProperty(key, number.doubleValue());
        else target.addProperty(key, String.valueOf(value));
    }

    /** Python json parity for computed doubles: integral values are written as ints. */
    public static void addNumber(JsonObject target, String key, Object value) {
        if (value instanceof Integer integer) target.addProperty(key, integer);
        else if (value instanceof Double d
                && d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 2_147_483_647.0) {
            target.addProperty(key, d.intValue());
        } else if (value instanceof Number number) {
            target.addProperty(key, number.doubleValue());
        } else {
            target.addProperty(key, String.valueOf(value));
        }
    }

    /** Python json parity: integral doubles are written as bare ints. */


    public static JsonObject storageOrientations(boolean reverseAxisXSlots,
                                                 boolean compensatePitchedAxisXYaw) {
        JsonObject result = new JsonObject();
        result.add("north", orientation(0, 0, "1-x", "z", false));
        result.add("east", orientation(-90, compensatePitchedAxisXYaw ? 90 : -90,
                "1-z", "1-x", reverseAxisXSlots));
        result.add("south", orientation(180, 180, "x", "1-z", false));
        result.add("west", orientation(90, compensatePitchedAxisXYaw ? 270 : 90,
                "z", "x", reverseAxisXSlots));
        return result;
    }

    private static JsonObject orientation(int positionYaw, int modelYaw, String localX,
                                          String localZ, boolean reverseSlots) {
        JsonObject result = new JsonObject();
        result.addProperty("position_yaw", positionYaw);
        result.addProperty("model_yaw", modelYaw);
        result.addProperty("local_x", localX);
        result.addProperty("local_z", localZ);
        result.addProperty("reverse_slots", reverseSlots);
        return result;
    }

    /** Python :g float formatting (6 significant digits, trailing zeros stripped). */
    public static String formatG(double value) {
        String formatted = String.format(java.util.Locale.ROOT, "%.6g", value);
        int exponent = formatted.indexOf('e');
        if (exponent < 0) exponent = formatted.indexOf('E');
        String mantissa = exponent >= 0 ? formatted.substring(0, exponent) : formatted;
        String expPart = exponent >= 0 ? formatted.substring(exponent) : "";
        if (mantissa.indexOf('.') >= 0) {
            mantissa = mantissa.replaceAll("0+$", "");
            if (mantissa.endsWith(".")) mantissa = mantissa.substring(0, mantissa.length() - 1);
        }
        return mantissa + expPart;
    }

    public static JsonObject renderStackVisual(double x, double y, double z, double scale,
                                               double yRotation, double xRotation) {
        double xRad = Math.toRadians(xRotation);
        double xCos = Math.cos(xRad);
        double xSin = Math.sin(xRad);
        double centerY = xCos * (scale * 0.5);
        double centerZ = xSin * (scale * 0.5);
        double yRad = Math.toRadians(yRotation);
        double ySin = Math.sin(yRad);
        double yCos = Math.cos(yRad);
        double centerX = ySin * centerZ;
        double rotatedZ = yCos * centerZ;
        JsonObject result = new JsonObject();
        result.addProperty("position", formatG(x + centerX) + "," + formatG(y + centerY)
                + "," + formatG(z + rotatedZ));
        addNumber(result, "scale", scale);
        addNumber(result, "y_rotation", yRotation);
        addNumber(result, "x_rotation", xRotation);
        return result;
    }

    public static JsonArray storageSlotVisuals(String blockId) {
        JsonArray result = new JsonArray();
        if (Set.of("bar_cabinet", "glass_bar_cabinet").contains(blockId)) {
            double y = 0.0625 + 0.9 * 0.5;
            JsonObject first = new JsonObject();
            first.addProperty("position", formatG(0.75) + "," + formatG(y) + ",0.5");
            first.addProperty("axis_x_position", formatG(0.25) + "," + formatG(y) + ",0.5");
            first.addProperty("exclusive_position", formatG(0.5) + "," + formatG(y) + ",0.5");
            first.addProperty("exclusive_axis_x_position", formatG(0.5) + "," + formatG(y) + ",0.5");
            first.addProperty("scale", 0.9);
            result.add(first);
            JsonObject second = new JsonObject();
            second.addProperty("position", formatG(0.25) + "," + formatG(y) + ",0.5");
            second.addProperty("axis_x_position", formatG(0.75) + "," + formatG(y) + ",0.5");
            second.addProperty("scale", 0.9);
            result.add(second);
            return result;
        }
        if (blockId.equals("cellar_cabinet")) {
            for (int slot = 0; slot < 9; slot++) {
                result.add(renderStackVisual(0.825 - (slot % 3) * 0.325,
                        0.78 - (slot / 3) * 0.29, 0.875, 1, 0, -90));
            }
            return result;
        }
        if (blockId.equals("tilted_rack")) {
            double scale = 0.9;
            double angle = Math.toRadians(22.5);
            double centerY = (Math.cos(angle) - Math.sin(angle)) * 0.5;
            double centerZ = (Math.sin(angle) + Math.cos(angle)) * 0.5;
            for (int slot = 0; slot < 3; slot++) {
                double x = 0.425 - 0.375 * slot;
                double y = 0.3125;
                double z = 0.02 + (slot - 1) * 0.005;
                JsonObject visual = new JsonObject();
                visual.addProperty("position", formatG(scale * (x + 0.5)) + ","
                        + formatG(scale * (y + centerY)) + "," + formatG(scale * (z + centerZ)));
                visual.addProperty("scale", scale);
                visual.addProperty("x_rotation", 22.5);
                result.add(visual);
            }
            return result;
        }
        if (blockId.equals("circular_rack")) {
            double[][] entries = {
                    {0.5, 0.125, 0}, {0.875, 0.3125, 22.5}, {0.875, 0.6875, -22.5},
                    {0.5, 0.875, 180}, {0.125, 0.6875, 157.5}, {0.125, 0.3125, -157.5}};
            for (double[] entry : entries) {
                result.add(renderStackVisual(entry[0], 0.125, entry[1], 0.82, entry[2], 0));
            }
            return result;
        }
        if (blockId.equals("holder")) {
            result.add(renderStackVisual(0.5, 0.125, 0.75, 0.95, 0, -45));
            return result;
        }
        throw new IllegalArgumentException("No storage slot layout for " + blockId);
    }

    public static JsonObject storageSelector(String blockId) {
        return switch (blockId) {
            case "bar_cabinet", "glass_bar_cabinet" -> obj("type", "split", "axis", "x", "segments", 2);
            case "cellar_cabinet" -> obj("type", "grid", "columns", 3, "rows", 3,
                    "reverse_y", true, "front_only", true);
            case "tilted_rack" -> obj("type", "split", "axis", "x", "segments", 3);
            case "circular_rack" -> obj("type", "radial", "segments", 6,
                    "radial_offset", 4, "radial_clockwise", true);
            case "holder" -> obj("type", "single");
            default -> throw new IllegalArgumentException("No storage selector for " + blockId);
        };
    }

    public static JsonObject storageInteraction(String blockId, Map<String, List<String>> tags) {
        JsonObject interaction = new JsonObject();
        interaction.add("allowed_items", strings(STORAGE_ALLOWED_ITEMS));
        List<String> blocked = new ArrayList<>();
        if (STORAGE_BLOCK_SPECS.containsKey(blockId)) {
            String blocklistName = switch (blockId) {
                case "cellar_cabinet" -> "cellar_cabinet_blocklist";
                case "tilted_rack" -> "tilted_rack_blocklist";
                case "circular_rack" -> "circular_rack_blocklist";
                case "holder" -> "holder_blocklist";
                default -> null;
            };
            if (blocklistName != null) {
                List<String> fromTags = tags.get(NAMESPACE + ":" + blocklistName);
                if (fromTags != null) blocked.addAll(fromTags);
            }
        }
        blocked.sort(String::compareTo);
        interaction.add("blocked_items", strings(blocked));
        interaction.addProperty("consume_in_creative", true);
        boolean cabinet = Set.of("bar_cabinet", "glass_bar_cabinet").contains(blockId);
        interaction.addProperty("invalid_result", cabinet ? "pass" : "fail");
        interaction.addProperty("blocked_result", "fail");
        if (cabinet) {
            interaction.add("invalid_message", JsonNull.INSTANCE);
            interaction.add("blocked_message", JsonNull.INSTANCE);
        } else {
            interaction.addProperty("invalid_message", "message." + NAMESPACE + ".rack.not_drink");
            interaction.addProperty("blocked_message", "message." + NAMESPACE + ".rack.irregular");
        }
        if (cabinet) {
            JsonObject sounds = new JsonObject();
            sounds.add("put", configuredSound("minecraft:block.glass.place",
                    0.8, 1.0, 0.2, 0.4));
            sounds.add("put_last", configuredSound("minecraft:block.glass.place",
                    0.8, 1.0, 0.8, 1.0));
            sounds.add("take", configuredSound("minecraft:block.glass.place",
                    0.8, 1.0, 0.8, 1.0));
            interaction.add("sounds", sounds);
        } else {
            JsonObject sounds = new JsonObject();
            sounds.add("put", configuredSound("minecraft:block.stone.place"));
            sounds.add("take", configuredSound("minecraft:entity.item_frame.remove_item"));
            interaction.add("sounds", sounds);
        }
        if (cabinet) {
            List<String> exclusive = new ArrayList<>();
            List<String> fromTags = tags.get(NAMESPACE + ":bar_cabinet_irregular");
            if (fromTags != null) exclusive.addAll(fromTags);
            exclusive.sort(String::compareTo);
            interaction.add("exclusive_items", strings(exclusive));
            interaction.addProperty("exclusive_slot", 0);
            interaction.addProperty("fallback_take", true);
            interaction.addProperty("fallback_put", true);
        }
        return interaction;
    }

    public static JsonObject storageLaunch(String blockId) {
        JsonObject base = new JsonObject();
        base.add("candidate_items", strings(STORAGE_ALLOWED_ITEMS));
        base.add("projectile_items", strings(STORAGE_PROJECTILE_ITEMS));
        base.add("sound_items", strings(STORAGE_PROJECTILE_SOUND_ITEMS));
        base.add("sound", configuredSound(NAMESPACE + ":block.holder.pop",
                0.9, 0.9, 1, 1));
        base.addProperty("factor_min", 0.5);
        base.addProperty("factor_max", 1.5);
        base.addProperty("vertical_factor", 0.1);
        base.addProperty("origin_y", 0.5);
        base.addProperty("origin_forward", 0);
        base.addProperty("direction", "facing");
        switch (blockId) {
            case "cellar_cabinet" -> {
                base.addProperty("origin_forward", 0.5);
                base.addProperty("factor_max", 2.5);
            }
            case "circular_rack" -> {
                base.addProperty("direction", "up");
                base.addProperty("factor_max", 2.5);
                base.addProperty("vertical_factor", 0);
            }
            case "tilted_rack" -> {
                base.addProperty("origin_forward", -0.5);
                base.addProperty("origin_y", 0.875);
                base.addProperty("direction", "opposite");
                base.addProperty("vertical_factor", 0.75);
            }
            case "holder" -> {
                base.addProperty("origin_forward", 0.5);
                base.addProperty("origin_y", 0.875);
                base.addProperty("vertical_factor", 0.375);
            }
            default -> { }
        }
        return base;
    }

    public static JsonObject storageBehavior(String blockId, Map<String, List<String>> tags) {
        JsonObject config = new JsonObject();
        config.addProperty("type", NAMESPACE + ":storage");
        config.addProperty("data_key", NAMESPACE + ":storage_" + blockId);
        config.addProperty("render_item_prefix", NAMESPACE + ":_render/storage/");
        config.addProperty("view_range", 1.25);
        JsonArray refresh = new JsonArray();
        if (blockId.equals("cellar_cabinet")) {
            refresh.add("facing");
            refresh.add("position");
        } else {
            refresh.add("facing");
        }
        config.add("refresh_properties", refresh);
        config.add("orientations", storageOrientations(
                Set.of("bar_cabinet", "glass_bar_cabinet").contains(blockId),
                Set.of("cellar_cabinet", "tilted_rack", "holder").contains(blockId)));
        config.add("selector", storageSelector(blockId));
        config.add("interaction", storageInteraction(blockId, tags));
        config.add("slots", storageSlotVisuals(blockId));
        if (!Set.of("bar_cabinet", "glass_bar_cabinet").contains(blockId)) {
            config.add("launch", storageLaunch(blockId));
        }
        if (blockId.equals("circular_rack")) {
            JsonObject particle = new JsonObject();
            particle.addProperty("type", "END_ROD");
            particle.addProperty("chance", 49 * 8);
            particle.addProperty("min_x", 0.125);
            particle.addProperty("max_x", 0.375);
            particle.addProperty("alternate_min_x", 0.625);
            particle.addProperty("alternate_max_x", 0.875);
            particle.addProperty("min_y", 0);
            particle.addProperty("max_y", 1);
            particle.addProperty("min_z", 0.125);
            particle.addProperty("max_z", 0.375);
            particle.addProperty("alternate_min_z", 0.625);
            particle.addProperty("alternate_max_z", 0.875);
            particle.addProperty("offset_x", 0.01);
            particle.addProperty("offset_y", 0.01);
            particle.addProperty("offset_z", 0.01);
            particle.addProperty("speed", 1);
            config.add("particle", particle);
        }
        return config;
    }

    public static JsonObject cornerConnectionBehavior(List<String> connects) {
        JsonObject topology = new JsonObject();
        JsonObject outputs = new JsonObject();
        outputs.addProperty("none", "single");
        outputs.addProperty("left", "right");
        outputs.addProperty("right", "left");
        outputs.addProperty("both", "middle");
        outputs.addProperty("front_left", "right_corner");
        outputs.addProperty("front_left_with_right", "left");
        outputs.addProperty("front_right", "left_corner");
        outputs.addProperty("front_right_with_left", "right");
        topology.add("outputs", outputs);
        JsonObject compatibility = new JsonObject();
        JsonArray leftPerp = new JsonArray();
        leftPerp.add("single"); leftPerp.add("right"); leftPerp.add("right_corner");
        compatibility.add("left_perpendicular", leftPerp);
        JsonArray rightPerp = new JsonArray();
        rightPerp.add("single"); rightPerp.add("left"); rightPerp.add("left_corner");
        compatibility.add("right_perpendicular", rightPerp);
        compatibility.addProperty("front_left_excluded", "left_corner");
        compatibility.addProperty("front_right_excluded", "right_corner");
        topology.add("compatibility", compatibility);
        JsonObject result = new JsonObject();
        result.addProperty("type", NAMESPACE + ":connected_block");
        result.addProperty("mode", "corner");
        result.add("connects", strings(connects));
        result.addProperty("state_property", "connection");
        result.add("topology", topology);
        return result;
    }

    public static JsonObject linearConnectionBehavior(String blockId) {
        JsonObject topology = new JsonObject();
        JsonObject outputs = new JsonObject();
        outputs.addProperty("none", "single");
        outputs.addProperty("left", "right");
        outputs.addProperty("right", "left");
        outputs.addProperty("both", "middle");
        topology.add("outputs", outputs);
        JsonObject result = new JsonObject();
        result.addProperty("type", NAMESPACE + ":connected_block");
        result.addProperty("mode", "linear");
        result.add("connects", strings(List.of(NAMESPACE + ":" + blockId)));
        result.addProperty("state_property", "position");
        result.add("topology", topology);
        return result;
    }

    public static JsonObject tableConnectionBehavior() {
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
        JsonObject result = new JsonObject();
        result.addProperty("type", NAMESPACE + ":connected_block");
        result.addProperty("mode", "table");
        result.add("connects", strings(List.of(NAMESPACE + ":table")));
        result.addProperty("axis_property", "table_axis");
        result.addProperty("state_property", "position");
        result.add("topology", topology);
        return result;
    }

    /** Returns a behavior JsonObject, a JsonArray of behaviors, or null when absent. */
    public static JsonElement behaviorFor(String blockId, Set<String> propertyNames,
                                          Map<String, List<String>> tags) {
        if (blockId.equals(SHARED_SOFA_BLOCK)) {
            JsonArray behaviors = new JsonArray();
            behaviors.add(cornerConnectionBehavior(SOFA_CONNECT_IDS));
            JsonObject seat = new JsonObject();
            seat.addProperty("type", "seat_block");
            JsonArray seats = new JsonArray();
            seats.add("0," + formatG(com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.furniture.FurnitureBoxes.seatOffset(8.0 / 16)) + ",0 180");
            seat.add("seats", seats);
            behaviors.add(seat);
            JsonObject tint = new JsonObject();
            tint.addProperty("type", "tint_source_block");
            tint.addProperty("drop_item", true);
            behaviors.add(tint);
            return behaviors;
        }
        if (blockId.equals("bar_counter")) {
            return cornerConnectionBehavior(List.of(NAMESPACE + ":bar_counter"));
        }
        if (blockId.equals("table")) return tableConnectionBehavior();
        if (blockId.equals("tap")) return obj("type", NAMESPACE + ":tap");
        if (blockId.equals("pressing_tub")) return obj("type", NAMESPACE + ":pressing_tub_block");
        if (blockId.equals("chalkboard")) {
            JsonArray behaviors = new JsonArray();
            behaviors.add(obj("type", "double_high_block"));
            behaviors.add(obj("type", NAMESPACE + ":chalkboard"));
            return behaviors;
        }
        if (STORAGE_BLOCK_SPECS.containsKey(blockId)) {
            if (tags == null) {
                throw new IllegalArgumentException(
                        "Storage behavior " + blockId + " requires flattened item tags");
            }
            JsonObject configuredStorage = storageBehavior(blockId, tags);
            if (CONNECTED_STORAGE_BLOCKS.contains(blockId)) {
                JsonArray behaviors = new JsonArray();
                behaviors.add(linearConnectionBehavior(blockId));
                behaviors.add(configuredStorage);
                return behaviors;
            }
            return configuredStorage;
        }
        if (INCENSE_BLOCKS.contains(blockId)) {
            String[] spec = INCENSE_SPECS.get(blockId);
            return obj("type", NAMESPACE + ":incense",
                    "small_particle", spec[0], "large_particle", spec[1],
                    "large_particle_y_offset", Double.parseDouble(spec[2]),
                    "large_particle_y_range", Double.parseDouble(spec[3]));
        }
        if (blockId.equals("wild_grapevine")) {
            return obj("type", NAMESPACE + ":wild_grapevine",
                    "body", NAMESPACE + ":wild_grapevine_plant",
                    "direction", "down", "grow_speed", 0.15);
        }
        if (blockId.equals("wild_grapevine_plant")) {
            return obj("type", NAMESPACE + ":wild_grapevine",
                    "head", NAMESPACE + ":wild_grapevine",
                    "direction", "down", "bone_meal", obj("behavior", "grow", "grow_blocks", 1));
        }
        if (blockId.equals("trellis")) return obj("type", NAMESPACE + ":trellis");
        if (blockId.endsWith("_grapevine_trellis") || blockId.equals("grapevine_trellis")) {
            return obj("type", NAMESPACE + ":trellis", "spread_chance", 0.25);
        }
        if (blockId.endsWith("_grape_crop") || blockId.equals("grape_crop")) {
            return obj("type", NAMESPACE + ":hanging_grape_crop");
        }
        return null;
    }

    /** Sofa block settings: block_settings("white_sofa", false) with the source pickaxe tag. */
    public static JsonObject sofaSettings(String publicItem) {
        JsonObject settings = blockSettings("white_sofa", false);
        JsonArray tags = new JsonArray();
        tags.add("minecraft:mineable/pickaxe");
        settings.add("tags", tags);
        if (publicItem != null) settings.addProperty("item", publicItem);
        return settings;
    }

    public static JsonObject blockSettings(String blockId, boolean hasItem) {
        boolean isWildVine = Set.of("wild_grapevine", "wild_grapevine_plant").contains(blockId);
        boolean isCrop = blockId.endsWith("_grape_crop") || blockId.equals("grape_crop");
        boolean isStorage = STORAGE_BLOCKS.contains(blockId);
        boolean isSofa = isSofaBlock(blockId);
        boolean sturdy = STURDY_BLOCKS.contains(blockId) || isStorage || isSofa
                || Set.of("chalkboard", "pressing_tub", "table", "bar_counter").contains(blockId);
        JsonObject sounds = new JsonObject();
        if (isWildVine) {
            for (String action : List.of("break", "step", "place", "hit", "fall")) {
                sounds.addProperty(action, "minecraft:block.cave_vines." + action);
            }
        } else if (isCrop) {
            sounds.addProperty("break", "minecraft:block.crop.break");
            sounds.addProperty("step", "minecraft:block.grass.step");
            sounds.addProperty("place", "minecraft:item.crop.plant");
            sounds.addProperty("hit", "minecraft:block.grass.hit");
            sounds.addProperty("fall", "minecraft:block.grass.fall");
        } else {
            String family = blockId.equals("tap") ? "metal"
                    : INCENSE_BLOCKS.contains(blockId) ? "decorated_pot"
                    : isSofa ? "wool" : "wood";
            for (String action : List.of("break", "step", "place", "hit", "fall")) {
                sounds.addProperty(action, "minecraft:block." + family + "." + action);
            }
        }
        double hardness = isStorage ? 2.5 : blockId.equals("table") ? 2.0
                : (isWildVine || isCrop || INCENSE_BLOCKS.contains(blockId)) ? 0.0 : 0.8;
        double resistance = blockId.equals("table") ? 3.0 : hardness;
        JsonObject settings = new JsonObject();
        settings.addProperty("hardness", hardness);
        settings.addProperty("resistance", resistance);
        settings.addProperty("push_reaction",
                (sturdy || blockId.equals("tap")) ? "NORMAL" : "DESTROY");
        settings.addProperty("is_redstone_conductor", false);
        settings.addProperty("is_suffocating", false);
        settings.addProperty("is_view_blocking", false);
        settings.addProperty("can_occlude", false);
        settings.addProperty("propagate_skylight", true);
        settings.add("sounds", sounds);
        JsonArray tags = new JsonArray();
        if (CLIMBABLE_BLOCKS.contains(blockId)) {
            tags.add("minecraft:climbable");
        }
        if (blockId.equals("tap") || isSofa
                || Set.of("tilted_rack", "circular_rack", "holder").contains(blockId)) {
            tags.add("minecraft:mineable/pickaxe");
        } else if (!isWildVine && !isCrop && !INCENSE_BLOCKS.contains(blockId)
                && !NON_AXE_TRELLISES.contains(blockId)) {
            tags.add("minecraft:mineable/axe");
        }
        settings.add("tags", tags);
        if (isStorage || blockId.equals("chalkboard") || blockId.equals("pressing_tub")) {
            settings.add("destroy_stages", obj("template", "internal:destroy_stages"));
        }
        if (blockId.equals("chalkboard") || blockId.equals("pressing_tub")) {
            combustible(settings, 13, "guitar");
        }
        if (WOODEN_STORAGE_BLOCKS.contains(blockId)) {
            settings.addProperty("map_color", 13);
            burnable(settings);
        }
        if (WOODEN_TRELLISES.contains(blockId)) {
            combustible(settings, 13, "guitar");
        }
        if (isSofa) {
            combustible(settings, 27, "guitar");
            settings.addProperty("support_shape", "minecraft:cobweb");
        } else if (blockId.equals("table")) {
            combustible(settings, 13, "bass");
        } else if (Set.of("bar_counter", "bar_cabinet", "glass_bar_cabinet").contains(blockId)) {
            combustible(settings, blockId.equals("bar_counter") ? 29 : 13, "guitar");
        }
        if (hasItem) settings.addProperty("item", NAMESPACE + ":" + blockId);
        if (blockId.contains("lamp")) settings.addProperty("luminance", 15);
        else if (blockId.equals("circular_rack")) settings.addProperty("luminance", 14);
        return settings;
    }

    private static void combustible(JsonObject target, int color, String instrument) {
        target.addProperty("map_color", color);
        target.addProperty("instrument", instrument);
        burnable(target);
    }

    private static void burnable(JsonObject target) {
        target.addProperty("burnable", true);
        target.addProperty("burn_chance", 5);
        target.addProperty("fire_spread_chance", 20);
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
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

    private static List<String> sortedFull(Set<String> bare) {
        List<String> result = new ArrayList<>();
        bare.stream().sorted().forEach(id -> result.add(NAMESPACE + ":" + id));
        return result;
    }

    private static <T> Set<T> union(Set<T> a, Set<T> b) {
        java.util.LinkedHashSet<T> result = new java.util.LinkedHashSet<>(a);
        result.addAll(b);
        return result;
    }

    private static <T> Set<T> difference(Set<T> a, Set<T> b) {
        java.util.LinkedHashSet<T> result = new java.util.LinkedHashSet<>(a);
        result.removeAll(b);
        return result;
    }
}
