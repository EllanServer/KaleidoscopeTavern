package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Native port of validate_pack.py's source-token contracts: every required /
 * stale / forbidden token check against src/paper Java sources, plugin config
 * and bundled YAML. Rules are table-driven so the remaining Python contracts
 * can be appended as rows.
 */
public final class SourceTokenValidator {
    private final Path projectRoot;

    public SourceTokenValidator(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    public static final class ValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public ValidationException(String message) { super(message); }
    }

    private static final String GAME = "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/game/";
    private static final String PAPER = "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/";

    private record Rule(String path, String[] tokens, Mode mode, String message) {}

    private enum Mode {
        /** Every token must be present. */
        REQUIRED_ALL,
        /** No token may be present. */
        STALE_NONE
    }

    private static final List<Rule> RULES = new ArrayList<>();
    static {
        addRequired(GAME + "grape/TrellisBehavior.java", new String[] {
                "public InteractionResult useOnBlock", "player.swingHand(context.getHand())",
                "return InteractionResult.SUCCESS;",
                "MutableBlockPosProxy.INSTANCE.newInstance()",
                "MutableBlockPosProxy.INSTANCE.setWithOffset(",
                "BlockGetterProxy.INSTANCE.getBlockState(level, targetPosition)",
                "private static final Set<Key> TRELLISES",
                "public static void prewarmLoadedBlockIds()",
                "return state.owner().value().id();",
                "extends BukkitBlockBehavior",
                "private final Property<Direction.Axis> axisProperty",
                "block, \"axis\", Direction.Axis.class",
                "state.get(axisProperty)",
                "TrellisConnectionSemantics.typeFor(",
                "copyNamed(targetState, grown, \"axis\")",
                "copyNamed(targetState, grown, \"waterlogged\")"},
                "Trellis/wild-vine CE semantics drifted");
        addStale(GAME + "grape/TrellisBehavior.java", new String[] {
                "TRELLISES.contains(state.ownerId().toString())",
                "return state.owner().value().id().toString();",
                "extends WaterloggedBlockBehavior", "waterloggedProperty",
                "FluidStateProxy", "FluidsProxy",
                "LevelAccessorProxy.INSTANCE.scheduleTick$1(",
                "context.getClickedFace().axis()", "typeForPlacement(", "updateType("},
                "Trellis must not duplicate CE-owned axis/waterlogging");
        addStale(GAME + "block/BlockService.java", new String[] {
                "grapevineFor returned null", "onRightClickWithGrapevine: clicked",
                "Material.BONE_MEAL", "CustomBlockInteractEvent", "CustomBlockBreakEvent",
                "implements Listener", "onCustomBlockBreak", "interactVineTrellis",
                "damageTool(", "interactWildHead", "Material.HONEYCOMB", "WAX_ON",
                "WAX_OFF", "PlayerInteractEvent", "onRightClickWithGrapevine"},
                "BlockService must keep CE-owned interactions removed");
        addRequired(GAME + "grape/WildGrapevineBehavior.java", new String[] {
                "public InteractionResult useOnBlock", "player.swingHand(context.getHand())",
                "return InteractionResult.SUCCESS;",
                "Key.of(\"minecraft\", \"leaves\")", "if (!isAttachedToLeaves(args))",
                "return isAttachedToLeaves(args)", "LocationUtils.above(args[2])",
                "BlockStateUtils.isTag(attachedState, LEAVES)",
                "|| lifecycle().canSurvive(thisBlock, args)"}, "Wild vine must acknowledge use");
        addRequired(GAME + "grape/TrellisConnectionSemantics.java", new String[] {
                "xConnected || baseAxis.equals(\"x\")",
                "yConnected || baseAxis.equals(\"y\")",
                "zConnected || baseAxis.equals(\"z\")"},
                "Trellis connection reduction must preserve CE native axis");
        addRequired("src/paperTest/java/com/github/ysbbbbbb/kaleidoscopetavern/"
                + "paper/game/grape/TrellisConnectionSemanticsTest.java", new String[] {
                "verticalPlacementNeverCollapsesIntoAHorizontalShape"},
                "Trellis vertical placement regression test missing");
        addRequired(PAPER + "KaleidoscopeTavernPlugin.java", new String[] {
                "StateFurnitureBehavior.register()", "LifecycleFurnitureBehavior.register()",
                "PressingTubBlockBehavior.register()", "IncenseBlockBehavior.register()",
                "IncenseBlockBehavior.prewarmRuntime()", "TapBlockBehavior.register()",
                "StorageBlockBehavior.register()", "ChalkboardBlockBehavior.register()",
                "TickingFurnitureBehavior.register()", "TickingFurnitureBehavior.start(this)",
                "TickingFurnitureBehavior.stop()", "StorageVisualFurnitureBehavior.register()",
                "StorageInteractionFurnitureBehavior.register()",
                "StationVisualFurnitureBehavior.register()",
                "StationInteractionFurnitureBehavior.register()",
                "BoardTextFurnitureBehavior.register()", "AnimatedItemFurnitureBehavior.register()",
                "items.warmCocktailFurnitureSerialization()", "items.clearVisualCache()",
                "BottleFurnitureBehavior.register()", "bottleFurniture.start()",
                "bottleFurniture.stop()", "GrapevineItemBehavior.register()", "blocks.start()",
                "blocks.stop()", "new StationRecipeLoader(", "new StationRecipeRegistry(",
                "var nextRecipes = stationRecipeLoader.load()",
                "stationRecipes.replace(nextRecipes)", "继续使用上一份有效配置",
                "new TapAppearanceConfigLoader(", "new TapAppearanceRegistry(",
                "tapAppearances.replace(nextTapAppearances)"},
                "Plugin startup wiring drifted");
        addStale(PAPER + "KaleidoscopeTavernPlugin.java", new String[] {
                "registerEvents(items, this)", "SofaBlockBehavior", "SofaBlockShape",
                "RedstoneFurnitureBehavior", "registerEvents(blocks, this)"},
                "Plugin must not keep removed wiring");
        addRequired(PAPER + "item/ItemService.java", new String[] {
                "warmCocktailFurnitureSerialization()", "catalog.cocktailItems()",
                "BukkitAdaptor.adapt(built.get()).copyWithCount(1).toBytes()",
                "private final Map<String, Optional<ItemStack>> visualItemPrototypes",
                "public Optional<ItemStack> buildVisual(String id)",
                "prototype = buildBase(id, null)", "Optional.of(prototype.get().clone())",
                "public void clearVisualCache()"}, "ItemService warmup/prototype contract");
        addStale(PAPER + "item/ItemService.java", new String[] {
                "repairLegacyDrinkMetadata", "refreshInventory(", "InventoryOpenEvent",
                "EntityPickupItemEvent", "knownEffectKeys"}, "ItemService legacy migration removed");
        addStale(PAPER + "item/DrinkLore.java", new String[] {"isManagedOrLegacy"},
                "DrinkLore legacy lore removed");
        addStale(PAPER + "item/ManagedLoreSemantics.java", new String[] {"isLegacyShakerLine"},
                "ManagedLore legacy shaker line removed");
        addStale(GAME + "effect/EffectService.java", new String[] {"items.refreshInventory"},
                "EffectService refresh bridge removed");
        addRequired(PAPER + "game/drink/SneakPlaceDrinkItemBehavior.java", new String[] {
                "if (!context.isSecondaryUseActive())", "return InteractionResult.PASS;",
                "return furnitureItem.place(context);"}, "Vessel CE behavior delegation");
        addStale(PAPER + "game/drink/SneakPlaceDrinkItemBehavior.java", new String[] {
                "new BlockHitResult", "Direction.UP", "targetPos", "atBottomCenterOf",
                "sync_active_use", "startUsingItem(", "EquipmentSlot"},
                "CE must own vessel surface/active-use state");
        addRequired(PAPER + "game/drink/SneakPlaceVanillaBottleItemBehavior.java", new String[] {
                "!context.isSecondaryUseActive()",
                "TheBrewingProjectCompat.isBrew(stack)", "PotionType.WATER",
                "placement.furnitureItem().place(context)",
                "return InteractionResult.SUCCESS_AND_CANCEL;"},
                "Vanilla bottle CE routing missing");
        addStale(GAME + "drink/BottlePlacementService.java", new String[] {
                "PlayerInteractEvent", "onPlaceVanillaBottle", "player.swingHand(",
                "FurnitureAttemptPlaceEvent", "FurniturePlaceEvent",
                "new Placement(customId"}, "BottlePlacementService must stay CE-owned");
        addStale(GAME + "drink/BottleFurnitureService.java", new String[] {
                "onPlace(FurniturePlaceEvent", "FurnitureInteractEvent", "public void onInteract(",
                "List<ItemStack> stored = storedItems(event.furniture());"},
                "Bottle furniture must not duplicate CE state");
        addRequired(GAME + "drink/BottleFurnitureService.java", new String[] {
                "BottleFurnitureBehavior.bind(interactionHandler)",
                "BottleFurnitureBehavior.unbind(interactionHandler)",
                "private InteractionResult interact(", "context.getHand()",
                "InteractionResult.SUCCESS_AND_CANCEL",
                "Item source = furniture.sourceItem()", "if (maxBottleCount(furniture) > 1)",
                "BottleFurnitureSemantics.needsExpandedItemState(stored.size())",
                "? stored : List.of()", "new FurnitureState(event.furniture())",
                ".items(\"bottle_items\")"}, "Bottle CE controller lifecycle missing");
        addRequired(GAME + "drink/BottleFurnitureBehavior.java", new String[] {
                "extends FurnitureBehaviorTemplate",
                "FurnitureBehaviors.register(Key.of(TYPE", "public InteractionResult useOnFurniture",
                "current.interact(bukkitFurniture, context)"}, "Bottle CE behavior incomplete");
        addStale(GAME + "drink/BottleFurnitureBehavior.java", new String[] {
                "org.bukkit.event", "PersistentDataType", "NamespacedKey",
                "getNearbyEntities(", "runTaskTimer"}, "Bottle behavior must stay adapter-only");
        addRequired(GAME + "station/StationService.java", new String[] {
                "Item source = furniture.sourceItem()", "items.shakerIngredients(shaker)",
                "items.shakerResult(shaker)", "private void updateShakerSource",
                "furniture.setSourceItem(BukkitAdaptor.adapt(shaker))", "furniture.setUnsaved()",
                "player.hasActiveItem()", "player.getActiveItemHand() == usedHand",
                "player.clearActiveItem()"}, "Station sourceItem lifecycle missing");
        addStale(GAME + "station/StationService.java", new String[] {
                "state.items(\"shaker_ingredients\"", "state.item(\"shaker_result\"",
                "loadPortableShaker", "tickBarrels", "loadedBarrels", "barrelTask",
                "barrelTickCounter", "bootstrapBarrels"}, "Station must stay CE-sourceItem owned");
        addRequired(GAME + "storage/DisplayStorageService.java", new String[] {
                "items.buildVisual(prefix + storedId.substring(PREFIX.length()))",
                "private static DisplayItemFurnitureController displayController",
                "furniture.config.behaviors().size()",
                "DisplayItemFurnitureController.class, index", "ordinal++ == slot"},
                "Storage display controller resolution missing");
        addStale(GAME + "storage/DisplayStorageService.java", new String[] {
                "controller.get(DisplayItemFurnitureController.class, slot)"},
                "Storage must index controllers by behavior ordinal");
        addStale(GAME + "decor/AmbientFurnitureService.java", new String[] {
                "runTaskTimer", "Bukkit.getEntity", "CraftEngineFurniture",
                "FurniturePlaceEvent", "FurnitureBreakEvent", "EntitiesLoadEvent",
                "tracked", "bootstrap", "incense_active", "interactIncense",
                "tickIncense", "Channel.INCENSE"}, "Ambient/incense lifecycle must stay CE-owned");
        addStale(GAME + "furniture/FurnitureState.java", new String[] {
                "PersistentDataContainer", "PersistentDataType", "NamespacedKey", "JavaPlugin",
                "List<String> strings(String name)"}, "FurnitureState must use CE NBT");
        addRequired(GAME + "furniture/FurnitureState.java", new String[] {
                "UUID uuid(String name)", "List<UUID> uuids(String name)", "NBT.createUUID(value)"},
                "FurnitureState typed UUID helpers missing");
        addRequired(GAME + "furniture/StateFurnitureBehavior.java", new String[] {
                "loadCustomData(CompoundTag data)", "saveCustomData(CompoundTag data)",
                "bukkitFurniture.setUnsaved()"}, "state_furniture persistence missing");
        addRequired(GAME + "pressing/PressingTubBlockBehavior.java", new String[] {
                "implements EntityBlock, PrioritizedFallOnHandler",
                "BlockBehaviors.register(TYPE, PressingTubBlockBehavior::new)",
                "Controller.prewarm()",
                "implements DifferentialItemDisplayElement.VisualProvider",
                "new DifferentialItemDisplayElement(", "MAX_ELEMENTS, VIEW_RANGE)",
                "public List<DisplayVisual> visuals(int limit)",
                "super.fallOn(thisBlock, args)",
                "LivingEntityProxy.CLASS.isInstance(args[3])",
                "((Number) args[4]).doubleValue()",
                "BlockBehaviorFactory.getProperty(", "block, \"facing\", Direction.class",
                "public static void bind(Handler value)", "public static void unbind(Handler value)",
                "public Object playerWillDestroy(", "public void onRemove()",
                "suppressContentDrops()"}, "pressing_tub Java behavior contract");
        addStale(GAME + "pressing/PressingTubBlockBehavior.java", new String[] {
                "updateStateForPlacement(", "BlockPlaceContext", "waterloggedProperty",
                "clickedFace.axis()", "limit -> {"}, "pressing_tub must stay CE-configured");
        addRequired(GAME + "visual/DifferentialItemDisplayElement.java", new String[] {
                "public static void prewarm()", "EmptyVisualProvider.INSTANCE",
                "new DifferentialItemDisplayElement("}, "Differential display prewarm missing");
        addRequired(GAME + "furniture/LifecycleFurnitureBehavior.java", new String[] {
                "public void onPlace(Player player)", "public void onLoad()",
                "public void preRemove(Player player)", "public void postRemove(Player player)",
                "public void onUnload()",
                "default void onReady(BukkitFurniture furniture, ReadyReason reason, Player placingPlayer)",
                "ready(ReadyReason.PLACE, player)",
                "handler.onReady(bukkitFurniture, readyReason, placingPlayer)",
                "currentHandler.onUnavailable(bukkitFurniture, removed)",
                "public static List<BukkitFurniture> nearby(", "public static boolean hasNearby(",
                "public static Optional<BukkitFurniture> atBlock(", "FurnitureSpatialSemantics.minimumColumn(",
                "FurnitureSpatialSemantics.insideBox("}, "lifecycle_furniture routing missing");
        addStale(GAME + "furniture/LifecycleFurnitureBehavior.java", new String[] {
                "furniture.isValid()", "ConcurrentHashMap", "ConcurrentMap"},
                "lifecycle spatial queries must stay lean");
        addRequired(GAME + "board/BoardTextFurnitureBehavior.java", new String[] {
                "implements FurnitureElement", "EntityTypesProxy.TEXT_DISPLAY",
                "public static void refresh(BukkitFurniture furniture)",
                "private List<PreparedVisual> cachedVisuals = List.of()",
                "ComponentUtils.jsonToMinecraft(", "GsonComponentSerializer.gson().serialize(visual.text())",
                "List<PreparedVisual> current = controller.visuals()",
                "DisplayData.TextDisplayData.Text.addEntityData",
                "DisplayData.TextDisplayData.LineWidth",
                "DisplayData.TextDisplayData.BackgroundColor",
                "ClientboundAddEntityPacketProxy.INSTANCE.newInstance",
                "ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance",
                "player.sendPackets", "public void gatherElements",
                "public static void bindInteraction(", "public static void unbindInteraction("},
                "board_text CE packet element contract");
        addRequired(PAPER + "catalog/ContentCatalog.java", new String[] {
                "barrelByIngredients.get(fluid)", "barrelPartialAnyFluid.contains(key)",
                "shakerByIngredients.get(IngredientKey.of(ingredients))",
                "shakerPartial.contains(IngredientKey.of(proposed))"},
                "ContentCatalog precomputed indexes missing");
        addStale(PAPER + "catalog/ContentCatalog.java", new String[] {
                "barrelRecipes.stream()", "shakerRecipes.stream()"},
                "ContentCatalog must not linearly scan");
        addRequired(PAPER + "recipe/StationRecipeRegistry.java", new String[] {
                "snapshot.barrelByIngredients().get(fluid)",
                "snapshot.shakerByIngredients().get(IngredientKey.of(ingredients))",
                "snapshot.shakerPartial().contains(IngredientKey.of(proposed))",
                "snapshot = Snapshot.create(content",
                "Collections.unmodifiableMap(shakerIngredients)"},
                "StationRecipeRegistry immutable indexes missing");
        addStale(PAPER + "recipe/StationRecipeRegistry.java", new String[] {
                "private boolean matchNext", "private boolean matches"},
                "Station recipe lookup must not backtrack");
        addRequired(PAPER + "recipe/StationRecipeParser.java", new String[] {
                "SparrowYaml.builder()", ".setAllowDuplicateKeys(false)",
                "requiredInt(document, \"config-version\"",
                "optionalRgb(row, \"tap-color\"", "MAX_BARREL_INGREDIENTS = 4",
                "MAX_SHAKER_INGREDIENTS = 3"}, "StationRecipeParser strictness missing");
        addRequired(GAME + "tap/TapAppearanceConfigLoader.java", new String[] {
                "SparrowYaml.builder()", ".setAllowDuplicateKeys(false)",
                "RESOURCE = \"visuals/tap.yml\"", "TapFlowAppearance.colored("},
                "TapAppearanceConfigLoader strict YAML missing");
    }

    private static void addRequired(String path, String[] tokens, String message) {
        RULES.add(new Rule(path, tokens, Mode.REQUIRED_ALL, message));
    }

    private static void addStale(String path, String[] tokens, String message) {
        RULES.add(new Rule(path, tokens, Mode.STALE_NONE, message));
    }

    private static final Pattern FORBIDDEN_GAME_FILES = Pattern.compile("^[^/]+\\.java$");

    public void validate() throws IOException {
        Path gamePackage = projectRoot.resolve(GAME);
        List<String> ungrouped = new ArrayList<>();
        try (var stream = Files.list(gamePackage)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (Files.isRegularFile(path) && !name.equals("package-info.java")
                        && FORBIDDEN_GAME_FILES.matcher(name).matches()) {
                    ungrouped.add(name);
                }
            }
        }
        if (!ungrouped.isEmpty()) {
            throw new ValidationException("Gameplay classes must be grouped by feature module "
                    + "instead of being placed in the game root package: " + ungrouped);
        }
        for (Rule rule : RULES) {
            Path path = projectRoot.resolve(rule.path());
            if (!Files.isRegularFile(path)) {
                throw new ValidationException("Missing source for token rule: " + rule.path());
            }
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (source.startsWith("\uFEFF")) source = source.substring(1);
            switch (rule.mode()) {
                case REQUIRED_ALL -> {
                    for (String token : rule.tokens()) {
                        if (!source.contains(token)) {
                            throw new ValidationException(rule.message()
                                    + " (missing in " + rule.path() + ": " + token + ")");
                        }
                    }
                }
                case STALE_NONE -> {
                    for (String token : rule.tokens()) {
                        if (source.contains(token)) {
                            throw new ValidationException(rule.message()
                                    + " (stale in " + rule.path() + ": " + token + ")");
                        }
                    }
                }
            }
        }
        String pressingSource = Files.readString(projectRoot.resolve(
                GAME + "pressing/PressingTubBlockBehavior.java"), StandardCharsets.UTF_8);
        int fallStart = pressingSource.indexOf("public void fallOn(Object thisBlock, Object[] args) {");
        int fallEnd = pressingSource.indexOf("public InteractionResult useOnBlock(");
        if (fallStart >= 0 && fallEnd > fallStart) {
            String fallOnHotPath = pressingSource.substring(fallStart, fallEnd);
            for (String stale : new String[] {"getBukkitEntity", "PressingTubSemantics",
                    "hasPotentialBelow", "findBelow", "pressLandingTracker", "EntityMoveEvent",
                    "PlayerMoveEvent", "onFallDamage"}) {
                if (fallOnHotPath.contains(stale)) {
                    throw new ValidationException(
                            "pressing_tub fallOn must stay proxy-level without Bukkit entity "
                            + "or landing-index machinery; stale token: " + stale);
                }
            }
        }
        String pluginSource = Files.readString(projectRoot.resolve(
                PAPER + "KaleidoscopeTavernPlugin.java"), StandardCharsets.UTF_8);
        String itemBehaviorSource = Files.readString(projectRoot.resolve(
                PAPER + "game/drink/SneakPlaceDrinkItemBehavior.java"), StandardCharsets.UTF_8);
        String vanillaBottleSource = Files.readString(projectRoot.resolve(
                PAPER + "game/drink/SneakPlaceVanillaBottleItemBehavior.java"), StandardCharsets.UTF_8);
        String combinedVessel = pluginSource + itemBehaviorSource;
        for (String token : new String[] {"SneakPlaceDrinkItemBehavior.register()",
                "FurnitureItemBehavior.FACTORY.create"}) {
            if (!combinedVessel.contains(token)) {
                throw new ValidationException(
                        "Vessel placement behavior must delegate to native CE furniture placement; "
                        + "missing " + token);
            }
        }
        String combinedVanilla = pluginSource + vanillaBottleSource;
        for (String token : new String[] {"SneakPlaceVanillaBottleItemBehavior.register(this)",
                "FurnitureItemBehavior.FACTORY.create"}) {
            if (!combinedVanilla.contains(token)) {
                throw new ValidationException(
                        "Vanilla bottles must route through CE native furniture item behavior; "
                        + "missing " + token);
            }
        }
        StringBuilder allPaperJava = new StringBuilder();
        try (var stream = Files.walk(projectRoot.resolve("src/paper/java"))) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) text = text.substring(1);
                allPaperJava.append(text).append('\n');
            }
        }
        if (allPaperJava.toString().contains("new FurnitureState(plugin,")) {
            throw new ValidationException(
                    "FurnitureState construction must not retain the obsolete Bukkit PDC owner");
        }
    }
}
