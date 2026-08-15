package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.assets;

import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.data.MigrationDataIO;
import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.furniture.FurnitureBoxes;
import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.image.LegacyImageAndPlacedDrinkStage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native port of the legacy static asset generators (tools/migrate_legacy.py):
 * create_barrel_models, create_pressing_fluid_models, create_barrel_fluid_models,
 * create_bar_stool_body_models, create_shaker_models, create_molotov_charging_model
 * and create_tintable_sofa_models. Chalkboard/pendant/font/hud/worldgen remain.
 */
public final class AssetMigrationStage {
    public static final String NAMESPACE = "kaleidoscope_tavern";
    private static final Set<String> PRESS_FLUIDS = Set.of(
            "glow_berries_juice", "gold_grape_juice", "grape_juice", "green_grape_juice",
            "ice_grape_juice", "sweet_berries_juice");
    private static final Set<String> BARREL_FLUIDS = Set.of(
            "glow_berries_juice", "gold_grape_juice", "grape_juice", "green_grape_juice",
            "ice_grape_juice", "sweet_berries_juice", "water", "lava");
    private static final List<String> BAR_STOOL_COLORS = List.of(
            "black", "blue", "brown", "cyan", "gray", "green", "light_blue",
            "light_gray", "lime", "magenta", "orange", "pink", "purple", "red",
            "white", "yellow");
    private static final List<String> SOFA_CONNECTIONS = List.of(
            "single", "left", "left_corner", "middle", "right", "right_corner");

    private final Path projectRoot;
    private final Path outputRoot;

    public AssetMigrationStage(Path projectRoot, Path outputRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
    }

    public void run() throws IOException {
        createBarrelModels();
        createPressingFluidModels();
        createBarrelFluidModels();
        createBarStoolBodyModels();
        createShakerModels();
        createMolotovChargingModel();
        createTintableSofaModels();
        createChalkboardModels();
        createPendantLampModels();
        writeWorldgenFeatures();
        createCustomEffectFont();
        createCustomEffectHudAssets();
        createShakerHudAssets();
    }

    private static final List<String> CUSTOM_EFFECT_ICON_IDS = List.of(
            "slightly_tipsy", "high_heels", "grass_stealth", "vision",
            "bloody_mary", "ardent_heat", "long_reach", "tomb_raider",
            "xp_drain", "upside_down", "zenith", "shriek_attack");
    private static final int[] HUD_OFFSET_POWERS = {1, 2, 4, 8, 16, 32, 64, 128, 256};
    private static final int HUD_BG_ROW1 = 0xE320;
    private static final int HUD_BG_ROW2 = 0xE321;
    private static final int HUD_ICON_ROW1 = 0xE330;
    private static final int HUD_ICON_ROW2 = 0xE340;

    public void createCustomEffectFont() throws IOException {
        Path textureRoot = projectRoot.resolve(
                "src/main/resources/assets/" + NAMESPACE + "/textures/mob_effect");
        JsonArray providers = new JsonArray();
        int index = 0;
        for (String effectId : CUSTOM_EFFECT_ICON_IDS) {
            Path texture = textureRoot.resolve(effectId + ".png");
            if (!Files.isRegularFile(texture)) {
                throw new AssertionError("Missing custom effect icon: " + texture);
            }
            JsonObject provider = new JsonObject();
            provider.addProperty("type", "bitmap");
            provider.addProperty("file", NAMESPACE + ":mob_effect/" + effectId + ".png");
            provider.addProperty("ascent", 8);
            provider.addProperty("height", 9);
            JsonArray chars = new JsonArray();
            chars.add(new String(Character.toChars(0xE100 + index)));
            provider.add("chars", chars);
            providers.add(provider);
            index++;
        }
        JsonObject document = new JsonObject();
        document.add("providers", providers);
        MigrationDataIO.writeJson(outputRoot.resolve("font/custom_effects.json"), document);
    }

    public void createCustomEffectHudAssets() throws IOException {
        Path textureRoot = projectRoot.resolve(
                "src/main/resources/assets/" + NAMESPACE + "/textures/mob_effect");
        Path paddedRoot = outputRoot.resolve("textures/font/hud_effect");
        Files.createDirectories(paddedRoot);
        for (String effectId : CUSTOM_EFFECT_ICON_IDS) {
            java.awt.image.BufferedImage icon = readImage(textureRoot.resolve(effectId + ".png"));
            if (icon.getWidth() != 18 || icon.getHeight() != 18) {
                throw new AssertionError(effectId + ": expected an 18x18 icon, got "
                        + icon.getWidth() + "x" + icon.getHeight());
            }
            for (int x : new int[] {0, 17}) {
                boolean allTransparent = true;
                for (int y = 0; y < 18; y++) {
                    if (((icon.getRGB(x, y) >>> 24) & 0xFF) != 0) { allTransparent = false; break; }
                }
                if (allTransparent) icon.setRGB(x, 9, 0x02FFFFFF);
            }
            writePng(paddedRoot.resolve(effectId + ".png"), icon);
        }
        Path spriteRoot = outputRoot.resolve("../minecraft/textures/gui/sprites/boss_bar").normalize();
        Files.createDirectories(spriteRoot);
        java.awt.image.BufferedImage transparentBar =
                new java.awt.image.BufferedImage(182, 5, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (String sprite : List.of("yellow_background", "yellow_progress")) {
            writePng(spriteRoot.resolve(sprite + ".png"), transparentBar);
        }
        JsonObject advances = new JsonObject();
        int powerIndex = 0;
        for (int power : HUD_OFFSET_POWERS) {
            advances.addProperty(new String(Character.toChars(0xE300 + powerIndex)), power);
            advances.addProperty(new String(Character.toChars(0xE310 + powerIndex)), -power);
            powerIndex++;
        }
        JsonArray providers = new JsonArray();
        providers.add(obj("type", "space", "advances", advances));
        for (int[] row : new int[][] {
                {HUD_BG_ROW1, 9, HUD_ICON_ROW1, 6},
                {HUD_BG_ROW2, -16, HUD_ICON_ROW2, -19}}) {
            int bgChar = row[0];
            int bgAscent = row[1];
            int iconBase = row[2];
            int iconAscent = row[3];
            JsonObject bg = new JsonObject();
            bg.addProperty("type", "bitmap");
            bg.addProperty("file", "minecraft:gui/sprites/hud/effect_background.png");
            bg.addProperty("ascent", bgAscent);
            bg.addProperty("height", 24);
            JsonArray bgChars = new JsonArray();
            bgChars.add(new String(Character.toChars(bgChar)));
            bg.add("chars", bgChars);
            providers.add(bg);
            int iconIndex = 0;
            for (String effectId : CUSTOM_EFFECT_ICON_IDS) {
                JsonObject icon = new JsonObject();
                icon.addProperty("type", "bitmap");
                icon.addProperty("file", NAMESPACE + ":font/hud_effect/" + effectId + ".png");
                icon.addProperty("ascent", iconAscent);
                icon.addProperty("height", 18);
                JsonArray iconChars = new JsonArray();
                iconChars.add(new String(Character.toChars(iconBase + iconIndex)));
                icon.add("chars", iconChars);
                providers.add(icon);
                iconIndex++;
            }
        }
        JsonObject document = new JsonObject();
        document.add("providers", providers);
        MigrationDataIO.writeJson(outputRoot.resolve("font/custom_effects_hud.json"), document);
    }

    public void createShakerHudAssets() throws IOException {
        Path sourcePath = projectRoot.resolve(
                "src/main/resources/assets/" + NAMESPACE + "/textures/gui/shaker.png");
        java.awt.image.BufferedImage overlay = readImage(sourcePath);
        if (overlay.getWidth() != 256 || overlay.getHeight() != 256) {
            throw new AssertionError("shaker overlay: expected a 256x256 texture, got "
                    + overlay.getWidth() + "x" + overlay.getHeight());
        }
        Path textureRoot = outputRoot.resolve("textures/font/shaker");
        Files.createDirectories(textureRoot);
        java.awt.image.BufferedImage bar =
                new java.awt.image.BufferedImage(181, 18, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        bar.getGraphics().drawImage(overlay.getSubimage(0, 0, 181, 17), 0, 0, null);
        writePng(textureRoot.resolve("bar.png"), bar);
        java.awt.image.BufferedImage pointer =
                new java.awt.image.BufferedImage(11, 14, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        pointer.getGraphics().drawImage(overlay.getSubimage(181, 0, 11, 13), 0, 0, null);
        writePng(textureRoot.resolve("pointer.png"), pointer);
        JsonObject advances = new JsonObject();
        int powerIndex = 0;
        for (int power : HUD_OFFSET_POWERS) {
            advances.addProperty(new String(Character.toChars(0xE410 + powerIndex)), power / 2.0);
            advances.addProperty(new String(Character.toChars(0xE420 + powerIndex)), -power / 2.0);
            powerIndex++;
        }
        JsonArray providers = new JsonArray();
        providers.add(obj("type", "space", "advances", advances));
        providers.add(bitmapProvider(NAMESPACE + ":font/shaker/bar.png", 0, 9, 0xE400));
        providers.add(bitmapProvider(NAMESPACE + ":font/shaker/pointer.png", 3, 7, 0xE401));
        providers.add(bitmapProvider(NAMESPACE + ":gui/rhombus.png", 3, 8, 0xE402));
        JsonObject document = new JsonObject();
        document.add("providers", providers);
        MigrationDataIO.writeJson(outputRoot.resolve("font/shaker_hud.json"), document);
    }

    private static JsonObject bitmapProvider(String file, int ascent, int height, int charCode) {
        JsonObject provider = new JsonObject();
        provider.addProperty("type", "bitmap");
        provider.addProperty("file", file);
        provider.addProperty("ascent", ascent);
        provider.addProperty("height", height);
        JsonArray chars = new JsonArray();
        chars.add(new String(Character.toChars(charCode)));
        provider.add("chars", chars);
        return provider;
    }

    private static java.awt.image.BufferedImage readImage(Path path) throws IOException {
        return javax.imageio.ImageIO.read(path.toFile());
    }

    private static void writePng(Path path, java.awt.image.BufferedImage image) throws IOException {
        Files.createDirectories(path.getParent());
        javax.imageio.ImageIO.write(image, "png", path.toFile());
    }

    /** worldgen.json: CraftEngine configured/placed features for wild grapevines. */
    public void writeWorldgenFeatures() throws IOException {
        JsonObject configured = new JsonObject();
        configured.addProperty("type", "minecraft:block_column");
        JsonObject config = new JsonObject();
        config.addProperty("direction", "down");
        JsonObject allowed = new JsonObject();
        allowed.addProperty("type", "minecraft:matching_blocks");
        allowed.addProperty("blocks", "minecraft:air");
        config.add("allowed_placement", allowed);
        config.addProperty("prioritize_tip", true);
        JsonArray layers = new JsonArray();
        JsonObject layer0 = new JsonObject();
        JsonObject height0 = new JsonObject();
        height0.addProperty("type", "minecraft:uniform");
        height0.addProperty("min_inclusive", 0);
        height0.addProperty("max_inclusive", 6);
        layer0.add("height", height0);
        JsonObject provider0 = new JsonObject();
        provider0.addProperty("type", "minecraft:simple_state_provider");
        JsonObject state0 = new JsonObject();
        state0.addProperty("Name", NAMESPACE + ":wild_grapevine_plant");
        provider0.add("state", state0);
        layer0.add("provider", provider0);
        layers.add(layer0);
        JsonObject layer1 = new JsonObject();
        layer1.addProperty("height", 1);
        JsonObject provider1 = new JsonObject();
        provider1.addProperty("type", "minecraft:simple_state_provider");
        JsonObject state1 = new JsonObject();
        state1.addProperty("Name", NAMESPACE + ":wild_grapevine");
        provider1.add("state", state1);
        layer1.add("provider", provider1);
        layers.add(layer1);
        config.add("layers", layers);
        configured.add("config", config);
        JsonObject placed = new JsonObject();
        JsonArray dimensions = new JsonArray();
        dimensions.add("minecraft:overworld");
        placed.add("dimensions", dimensions);
        placed.addProperty("feature", NAMESPACE + ":wild_grapevine_chain");
        JsonArray placement = new JsonArray();
        placement.add(obj("type", "minecraft:rarity_filter", "chance", 12));
        JsonObject count = new JsonObject();
        count.addProperty("type", "minecraft:uniform");
        count.addProperty("min_inclusive", 1);
        count.addProperty("max_inclusive", 5);
        placement.add(obj("type", "minecraft:count", "count", count));
        placement.add(obj("type", "minecraft:in_square"));
        placement.add(obj("type", "minecraft:heightmap", "heightmap", "WORLD_SURFACE"));
        JsonObject scan = new JsonObject();
        scan.addProperty("type", "minecraft:environment_scan");
        scan.addProperty("direction_of_search", "down");
        scan.addProperty("max_steps", 32);
        JsonObject target = new JsonObject();
        target.addProperty("type", "minecraft:all_of");
        JsonArray targetPredicates = new JsonArray();
        targetPredicates.add(obj("type", "minecraft:matching_blocks", "blocks", "minecraft:air"));
        JsonObject offset = new JsonObject();
        offset.addProperty("type", "minecraft:matching_blocks");
        JsonArray offsetArray = new JsonArray();
        offsetArray.add(0); offsetArray.add(1); offsetArray.add(0);
        offset.add("offset", offsetArray);
        JsonArray leaves = new JsonArray();
        leaves.add("minecraft:oak_leaves");
        leaves.add("minecraft:birch_leaves");
        offset.add("blocks", leaves);
        targetPredicates.add(offset);
        target.add("predicates", targetPredicates);
        scan.add("target_condition", target);
        JsonObject allowedSearch = new JsonObject();
        allowedSearch.addProperty("type", "minecraft:any_of");
        JsonArray searchPredicates = new JsonArray();
        searchPredicates.add(obj("type", "minecraft:matching_blocks", "blocks", "minecraft:air"));
        searchPredicates.add(obj("type", "minecraft:matching_block_tag", "tag", "minecraft:leaves"));
        allowedSearch.add("predicates", searchPredicates);
        scan.add("allowed_search_condition", allowedSearch);
        placement.add(scan);
        JsonObject filter = new JsonObject();
        filter.addProperty("type", "minecraft:block_predicate_filter");
        JsonObject predicate = new JsonObject();
        predicate.addProperty("type", "minecraft:all_of");
        JsonArray filterPredicates = new JsonArray();
        filterPredicates.add(obj("type", "minecraft:matching_blocks", "blocks", "minecraft:air"));
        JsonObject downOffset = new JsonObject();
        downOffset.addProperty("type", "minecraft:matching_blocks");
        JsonArray downArray = new JsonArray();
        downArray.add(0); downArray.add(-1); downArray.add(0);
        downOffset.add("offset", downArray);
        downOffset.addProperty("blocks", "minecraft:air");
        filterPredicates.add(downOffset);
        predicate.add("predicates", filterPredicates);
        filter.add("predicate", predicate);
        placement.add(filter);
        placed.add("placement", placement);
        JsonObject document = new JsonObject();
        JsonObject configuredFeatures = new JsonObject();
        configuredFeatures.add(NAMESPACE + ":wild_grapevine_chain", configured);
        document.add("configured_features", configuredFeatures);
        JsonObject placedFeatures = new JsonObject();
        placedFeatures.add(NAMESPACE + ":wild_grapevine", placed);
        document.add("placed_features", placedFeatures);
        Path configDir = outputRoot.resolve("../../../configuration").normalize();
        MigrationDataIO.writeJson(configDir.resolve("worldgen.json"), document);
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

    private static final List<String> PENDANT_LAMPS = List.of(
            "bell_pendant_lamp", "yellow_pendant_lamp", "blue_pendant_lamp");

    public void createChalkboardModels() throws IOException {
        Path modelRoot = modelsRoot().resolve("furniture");
        String textureRoot = NAMESPACE + ":entity/deco";
        JsonObject small = chalkboardModel(16, 64, textureRoot, 0, 2, 15, 16, 30, 16);
        MigrationDataIO.writeJson(modelRoot.resolve("chalkboard_small.json"), small);
        JsonObject large = chalkboardModel(48, 128, textureRoot, -16, 2, 15, 32, 30, 16);
        MigrationDataIO.writeJson(modelRoot.resolve("chalkboard_large.json"), large);
    }

    private static JsonObject chalkboardModel(int width, int textureWidth, String textureRoot,
                                              double fromX, double fromY, double fromZ,
                                              double toX, double toY, double toZ) {
        JsonObject model = new JsonObject();
        model.addProperty("ambientocclusion", false);
        JsonObject textures = new JsonObject();
        textures.addProperty("board", textureRoot
                + (width == 16 ? "/small_chalkboard" : "/large_chalkboard"));
        textures.addProperty("particle", NAMESPACE + ":block/deco/chalkboard_particle");
        model.add("textures", textures);
        JsonObject element = new JsonObject();
        JsonArray from = new JsonArray();
        addNumber(from, fromX); addNumber(from, fromY); addNumber(from, fromZ);
        element.add("from", from);
        JsonArray to = new JsonArray();
        addNumber(to, toX); addNumber(to, toY); addNumber(to, toZ);
        element.add("to", to);
        element.add("faces", cubeFaces(width, textureWidth));
        JsonArray elements = new JsonArray();
        elements.add(element);
        model.add("elements", elements);
        return model;
    }

    private static JsonObject cubeFaces(int width, int textureWidth) {
        int depth = 1;
        int height = 28;
        int frontLeft = depth;
        int frontRight = frontLeft + width;
        int eastRight = frontRight + depth;
        int backRight = eastRight + width;
        JsonObject faces = new JsonObject();
        faces.add("down", boardFace(uv(frontLeft, 0, frontRight, depth, textureWidth)));
        faces.add("up", boardFace(uv(frontRight, 0, frontRight + width, depth, textureWidth)));
        faces.add("west", boardFace(uv(0, depth, depth, depth + height, textureWidth)));
        faces.add("north", boardFace(uv(frontLeft, depth, frontRight, depth + height, textureWidth)));
        faces.add("east", boardFace(uv(frontRight, depth, eastRight, depth + height, textureWidth)));
        faces.add("south", boardFace(uv(eastRight, depth, backRight, depth + height, textureWidth)));
        return faces;
    }

    private static void addNumber(JsonArray array, double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 2_147_483_647.0) {
            array.add((int) value);
        } else {
            array.add(value);
        }
    }

    private static JsonObject boardFace(JsonArray uv) {
        JsonObject face = new JsonObject();
        face.add("uv", uv);
        face.addProperty("texture", "#board");
        return face;
    }

    private static JsonArray uv(int left, int top, int right, int bottom, int textureWidth) {
        JsonArray array = new JsonArray();
        array.add(left * 16.0 / textureWidth);
        array.add(top / 4.0);
        array.add(right * 16.0 / textureWidth);
        array.add(bottom / 4.0);
        return array;
    }

    public void createPendantLampModels() throws IOException {
        Path sourceRoot = projectRoot.resolve(
                "src/main/resources/assets/" + NAMESPACE + "/models/block/deco");
        Path targetRoot = outputRoot.resolve("models/block/deco");
        LegacyImageAndPlacedDrinkStage imageStage =
                new LegacyImageAndPlacedDrinkStage(projectRoot, outputRoot);
        for (String blockId : PENDANT_LAMPS) {
            for (String half : List.of("top", "bottom")) {
                String owner = blockId + "/" + half;
                JsonObject model = readJson(sourceRoot.resolve(blockId).resolve(half + ".json"));
                imageStage.migrateTranslucentModel(model, owner);
                if (!model.has("textures")) throw new AssertionError(owner + ": missing model textures");
                JsonObject textures = model.getAsJsonObject("textures");
                String particle = textures.has("particle") ? textures.get("particle").getAsString() : null;
                String normalized = LEGACY_MODEL_TEXTURE_RENAMES.getOrDefault(particle, particle);
                if (normalized == null || !normalized.equals("minecraft:block/iron_chain")) {
                    throw new AssertionError(owner + ": unexpected particle texture " + particle);
                }
                textures.addProperty("particle", normalized);
                repairPendantLampRodUvs(model, owner);
                MigrationDataIO.writeJson(targetRoot.resolve(blockId).resolve(half + ".json"), model);
            }
        }
    }

    private static final Map<String, String> LEGACY_MODEL_TEXTURE_RENAMES = Map.of(
            "block/chain", "minecraft:block/iron_chain",
            "minecraft:block/chain", "minecraft:block/iron_chain");

    private static void repairPendantLampRodUvs(JsonObject model, String owner) {
        int repairedFaces = 0;
        int rodCount = 0;
        if (!model.has("elements")) return;
        for (JsonElement rawElement : model.getAsJsonArray("elements")) {
            JsonObject element = rawElement.getAsJsonObject();
            if (!element.has("from") || !element.has("to")) continue;
            JsonArray start = element.getAsJsonArray("from");
            JsonArray end = element.getAsJsonArray("to");
            if (start.size() != 3 || end.size() != 3) continue;
            double[] dimensions = {end.get(0).getAsDouble() - start.get(0).getAsDouble(),
                    end.get(1).getAsDouble() - start.get(1).getAsDouble(),
                    end.get(2).getAsDouble() - start.get(2).getAsDouble()};
            if (dimensions[0] != 1 || dimensions[2] != 1
                    || (dimensions[1] != 10 && dimensions[1] != 16)) continue;
            rodCount++;
            if (!element.has("faces")) throw new AssertionError(owner + ": pendant rod has no faces");
            JsonObject faces = element.getAsJsonObject("faces");
            for (String direction : List.of("east", "west", "up")) {
                JsonElement rawFace = faces.get(direction);
                if (rawFace == null || !rawFace.isJsonObject()
                        || !rawFace.getAsJsonObject().has("uv")) {
                    throw new AssertionError(owner + ": unexpected " + direction + " rod UV");
                }
                JsonArray uv = rawFace.getAsJsonObject().getAsJsonArray("uv");
                if (uv.size() != 4 || uv.get(1).getAsDouble() != 9 || uv.get(3).getAsDouble() != 9) {
                    throw new AssertionError(owner + ": unexpected " + direction + " rod UV " + uv);
                }
                uv.set(3, new com.google.gson.JsonPrimitive(9.5));
                repairedFaces++;
            }
        }
        if (rodCount != 2 || repairedFaces != 6) {
            throw new AssertionError(owner + ": expected two pendant rods and six repaired faces, "
                    + "found " + rodCount + " rods and " + repairedFaces + " faces");
        }
    }

    private Path modelsRoot() {
        return outputRoot.resolve("models");
    }

    public void createBarrelModels() throws IOException {
        double[][] specs = {
                {6, 7, -10, 4, 8, 4, 28, 136},
                {-26, 7, -10, 4, 8, 4, 28, 136},
                {-22, 7, -8, 28, 4, 2, 174, 118},
                {-22, 7, 22, 28, 4, 2, 174, 118},
                {6, 7, 22, 4, 8, 4, 28, 136},
                {-26, 7, 22, 4, 8, 4, 28, 136},
                {-28, -33, -15, 40, 40, 48, 0, 0},
                {-16, -33, 1, 16, 21, 0, 0, 160},
                {0, -33, 1, 0, 24, 16, 0, 144},
                {-16, -33, 1, 0, 21, 16, 0, 144},
                {-16, -12, 1, 16, 0, 16, 32, 160},
                {-16, -33, 17, 16, 21, 0, 0, 160},
        };
        JsonArray bodyElements = new JsonArray();
        for (double[] spec : specs) {
            bodyElements.add(FurnitureBoxes.solidifyPlanes(
                    FurnitureBoxes.entityBarrelBox(spec[0], spec[1], spec[2], spec[3],
                            spec[4], spec[5], spec[6], spec[7])));
        }
        JsonObject closedLid = FurnitureBoxes.entityBarrelBox(-16, -33, 1, 16, 2, 16, 102, 113);
        JsonObject base = new JsonObject();
        base.addProperty("ambientocclusion", false);
        JsonObject textures = new JsonObject();
        textures.addProperty("barrel", NAMESPACE + ":entity/brew/barrel");
        textures.addProperty("particle", "minecraft:block/barrel_side");
        base.add("textures", textures);
        // BarrelModel.open_r1 has its own -35 degree child rotation under the
// 75 degree open group, so its final source angle is about -40 degrees.
// CE ground-furniture zero yaw is horizontally opposite to the
// archived renderer's unrotated NORTH basis. The barrel body and lid
// hide that difference through symmetry, but this one-sided support
// only moves laterally from x=1 to x=15; the authored z direction and rotation pivot stay unchanged.
JsonObject supportStrip = new JsonObject();
supportStrip.add("from", numbers(new double[] {15, 28.215627824, -1.72625203}));
supportStrip.add("to", numbers(new double[] {15, 30.215627824, 18.27374797}));
supportStrip.add("faces", FurnitureBoxes.entityUvFaces(106, 114, 0, 2, 20));
JsonObject supportRotation = new JsonObject();
supportRotation.add("origin", numbers(new double[] {15, 28.215627824, -1.72625203}));
supportRotation.addProperty("axis", "x");
supportRotation.addProperty("angle", -39.998183678);
supportRotation.addProperty("rescale", false);
supportStrip.add("rotation", supportRotation);

JsonObject bodyModel = base.deepCopy();
JsonArray openBodyElements = bodyElements.deepCopy();
openBodyElements.add(FurnitureBoxes.solidifyPlanes(supportStrip));
bodyModel.add("elements", openBodyElements);
MigrationDataIO.writeJson(modelsRoot().resolve("furniture/barrel_body.json"), bodyModel);
JsonObject closed = base.deepCopy();
JsonArray closedElements = bodyElements.deepCopy();
closedElements.add(closedLid);
closed.add("elements", closedElements);
MigrationDataIO.writeJson(modelsRoot().resolve("furniture/barrel_closed.json"), closed);
JsonObject openLid = new JsonObject();
JsonArray from = new JsonArray();
from.add(0); from.add(8); from.add(-8);
openLid.add("from", from);
JsonArray to = new JsonArray();
to.add(16); to.add(10); to.add(8);
openLid.add("to", to);
openLid.add("faces", closedLid.get("faces").deepCopy());
JsonObject openModel = base.deepCopy();
JsonArray openElements = new JsonArray();
openElements.add(openLid);
openModel.add("elements", openElements);
MigrationDataIO.writeJson(modelsRoot().resolve("furniture/barrel_open_lid.json"), openModel);
    }

    public void createPressingFluidModels() throws IOException {
        Path root = modelsRoot().resolve("furniture/pressing_fluid");
        for (String fluid : sorted(PRESS_FLUIDS)) {
            String texture = NAMESPACE + ":block/" + fluid + "_still";
            JsonObject face = new JsonObject();
            JsonArray uv = new JsonArray();
            uv.add(0); uv.add(0); uv.add(12); uv.add(12);
            face.add("uv", uv);
            face.addProperty("texture", "#fluid");
            JsonObject upFace = face.deepCopy();
            JsonObject downFace = face.deepCopy();
            JsonObject element = new JsonObject();
            JsonArray from = new JsonArray();
            from.add(2); from.add(7.99); from.add(2);
            element.add("from", from);
            JsonArray to = new JsonArray();
            to.add(14); to.add(8.01); to.add(14);
            element.add("to", to);
            JsonObject faces = new JsonObject();
            faces.add("up", upFace);
            faces.add("down", downFace);
            element.add("faces", faces);
            JsonObject model = new JsonObject();
            model.addProperty("ambientocclusion", false);
            JsonObject textures = new JsonObject();
            textures.add("fluid", LegacyImageAndPlacedDrinkStage.translucentTexture(texture));
            textures.addProperty("particle", texture);
            model.add("textures", textures);
            JsonArray elements = new JsonArray();
            elements.add(element);
            model.add("elements", elements);
            MigrationDataIO.writeJson(root.resolve(fluid + ".json"), model);
        }
    }

    public void createBarrelFluidModels() throws IOException {
        Path root = modelsRoot().resolve("furniture/barrel_fluid");
        for (String fluid : sorted(BARREL_FLUIDS)) {
            String namespace = (fluid.equals("water") || fluid.equals("lava"))
                    ? "minecraft" : NAMESPACE;
            String texture = namespace + ":block/" + fluid + "_still";
            JsonObject face = new JsonObject();
            JsonArray uv = new JsonArray();
            uv.add(0); uv.add(0); uv.add(16); uv.add(16);
            face.add("uv", uv);
            face.addProperty("texture", "#fluid");
            if (fluid.equals("water")) face.addProperty("tintindex", 0);
            JsonObject element = new JsonObject();
            JsonArray from = new JsonArray();
            from.add(0); from.add(7.99); from.add(0);
            element.add("from", from);
            JsonArray to = new JsonArray();
            to.add(16); to.add(8.01); to.add(16);
            element.add("to", to);
            JsonObject faces = new JsonObject();
            faces.add("up", face);
            faces.add("down", face.deepCopy());
            element.add("faces", faces);
            JsonObject model = new JsonObject();
            model.addProperty("ambientocclusion", false);
            JsonObject textures = new JsonObject();
            textures.add("fluid", LegacyImageAndPlacedDrinkStage.translucentTexture(texture));
            textures.addProperty("particle", texture);
            model.add("textures", textures);
            JsonArray elements = new JsonArray();
            elements.add(element);
            model.add("elements", elements);
            MigrationDataIO.writeJson(root.resolve(fluid + ".json"), model);
        }
    }

    public void createBarStoolBodyModels() throws IOException {
        JsonObject source = readJson(projectRoot.resolve(
                "src/main/resources/assets/" + NAMESPACE + "/models/item/bar_stool_base.json"));
        if (!source.has("elements") || source.getAsJsonArray("elements").size() != 7) {
            throw new AssertionError("bar_stool_base must retain its 3 pedestal + 4 body cuboids");
        }
        JsonObject body = new JsonObject();
        for (var entry : source.entrySet()) {
            if (!entry.getKey().equals("display") && !entry.getKey().equals("gui_light")) {
                body.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        JsonArray elements = new JsonArray();
        for (int i = 3; i < 7; i++) elements.add(source.getAsJsonArray("elements").get(i).deepCopy());
        body.add("elements", elements);
        body.remove("render_type");
        MigrationDataIO.writeJson(modelsRoot().resolve("furniture/bar_stool_body_base.json"), body);
        Path root = modelsRoot().resolve("furniture/bar_stool_body");
        for (String color : BAR_STOOL_COLORS) {
            JsonObject child = new JsonObject();
            child.addProperty("parent", NAMESPACE + ":furniture/bar_stool_body_base");
            JsonObject textures = new JsonObject();
            textures.addProperty("particle", "minecraft:block/" + color + "_wool");
            textures.add("texture", LegacyImageAndPlacedDrinkStage.translucentTexture(
                    NAMESPACE + ":block/deco/bar_stool/" + color));
            child.add("textures", textures);
            MigrationDataIO.writeJson(root.resolve(color + ".json"), child);
        }
    }

    public void createShakerModels() throws IOException {
        JsonObject source = readJson(projectRoot.resolve(
                "src/main/resources/assets/" + NAMESPACE + "/models/item/shaker_3d.json"));
        if (!source.has("elements") || source.getAsJsonArray("elements").size() != 5) {
            throw new AssertionError("shaker_3d must retain its 2 body + 3 lid cuboids");
        }
        JsonObject flat = new JsonObject();
        flat.addProperty("parent", "minecraft:item/generated");
        JsonObject flatTextures = new JsonObject();
        flatTextures.addProperty("layer0", NAMESPACE + ":item/shaker");
        flat.add("textures", flatTextures);
        MigrationDataIO.writeJson(modelsRoot().resolve("item/shaker.json"), flat);
        JsonObject threeD = new JsonObject();
        for (var entry : source.entrySet()) {
            if (!entry.getKey().equals("groups")) threeD.add(entry.getKey(), entry.getValue().deepCopy());
        }
        MigrationDataIO.writeJson(modelsRoot().resolve("item/shaker_3d.json"), threeD);
        JsonObject base = new JsonObject();
        for (var entry : source.entrySet()) {
            if (!entry.getKey().equals("display") && !entry.getKey().equals("groups")) {
                base.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        JsonArray baseElements = new JsonArray();
        baseElements.add(source.getAsJsonArray("elements").get(0).deepCopy());
        baseElements.add(source.getAsJsonArray("elements").get(1).deepCopy());
        base.add("elements", baseElements);
        MigrationDataIO.writeJson(modelsRoot().resolve("furniture/shaker_base.json"), base);
        double pivotDelta = 12.16667 - 8.0;
        JsonArray lidElements = new JsonArray();
        for (int i = 2; i < 5; i++) lidElements.add(source.getAsJsonArray("elements").get(i).deepCopy());
        for (JsonElement rawElement : lidElements) {
            JsonObject element = rawElement.getAsJsonObject();
            JsonArray from = element.getAsJsonArray("from");
            from.set(1, new com.google.gson.JsonPrimitive(from.get(1).getAsDouble() - pivotDelta));
            JsonArray to = element.getAsJsonArray("to");
            to.set(1, new com.google.gson.JsonPrimitive(to.get(1).getAsDouble() - pivotDelta));
            if (element.has("rotation")) {
                JsonObject rotation = element.getAsJsonObject("rotation");
                if (rotation.has("origin") && rotation.get("origin").isJsonArray()) {
                    JsonArray origin = rotation.getAsJsonArray("origin");
                    origin.set(1, new com.google.gson.JsonPrimitive(origin.get(1).getAsDouble() - pivotDelta));
                }
            }
        }
        JsonObject lid = new JsonObject();
        for (var entry : source.entrySet()) {
            if (!entry.getKey().equals("display") && !entry.getKey().equals("groups")) {
                lid.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        lid.add("elements", lidElements);
        MigrationDataIO.writeJson(modelsRoot().resolve("furniture/shaker_lid.json"), lid);
    }

    public void createMolotovChargingModel() throws IOException {
        JsonObject model = new JsonObject();
        model.addProperty("parent", NAMESPACE + ":item/molotov");
        JsonObject display = new JsonObject();
        addDisplay(display, "firstperson_righthand", new double[] {-40, 0, -15},
                new double[] {1.13, 5.2, 1.13}, new double[] {0.68, 0.68, 0.68});
        addDisplay(display, "firstperson_lefthand", new double[] {-40, 0, 15},
                new double[] {-1.13, 5.2, 1.13}, new double[] {0.68, 0.68, 0.68});
        addDisplay(display, "thirdperson_righthand", new double[] {-80, 0, 0},
                new double[] {0, 5.5, 2}, new double[] {0.55, 0.55, 0.55});
        addDisplay(display, "thirdperson_lefthand", new double[] {-80, 0, 0},
                new double[] {0, 5.5, 2}, new double[] {0.55, 0.55, 0.55});
        model.add("display", display);
        MigrationDataIO.writeJson(modelsRoot().resolve("item/molotov_charging.json"), model);
    }

    private static void addDisplay(JsonObject display, String name, double[] rotation,
                                   double[] translation, double[] scale) {
        JsonObject entry = new JsonObject();
        entry.add("rotation", numbers(rotation));
        entry.add("translation", numbers(translation));
        entry.add("scale", numbers(scale));
        display.add(name, entry);
    }

    private static JsonArray numbers(double[] values) {
        JsonArray array = new JsonArray();
        for (double value : values) {
            if (value == Math.rint(value) && Math.abs(value) < 2_147_483_647.0) {
                array.add((int) value);
            } else {
                array.add(value);
            }
        }
        return array;
    }

    public void createTintableSofaModels() throws IOException {
        Path sourceRoot = projectRoot.resolve(
                "src/main/resources/assets/" + NAMESPACE + "/models/block/deco/sofa/base");
        Path targetRoot = outputRoot.resolve("models/block/deco/sofa/tint");
        Path baseRoot = targetRoot.resolve("base");
        for (String connection : SOFA_CONNECTIONS) {
            JsonObject model = readJson(sourceRoot.resolve(connection + ".json"));
            model.remove("render_type");
            if (model.has("elements")) {
                for (JsonElement rawElement : model.getAsJsonArray("elements")) {
                    JsonObject element = rawElement.getAsJsonObject();
                    if (!element.has("faces")) continue;
                    for (JsonElement rawFace : element.getAsJsonObject("faces").asMap().values()) {
                        JsonObject face = rawFace.getAsJsonObject();
                        if (face.has("texture") && face.get("texture").getAsString().equals("#texture")) {
                            face.addProperty("tintindex", 0);
                        }
                    }
                }
            }
            MigrationDataIO.writeJson(baseRoot.resolve(connection + ".json"), model);
            JsonObject child = new JsonObject();
            child.addProperty("parent", NAMESPACE + ":block/deco/sofa/tint/base/" + connection);
            JsonObject textures = new JsonObject();
            textures.addProperty("particle", "minecraft:block/white_wool");
            textures.addProperty("texture", NAMESPACE + ":block/deco/sofa/white");
            child.add("textures", textures);
            MigrationDataIO.writeJson(targetRoot.resolve(connection + ".json"), child);
        }
    }

    private static List<String> sorted(Set<String> values) {
        List<String> result = new ArrayList<>(values);
        result.sort(String::compareTo);
        return result;
    }

    private static JsonObject readJson(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
