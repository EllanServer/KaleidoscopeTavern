package com.github.ysbbbbbb.kaleidoscopetavern.buildtools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Audits the deployable plugin JAR and managed content bundles. */
public final class PluginJarVerifier {
    private static final List<String> REQUIRED_ENTRIES = List.of(
            "plugin.yml",
            "META-INF/MANIFEST.MF",
            "META-INF/LICENSE-CODE",
            "META-INF/LICENSE-ASSETS",
            "META-INF/ASSET-CREDITS.md",
            "META-INF/THIRD-PARTY-NOTICES.md",
            "META-INF/third-party-licenses/SPARROW-YAML-GPL-3.0.txt",
            "META-INF/third-party-licenses/SNAKEYAML-APACHE-2.0.txt",
            "tavern-pack/pack.yml",
            "tavern-pack/configuration/blocks.json",
            "tavern-pack/configuration/furniture.json",
            "tavern-pack/configuration/items.json",
            "tavern-pack/configuration/render-items.json",
            "tavern-pack/configuration/worldgen.json",
            "customcrops/contents/crops/kaleidoscope_tavern.yml",
            "recipes/barrel.yml",
            "recipes/shaker.yml",
            "visuals/tap.yml",
            "net/momirealms/sparrow/yaml/SparrowYaml.class",
            "customnameplates/bossbar-tavern-effects.yml",
            "tavern-pack/resourcepack/assets/kaleidoscope_tavern/font/custom_effects_hud.json",
            "tavern-pack/resourcepack/assets/kaleidoscope_tavern/textures/font/hud_effect/slightly_tipsy.png",
            "tavern-pack/resourcepack/assets/minecraft/textures/gui/sprites/boss_bar/yellow_background.png",
            "tavern-pack/resourcepack/assets/minecraft/textures/gui/sprites/boss_bar/yellow_progress.png",
            "com/github/ysbbbbbb/kaleidoscopetavern/paper/pack/PackInstaller.class",
            "com/github/ysbbbbbb/kaleidoscopetavern/paper/pack/CustomCropsInstaller.class",
            "com/github/ysbbbbbb/kaleidoscopetavern/paper/integration/EffectHudPlaceholder.class");
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "net/momirealms/craftengine/",
            "net/momirealms/customcrops/",
            "me/clip/placeholderapi/",
            "io/papermc/paper/",
            "org/bukkit/",
            "org/junit/",
            "com/github/ysbbbbbb/kaleidoscopetavern/buildtools/");
    private static final Map<String, List<String>> LEGAL_MARKERS = Map.of(
            "META-INF/LICENSE-CODE", List.of("BSD 3-Clause License", "Kaleidoscope Official Production Team"),
            "META-INF/LICENSE-ASSETS", List.of(
                    "Creative Commons Attribution-NonCommercial-ShareAlike 4.0",
                    "https://creativecommons.org/licenses/by-nc-sa/4.0/legalcode"),
            "META-INF/third-party-licenses/SPARROW-YAML-GPL-3.0.txt", List.of(
                    "GNU GENERAL PUBLIC LICENSE", "Version 3, 29 June 2007"),
            "META-INF/third-party-licenses/SNAKEYAML-APACHE-2.0.txt", List.of(
                    "Apache License", "Version 2.0, January 2004"),
            "META-INF/ASSET-CREDITS.md", List.of(
                    "KaleidoscopeMods/KaleidoscopeTavern", "NonCommercial", "modified"),
            "META-INF/THIRD-PARTY-NOTICES.md", List.of(
                    "CraftEngine", "CustomCrops", "Sparrow YAML", "SnakeYAML Engine",
                    "GPL-3.0", "Apache License 2.0", "bundled", "not bundled"));

    private PluginJarVerifier() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: PluginJarVerifier <archive> <custom-crops-version>");
            System.exit(2);
        }
        try {
            verify(Path.of(args[0]), args[1]);
        } catch (IOException | IllegalStateException | IllegalArgumentException error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    static void verify(Path archivePath, String customCropsVersion) throws IOException {
        if (!Files.isRegularFile(archivePath)) {
            throw new IllegalStateException("Deployable JAR does not exist: " + archivePath);
        }
        try (ZipFile archive = new ZipFile(archivePath.toFile(), StandardCharsets.UTF_8)) {
            Set<String> names = new HashSet<>();
            archive.stream().map(ZipEntry::getName).forEach(names::add);
            List<String> missing = REQUIRED_ENTRIES.stream().filter(entry -> !names.contains(entry)).toList();
            require(missing.isEmpty(), "Deployable JAR is missing: " + String.join(", ", missing));

            String embedded = names.stream()
                    .filter(name -> name.endsWith(".class"))
                    .filter(name -> FORBIDDEN_PREFIXES.stream().anyMatch(name::startsWith))
                    .sorted()
                    .findFirst()
                    .orElse(null);
            require(embedded == null,
                    "Runtime dependencies must remain separate plugins; embedded class found: " + embedded);
            String unexpectedSparrow = names.stream()
                    .filter(name -> name.endsWith(".class"))
                    .filter(name -> name.startsWith("net/momirealms/sparrow/"))
                    .filter(name -> !name.startsWith("net/momirealms/sparrow/yaml/"))
                    .sorted()
                    .findFirst()
                    .orElse(null);
            require(unexpectedSparrow == null,
                    "Only sparrow-yaml may be embedded; unexpected class found: " + unexpectedSparrow);

            for (Map.Entry<String, List<String>> legal : LEGAL_MARKERS.entrySet()) {
                String document = readText(archive, legal.getKey(), true);
                for (String marker : legal.getValue()) {
                    require(document.contains(marker),
                            legal.getKey() + " is missing required legal marker: " + marker);
                }
            }

            String manifest = readText(archive, "META-INF/MANIFEST.MF", false)
                    .replace("\r\n ", "")
                    .replace("\n ", "");
            String expectedManifest = "Required-CustomCrops-Version: " + customCropsVersion;
            require(manifest.contains(expectedManifest),
                    "Manifest is missing '" + expectedManifest + "'");

            String pluginYml = readText(archive, "plugin.yml", false);
            require(pluginYml.contains("depend: [CraftEngine, CustomCrops]"),
                    "plugin.yml must require both CraftEngine and CustomCrops");
            require(pluginYml.contains("api-version: '26.2'"),
                    "plugin.yml is not pinned to Paper 26.2");

            JsonObject blocks = jsonObject(archive, "tavern-pack/configuration/blocks.json");
            require(objectSize(blocks, "blocks") == 44,
                    "Embedded CraftEngine project must contain 44 block ids");
            JsonObject furniture = jsonObject(archive, "tavern-pack/configuration/furniture.json");
            require(objectSize(furniture, "furniture") == 116,
                    "Embedded CraftEngine project must contain 116 furniture ids");

            JsonObject items = childObject(
                    jsonObject(archive, "tavern-pack/configuration/items.json"), "items");
            Set<String> publicItems = new HashSet<>();
            Set<String> vanilla = new HashSet<>();
            for (String itemId : items.keySet()) {
                if (itemId.startsWith("kaleidoscope_tavern:")) {
                    publicItems.add(itemId);
                } else {
                    vanilla.add(itemId);
                }
            }
            require(publicItems.size() == 157,
                    "Embedded CraftEngine project must contain 157 public item ids");
            require(vanilla.equals(Set.of(
                            "minecraft:potion", "minecraft:honey_bottle",
                            "minecraft:dragon_breath", "minecraft:experience_bottle")),
                    "Embedded CraftEngine project must contain the four managed vanilla bottle item extensions");

            JsonObject renderItems = jsonObject(
                    archive, "tavern-pack/configuration/render-items.json");
            require(objectSize(renderItems, "items") == 414,
                    "Embedded CraftEngine project must contain 414 render item ids");

            JsonObject worldgen = jsonObject(archive, "tavern-pack/configuration/worldgen.json");
            String configuredId = "kaleidoscope_tavern:wild_grapevine_chain";
            String placedId = "kaleidoscope_tavern:wild_grapevine";
            require(childObject(worldgen, "configured_features").has(configuredId),
                    "Embedded CraftEngine project is missing wild grapevine worldgen");
            JsonElement placed = childObject(worldgen, "placed_features").get(placedId);
            require(placed != null && placed.isJsonObject()
                            && configuredId.equals(string(placed.getAsJsonObject(), "feature")),
                    "Embedded wild grapevine placed feature has an invalid chain reference");

            String customCrops = readText(
                    archive, "customcrops/contents/crops/kaleidoscope_tavern.yml", false);
            for (String crop : List.of(
                    "kaleidoscope_tavern_grape:",
                    "kaleidoscope_tavern_ice_grape:",
                    "kaleidoscope_tavern_gold_grape:")) {
                require(customCrops.contains(crop),
                        "Managed CustomCrops bundle is missing " + crop);
            }
            require(names.stream().anyMatch(
                            name -> name.startsWith("tavern-pack/resourcepack/assets/")),
                    "Embedded CraftEngine resource pack is empty");
        }
        System.out.println("Plugin JAR verified: Paper 26.2, CustomCrops "
                + customCropsVersion
                + ", managed CraftEngine project, resource pack and legal notices present");
    }

    private static JsonObject jsonObject(ZipFile archive, String name) throws IOException {
        JsonElement element = JsonParser.parseString(readText(archive, name, true));
        require(element.isJsonObject(), name + " must contain a JSON object");
        return element.getAsJsonObject();
    }

    private static JsonObject childObject(JsonObject parent, String key) {
        JsonElement child = parent.get(key);
        require(child != null && child.isJsonObject(),
                "JSON key '" + key + "' must contain an object");
        return child.getAsJsonObject();
    }

    private static int objectSize(JsonObject parent, String key) {
        return childObject(parent, key).size();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String readText(ZipFile archive, String name, boolean stripBom)
            throws IOException {
        ZipEntry entry = archive.getEntry(name);
        require(entry != null, "Deployable JAR is missing: " + name);
        String text;
        try (var input = archive.getInputStream(entry)) {
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        return stripBom && !text.isEmpty() && text.charAt(0) == '﻿'
                ? text.substring(1)
                : text;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
