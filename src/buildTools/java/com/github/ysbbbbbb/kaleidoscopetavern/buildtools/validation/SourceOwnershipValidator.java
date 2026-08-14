package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Closed-world source ownership parity: every archived Forge runtime class
 * with inventory/entity/world interaction (plus ticking block entities, tap
 * behaviors, effects, events, entities and persistent block entities) must
 * name a concrete Paper/CE owner and an evidence token, so a new source
 * interaction cannot silently become a decorative-only port.
 */
public final class SourceOwnershipValidator {
    private static final String NAMESPACE = "kaleidoscope_tavern";
    private static final String GAME_PREFIX =
            "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/game/";
    private static final Path MIGRATION_PACKAGE = Path.of(
            "src/buildTools/java/com/github/ysbbbbbb/kaleidoscopetavern/buildtools/migration");
    private static final Pattern RUNTIME_DECLARATION = Pattern.compile(
            "(?m)^\\s*(?:public|protected)\\s+[^\\n;{]+\\b(?:use|useOn|onUseTick|releaseUsing"
                    + "|finishUsingItem|neighborChanged|fallOn|onProjectileHit|destroy|execute"
                    + "|animateTick|randomTick|performBonemeal|pickupBlock|getBurnTime"
                    + "|getEquipmentSlot)\\s*\\(");
    private static final Pattern TICK_DECLARATION = Pattern.compile(
            "(?m)^\\s*public\\s+(?:static\\s+)?void\\s+tick\\s*\\(");

    private final Path projectRoot;
    private final Map<Path, String> textCache = new LinkedHashMap<>();

    public SourceOwnershipValidator(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    public static final class ValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public ValidationException(String message) { super(message); }
    }

    private record Evidence(String implementation, String token) {}

    public Result validate() throws IOException {
        Path blocksRoot = sourceRoot("block");
        Path itemsRoot = sourceRoot("item");
        Path blockEntitiesRoot = sourceRoot("blockentity");
        Set<String> runtimeFiles = runtimeBehaviorFiles(blocksRoot, itemsRoot);
        require(runtimeFiles.equals(RUNTIME_BEHAVIOR_COVERAGE.keySet()),
                "Source runtime behavior coverage drift: expected " + RUNTIME_BEHAVIOR_COVERAGE.keySet()
                        + ", found " + runtimeFiles);
        for (Map.Entry<String, List<Evidence>> entry : RUNTIME_BEHAVIOR_COVERAGE.entrySet()) {
            assertOwnerEvidence(entry.getKey(), entry.getValue());
        }
        Path tapRoot = sourceRoot("game/tap/impl");
        Set<String> tapFiles = javaBasenames(tapRoot);
        require(tapFiles.equals(TAP_BEHAVIOR_COVERAGE.keySet()),
                "Source tap behavior coverage drift: expected " + TAP_BEHAVIOR_COVERAGE.keySet()
                        + ", found " + tapFiles);
        for (Map.Entry<String, List<Evidence>> entry : TAP_BEHAVIOR_COVERAGE.entrySet()) {
            assertOwnerEvidence(entry.getKey(), entry.getValue());
        }
        Set<String> tickingFiles = tickingBlockEntities(blockEntitiesRoot);
        require(tickingFiles.equals(TICKING_BLOCK_ENTITY_COVERAGE.keySet()),
                "Source ticking block-entity coverage drift: expected "
                        + TICKING_BLOCK_ENTITY_COVERAGE.keySet() + ", found " + tickingFiles);
        for (Map.Entry<String, List<Evidence>> entry : TICKING_BLOCK_ENTITY_COVERAGE.entrySet()) {
            assertOwnerEvidence(entry.getKey(), entry.getValue());
        }
        Set<String> effectFiles = javaBasenames(sourceRoot("effect"));
        require(effectFiles.equals(EFFECT_BEHAVIOR_COVERAGE.keySet()),
                "Source effect coverage drift: expected " + EFFECT_BEHAVIOR_COVERAGE.keySet()
                        + ", found " + effectFiles);
        for (Map.Entry<String, List<Evidence>> entry : EFFECT_BEHAVIOR_COVERAGE.entrySet()) {
            assertOwnerEvidence(entry.getKey(), entry.getValue());
        }
        Set<String> eventFiles = javaBasenames(sourceRoot("event"));
        require(eventFiles.equals(EVENT_BEHAVIOR_COVERAGE.keySet()),
                "Source event coverage drift: expected " + EVENT_BEHAVIOR_COVERAGE.keySet()
                        + ", found " + eventFiles);
        for (Map.Entry<String, List<Evidence>> entry : EVENT_BEHAVIOR_COVERAGE.entrySet()) {
            assertOwnerEvidence(entry.getKey(), entry.getValue());
        }
        Set<String> entityFiles = javaBasenames(sourceRoot("entity"));
        require(entityFiles.equals(ENTITY_BEHAVIOR_COVERAGE.keySet()),
                "Source entity coverage drift: expected " + ENTITY_BEHAVIOR_COVERAGE.keySet()
                        + ", found " + entityFiles);
        for (Map.Entry<String, List<Evidence>> entry : ENTITY_BEHAVIOR_COVERAGE.entrySet()) {
            assertOwnerEvidence(entry.getKey(), entry.getValue());
        }
        Set<String> blockEntityFiles = blockEntityBasenames(blockEntitiesRoot);
        require(blockEntityFiles.equals(BLOCK_ENTITY_COVERAGE.keySet()),
                "Source block-entity coverage drift: expected " + BLOCK_ENTITY_COVERAGE.keySet()
                        + ", found " + blockEntityFiles);
        for (Map.Entry<String, List<Evidence>> entry : BLOCK_ENTITY_COVERAGE.entrySet()) {
            assertOwnerEvidence(entry.getKey(), entry.getValue());
        }
        return new Result(runtimeFiles.size(), tapFiles.size(), tickingFiles.size(),
                effectFiles.size(), eventFiles.size(), entityFiles.size(), blockEntityFiles.size());
    }

    private Path sourceRoot(String relative) {
        return projectRoot.resolve("src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/"
                + relative);
    }

    private static Set<String> runtimeBehaviorFiles(Path blocksRoot, Path itemsRoot) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        for (Path root : List.of(blocksRoot, itemsRoot)) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java"))
                        .forEach(p -> {
                            if (RUNTIME_DECLARATION.matcher(readTextSafe(p)).find()) {
                                result.add(p.getFileName().toString());
                            }
                        });
            }
        }
        return Set.copyOf(result);
    }

    private static Set<String> tickingBlockEntities(Path root) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        if (TICK_DECLARATION.matcher(readTextSafe(p)).find()) {
                            result.add(p.getFileName().toString());
                        }
                    });
        }
        return Set.copyOf(result);
    }

    private static Set<String> blockEntityBasenames(Path root) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith("BlockEntity.java"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        if (!name.equals("BaseBlockEntity.java")) result.add(name);
                    });
        }
        return Set.copyOf(result);
    }

    private static Set<String> javaBasenames(Path directory) throws IOException {
        require(Files.isDirectory(directory), "Missing archived source directory " + directory);
        Set<String> result = new LinkedHashSet<>();
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".java") && !name.equals("package-info.java"))
                    .forEach(result::add);
        }
        return Set.copyOf(result);
    }

    private void assertOwnerEvidence(String sourceName, List<Evidence> owners) throws IOException {
        for (Evidence evidence : owners) {
            Path owner = paperOwnerPath(evidence.implementation());
            require(Files.exists(owner),
                    sourceName + ": missing Paper owner " + evidence.implementation());
            String text = readText(owner);
            if (!text.contains(evidence.token())) {
                String actual = evidence.token();
                throw new ValidationException(sourceName + ": Paper owner "
                        + evidence.implementation() + " lacks evidence token '" + actual + "'");
            }
        }
    }

    private Path paperOwnerPath(String implementation) {
        if (implementation.startsWith("src/")) return projectRoot.resolve(implementation);
        if (implementation.equals("tools/migrate_legacy.py")) {
            return projectRoot.resolve(MIGRATION_PACKAGE);
        }
        return projectRoot.resolve(GAME_PREFIX + implementation);
    }

    private String readText(Path path) throws IOException {
        Path key = path.toAbsolutePath().normalize();
        if (Files.isDirectory(key)) {
            StringBuilder builder = new StringBuilder();
            try (Stream<Path> files = Files.walk(key)) {
                List<Path> list = files.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java")).toList();
                for (Path file : list) {
                    builder.append(textCache.computeIfAbsent(file, this::readTextUnchecked));
                }
            }
            return builder.toString();
        }
        return textCache.computeIfAbsent(key, this::readTextUnchecked);
    }

    private String readTextUnchecked(Path path) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (IOException error) {
            throw new ValidationException("Cannot read " + path + ": " + error.getMessage());
        }
    }

    private static String readTextSafe(Path path) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (IOException error) {
            throw new ValidationException("Cannot read " + path + ": " + error.getMessage());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new ValidationException(message);
    }

    public record Result(int runtimeBehaviors, int tapBehaviors, int tickingBlockEntities,
                         int effectFiles, int eventFiles, int entityFiles, int blockEntityFiles) {}

    private static Map<String, List<Evidence>> runtimeCoverage() {
        Map<String, List<Evidence>> map = new LinkedHashMap<>();
        map.put("AbstractStorageBlock.java", List.of(
                ev("storage/StorageBlockBehavior.java", "public void neighborChanged"),
                ev("storage/StorageBlockBehavior.java", "private InteractionResult interact("),
                ev("storage/StorageBlockBehavior.java", "private void launchRandom()"),
                ev("storage/StorageBlockConfig.java", "record Interaction(")));
        map.put("BarCabinetBlock.java", List.of(
                ev("decor/ConnectedBlockBehavior.java", "private ImmutableBlockState updateLinear("),
                ev("storage/StorageBlockConfig.java", "boolean fallbackPut"),
                ev("src/paper/pack/configuration/blocks.json", "\"exclusive_items\"")));
        map.put("BarStoolBlock.java", List.of(
                ev("tools/migrate_legacy.py", "_bar_stool"),
                ev("decor/BarStoolVisualService.java", "onMount")));
        map.put("BarrelBlock.java", List.of(ev("station/StationService.java", "interactBarrel")));
        map.put("BottleBlock.java", List.of(
                ev("drink/BottleFurnitureBehavior.java", "useOnFurniture"),
                ev("drink/BottleFurnitureService.java", "private InteractionResult interact")));
        map.put("BottleBlockDispenseBehavior.java", List.of(
                ev("drink/BottlePlacementService.java", "onDispenseBottle")));
        map.put("CellarCabinetBlock.java", List.of(
                ev("decor/ConnectedBlockBehavior.java", "private ImmutableBlockState updateLinear("),
                ev("storage/StorageBlockConfig.java", "boolean frontOnly"),
                ev("src/paper/pack/configuration/blocks.json", "\"selector\"")));
        map.put("ChalkboardBlock.java", List.of(
                ev("board/ChalkboardBlockBehavior.java", "private void tryMerge("),
                ev("board/ChalkboardBlockBehavior.java", "private void removeOtherParts("),
                ev("board/BoardTextService.java", "private InteractionResult interactChalkboard(")));
        map.put("CircularRackBlock.java", List.of(
                ev("storage/StorageBlockConfig.java", "record ParticleEffect("),
                ev("storage/StorageBlockBehavior.java", "private void tickParticle("),
                ev("src/paper/pack/configuration/blocks.json", "\"alternate_min_x\"")));
        map.put("CocktailBlockItem.java", List.of(
                ev("effect/EffectService.java", "onConsume"),
                ev("src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/game/drink/SneakPlaceDrinkItemBehavior.java", "useOnBlock")));
        map.put("DrinkBlock.java", List.of(
                ev("drink/BottleFurnitureService.java", "onProjectileHit")));
        map.put("DrinkBlockItem.java", List.of(
                ev("effect/EffectService.java", "onConsume"),
                ev("src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/game/drink/SneakPlaceDrinkItemBehavior.java", "useOnBlock")));
        map.put("GlasswareBlock.java", List.of(
                ev("drink/BottleFurnitureService.java", "onProjectileHit")));
        map.put("GlasswareHolderBlock.java", List.of(
                ev("storage/DisplayStorageService.java", "GLASSWARE_HOLDER")));
        map.put("GrapeCropBlock.java", List.of(
                ev("src/paper/customcrops/contents/crops/kaleidoscope_tavern.yml", "grow-conditions:"),
                ev("grape/HangingGrapeCropBehavior.java", "CustomCropsBridge.removeCrop"),
                ev("src/paper/customcrops/contents/crops/kaleidoscope_tavern.yml", "harvest_with_shears")));
        map.put("GrapevineItem.java", List.of(
                ev("src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/game/grape/GrapevineItemBehavior.java", "useOnBlock"),
                ev("block/BlockService.java", "useGrapevineOnBlock"),
                ev("tools/migrate_legacy.py", "\"fuel_time\"")));
        map.put("GrapevineTrellisBlock.java", List.of(
                ev("tools/migrate_legacy.py", "grapevine_trellis_shear_events"),
                ev("grape/TrellisBehavior.java", "implements BonemealableBlock"),
                ev("grape/TrellisBehavior.java", "public static boolean grow")));
        map.put("HolderBlock.java", List.of(
                ev("storage/StorageBlockConfig.java", "record Launch("),
                ev("storage/StorageBlockConfig.java", "case SINGLE ->"),
                ev("src/paper/pack/configuration/blocks.json", "\"origin_forward\"")));
        map.put("IncenseBlock.java", List.of(
                ev("src/paper/pack/configuration/blocks.json", "minecraft:copper_lantern"),
                ev("tools/migrate_legacy.py", "incense_toggle_events"),
                ev("effect/IncenseBlockBehavior.java", "updateStateForPlacement"),
                ev("effect/IncenseBlockBehavior.java", "neighborChanged"),
                ev("effect/IncenseBlockBehavior.java", "spawnParticles")));
        map.put("JuiceBucketItem.java", List.of(ev("tools/migrate_legacy.py", "milk_bucket")));
        map.put("MolotovBlock.java", List.of(
                ev("molotov/MolotovService.java", "onProjectileHit"),
                ev("molotov/MolotovService.java", "LevelWriterProxy.INSTANCE"),
                ev("molotov/MolotovService.java", "UPDATE_CLIENTS | UpdateFlags.UPDATE_KNOWN_SHAPE")));
        map.put("MolotovBlockItem.java", List.of(
                ev("molotov/MolotovService.java", "onStopUsing"),
                ev("tools/migrate_legacy.py", "consume_seconds")));
        map.put("MysteryCocktailBlock.java", List.of(
                ev("decor/AmbientFurnitureService.java", "tickMysteryCocktail"),
                ev("furniture/TickingFurnitureBehavior.java", "MYSTERY_PARTICLE")));
        map.put("PressingTubBlock.java", List.of(
                ev("pressing/PressingTubBlockBehavior.java", "void fallOn(Object thisBlock, Object[] args)"),
                ev("tools/migrate_legacy.py", "ground_block_item"),
                ev("tools/migrate_legacy.py", "WALL_PRESSING_TUB_ID"),
                ev("pressing/PressingTubService.java", "interactPress"),
                ev("pressing/PressingTubService.java", "boolean press")));
        map.put("SandwichBoardBlock.java", List.of(
                ev("board/BoardTextService.java", "transformSandwichBoard")));
        map.put("ShakerBlock.java", List.of(ev("station/StationService.java", "interactShaker")));
        map.put("ShakerItem.java", List.of(
                ev("src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/game/shaker/ShakerItemBehavior.java", "InteractionResult use(World world"),
                ev("station/StationService.java", "usePortableShaker"),
                ev("shaker/ShakerSemantics.java", "AUTO_RELEASE_AFTER_TICKS")));
        map.put("SofaBlock.java", List.of(
                ev("decor/ConnectedBlockBehavior.java", "private ImmutableBlockState updateCorner("),
                ev("tools/migrate_legacy.py", "seat_block")));
        map.put("StringLightsBlock.java", List.of(
                ev("tools/migrate_legacy.py", "variants.add(\"wall\""),
                ev("tools/migrate_legacy.py", "glowing_furniture"),
                ev("tools/migrate_legacy.py", "string_lights_dye_events"),
                ev("tools/migrate_legacy.py", "replace_furniture")));
        map.put("StringLightsBlockItem.java", List.of(
                ev("src/paper/resources/catalog/tags.tsv", "curios:charm"),
                ev("src/paper/pack/configuration/items.json", "\"type\": \"furniture_item\"")));
        map.put("TapBlock.java", List.of(
                ev("src/paper/pack/configuration/blocks.json", "minecraft:lightning_rod"),
                ev("tap/TapBlockBehavior.java", "updateStateForPlacement"),
                ev("tap/TapBlockBehavior.java", "neighborChanged"),
                ev("tap/TapBlockBehavior.java", "useOnBlock"),
                ev("tap/TapBlockBehavior.java", "TAKE_TICKS = 30"),
                ev("tap/TapService.java", "TapBlockBehavior.bind(this)"),
                ev("tap/TapSemantics.java", "isBarrelConnection")));
        map.put("TiltedRackBlock.java", List.of(
                ev("storage/StorageBlockConfig.java", "case SPLIT ->"),
                ev("storage/StorageBlockConfig.java", "public enum LaunchDirection"),
                ev("src/paper/pack/configuration/blocks.json", "\"x_rotation\"")));
        map.put("TrellisBlock.java", List.of(
                ev("block/BlockService.java", "useGrapevineOnBlock"),
                ev("grape/TrellisBehavior.java", "updateStateForPlacement"),
                ev("src/paper/pack/configuration/blocks.json", "item.axe.wax_off")));
        map.put("WildGrapevineBlock.java", List.of(
                ev("src/paper/pack/configuration/blocks.json", "entity.sheep.shear"),
                ev("grape/WildGrapevineBehavior.java", "implements BonemealableBlock"),
                ev("grape/WildGrapevineBehavior.java", "isValidBonemealTarget"),
                ev("grape/WildGrapevineBehavior.java", "randomTick")));
        return Map.copyOf(map);
    }

    private static Map<String, List<Evidence>> tapCoverage() {
        Map<String, List<Evidence>> map = new LinkedHashMap<>();
        map.put("BarrelTapBehavior.java", List.of(ev("tap/TapService.java", "BOTTLE_BARREL")));
        map.put("BeehiveTapBehavior.java", List.of(ev("tap/TapService.java", "BOTTLE_HONEY")));
        map.put("DragonHeadTapBehavior.java", List.of(ev("tap/TapService.java", "BOTTLE_DRAGON_BREATH")));
        map.put("LavaCauldronTapBehavior.java", List.of(ev("tap/TapService.java", "FILL_LAVA_CAULDRON")));
        map.put("WaterCauldronTapBehavior.java", List.of(ev("tap/TapService.java", "FILL_WATER_CAULDRON")));
        map.put("WaterloggedBehavior.java", List.of(ev("tap/TapService.java", "BOTTLE_WATER")));
        map.put("WatermelonTapBehavior.java", List.of(ev("tap/TapService.java", "BOTTLE_WATERMELON")));
        return Map.copyOf(map);
    }

    private static Map<String, List<Evidence>> tickingCoverage() {
        Map<String, List<Evidence>> map = new LinkedHashMap<>();
        map.put("BarrelBlockEntity.java", List.of(ev("station/StationService.java", "barrelTickingHandler")));
        map.put("BarStoolBlockEntity.java", List.of(ev("decor/BarStoolVisualService.java", "tickOccupied")));
        map.put("TapBlockEntity.java", List.of(ev("tap/TapBlockBehavior.java", "TAKE_PARTICLE_TICKS = 5")));
        map.put("TextBlockEntity.java", List.of(ev("board/BoardTextService.java", "validateEditDistance")));
        return Map.copyOf(map);
    }

    private static Map<String, List<Evidence>> effectCoverage() {
        Map<String, List<Evidence>> map = new LinkedHashMap<>();
        map.put("ArdentHeatEffect.java", List.of(ev("effect/EffectService.java", "ardentHeat")));
        map.put("BaseEffect.java", List.of(
                ev("effect/EffectService.java", "slightly_tipsy"),
                ev("effect/EffectService.java", "bloody_mary"),
                ev("effect/EffectService.java", "tomb_raider")));
        map.put("GrassStealthEffect.java", List.of(ev("effect/EffectService.java", "grassStealth")));
        map.put("HighHeelsEffect.java", List.of(ev("effect/EffectService.java", "Attribute.STEP_HEIGHT")));
        map.put("LongReachEffect.java", List.of(
                ev("effect/EffectService.java", "Attribute.BLOCK_INTERACTION_RANGE")));
        map.put("ShriekAttackEffect.java", List.of(
                ev("effect/EffectService.java", "DamageType.SONIC_BOOM"),
                ev("effect/EffectService.java", "EffectSemantics.shriekHits("),
                ev("effect/EffectSemantics.java", "static boolean shriekHits(")));
        map.put("UpsideDownEffect.java", List.of(ev("effect/EffectService.java", "upside_down")));
        map.put("VisionEffect.java", List.of(ev("effect/EffectService.java", "void vision")));
        map.put("XpDrainEffect.java", List.of(ev("effect/EffectService.java", "xpDrain")));
        map.put("ZenithEffect.java", List.of(ev("effect/EffectService.java", "zenith")));
        return Map.copyOf(map);
    }

    private static Map<String, List<Evidence>> eventCoverage() {
        Map<String, List<Evidence>> map = new LinkedHashMap<>();
        map.put("AddFeaturesEvent.java", List.of(
                ev("src/paper/pack/configuration/worldgen.json", "wild_grapevine")));
        map.put("ChangeTargetEvent.java", List.of(ev("effect/EffectService.java", "onTarget")));
        map.put("EffectEvent.java", List.of(
                ev("effect/EffectService.java", "onDeath"),
                ev("effect/EffectService.java", "onDamage"),
                ev("effect/EffectService.java", "ardentHeat")));
        map.put("VanillaBottlePlaceEvent.java", List.of(
                ev("drink/SneakPlaceVanillaBottleItemBehavior.java", "useOnBlock")));
        return Map.copyOf(map);
    }

    private static Map<String, List<Evidence>> entityCoverage() {
        Map<String, List<Evidence>> map = new LinkedHashMap<>();
        map.put("SitEntity.java", List.of(
                ev("tools/migrate_legacy.py", "_sofa"),
                ev("tools/migrate_legacy.py", "_bar_stool")));
        map.put("ThrownMolotovEntity.java", List.of(ev("molotov/MolotovService.java", "spreadFire")));
        return Map.copyOf(map);
    }

    private static Map<String, List<Evidence>> blockEntityCoverage() {
        Map<String, List<Evidence>> map = new LinkedHashMap<>();
        map.put("BarCabinetBlockEntity.java", List.of(
                ev("storage/StorageBlockBehavior.java", "private final Item[] items")));
        map.put("BarrelBlockEntity.java", List.of(ev("station/StationService.java", "barrel_items")));
        map.put("CellarCabinetBlockEntity.java", List.of(
                ev("storage/StorageBlockBehavior.java", "private final Item[] items")));
        map.put("DrinkBlockEntity.java", List.of(ev("drink/BottleFurnitureService.java", "storedItems")));
        map.put("PotionBottleBlockEntity.java", List.of(ev("drink/BottleFurnitureService.java", "sourceItem")));
        map.put("PressingTubBlockEntity.java", List.of(ev("pressing/PressingTubBlockBehavior.java",
                "private static final String DATA_KEY = \"kaleidoscope_tavern:press\"")));
        map.put("TapBlockEntity.java", List.of(
                ev("tap/TapBlockBehavior.java", "private Cycle cycle"),
                ev("tap/TapBlockBehavior.java", "DRIP_LIFETIME_TICKS = 18")));
        map.put("BarStoolBlockEntity.java", List.of(
                ev("decor/BarStoolVisualService.java", "AnimatedItemFurnitureBehavior.updatePosition")));
        map.put("ChalkboardBlockEntity.java", List.of(
                ev("board/ChalkboardBlockBehavior.java", "private static final String DATA_KEY"),
                ev("board/BoardTextService.java", "controller.isLarge() ? 1_500 : 350")));
        map.put("CircularRackBlockEntity.java", List.of(
                ev("storage/StorageBlockBehavior.java", "private final Item[] items"),
                ev("storage/StorageBlockBehavior.java", "private void tickParticle(")));
        map.put("GlasswareHolderBlockEntity.java", List.of(
                ev("storage/DisplayStorageService.java", "GLASSWARE_HOLDER")));
        map.put("HolderBlockEntity.java", List.of(
                ev("storage/StorageBlockBehavior.java", "Item[] items")));
        map.put("IncenseBlockEntity.java", List.of(
                ev("effect/IncenseBlockBehavior.java", "hurtNearbyUndead"),
                ev("effect/IncenseBlockBehavior.java", "takeDamageDue()")));
        map.put("SandwichBlockEntity.java", List.of(ev("board/BoardTextService.java", "isSandwichBoard")));
        map.put("StorageBlockEntity.java", List.of(ev("storage/StorageBlockBehavior.java", "Item[] items")));
        map.put("TextBlockEntity.java", List.of(ev("board/BoardTextService.java", "board_text")));
        map.put("TiltedRackBlockEntity.java", List.of(ev("storage/StorageBlockBehavior.java", "Item[] items")));
        map.put("ShakerBlockEntity.java", List.of(
                ev("station/StationService.java", "updateShakerSource"),
                ev("shaker/ShakerVisualService.java", "animatePut")));
        map.put("SignatureCocktailBlockEntity.java", List.of(
                ev("src/paper/resources/recipes/shaker.yml", "signature_cocktail"),
                ev("src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/item/ItemService.java",
                        "signatureColor")));
        return Map.copyOf(map);
    }

    private static final Map<String, List<Evidence>> RUNTIME_BEHAVIOR_COVERAGE = runtimeCoverage();
    private static final Map<String, List<Evidence>> TAP_BEHAVIOR_COVERAGE = tapCoverage();
    private static final Map<String, List<Evidence>> TICKING_BLOCK_ENTITY_COVERAGE = tickingCoverage();
    private static final Map<String, List<Evidence>> EFFECT_BEHAVIOR_COVERAGE = effectCoverage();
    private static final Map<String, List<Evidence>> EVENT_BEHAVIOR_COVERAGE = eventCoverage();
    private static final Map<String, List<Evidence>> ENTITY_BEHAVIOR_COVERAGE = entityCoverage();
    private static final Map<String, List<Evidence>> BLOCK_ENTITY_COVERAGE = blockEntityCoverage();

    private static Evidence ev(String implementation, String token) {
        return new Evidence(implementation, token);
    }
}
