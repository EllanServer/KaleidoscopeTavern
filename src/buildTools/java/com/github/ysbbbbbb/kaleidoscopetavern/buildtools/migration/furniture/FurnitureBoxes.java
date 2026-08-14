package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.furniture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Native port of the legacy furniture box helpers (tools/migrate_legacy.py:
 * number/vector/parse_vector/add_vector, drink_boxes, source_boxes,
 * hitbox_position, aggregate_box, seat_offset, interaction_box, shulker_box,
 * peek_for, physical_box, entity_uv_faces, entity_barrel_box, solidify_planes
 * and furniture_hitboxes). Key order and number formatting follow the script.
 */
public final class FurnitureBoxes {
    public static final String NAMESPACE = "kaleidoscope_tavern";
    public static final double SEAT_MOUNT_HEIGHT = 0.6;
    public static final double INTERIOR_PANEL_THICKNESS = 0.01;

    public static final Set<String> TALL_DRINKS = Set.of(
            "wine", "champagne", "sakura_wine", "whiskey", "ice_wine",
            "polaris_sweet_white", "honey_wine", "red_queen", "miners_star", "rum",
            "sherry", "luminous_bride", "glowflower_brew",
            "sauvignon_blanc_dry_white", "vinegar", "watermelon_juice");
    public static final Set<String> WIDE_DRINKS = Set.of(
            "vodka", "riesling_dry_white", "madame_shexiang",
            "sweet_berry_wine", "mother_snow");
    public static final Set<String> BRANDY_DRINKS = Set.of("brandy", "sunset_glow");
    public static final Set<String> COCKTAILS = Set.of(
            "empty_glassware", "signature_cocktail", "mystery_cocktail", "white_lady",
            "emerald", "brass_heart", "godfather", "grasshopper", "screwdriver",
            "mojito", "allium_garden", "depth_charge", "nether_special", "bloody_mary",
            "sculk_special");
    public static final Set<String> SIMPLE_BOTTLES = Set.of(
            "water_bottle", "honey_bottle", "dragon_breath_bottle",
            "potion_bottle", "xp_bottle");
    public static final Set<String> PENDANT_LAMPS = Set.of(
            "bell_pendant_lamp", "yellow_pendant_lamp", "blue_pendant_lamp");
    public static final Set<String> PAINTINGS = Set.of(
            "ysbb_painting", "tartaric_acid_painting", "cr019_painting", "unknown_painting",
            "master_marisa_painting", "son_of_man_painting", "david_painting",
            "girl_with_pearl_earring_painting", "starry_night_painting",
            "van_gogh_self_portrait_painting", "father_painting", "great_wave_painting",
            "mona_lisa_painting", "mondrian_painting");
    public static final Set<String> SMALL_FURNITURE = Set.of(
            "empty_bottle", "empty_glassware", "signature_cocktail", "mystery_cocktail",
            "white_lady", "emerald", "brass_heart", "godfather", "grasshopper",
            "screwdriver", "mojito", "allium_garden", "depth_charge", "nether_special",
            "bloody_mary", "sculk_special", "shaker", "molotov", "water_bottle",
            "honey_bottle", "dragon_breath_bottle", "potion_bottle", "xp_bottle",
            "wine", "champagne", "vodka", "brandy", "carignan", "sakura_wine",
            "plum_wine", "whiskey", "ice_wine", "polaris_sweet_white", "honey_wine",
            "red_queen", "miners_star", "rum", "riesling_dry_white", "sunset_glow",
            "madame_shexiang", "sweet_berry_wine", "sherry", "mother_snow",
            "luminous_bride", "glowflower_brew", "sauvignon_blanc_dry_white",
            "vinegar", "watermelon_juice");

    private FurnitureBoxes() {}

    /** Immutable six-component source-space box. */
    public record Box(double x1, double y1, double z1, double x2, double y2, double z2) {
        public Box(double... values) {
            this(values[0], values[1], values[2], values[3], values[4], values[5]);
        }
    }

    /** Python float()/str parity: six decimals, trailing zeros and dot stripped. */
    public static String number(double value) {
        if (Math.abs(value) < 1.0e-8) return "0";
        String fixed = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_EVEN).toPlainString();
        if (fixed.indexOf('.') >= 0) {
            fixed = fixed.replaceAll("0+$", "");
            if (fixed.endsWith(".")) fixed = fixed.substring(0, fixed.length() - 1);
        }
        return fixed;
    }

    public static String vector(double x, double y, double z) {
        return number(x) + "," + number(y) + "," + number(z);
    }

    public static double[] parseVector(String raw) {
        if (raw == null) return new double[] {0.0, 0.0, 0.0};
        String[] parts = raw.split(",", -1);
        if (parts.length != 3) throw new IllegalArgumentException(
                "Expected three vector components, got " + raw);
        return new double[] {Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2])};
    }

    public static double[] addVector(double[] first, double[] second) {
        return new double[] {first[0] + second[0], first[1] + second[1], first[2] + second[2]};
    }

    public static double[] hitboxPosition(String anchor, double x, double y, double z) {
        return switch (anchor) {
            case "ground" -> new double[] {x / 16 - 0.5, y / 16, z / 16 - 0.5};
            case "ceiling" -> new double[] {x / 16 - 0.5, -1 + y / 16, z / 16 - 0.5};
            case "wall" -> new double[] {x / 16 - 0.5, y / 16 - 0.5, z / 16};
            default -> throw new IllegalArgumentException("Unknown furniture anchor " + anchor);
        };
    }

    public static List<Box> drinkBoxes(String blockId, int count) {
        if (TALL_DRINKS.contains(blockId)) {
            return switch (count) {
                case 1 -> List.of(new Box(6, 0, 6, 10, 16, 10));
                case 2 -> List.of(new Box(2, 0, 6, 14, 16, 10));
                case 3 -> List.of(new Box(2, 0, 10, 14, 16, 14), new Box(6, 0, 2, 10, 16, 14));
                case 4 -> List.of(new Box(2, 0, 2, 14, 16, 14));
                default -> List.of();
            };
        }
        if (WIDE_DRINKS.contains(blockId)) {
            return switch (count) {
                case 1 -> List.of(new Box(4, 0, 4, 12, 15, 12));
                case 2 -> List.of(new Box(0, 0, 4, 16, 15, 12));
                case 3 -> List.of(new Box(0, 0, 8, 16, 15, 16), new Box(4, 0, 0, 12, 15, 16));
                case 4 -> List.of(new Box(0, 0, 0, 16, 16, 16));
                default -> List.of();
            };
        }
        if (BRANDY_DRINKS.contains(blockId)) {
            return switch (count) {
                case 1 -> List.of(new Box(3, 0, 6, 13, 13, 10));
                case 2 -> List.of(new Box(1, 0, 3, 15, 12, 12));
                case 3 -> List.of(new Box(1, 0, 1, 16, 12, 13));
                default -> List.of();
            };
        }
        if (blockId.equals("carignan")) {
            return switch (count) {
                case 1 -> List.of(new Box(3, 0, 6, 13, 12, 10));
                case 2 -> List.of(new Box(1, 0, 3, 15, 12, 12));
                case 3 -> List.of(new Box(0, 0, 1, 16, 12, 13));
                default -> List.of();
            };
        }
        if (blockId.equals("plum_wine")) {
            return switch (count) {
                case 1 -> List.of(new Box(6, 0, 6, 10, 12, 10));
                case 2 -> List.of(new Box(3, 0, 6, 13, 12, 10));
                case 3 -> List.of(new Box(3, 0, 9, 13, 12, 13), new Box(6, 0, 3, 10, 12, 13));
                case 4 -> List.of(new Box(3, 0, 3, 13, 12, 13));
                default -> List.of();
            };
        }
        return List.of();
    }

    public static List<Box> sourceBoxes(String blockId, String anchor, java.util.Map<String, String> properties) {
        int count = 1;
        if (properties != null && properties.get("count") != null) {
            count = Integer.parseInt(properties.get("count"));
        }
        List<Box> drinks = drinkBoxes(blockId, count);
        if (!drinks.isEmpty()) return drinks;
        if (blockId.endsWith("_sofa")) return List.of(new Box(0, 0, 0, 16, 18, 16));
        if (blockId.endsWith("_bar_stool")) return List.of(new Box(2, 0, 2, 14, 21, 14));
        if (blockId.endsWith("_sandwich_board")) return List.of(new Box(2, 0, 2, 14, 22, 14));
        if (PENDANT_LAMPS.contains(blockId)) return List.of(new Box(1, -15, 5, 15, 16, 11));
        if (blockId.equals("stepladder")) return List.of(new Box(0, 0, 0, 16, 32, 16));
        if (PAINTINGS.contains(blockId)) {
            return switch (anchor) {
                case "ground" -> List.of(new Box(1, 0, 1, 15, 1, 15));
                case "wall" -> List.of(new Box(1, 1, 0, 15, 15, 1));
                case "ceiling" -> List.of(new Box(1, 15, 1, 15, 16, 15));
                default -> throw new IllegalArgumentException("Unknown furniture anchor " + anchor);
            };
        }
        if (blockId.equals("glassware_holder")) return List.of(new Box(0, 11, 1, 16, 16, 15));
        if (COCKTAILS.contains(blockId)) return List.of(new Box(4, 0, 4, 12, 10, 12));
        if (blockId.equals("shaker")) return List.of(new Box(4, 0, 4, 12, 16, 12));
        if (SIMPLE_BOTTLES.contains(blockId)) return List.of(new Box(5, 0, 5, 11, 10, 11));
        if (blockId.equals("empty_bottle") || blockId.equals("molotov")) {
            return List.of(new Box(5, 0, 5, 11, 14, 11));
        }
        if (blockId.equals("table")) return List.of(new Box(0, 13, 0, 16, 16, 16));
        if (blockId.equals("tilted_rack")) return List.of(new Box(0, 0, 5, 16, 14, 15));
        if (blockId.equals("circular_rack")) return List.of(new Box(0, 0, 0, 16, 2, 16));
        if (blockId.equals("holder")) return List.of(new Box(5, 0, 2, 11, 16, 14));
        return List.of(new Box(0, 0, 0, 16, 16, 16));
    }

    public static Box aggregateBox(List<Box> boxes) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Box box : boxes) {
            minX = Math.min(minX, box.x1()); minY = Math.min(minY, box.y1()); minZ = Math.min(minZ, box.z1());
            maxX = Math.max(maxX, box.x2()); maxY = Math.max(maxY, box.y2()); maxZ = Math.max(maxZ, box.z2());
        }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static double seatOffset(double cushionTop) {
        return round(cushionTop - SEAT_MOUNT_HEIGHT, 6);
    }

    private static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_EVEN).doubleValue();
    }

    public static JsonObject interactionBox(Box box, String anchor, List<String> seats) {
        double minX = box.x1(), minY = box.y1(), minZ = box.z1();
        double maxX = box.x2(), maxY = box.y2(), maxZ = box.z2();
        double[] position = hitboxPosition(anchor, (minX + maxX) / 2, minY, (minZ + maxZ) / 2);
        JsonObject result = new JsonObject();
        result.addProperty("type", "interaction");
        result.addProperty("position", vector(position[0], position[1], position[2]));
        result.addProperty("width", round(Math.max(maxX - minX, maxZ - minZ) / 16, 6));
        result.addProperty("height", round((maxY - minY) / 16, 6));
        result.addProperty("can_use_item_on", true);
        result.addProperty("can_be_hit_by_projectile", true);
        result.addProperty("interactive", true);
        result.addProperty("blocks_building", true);
        if (seats != null && !seats.isEmpty()) {
            JsonArray seatArray = new JsonArray();
            seats.forEach(seatArray::add);
            result.add("seats", seatArray);
        }
        return result;
    }

    public static JsonObject shulkerBox(double[] position, double scale, int peek,
                                        String direction, boolean blocksBuilding, Boolean invisible) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "shulker");
        result.addProperty("position", vector(position[0], position[1], position[2]));
        result.addProperty("peek", peek);
        result.addProperty("interaction_entity", false);
        result.addProperty("can_use_item_on", true);
        result.addProperty("can_be_hit_by_projectile", true);
        result.addProperty("interactive", false);
        result.addProperty("blocks_building", blocksBuilding);
        if (Math.abs(scale - 1.0) > 1.0e-8) result.addProperty("scale", round(scale, 6));
        if (direction != null) result.addProperty("direction", direction);
        if (invisible != null) result.addProperty("invisible", invisible);
        return result;
    }

    public static int peekFor(double scale, double height) {
        if (height <= scale) return 0;
        double physicalPeek = Math.max(0.0, Math.min(1.0, height / scale - 1.0));
        double raw = 0.5 - Math.asin(1.0 - 2.0 * physicalPeek) / Math.PI;
        return Math.max(0, Math.min(100, (int) Math.rint(raw * 100)));
    }

    public static List<JsonObject> physicalBox(Box box, String anchor, int tileLimit) {
        double minX = box.x1(), minY = box.y1(), minZ = box.z1();
        double maxX = box.x2(), maxY = box.y2(), maxZ = box.z2();
        double widthX = (maxX - minX) / 16;
        double widthZ = (maxZ - minZ) / 16;
        double height = (maxY - minY) / 16;
        double cell = Math.min(widthX, widthZ);
        int tilesX = (int) Math.rint(widthX / cell);
        int tilesZ = (int) Math.rint(widthZ / cell);
        boolean canTile = Math.abs(tilesX * cell - widthX) < 1.0e-6
                && Math.abs(tilesZ * cell - widthZ) < 1.0e-6
                && tilesX * tilesZ <= tileLimit;
        if (!canTile) {
            cell = Math.max(widthX, widthZ);
            tilesX = 1;
            tilesZ = 1;
        }
        List<JsonObject> result = new ArrayList<>();
        for (int tileX = 0; tileX < tilesX; tileX++) {
            double x = !canTile ? (minX + maxX) / 32 : minX / 16 + cell * (tileX + 0.5);
            for (int tileZ = 0; tileZ < tilesZ; tileZ++) {
                double z = !canTile ? (minZ + maxZ) / 32 : minZ / 16 + cell * (tileZ + 0.5);
                double remaining = height;
                double y = minY / 16;
                while (remaining > 1.0e-8) {
                    double segmentScale = cell;
                    double segmentHeight = Math.min(remaining, 2 * cell);
                    if (segmentHeight < cell) segmentScale = segmentHeight;
                    double sourceY = y * 16;
                    double[] position = hitboxPosition(anchor, x * 16, sourceY, z * 16);
                    result.add(shulkerBox(position, segmentScale, peekFor(segmentScale, segmentHeight),
                            null, true, null));
                    y += segmentHeight;
                    remaining -= segmentHeight;
                }
            }
        }
        return result;
    }

    public static JsonObject entityUvFaces(double u, double v, double dx, double dy, double dz) {
        double u0 = u, u1 = u + dz, u2 = u + dz + dx, u3 = u + dz + dx + dx;
        double u4 = u + dz + dx + dz, u5 = u + dz + dx + dz + dx;
        double v0 = v, v1 = v + dz, v2 = v + dz + dy;
        JsonArray down = uv(u1, v0, u2, v1), up = uv(u2, v1, u3, v0);
        JsonArray west = uv(u0, v1, u1, v2), north = uv(u1, v1, u2, v2);
        JsonArray east = uv(u2, v1, u4, v2), south = uv(u4, v1, u5, v2);
        java.util.LinkedHashMap<String, JsonArray> source = new java.util.LinkedHashMap<>();
        source.put("down", down); source.put("up", up); source.put("west", west);
        source.put("north", north); source.put("east", east); source.put("south", south);
        java.util.LinkedHashMap<String, JsonArray> transformed = new java.util.LinkedHashMap<>();
        transformed.put("up", source.get("down")); transformed.put("down", source.get("up"));
        transformed.put("east", source.get("west")); transformed.put("west", source.get("east"));
        transformed.put("north", source.get("north")); transformed.put("south", source.get("south"));
        JsonObject result = new JsonObject();
        for (var entry : transformed.entrySet()) {
            JsonObject face = new JsonObject();
            face.add("uv", entry.getValue());
            face.addProperty("texture", "#barrel");
            result.add(entry.getKey(), face);
        }
        return result;
    }

    private static JsonArray uv(double a, double b, double c, double d) {
        JsonArray array = new JsonArray();
        array.add(round(a / 16, 6));
        array.add(round(b / 16, 6));
        array.add(round(c / 16, 6));
        array.add(round(d / 16, 6));
        return array;
    }

    public static JsonObject entityBarrelBox(double x, double y, double z,
                                             double dx, double dy, double dz,
                                             double u, double v) {
        JsonObject result = new JsonObject();
        JsonArray from = new JsonArray();
        addNumber(from, -x - dx); addNumber(from, -1 - y - dy); addNumber(from, z - 1);
        result.add("from", from);
        JsonArray to = new JsonArray();
        addNumber(to, -x); addNumber(to, -1 - y); addNumber(to, z + dz - 1);
        result.add("to", to);
        result.add("faces", entityUvFaces(u, v, dx, dy, dz));
        return result;
    }

    private static void addNumber(JsonArray array, double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 2_147_483_647.0) {
            array.add((int) value);
        } else {
            array.add(value);
        }
    }

    private static final java.util.Map<Integer, String[]> FLAT_AXIS_FACE_PAIRS = java.util.Map.of(
            0, new String[] {"east", "west"},
            1, new String[] {"up", "down"},
            2, new String[] {"north", "south"});

    public static JsonObject solidifyPlanes(JsonObject element) {
        JsonArray fromArray = element.getAsJsonArray("from");
        JsonArray toArray = element.getAsJsonArray("to");
        int flatAxis = -1;
        int flatCount = 0;
        for (int index = 0; index < 3; index++) {
            if (fromArray.get(index).getAsDouble() == toArray.get(index).getAsDouble()) {
                flatAxis = index;
                flatCount++;
            }
        }
        if (flatCount != 1) return element;
        JsonArray start = fromArray.deepCopy();
        JsonArray end = toArray.deepCopy();
        start.set(flatAxis, new com.google.gson.JsonPrimitive(
                start.get(flatAxis).getAsDouble() - INTERIOR_PANEL_THICKNESS));
        end.set(flatAxis, new com.google.gson.JsonPrimitive(
                end.get(flatAxis).getAsDouble() + INTERIOR_PANEL_THICKNESS));
        JsonObject faces = new JsonObject();
        if (element.has("faces")) {
            for (var entry : element.getAsJsonObject("faces").entrySet()) {
                faces.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        String[] pair = FLAT_AXIS_FACE_PAIRS.get(flatAxis);
        if (faces.has(pair[0]) && faces.has(pair[1])) {
            JsonObject back = faces.getAsJsonObject(pair[1]);
            back.add("uv", faces.getAsJsonObject(pair[0]).get("uv").deepCopy());
        }
        JsonObject result = new JsonObject();
        for (var entry : element.entrySet()) result.add(entry.getKey(), entry.getValue().deepCopy());
        result.add("from", start);
        result.add("to", end);
        result.add("faces", faces);
        return result;
    }

    public static JsonArray jsonArrayOf(java.util.Collection<JsonObject> objects) {
        JsonArray array = new JsonArray();
        objects.forEach(array::add);
        return array;
    }

    /** Full furniture hitbox list for one block/anchor/variant. */
    public static List<JsonObject> furnitureHitboxes(String blockId, String anchor,
                                                     java.util.Map<String, String> properties) {
        java.util.Map<String, String> props = properties == null ? java.util.Map.of() : properties;
        if (blockId.startsWith("string_lights_")) {
            List<JsonObject> result = new ArrayList<>();
            for (int start : new int[] {0, 5, 10}) {
                result.add(interactionBox(new Box(start, 4, 0, start + 6, 16, 6), "wall", null));
            }
            return result;
        }
        List<Box> boxes = sourceBoxes(blockId, anchor, props);
        Box aggregate = aggregateBox(boxes);
        if (blockId.equals("barrel")) {
            JsonObject ghast = new JsonObject();
            ghast.addProperty("type", "happy_ghast");
            ghast.addProperty("position", "0,0,0");
            ghast.addProperty("scale", 0.75);
            ghast.addProperty("hard_collision", true);
            ghast.addProperty("can_use_item_on", true);
            ghast.addProperty("can_be_hit_by_projectile", true);
            ghast.addProperty("blocks_building", true);
            return List.of(ghast);
        }
        if (blockId.equals("stepladder")) {
            List<JsonObject> result = new ArrayList<>();
            result.add(shulkerBox(new double[] {0, 0, 0}, 0.75, 0, "up", true, false));
            result.add(shulkerBox(new double[] {0, 0.75, -0.25}, 0.625, 25, "north", false, false));
            result.add(shulkerBox(new double[] {-0.25, 1.5, -0.25}, 0.4, 35, "up", false, false));
            result.add(shulkerBox(new double[] {0.25, 1.5, -0.25}, 0.4, 35, "up", false, false));
            return result;
        }
        if (blockId.endsWith("_sofa")) {
            List<JsonObject> result = new ArrayList<>();
            result.add(interactionBox(aggregate, anchor,
                    List.of("0," + number(seatOffset(8.0 / 16)) + ",0 0")));
            for (double x : new double[] {-0.25, 0.25}) {
                for (double z : new double[] {-0.25, 0.25}) {
                    result.add(shulkerBox(new double[] {x, 0, z}, 0.5, 0, null, true, null));
                }
            }
            return result;
        }
        if (blockId.endsWith("_bar_stool")) {
            List<JsonObject> result = new ArrayList<>();
            result.add(interactionBox(aggregate, anchor,
                    List.of("0," + number(seatOffset(15.0 / 16)) + ",0 0")));
            result.add(shulkerBox(new double[] {0, 3.0 / 16, 0}, 0.75, 0, null, true, null));
            return result;
        }
        if (blockId.endsWith("_sandwich_board")) {
            List<JsonObject> result = new ArrayList<>();
            result.add(interactionBox(aggregate, anchor, null));
            result.add(shulkerBox(new double[] {0, 0, 0}, 0.75, peekFor(0.75, 1.375), null, true, null));
            return result;
        }
        if (PENDANT_LAMPS.contains(blockId)) {
            return List.of(interactionBox(aggregate, anchor, null));
        }
        if (PAINTINGS.contains(blockId)) {
            JsonObject hitbox = interactionBox(aggregate, anchor, null);
            hitbox.addProperty("blocks_building", false);
            return List.of(hitbox);
        }
        if (blockId.equals("glassware_holder")) {
            return List.of(interactionBox(aggregate, anchor, null));
        }
        if (blockId.equals("table")) {
            List<JsonObject> result = new ArrayList<>();
            result.add(interactionBox(aggregate, anchor, null));
            double[] quarters = {-0.375, -0.125, 0.125, 0.375};
            for (double x : quarters) {
                for (double z : quarters) {
                    result.add(shulkerBox(new double[] {x, 0.75, z}, 0.25, 0, null, true, null));
                }
            }
            return result;
        }
        if (Set.of("bar_counter", "bar_cabinet", "glass_bar_cabinet", "cellar_cabinet").contains(blockId)) {
            return List.of(shulkerBox(hitboxPosition(anchor, 8, 0, 8), 1.0, 0, null, true, null));
        }
        if (blockId.equals("circular_rack")) {
            return List.of(interactionBox(aggregate, anchor, null));
        }
        if (blockId.equals("tilted_rack") || blockId.equals("holder")) {
            List<JsonObject> result = new ArrayList<>();
            result.add(interactionBox(aggregate, anchor, null));
            result.addAll(physicalBox(aggregate, anchor, 4));
            return result;
        }
        if (SMALL_FURNITURE.contains(blockId)) {
            List<JsonObject> result = new ArrayList<>();
            result.add(interactionBox(aggregate, anchor, null));
            for (Box box : boxes) result.addAll(physicalBox(box, anchor, 4));
            return result;
        }
        return List.of(shulkerBox(hitboxPosition(anchor, 8, 0, 8), 1.0, 0, null, true, null));
    }
}
