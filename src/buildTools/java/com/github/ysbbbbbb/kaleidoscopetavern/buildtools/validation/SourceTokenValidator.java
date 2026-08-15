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

        addRequired(GAME + "board/BoardTextService.java", new String[] {
                "BoardTextFurnitureBehavior.bind(boardVisualHandler)",
                "BoardTextFurnitureBehavior.unbind(boardVisualHandler)",
                "BoardTextFurnitureBehavior.bindInteraction(boardInteractionHandler)",
                "BoardTextFurnitureBehavior.unbindInteraction(boardInteractionHandler)",
                "private InteractionResult interactBoard(",
                "public void onFurniturePlace(FurniturePlaceEvent event)",
                "context.getHand() != InteractionHand.MAIN_HAND",
                "InteractionResult.SUCCESS_AND_CANCEL",
                "private List<BoardTextFurnitureBehavior.Visual> boardVisuals",
                "BoardTextFurnitureBehavior.refresh(furniture)",
                "LifecycleFurnitureBehavior.Channel.BOARD, lifecycleHandler",
                "private final class EditSessionListener implements Listener",
                "registerEvents(editSessionListener, plugin)",
                "HandlerList.unregisterAll(editSessionListener)",
                "private void ensureEditSessionListener()",
                "private void stopEditSessionListenerIfIdle()",
                "private InteractionResult interactChalkboard(",
                "private List<ChalkboardBlockBehavior.Visual> chalkboardVisuals",
                "EditSession.chalkboard(",
                "private void cancelChalkboardEditors(",
                "matchesChalkboard(world, pos)",
                "onMove(PlayerMoveEvent event)", "validateEditDistance(event.getPlayer())"},
                "Board text edit lifecycle/tokens drifted");
        addStale(GAME + "board/BoardTextService.java", new String[] {
                "runTaskTimer",
                "tryMergeChalkboards(", "nearbyFurniture(",
                "chalkboardMergeOrigin(", "currentVariant().name() + \"_large\"",
                "org.bukkit.entity.TextDisplay", "PersistentDataType", "NamespacedKey",
                "board_owner", "board_line", "board_displays", "getNearbyEntities(",
                "removeDisplay(", "TextDisplay.TextAlignment", "FurnitureInteractEvent",
                "public void onFurnitureInteract(", "BoardTextFurnitureBehavior.bindPlacement(",
                "BoardTextFurnitureBehavior.unbindPlacement("},
                "Board text must not recreate persistent Bukkit displays");
        addRequired(GAME + "board/ChalkboardBlockBehavior.java", new String[] {
                "extends BukkitBlockBehavior", "implements EntityBlock",
                "private void tryMerge(", "resetMergedData(world,",
                "private BlockPos rootPos(", "double_high_block behavior",
                "blockEntity.world.blockEntityChanged(blockEntity.pos)",
                "current.unavailable(this)", "new WeakHashMap<>()"},
                "Chalkboard must keep only its source-specific merge bridge");
        addStale(GAME + "board/ChalkboardBlockBehavior.java", new String[] {
                "extends WaterloggedBlockBehavior", "FluidStateProxy",
                "FluidsProxy", "LevelAccessorProxy.INSTANCE.scheduleTick$1(",
                "public Object updateShape("},
                "Chalkboard waterlogging must be CE-owned");
        addRequired(GAME + "block/BlockService.java", new String[] {
                "String planted = grapevineFor(soil);",
                "replacement, \"axis\", stringProperty(trellisState, \"axis\")",
                "withNamed(replacement, \"type\", stringProperty(trellisState, \"type\"))",
                "void plantGrapevineOnTrellis(",
                "useGrapevineOnBlock",
                "!\"single\".equals(stringProperty(trellisState, \"type\"))"},
                "BlockService grapevine planting evidence missing");
        addRequired("src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/game/grape/"
                + "GrapevineItemBehavior.java", new String[] {
                "TYPE = Key.of(\"kaleidoscope_tavern\", \"grapevine_item\")",
                "ItemBehaviors.register(TYPE", "current.useOnBlock(context)"},
                "CE grapevine item behavior evidence missing");
        addRequired(GAME + "effect/IncenseBlockBehavior.java", new String[] {
                "implements EntityBlock",
                "BlockBehaviors.register(TYPE, IncenseBlockBehavior::new)",
                "state.with(openProperty, powered).with(poweredProperty, powered)",
                "powered == state.get(poweredProperty)",
                "state.with(poweredProperty, powered)",
                "Controller.prewarm()",
                "implements BlockEntityTicker<Controller>",
                "return createTickerHelper(this)",
                "takeParticleDue()", "scheduleNextParticle(random)",
                "random.nextInt(PARTICLE_DELAY_BOUND)",
                "random.nextInt(3) == 0",
                "centerY + largeParticleYOffset + largeParticleYRange * 0.5",
                "LARGE_PARTICLE_HORIZONTAL_STDDEV",
                "largeParticleYRange * 0.5 * UNIFORM_HALF_RANGE_TO_GAUSSIAN_STDDEV",
                "takeDamageDue()", "damageDelay = DAMAGE_INTERVAL - 1",
                "world.getNearbyLivingEntities(center, 32.5)",
                "zombieVillager.setConversionTime(60)"},
                "CE incense behavior no longer preserves source tick semantics");
        addStale(GAME + "effect/IncenseBlockBehavior.java", new String[] {
                "PersistentDataContainer", "BukkitTask", "runTaskTimer",
                "public InteractionResult useOnBlock",
                "random.nextInt(49) == 0",
                "world.getGameTime() % 120L",
                "center.clone().add(",
                "for (int index = 0; index < 5; index++)",
                "random.nextDouble(-16.0, 16.0)",
                "Controller::tick"},
                "CE incense state/lifecycle must not be duplicated");
        addStale(GAME + "furniture/FurnitureState.java", new String[] {
                "PersistentDataContainer", "PersistentDataType", "NamespacedKey", "JavaPlugin",
                "List<String> strings(String name)"},
                "FurnitureState must stay CE CompoundTag data");
        addStale(GAME + "board/BoardTextFurnitureBehavior.java", new String[] {
                "org.bukkit.entity.TextDisplay", "PersistentDataType", "World.spawn",
                "ComponentUtils.adventureToMinecraft", "PlacementHandler",
                "bindPlacement(", "placementHandler"},
                "board_text must never create persistent Bukkit entities");
        addRequired(GAME + "decor/BarStoolVisualService.java", new String[] {
                "Channel.BAR_STOOL, mount, 1.5, 1.5",
                "private final class SeatEventListener implements Listener",
                "registerEvents(seatEventListener, plugin)",
                "HandlerList.unregisterAll(seatEventListener)",
                "private void ensureSeatEventsRegistered()",
                "private void stopSeatEventsIfIdle()",
                "loaded.put(furniture.uuid(), furniture);",
                "ensureSeatEventsRegistered();",
                "loaded.remove(owner, furniture);",
                "stopSeatEventsIfIdle();",
                "Map<UUID, Occupancy> occupied",
                "new Occupancy(owner, rider)",
                "LivingEntity rider = occupancy.rider()",
                "private record Occupancy(UUID owner, LivingEntity rider)",
                "private void releaseOccupancy(UUID owner)",
                "releaseOccupancy(owner);",
                "public void onUnavailable(BukkitFurniture furniture",
                "AnimatedItemFurnitureBehavior.bind(",
                "AnimatedItemFurnitureBehavior.unbind("},
                "Bar-stool seat/lifecycle contract missing");
        addStale(GAME + "decor/BarStoolVisualService.java", new String[] {
                "Bukkit.getEntity(entry.getKey())", "private void activate(UUID",
                "org.bukkit.entity.ItemDisplay", "PersistentDataType", "NamespacedKey",
                "getNearbyEntities(", "Bukkit.getEntity(owner)",
                "bar_stool_body_owner", "bar_stool_body_visual",
                "Bukkit.getWorlds()", "private void bootstrap(",
                "private void bootstrapDisplays("},
                "Bar-stool rotation must not resolve riders through Bukkit lookups");
        addRequired(GAME + "decor/ConnectedBlockBehavior.java", new String[] {
                "extends BukkitBlockBehavior",
                "BlockBehaviors.register(TYPE, ConnectedBlockBehavior::new)",
                "CornerConfig.parse(section.getNonNullSection(\"topology\"))",
                "LinearConfig.parse(section.getNonNullSection(\"topology\"))",
                "TableConfig.parse(section.getNonNullSection(\"topology\"))",
                "section.getString(\"state_property\"",
                "section.getString(\"axis_property\"",
                "private ImmutableBlockState updateCorner(",
                "private ImmutableBlockState updateLinear(",
                "private ImmutableBlockState updateTable(",
                "BlockGetterProxy.INSTANCE.getBlockState(",
                "case LINEAR -> updateLinear",
                "LinearConfig.parse(", "linear.output(left, right)"},
                "ConnectedBlockBehavior must be one generic config-driven adapter");
        addStale(GAME + "decor/ConnectedBlockBehavior.java", new String[] {
                "PlayerMoveEvent", "EntityMoveEvent", "Bukkit.getScheduler",
                "runTask", "ConcurrentHashMap", "BukkitFurniture",
                "FurnitureElement", "SeatBlockEntity", "CraftEngineFurniture",
                "waterloggedProperty", "FluidStateProxy", "FluidsProxy",
                "getFluidState(", "SofaBlockIds",
                "\"single\"", "\"middle\"", "\"left_corner\"", "\"right_corner\"",
                "\"bar_counter\"", "\"bar_cabinet\"", "\"cellar_cabinet\"",
                "state.with(\n                    facingProperty"},
                "ConnectedBlockBehavior must not duplicate CE lifecycle/static config");
        addRequired(GAME + "grape/TrellisBlockShape.java", new String[] {
                "implements BlockShape", "definition.defaultState()",
                "getOptionalCustomBlockState(minecraftState)",
                "Property.formatValue(", "COLLISION_SHAPES.computeIfAbsent(",
                "SELECTION_SHAPES.computeIfAbsent(",
                "public Object getSupportShape"},
                "CE trellis delegates must resolve the current shared block state");
        addRequired(GAME + "furniture/AnimatedItemFurnitureBehavior.java", new String[] {
                "implements FurnitureElement", "EntityTypesProxy.ITEM_DISPLAY",
                "private static final Map<UUID, Controller> LOADED = new HashMap<>()",
                "private List<Visual> cachedVisuals = List.of()",
                "return controller.visuals()",
                "DisplayData.ItemDisplayData.ItemStack.addEntityData",
                "DisplayData.Translation.addEntityData",
                "DisplayData.LeftRotation.addEntityData",
                "EntityUtils.createUpdatePosPacket",
                "furniture.trackedBy()",
                "public static void updateTransforms",
                "public static void updatePosition"},
                "animated_item_furniture CE tracking contract missing");
        addStale(GAME + "furniture/AnimatedItemFurnitureBehavior.java", new String[] {
                "org.bukkit.entity.ItemDisplay", "PersistentDataType", "World.spawn",
                "ConcurrentHashMap", "ConcurrentMap", "synchronized (HANDLERS)"},
                "animated_item_furniture must never create persistent entities");

        addRequired(GAME + "shaker/ShakerVisualService.java", new String[] {
                "LifecycleFurnitureBehavior.Channel.SHAKER, lifecycleHandler",
                "AnimatedItemFurnitureBehavior.bind(", "AnimatedItemFurnitureBehavior.unbind(",
                "new ShakerIngredientHudController(",
                "ingredientHud.start()", "ingredientHud.stop()",
                "ingredientHud.suppress(player)", "ingredientHud.resume(player)",
                "ingredientHud.furnitureAvailable(furniture)",
                "ingredientHud.furnitureUnavailable(owner)",
                "ingredientHud.furnitureChanged(furniture)",
                "ingredientHudSubtitles",
                "items.hasShakerIngredients(", "items.shakerIngredients(shaker)",
                "ShakerSemantics.ingredientColor(",
                "ShakerHudSemantics.progressSubtitle(ticks)",
                "ShakerHudSemantics.ingredientSubtitle(colors)"},
                "Shaker visual/HUD CE contract missing");
        addStale(GAME + "shaker/ShakerVisualService.java", new String[] {
                "implements Listener", "org.bukkit.entity.ItemDisplay", "PersistentDataType",
                "NamespacedKey", "getNearbyEntities(", "Bukkit.getEntity(owner)",
                "shaker_visual_owner", "shaker_visual_role", "shaker_base_visual",
                "shaker_lid_visual", "getTargetEntity(", "tickIngredientHud(",
                "ingredientHudTask", "beginPoll()", "endPoll()", "MAX_REUSE_TICKS",
                "PlayerMoveEvent", "runTaskLater(", "flushDirty",
                "subtitleProvider.apply(furniture)",
                "Bukkit.getWorlds()", "private void bootstrap(",
                "private void bootstrapDisplays("},
                "Shaker visuals/HUD must stay CE-driven without Bukkit helpers");
        addRequired(GAME + "shaker/ShakerHudSemantics.java", new String[] {
                "FONT_KEY = \"kaleidoscope_tavern:shaker_hud\"",
                "BAR_GLYPH = '\\uE400'", "POINTER_GLYPH = '\\uE401'",
                "INGREDIENT_GLYPH = '\\uE402'",
                "BAR_GLYPH_ADVANCE_PIXELS = 184", "BAR_ADVANCE_PIXELS = 182",
                "Math.round(Math.max(0, ticks) * 1.5F)",
                "static Component ingredientSubtitle(List<Integer> colors)"},
                "Shaker HUD must retain the archived overlay geometry");
        addRequired(GAME + "shaker/ShakerHudTargetResolver.java", new String[] {
                "LifecycleFurnitureBehavior.hasNearby(",
                "CraftEngineFurniture.rayTrace(player, TARGET_RANGE)"},
                "Shaker HUD targeting must prefilter through the lifecycle index");
        addRequired(GAME + "shaker/ShakerIngredientHudController.java", new String[] {
                "implements Listener", "PlayerTrackEntityEvent", "PlayerUntrackEntityEvent",
                "Predicate<BukkitFurniture> ingredientPresence",
                "if (!ingredientPresence.test(furniture))",
                "Optional<Component> subtitle = subtitleProvider.apply(target)",
                "furniture.bukkitEntity().getTrackedBy()",
                "ownersByPlayer", "playersByOwner",
                "TARGET_REFRESH_PERIOD_TICKS = 2L",
                "plugin, this::refreshTrackedPlayers",
                "if (targetTask == null && !ownersByPlayer.isEmpty())",
                "if (targetTask != null && ownersByPlayer.isEmpty())",
                "isTracked(playerId, target.uuid())",
                "HandlerList.unregisterAll(this)",
                "void furnitureAvailable(", "void furnitureUnavailable(",
                "void furnitureChanged(", "Duration.ofDays(1)"},
                "Shaker ingredient HUD interest-set contract missing");
        addRequired("src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/game/shaker/"
                + "ShakerItemBehavior.java", new String[] {
                "TYPE = Key.of(\"kaleidoscope_tavern\", \"shaker_item\")",
                "ItemBehaviors.register(TYPE",
                "InteractionResult use(World world",
                "InteractionResult useOnBlock(UseOnContext context)",
                "shouldUsePortableOnBlock(context.isSecondaryUseActive())",
                "current.use(player, hand)"},
                "Portable shaker item-use pipeline missing");
        addRequired(GAME + "station/StationService.java", new String[] {
                "ShakerItemBehavior.bind(shakerItemHandler)",
                "ShakerItemBehavior.unbind(shakerItemHandler)",
                "private InteractionResult usePortableShaker(",
                "ensurePortableShakerTask();", "stopPortableShakerTaskIfIdle();",
                "new PortableShakerUse(player, hand, 0)",
                "var iterator = portableShakers.entrySet().iterator()",
                "Player player = use.player()",
                "shakerVisuals.beginMix(player)",
                "shakerVisuals.updateMix(player, ticks)",
                "shakerVisuals.endMix(player)",
                "player.swingHand(use.hand())",
                "PORTABLE_SHAKER_SWING_INTERVAL_TICKS",
                "private record PortableShakerUse(Player player, EquipmentSlot hand, int ticks)",
                "StationInteractionFurnitureBehavior.bind(stationInteractionHandler)",
                "StationInteractionFurnitureBehavior.unbind(stationInteractionHandler)",
                "private InteractionResult interactStation(",
                "context.getHand() != InteractionHand.MAIN_HAND",
                "Vec3d click = context.getClickLocation()",
                "InteractionResult.SUCCESS_AND_CANCEL",
                "StationVisualFurnitureBehavior.bind(stationVisualHandler)",
                "StationVisualFurnitureBehavior.unbind(stationVisualHandler)",
                "StationVisualFurnitureBehavior.refresh(furniture)",
                "public boolean shouldSchedule(BukkitFurniture furniture)",
                "return shouldTickBarrel(furniture)",
                "TickingFurnitureBehavior.refreshSchedule(",
                "private boolean shouldTickBarrel(",
                "BarrelSemantics.needsTick(false,",
                "LifecycleFurnitureBehavior.Channel.BARREL, center, 3.0, 3.0",
                "open ? \"ground\" : \"ground_closed\"",
                "currentVariant().name().equals(\"ground\")",
                "TapSemantics.shouldDelegateBarrelTapPlacement(",
                "context.isSecondaryUseActive()",
                "context.getItem().id().toString()",
                "placeHeldTapBlockWithCraftEngine(furniture, context)",
                "behavior.getFirst(BlockItem.class)",
                "origin.getBlockX() + x * 2",
                "origin.getBlockY() + 1",
                "origin.getBlockZ() + z * 2",
                "placementBehavior.useOnBlock(new UseOnContext("},
                "StationService bridge contract missing");
        addStale(GAME + "station/StationService.java", new String[] {
                "onUsePortableShaker(PlayerInteractEvent",
                "new ArrayList<>(portableShakers.keySet())",
                "Bukkit.getPlayer(playerId)",
                "bootstrapPressVisuals", "onEntitiesLoad(EntitiesLoadEvent event)",
                "pressingTubBelow", "getNearbyEntities(feet",
                "PressLandingTracker", "PressingTubFurnitureBehavior",
                "pressLandingTracker", "pressLandingListener", "onFallDamage",
                "EntityMoveEvent", "PlayerMoveEvent", "fallingCleanupTask",
                "ensureFallingCleanupTask", "stopFallingCleanupTaskIfIdle",
                "hasPotentialBelow", "hasGroundTubInWorld", "occupiesBlock(",
                "bindAvailability", "unbindAvailability", "registerEvents(",
                "HandlerList.unregisterAll",
                "FurnitureInteractEvent", "public void onFurnitureInteract(",
                "FurniturePlaceEvent", "public void onFurniturePlace(",
                "stationPlacementHandler", "private void onStationPlaced(",
                "StationInteractionFurnitureBehavior.bindPlacement(",
                "StationInteractionFurnitureBehavior.unbindPlacement(",
                "org.bukkit.entity.ItemDisplay", "PersistentDataType",
                "press_visual_owner", "press_visual_role", "press_visual_index",
                "barrel_visual_owner", "barrel_visual_role", "barrel_visual_index",
                "press_item_visuals", "press_fluid_visual",
                "barrel_item_visuals", "barrel_fluid_visual",
                "findFurnitureAtBlock"},
                "StationService must stay CE-owned");
        addRequired(GAME + "pressing/PressingTubService.java", new String[] {
                "implements PressingTubBlockBehavior.Handler",
                "WALL_FURNITURE_ID",
                "PressingTubBlockBehavior.bind(this)",
                "PressingTubBlockBehavior.unbind(this)",
                "List<DisplayVisual> furnitureVisuals(",
                "InteractionResult interactFurniture(",
                "Optional<ItemStack> furnitureIngredientDrop(",
                "private InteractionResult interactPress(",
                "private static final class FurnitureTub implements TubAccess",
                "PRESS_MIN_FALL_DISTANCE = 0.5",
                "PlayerProxy.CLASS.isInstance(nmsEntity)",
                "BukkitCraftEngine.instance().antiGriefProvider()",
                "GameRule.MOB_GRIEFING",
                "ejectInvalidPressContents(tub"},
                "PressingTubService press/gameplay contract missing");
        addStale(GAME + "pressing/PressingTubService.java", new String[] {
                "EntityMoveEvent", "PlayerMoveEvent", "PressLandingTracker",
                "PressingTubLandingIndex", "hasPotentialBelow", "onFallDamage"},
                "PressingTubService must stay free of landing-index machinery");
        addRequired(GAME + "pressing/PressingTubVisualFactory.java", new String[] {
                "static double[] tiltDisplay(", "static Quaternionf tiltRotation(",
                "displayYaw = 0",
                "rotation = tiltRotation(facing, yRotation, zRotation)",
                "case NORTH -> 0", "case EAST -> 90",
                "case SOUTH -> -180", "case WEST -> -90",
                "DisplayVisual.of(",
                "stableRandom(seed, index, 4) * count / 10.0F"},
                "PressingTubVisualFactory wall transform or count-coupled yaw missing");
        addStale(GAME + "pressing/PressingTubVisualFactory.java", new String[] {
                "facingYaw(", "stableRandom(seed, index, 4) * 6.4F"},
                "Tilted contents must not split source facing into entity yaw, and yaw must keep Forge count/10F coupling");
        addRequired(GAME + "station/StationInteractionFurnitureBehavior.java", new String[] {
                "extends FurnitureBehaviorTemplate",
                "FurnitureBehaviors.register(Key.of(TYPE",
                "public InteractionResult useOnFurniture(",
                "current.interact(bukkitFurniture, context)"},
                "Station CE interaction adapter incomplete");
        addStale(GAME + "station/StationInteractionFurnitureBehavior.java", new String[] {
                "org.bukkit.event", "PersistentDataType", "NamespacedKey",
                "getNearbyEntities(", "runTaskTimer", "PlacementHandler",
                "bindPlacement(", "onPlace("},
                "Station CE interaction adapter must not own Paper polling/PDC");
        addRequired(GAME + "station/StationVisualFurnitureBehavior.java", new String[] {
                "implements FurnitureElement",
                "public static void refresh(BukkitFurniture furniture)",
                "private VisualSnapshot currentSnapshot",
                "currentHandler.visuals(bukkitFurniture, maxElements)",
                "VisualSnapshot current = controller.currentSnapshot()",
                "DisplayData.ItemDisplayData.ItemStack.addEntityData",
                "DisplayData.ItemDisplayData.ItemTransform.addEntityData(",
                "DisplayData.Scale.addEntityData(",
                "DisplayData.LeftRotation.addEntityData(",
                "ClientboundAddEntityPacketProxy.INSTANCE.newInstance",
                "ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance",
                "player.sendPackets", "public void gatherElements"},
                "station_visual CE packet element missing");
        addStale(GAME + "station/StationVisualFurnitureBehavior.java", new String[] {
                "org.bukkit.entity.ItemDisplay", "PersistentDataType", "World.spawn",
                "ItemTransform.addEntityDataIfNotDefaultValue",
                "Scale.addEntityDataIfNotDefaultValue",
                "LeftRotation.addEntityDataIfNotDefaultValue"},
                "station_visual must write transform metadata unconditionally");
        addStale(GAME + "tap/TapService.java", new String[] {
                "BukkitRunnable", "BukkitTask", "running",
                "RedstoneFurnitureBehavior", "TapGeometry", "geometry(tap)"},
                "TapService must remain free of scheduler/furniture-redstone state");
        addRequired(GAME + "tap/TapService.java", new String[] {
                "LifecycleFurnitureBehavior.Channel.TAP_BOTTLE, block",
                "LifecycleFurnitureBehavior.Channel.BARREL,",
                "center, 3.25, 3.25",
                "private TapPlan resolve(Block tapBlock, BlockFace facing",
                "tapBlock.getRelative(facing.getOppositeFace())",
                "tapBlock.getRelative(BlockFace.DOWN)",
                "findConnectedBarrel(tapBlock, facing)",
                "stations.tapOutputColor(barrel)",
                "appearances.appearance(DirectOutput.DRAGON_BREATH)",
                "appearances.appearance(DirectOutput.WATERMELON)",
                "TapBlockBehavior.bind(this)", "TapBlockBehavior.unbind(this)"},
                "TapService business-only block handler contract missing");
        addRequired(GAME + "tap/TapSemantics.java", new String[] {
                "secondaryUse && TAP_ITEM.equals(heldItemId)",
                "isBarrelConnection"},
                "Tap semantics secondary-use bypass missing");
        addRequired(GAME + "tap/TapBlockBehavior.java", new String[] {
                "extends BukkitBlockBehavior", "implements EntityBlock",
                "BlockBehaviors.register(TYPE, TapBlockBehavior::new)",
                "context.getHand() != InteractionHand.MAIN_HAND",
                "clickedFace.axis().isHorizontal()",
                "SignalGetterProxy.INSTANCE.hasNeighborSignal(level, minecraftPos)",
                "LocationUtils.above(minecraftPos)",
                "powered && !triggered", "!powered && triggered",
                "TAKE_TICKS = 30", "TAKE_PARTICLE_TICKS = 5",
                "EMPTY_OPEN_TICKS = 6", "DRIP_LIFETIME_TICKS = 18",
                "implements BlockEntityTicker<Controller>",
                "return createTickerHelper(this)",
                "private boolean open;",
                "this.open = blockEntity.blockState.get(behavior.openProperty)",
                "public void preBlockStateChange(ImmutableBlockState newState)",
                "behavior.openProperty.name()",
                "state.getProperty(openProperty.name())",
                "if (!open)", "particles.emit(bukkitWorld, drip, ticks)",
                "current.finish("},
                "TapBlockBehavior source-equivalent state contract missing");
        addStale(GAME + "tap/TapBlockBehavior.java", new String[] {
                "extends WaterloggedBlockBehavior", "waterloggedProperty",
                "FluidStateProxy", "FluidsProxy",
                "LevelAccessorProxy.INSTANCE.scheduleTick$1(",
                "public Object updateShape(",
                "if (!state.get(behavior.openProperty))",
                "BukkitTask", "runTaskTimer", "PersistentDataContainer",
                "BukkitFurniture", "FurnitureInteractEvent", "Controller::tick"},
                "Tap block must not duplicate CE-owned state");
        addRequired(GAME + "tap/TapParticleEmitter.java", new String[] {
                "case WATER -> emitNative(", "case LAVA -> emitNative(",
                "case HONEY -> emitNative(", "case OBSIDIAN_TEAR -> emitNative(",
                "case COLOR -> emitColored(",
                "Color.fromRGB(appearance.rgb())",
                "Particle.DRIPPING_OBSIDIAN_TEAR",
                "Particle.FALLING_OBSIDIAN_TEAR",
                "COLORED_DROP_INTERVAL_TICKS = 2",
                "new Particle.Trail(", "Particle.TRAIL"},
                "Tap particle adapter must preserve native fluids");
        addStale(GAME + "tap/TapParticleEmitter.java", new String[] {
                "Particle.DUST", "new Particle.DustOptions"},
                "Configured tap colors must use moving liquid beads");
        addRequired(GAME + "storage/StorageBlockBehavior.java", new String[] {
                "implements EntityBlock",
                "BlockBehaviors.register(TYPE, StorageBlockBehavior::new)",
                "StorageBlockConfig.parse(section)",
                "config.selector().select(", "config.interaction()",
                "config.orientation(facing)",
                "controller.config().slots().get(slot)",
                "config.launch()", "config.particle()",
                "public void neighborChanged",
                "SignalGetterProxy.INSTANCE.hasNeighborSignal(level, minecraftPos)",
                "state.with(poweredProperty, powered)",
                "private final Item[] items",
                "private int occupiedSlots",
                "occupiedSlots++", "occupiedSlots--",
                "return occupiedSlots != 0",
                "saveCustomData(CompoundTag tag)",
                "loadCustomData(CompoundTag tag)",
                "blockEntity.world.blockEntityChanged(blockEntity.pos)",
                "implements BlockEntityElement",
                "ClientboundAddEntityPacketProxy.INSTANCE.newInstance",
                "implements BlockEntityTicker<Controller>",
                "createTickerHelper(this)",
                "private void tickParticle("},
                "CE storage generic slot engine missing");
        addStale(GAME + "storage/StorageBlockBehavior.java", new String[] {
                "Controller::tickParticle", "Arrays.stream(items)",
                "BukkitTask", "runTaskTimer", "PersistentDataContainer",
                "BukkitFurniture", "ConcurrentHashMap", "BlockRedstoneEvent",
                "StorageSemantics.Kind", "case BAR_CABINET", "case CELLAR_CABINET",
                "case TILTED_RACK", "case CIRCULAR_RACK", "case HOLDER",
                "\"bar_cabinet\"", "\"cellar_cabinet\"", "\"tilted_rack\"",
                "\"circular_rack\"", "\"holder\""},
                "Storage family rules must stay in CE configuration");
        addRequired(GAME + "storage/StorageBlockConfig.java", new String[] {
                "record Orientation(", "record SlotVisual(", "record Selector(",
                "record Interaction(", "record Launch(", "record ParticleEffect(",
                "positionYaw", "modelYaw", "allowedItems", "blockedItems",
                "exclusiveItems", "refreshProperties"},
                "Storage family config parsing missing");
        addStale(GAME + "storage/StorageBlockConfig.java", new String[] {
                "StorageSemantics.Kind", "case BAR_CABINET", "case CELLAR_CABINET",
                "case TILTED_RACK", "case CIRCULAR_RACK", "case HOLDER",
                "\"bar_cabinet\"", "\"cellar_cabinet\"", "\"tilted_rack\"",
                "\"circular_rack\"", "\"holder\""},
                "Active storage family rules must stay in CE configuration");
        addRequired(GAME + "furniture/TickingScheduler.java", new String[] {
                "PriorityQueue<DueBucket>",
                "Map<Long, DueBucket> bucketsByTick",
                "enqueueLocked", "dispatchAction", "dispatchDue",
                "scheduleWakeLocked", "finishRunIfCurrent",
                "peekLiveBucketLocked", "pruneStaleHeadLocked",
                "maybeCompactQueueLocked",
                "liveQueuedRuns", "staleQueuedRuns",
                "postTickScheduleDecision", "LongSupplier", "WakeTarget"},
                "TickingScheduler pure due-time queue missing");
        addStale(GAME + "furniture/TickingScheduler.java", new String[] {
                "PriorityQueue<ScheduledRun>",
                "org.bukkit", "BukkitTask", "Bukkit.getScheduler",
                "runTaskLater", "net.momirealms.craftengine"},
                "TickingScheduler must stay decoupled from the server");
        addRequired(GAME + "furniture/TickingFurnitureBehavior.java", new String[] {
                "implements TickingScheduler.Host",
                "runTaskLater", "geometricDelay", "firstFutureDelay",
                "public void onLoad()", "public void onPlace(Player player)",
                "public void preRemove(Player player)",
                "public static void refreshSchedule(",
                "default boolean shouldSchedule(",
                "default Boolean tickAndScheduleDecision(",
                "handler.shouldSchedule(bukkitFurniture)",
                "postTickScheduleDecision(",
                "owner, action, delayTicks",
                "targetChannel.activeControllers.get(targetFurniture.uuid())"},
                "TickingFurnitureBehavior thin adapter contract missing");
        addStale(GAME + "furniture/TickingFurnitureBehavior.java", new String[] {
                "createFurnitureTicker", "runTaskTimer",
                "runTaskLater(owner, () ->", "bukkitFurniture.isValid()"},
                "Sparse furniture scheduling must stay wake-on-demand");
        addRequired(GAME + "storage/StorageInteractionFurnitureBehavior.java", new String[] {
                "extends FurnitureBehaviorTemplate",
                "FurnitureBehaviors.register(Key.of(TYPE",
                "public InteractionResult useOnFurniture(",
                "current.interact(bukkitFurniture, context)",
                "public void preRemove(Player player)",
                "current.onRemove(bukkitFurniture,",
                "player != null && !player.canInstabuild()"},
                "Storage CE interaction adapter incomplete");
        addStale(GAME + "storage/StorageInteractionFurnitureBehavior.java", new String[] {
                "org.bukkit.event", "PersistentDataType", "NamespacedKey",
                "getNearbyEntities(", "runTaskTimer"},
                "Storage CE interaction adapter must not own Paper polling/PDC");
        addRequired(GAME + "storage/StorageVisualFurnitureBehavior.java", new String[] {
                "implements FurnitureElement",
                "public static void refresh(BukkitFurniture furniture)",
                "private final Visual[] cachedVisuals",
                "private final boolean[] visualsDirty",
                "consumer.accept(new StorageVisualElement(this, slots))",
                "private final int[] entityIds",
                "new IntArrayList(entityIds)",
                "Visual visual = controller.visual(slot)",
                "packets.add(removePacket)",
                "DisplayData.ItemDisplayData.ItemStack.addEntityData",
                "DisplayData.ItemDisplayData.LeftRotation.addEntityDataIfNotDefaultValue",
                "new Quaternionf().rotateX((float) Math.toRadians(visual.xRot()))",
                "0, position.yRot()",
                "ClientboundAddEntityPacketProxy.INSTANCE.newInstance",
                "player.sendPackets", "public void gatherElements"},
                "storage_visual batched CE packet element missing");
        addStale(GAME + "storage/StorageVisualFurnitureBehavior.java", new String[] {
                "org.bukkit.entity.ItemDisplay", "PersistentDataType", "World.spawn",
                "class StorageItemElement"},
                "storage_visual must never create persistent Bukkit entities");
        addRequired(GAME + "storage/DisplayStorageService.java", new String[] {
                "StorageBlockBehavior.bind(storageBlockHandler)",
                "StorageBlockBehavior.unbind(storageBlockHandler)",
                "private void launchConfiguredItem(",
                "private Item storageBlockVisual(StorageBlockBehavior.Controller",
                "StorageInteractionFurnitureBehavior.bind(storageInteractionHandler)",
                "StorageInteractionFurnitureBehavior.unbind(storageInteractionHandler)",
                "private InteractionResult interact(",
                "public void onRemove(BukkitFurniture furniture, boolean dropItems)",
                "private void dropAndClearStorage(BukkitFurniture furniture, boolean dropItems)",
                "setControllerItem(furniture, slot, null, false)",
                "furniture.world().dropItemNaturally(furniture.position(), item)",
                "context.getHand() != InteractionHand.MAIN_HAND",
                "Vec3d click = context.getClickLocation()",
                "InteractionResult.SUCCESS_AND_CANCEL",
                "StorageVisualFurnitureBehavior.bind(storageVisualHandler)",
                "StorageVisualFurnitureBehavior.unbind(storageVisualHandler)",
                "StorageVisualFurnitureBehavior.refresh(furniture)",
                "private StorageVisualFurnitureBehavior.Visual storageVisual"},
                "DisplayStorageService CE controller contract missing");
        addStale(GAME + "storage/DisplayStorageService.java", new String[] {
                "FurnitureInteractEvent", "FurnitureBreakEvent", "public void onInteract(",
                "public void onBreak(", "implements Listener", "Bukkit.getScheduler()",
                "ItemDisplay", "cabinet_visual", "PersistentDataType",
                "getNearbyEntities", "new FurnitureState", "Channel.STORAGE, storageLifecycle",
                "furniture.setUnsaved()"},
                "DisplayStorageService must not retain Paper listeners/state");
        addRequired(GAME + "board/BoardTextFurnitureBehavior.java", new String[] {
                "public InteractionResult useOnFurniture(",
                "current.interact(bukkitFurniture, context)"},
                "board_text useOnFurniture delegation missing");
        addStale(GAME + "grape/HangingGrapeCropBehavior.java", new String[] {
                "RandomTickBlock", "randomTick(", "canRandomlyTick(",
                "addGrowthPoints", "GrapeGrowthSemantics", "GrapeSeasonGate"},
                "Hanging grape lifecycle must be configured in CustomCrops, not Java");
        addRequired(GAME + "grape/HangingGrapeCropBehavior.java", new String[] {
                "LocationUtils.above(args[2])",
                "BlockGetterProxy.INSTANCE.getBlockState(args[1], above)",
                "DirectionUtils.fromNMSDirection(args[updateShape$direction])",
                "args[updateShape$neighborState]",
                "BlockStateUtils.getNullableCustomBlockState(minecraftState)"},
                "Hanging grape support checks must stay on CraftEngine NMS helper path");
        addStale(GAME + "grape/HangingGrapeCropBehavior.java", new String[] {
                "CraftEngineBlocks.getCustomBlockState(above)",
                "getRelative(BlockFace.UP)"},
                "Hanging grape support checks must not fall back to Bukkit block lookups");
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
        String allPaper = allPaperJava.toString();
        for (String growthApi : new String[] {"addPointToCrop(", "addGrowthPoints(",
                "GrapeGrowthSemantics"}) {
            if (allPaper.contains(growthApi)) {
                throw new ValidationException(
                        "CustomCrops-configurable grape growth APIs must not be implemented in Java; "
                        + "found " + growthApi);
            }
        }
        for (String stale : new String[] {"pollRedstone", "pollIncenseRedstone", "tap_triggered",
                "storage_powered", "storage_power_initialized", "incense_powered",
                "incense_initialized", "barrel_initialized", "barrel_open"}) {
            if (allPaper.contains(stale)) {
                throw new ValidationException(
                        "CE furniture controllers own redstone/variant state throughout Paper; "
                        + stale + " must stay deleted");
            }
        }
        String itemSource = Files.readString(projectRoot.resolve(
                PAPER + "item/ItemService.java"), StandardCharsets.UTF_8);
        int visualStart = itemSource.indexOf("public Optional<ItemStack> buildVisual(String id)");
        int visualEnd = itemSource.indexOf("public void clearVisualCache()");
        if (visualStart >= 0 && visualEnd > visualStart
                && itemSource.substring(visualStart, visualEnd).contains("refreshLore(")) {
            throw new ValidationException(
                    "Static render-helper construction must not enter gameplay lore/PDC repair");
        }
        String boardTextSource = Files.readString(projectRoot.resolve(
                GAME + "board/BoardTextService.java"), StandardCharsets.UTF_8);
        int boardStart = boardTextSource.indexOf("public void start() {");
        int boardEnd = boardTextSource.indexOf("public void stop() {");
        if (boardStart >= 0 && boardEnd > boardStart
                && boardTextSource.substring(boardStart, boardEnd).contains("editSessionListener")) {
            throw new ValidationException(
                    "BoardTextService.start must not register idle edit-session listeners");
        }
        String stationSource = Files.readString(projectRoot.resolve(
                GAME + "station/StationService.java"), StandardCharsets.UTF_8);
        int stationStart = stationSource.indexOf("public void start() {");
        int stationEnd = stationSource.indexOf("public void stop() {");
        if (stationStart >= 0 && stationEnd > stationStart) {
            String startBody = stationSource.substring(stationStart, stationEnd);
            if (startBody.contains("portableShakerTask") || startBody.contains("fallingCleanupTask")) {
                throw new ValidationException(
                        "StationService.start must not schedule an idle every-tick task");
            }
        }
        String barStoolSource = Files.readString(projectRoot.resolve(
                GAME + "decor/BarStoolVisualService.java"), StandardCharsets.UTF_8);
        int unavailableStart = barStoolSource.indexOf("public void onUnavailable(BukkitFurniture furniture");
        if (unavailableStart >= 0) {
            String unavailableBody = barStoolSource.substring(unavailableStart);
            int braceEnd = unavailableBody.indexOf("}");
            if (braceEnd >= 0 && unavailableBody.substring(0, braceEnd).contains("occupied.values().removeIf")) {
                throw new ValidationException("onUnavailable must not drop bar-stool occupancy inline; "
                        + "it has to run releaseOccupancy so the body yaw is reset while the stool is still loaded");
            }
        }
        for (String token : new String[] {"SneakPlaceDrinkItemBehavior.register()",
                "FurnitureItemBehavior.FACTORY.create"}) {
            if (!(pluginSource + itemBehaviorSource).contains(token)) {
                throw new ValidationException(
                        "Vessel placement behavior must delegate to native CE furniture placement; "
                        + "missing " + token);
            }
        }
        String bottlePlacementSource = Files.readString(projectRoot.resolve(
                GAME + "drink/BottlePlacementService.java"), StandardCharsets.UTF_8);
        if (bottlePlacementSource.contains("bottle-placement.drinks")) {
            throw new ValidationException(
                    "Custom DrinkBlockItem/CocktailBlockItem placement must remain unconditional");
        }
        String pluginConfigText = Files.readString(projectRoot.resolve(
                "src/paper/resources/config.yml"), StandardCharsets.UTF_8);
        if (Pattern.compile("(?m)^\\s+drinks:\\s*").matcher(pluginConfigText).find()) {
            throw new ValidationException(
                    "Custom DrinkBlockItem/CocktailBlockItem placement must remain unconditional");
        }
        for (String redundantOwner : new String[] {
                GAME + "drink/BottlePlacementService.java",
                GAME + "tap/TapService.java",
                GAME + "station/StationService.java"}) {
            String text = Files.readString(projectRoot.resolve(redundantOwner), StandardCharsets.UTF_8);
            if (text.contains("items(\"bottle_items\", List.of(source))")) {
                throw new ValidationException(redundantOwner
                        + ": a single bottle must use CE sourceItem, not duplicate state");
            }
        }
        String stationSourceText = Files.readString(projectRoot.resolve(
                GAME + "station/StationService.java"), StandardCharsets.UTF_8);
        String shakerItemBehaviorSource = Files.readString(projectRoot.resolve(
                "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/game/shaker/"
                + "ShakerItemBehavior.java"), StandardCharsets.UTF_8);
        String combinedShaker = pluginSource + stationSourceText + shakerItemBehaviorSource;
        for (String token : new String[] {"ShakerItemBehavior.register()",
                "ShakerItemBehavior.bind(shakerItemHandler)",
                "ShakerItemBehavior.unbind(shakerItemHandler)",
                "private InteractionResult usePortableShaker("}) {
            if (!combinedShaker.contains(token)) {
                throw new ValidationException(
                        "Portable shaker CE item lifecycle is incomplete; missing " + token);
            }
        }
        String stationInteractionSource = Files.readString(projectRoot.resolve(
                GAME + "station/StationInteractionFurnitureBehavior.java"), StandardCharsets.UTF_8);
        String storageInteractionSource = Files.readString(projectRoot.resolve(
                GAME + "storage/StorageInteractionFurnitureBehavior.java"), StandardCharsets.UTF_8);
        if (stationSource.contains("onUsePortableShaker(PlayerInteractEvent")) {
            throw new ValidationException(
                    "Portable shaker right-click must not retain a duplicate global Paper listener");
        }
        if (stationSource.contains("findFurnitureAtBlock")) {
            throw new ValidationException(
                    "TapService must not rediscover indexed placed bottles through Bukkit entities");
        }
        if (Files.exists(projectRoot.resolve(GAME + "FurnitureConnectionService.java"))
                || Files.exists(projectRoot.resolve(GAME + "furniture/RedstoneFurnitureBehavior.java"))
                || Files.exists(projectRoot.resolve(GAME + "decor/LegacySofaBlockMigrationService.java"))
                || Files.exists(projectRoot.resolve(GAME + "decor/SofaBlockIds.java"))
                || Files.exists(projectRoot.resolve(GAME + "decor/SofaTintSupport.java"))
                || Files.exists(projectRoot.resolve(GAME + "decor/LegacyConnectedBlockMigrationFurnitureBehavior.java"))
                || Files.exists(projectRoot.resolve(GAME + "decor/LegacyConnectedBlockMigrationSemantics.java"))
                || Files.exists(projectRoot.resolve(GAME + "pressing/LegacyPressingTubMigrationFurnitureBehavior.java"))) {
            throw new ValidationException("Removed runtime migration sources were restored");
        }
    }
}
