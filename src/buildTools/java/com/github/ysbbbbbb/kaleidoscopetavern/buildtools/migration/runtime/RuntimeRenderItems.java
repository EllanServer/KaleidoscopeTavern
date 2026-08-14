package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.runtime;

import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.block.BlockStateVariants;
import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.image.LegacyImageAndPlacedDrinkStage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Native port of add_runtime_render_items (tools/migrate_legacy.py 4141-4204):
 * stable render ids for storage bottles, pressing/barrel fluids, bar stools and
 * shaker parts used by Paper-side block-entity visual emulation.
 */
public final class RuntimeRenderItems {
    public static final String NAMESPACE = "kaleidoscope_tavern";
    private static final Set<String> COCKTAILS = Set.of(
            "empty_glassware", "signature_cocktail", "mystery_cocktail", "white_lady",
            "emerald", "brass_heart", "godfather", "grasshopper", "screwdriver",
            "mojito", "allium_garden", "depth_charge", "nether_special", "bloody_mary",
            "sculk_special");
    private static final Set<String> BOTTLE_AND_GLASS = Set.of(
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

    private final Path projectRoot;
    private final LegacyImageAndPlacedDrinkStage imageStage;
    private final Set<String> languageKeys;

    public RuntimeRenderItems(Path projectRoot, Path outputRoot) throws IOException {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.imageStage = new LegacyImageAndPlacedDrinkStage(this.projectRoot, outputRoot.toAbsolutePath().normalize());
        Path langFile = this.projectRoot.resolve(
                "src/main/resources/assets/" + NAMESPACE + "/lang/en_us.json");
        JsonObject lang = JsonParser.parseString(Files.readString(langFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
        this.languageKeys = new LinkedHashSet<>(lang.keySet());
    }

    public void add(JsonObject renderItems) throws IOException {
        List<String> storageItems = new ArrayList<>();
        for (String id : BOTTLE_AND_GLASS) {
            if (!COCKTAILS.contains(id) || id.equals("empty_glassware")) storageItems.add(id);
        }
        storageItems.sort(String::compareTo);
        for (String blockId : storageItems) {
            BlockStateVariants.Model model = BlockStateVariants.minByScore(
                    BlockStateVariants.read(projectRoot, blockId)).model();
            LegacyImageAndPlacedDrinkStage.ModelReference display = imageStage.migratePlacedDrinkModel(
                    blockId, new LegacyImageAndPlacedDrinkStage.ModelReference(
                            model.model(), model.x(), model.y(), model.z(), model.uvlock()));
            JsonObject definition = new JsonObject();
            definition.addProperty("material", "paper");
            JsonObject data = new JsonObject();
            data.addProperty("item_name", renderItemName(blockId));
            definition.add("data", data);
            JsonObject modelConfig = new JsonObject();
            modelConfig.addProperty("type", "minecraft:model");
            modelConfig.addProperty("path", display.resourceId());
            definition.add("model", modelConfig);
            definition.add("settings", internalSettings());
            if (blockId.equals("potion_bottle")) {
                JsonObject tint = new JsonObject();
                tint.addProperty("type", "minecraft:potion");
                tint.addProperty("default", -13083194);
                JsonArray tints = new JsonArray();
                tints.add(tint);
                modelConfig.add("tints", tints);
            }
            renderItems.add(NAMESPACE + ":_render/storage/" + blockId, definition);
        }
        for (String fluid : sorted(PRESS_FLUIDS)) {
            JsonObject definition = new JsonObject();
            definition.addProperty("material", "paper");
            JsonObject data = new JsonObject();
            data.addProperty("item_name", fluidRenderName(fluid));
            definition.add("data", data);
            JsonObject modelConfig = new JsonObject();
            modelConfig.addProperty("type", "minecraft:model");
            modelConfig.addProperty("path", NAMESPACE + ":furniture/pressing_fluid/" + fluid);
            definition.add("model", modelConfig);
            definition.add("settings", internalSettings());
            renderItems.add(NAMESPACE + ":_render/pressing_fluid/" + fluid, definition);
        }
        for (String fluid : sorted(BARREL_FLUIDS)) {
            JsonObject definition = new JsonObject();
            definition.addProperty("material", "paper");
            JsonObject data = new JsonObject();
            data.addProperty("item_name", fluidRenderName(fluid));
            definition.add("data", data);
            JsonObject modelConfig = new JsonObject();
            modelConfig.addProperty("type", "minecraft:model");
            modelConfig.addProperty("path", NAMESPACE + ":furniture/barrel_fluid/" + fluid);
            definition.add("model", modelConfig);
            definition.add("settings", internalSettings());
            if (fluid.equals("water")) {
                JsonObject tint = new JsonObject();
                tint.addProperty("type", "minecraft:constant");
                tint.addProperty("value", 0x3F76E4);
                JsonArray tints = new JsonArray();
                tints.add(tint);
                modelConfig.add("tints", tints);
            }
            renderItems.add(NAMESPACE + ":_render/barrel_fluid/" + fluid, definition);
        }
        for (String color : BAR_STOOL_COLORS) {
            JsonObject definition = new JsonObject();
            definition.addProperty("material", "paper");
            JsonObject data = new JsonObject();
            data.addProperty("item_name", renderItemName(color + "_bar_stool"));
            definition.add("data", data);
            JsonObject modelConfig = new JsonObject();
            modelConfig.addProperty("type", "minecraft:model");
            modelConfig.addProperty("path", NAMESPACE + ":furniture/bar_stool_body/" + color);
            definition.add("model", modelConfig);
            definition.add("settings", internalSettings());
            renderItems.add(NAMESPACE + ":_render/bar_stool_body/" + color, definition);
        }
        for (String part : List.of("base", "lid")) {
            JsonObject definition = new JsonObject();
            definition.addProperty("material", "paper");
            JsonObject data = new JsonObject();
            data.addProperty("item_name", renderItemName("shaker"));
            definition.add("data", data);
            JsonObject modelConfig = new JsonObject();
            modelConfig.addProperty("type", "minecraft:model");
            modelConfig.addProperty("path", NAMESPACE + ":furniture/shaker_" + part);
            definition.add("model", modelConfig);
            definition.add("settings", internalSettings());
            renderItems.add(NAMESPACE + ":_render/shaker_" + part, definition);
        }
    }

    private String renderItemName(String referenceId) {
        String[] parts = referenceId.split("_");
        for (int start = 0; start < parts.length; start++) {
            StringBuilder name = new StringBuilder();
            for (int i = start; i < parts.length; i++) {
                if (i > start) name.append('_');
                name.append(parts[i]);
            }
            for (String prefix : List.of("block", "item")) {
                String candidate = prefix + "." + NAMESPACE + "." + name;
                if (languageKeys.contains(candidate)) return "<!i><lang:" + candidate + ">";
            }
        }
        throw new IllegalArgumentException("No display-name translation for render item " + referenceId);
    }

    private String fluidRenderName(String fluid) {
        if (fluid.equals("water") || fluid.equals("lava")) {
            return "<!i><lang:block.minecraft." + fluid + ">";
        }
        return renderItemName(fluid);
    }

    private static JsonObject internalSettings() {
        JsonObject settings = new JsonObject();
        JsonArray tags = new JsonArray();
        tags.add(NAMESPACE + ":internal_render_items");
        settings.add("tags", tags);
        return settings;
    }

    private static List<String> sorted(Set<String> values) {
        List<String> result = new ArrayList<>(values);
        result.sort(String::compareTo);
        return result;
    }
}
