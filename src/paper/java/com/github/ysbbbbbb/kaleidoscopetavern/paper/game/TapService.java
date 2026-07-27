package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.TapBlockBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.LifecycleFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Beehive;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/** Business layer for the CE tap block's source/destination pipeline. */
public final class TapService implements TapBlockBehavior.Handler {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final String EMPTY_BOTTLE = PREFIX + "empty_bottle";
    private final JavaPlugin plugin;
    private final StationService stations;
    private final ItemService items;
    private final boolean infiniteLavaFromTap;
    private final LavaCauldronLevelStore lavaCauldronLevels;

    public TapService(JavaPlugin plugin, StationService stations, ItemService items) {
        this.plugin = plugin;
        this.stations = stations;
        this.items = items;
        this.infiniteLavaFromTap = plugin.getConfig()
                .getBoolean("gameplay.infinite-lava-from-tap", false);
        this.lavaCauldronLevels = new LavaCauldronLevelStore(plugin);
    }

    public Listener lavaCauldronListener() {
        return lavaCauldronLevels;
    }

    public void start() {
        TapBlockBehavior.bind(this);
    }

    public void stop() {
        TapBlockBehavior.unbind(this);
    }

    @Override
    public TapBlockBehavior.StartResult start(
            Block tapBlock, BlockFace facing, @Nullable Player player) {
        TapPlan plan = resolve(tapBlock, facing, player);
        return plan == null
                ? TapBlockBehavior.StartResult.EMPTY
                : new TapBlockBehavior.StartResult(true, plan.hot());
    }

    @Override
    public void finish(Block tapBlock, BlockFace facing, @Nullable Player player) {
        TapPlan plan = resolve(tapBlock, facing, null);
        if (plan != null) {
            execute(plan, player);
        }
    }

    private TapPlan resolve(Block tapBlock, BlockFace facing, @Nullable Player feedback) {
        Block source = tapBlock.getRelative(facing.getOppositeFace());
        Block destination = tapBlock.getRelative(BlockFace.DOWN);
        // Every archived ITapBehavior except BarrelBlockEntity matched an
        // EMPTY_BOTTLE block state only. A dropped carrier is therefore valid
        // exclusively for a connected barrel; all other sources still require
        // an actually placed bottle furniture.
        BottleCarrier bottle = findPlacedBottleCarrier(destination).orElse(null);

        if (source.getType() == Material.WATER_CAULDRON
                || source.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
            if (canFillWaterCauldron(destination)) {
                return new TapPlan(Kind.FILL_WATER_CAULDRON, source, destination, null, null, false);
            }
            if (bottle != null) {
                return new TapPlan(Kind.BOTTLE_WATER, source, destination, null, bottle, false);
            }
        }
        if (source.getType() == Material.LAVA_CAULDRON) {
            if (destination.getType() == Material.CAULDRON
                    && (infiniteLavaFromTap
                    || lavaCauldronLevels.level(source) == TapSemantics.FULL_LAVA_CAULDRON_LEVEL)) {
                return new TapPlan(Kind.FILL_LAVA_CAULDRON, source, destination, null, null, true);
            }
            if (bottle != null) {
                return new TapPlan(Kind.BOTTLE_MOLOTOV, source, destination, null, bottle, true);
            }
        }
        if ((source.getType() == Material.BEEHIVE || source.getType() == Material.BEE_NEST)
                && source.getBlockData() instanceof Beehive beehive && beehive.getHoneyLevel() > 0
                && bottle != null) {
            return new TapPlan(Kind.BOTTLE_HONEY, source, destination, null, bottle, true);
        }
        if ((source.getType() == Material.DRAGON_HEAD || source.getType() == Material.DRAGON_WALL_HEAD)
                && bottle != null) {
            return new TapPlan(Kind.BOTTLE_DRAGON_BREATH, source, destination, null, bottle, false);
        }
        if (source.getType() == Material.MELON && bottle != null) {
            return new TapPlan(Kind.BOTTLE_WATERMELON, source, destination, null, bottle, false);
        }
        Optional<BukkitFurniture> barrel = findConnectedBarrel(tapBlock, facing);
        if (barrel.isPresent()) {
            BarrelSemantics.TapExtractStatus status = stations.tapExtractStatus(barrel.get());
            if (status != BarrelSemantics.TapExtractStatus.READY) {
                showBarrelFailure(feedback, status);
                return null;
            }
            BottleCarrier barrelCarrier = bottle != null
                    ? bottle : findDroppedBottleCarrier(destination).orElse(null);
            if (barrelCarrier != null) {
                return new TapPlan(Kind.BOTTLE_BARREL, source, destination,
                        barrel.get(), barrelCarrier, stations.isTapOutputHot(barrel.get()));
            }
            showMissingCarrier(feedback, destination);
        }
        return null;
    }

    private static void showBarrelFailure(Player player, BarrelSemantics.TapExtractStatus status) {
        if (player == null || status == BarrelSemantics.TapExtractStatus.READY) {
            return;
        }
        String key = switch (status) {
            case NOT_BREWING -> "tap_extract_not_brewing";
            case EMPTY -> "tap_extract_empty";
            case INVALID_CONTAINER -> "tap_extract_invalid_container";
            case READY -> throw new IllegalStateException("READY has no failure message");
        };
        showBarrelTip(player, key);
    }

    private void showMissingCarrier(Player player, Block destination) {
        if (player == null) {
            return;
        }
        boolean empty = destination.getType() == Material.AIR
                && findAnyFurnitureAtBlock(destination).isEmpty();
        showBarrelTip(player, empty
                ? "tap_extract_empty_container" : "tap_extract_invalid_container");
    }

    private static void showBarrelTip(Player player, String key) {
        player.sendActionBar(Component.translatable(
                "message.kaleidoscope_tavern.barrel." + key));
    }

    private void execute(TapPlan plan, Player player) {
        boolean completed = switch (plan.kind()) {
            case FILL_WATER_CAULDRON -> fillWaterCauldron(plan.destination());
            case FILL_LAVA_CAULDRON -> fillLavaCauldron(plan.destination());
            case BOTTLE_WATER -> replaceBottle(
                    plan.bottle(), plan.destination(), PREFIX + "water_bottle", player);
            case BOTTLE_MOLOTOV -> replaceBottle(
                    plan.bottle(), plan.destination(), PREFIX + "molotov", player);
            case BOTTLE_DRAGON_BREATH -> replaceBottle(
                    plan.bottle(), plan.destination(), PREFIX + "dragon_breath_bottle", player);
            case BOTTLE_WATERMELON -> replaceBottle(
                    plan.bottle(), plan.destination(), PREFIX + "watermelon_juice", player);
            case BOTTLE_HONEY -> bottleHoney(plan, player);
            case BOTTLE_BARREL -> bottleBarrel(plan, player);
        };
        if (!completed) {
            return;
        }
        if (plan.lavaSourceLevels() > 0
                && TapSemantics.shouldConsumeLavaSource(infiniteLavaFromTap)) {
            lavaCauldronLevels.consume(plan.source(), plan.lavaSourceLevels());
        }
        Location location = plan.destination().getLocation().add(0.5, 0.5, 0.5);
        if (plan.kind() != Kind.FILL_WATER_CAULDRON && plan.kind() != Kind.FILL_LAVA_CAULDRON) {
            location.getWorld().playSound(location, Sound.BLOCK_BREWING_STAND_BREW,
                    SoundCategory.BLOCKS, 1F, 1F);
        }
        location.getWorld().spawnParticle(Particle.WAX_OFF, location, 10, 0.25, 0.25, 0.25, 0.1);
    }

    private boolean bottleHoney(TapPlan plan, Player player) {
        if (!(plan.source().getBlockData() instanceof Beehive beehive) || beehive.getHoneyLevel() <= 0
                || !replaceBottle(plan.bottle(), plan.destination(), PREFIX + "honey_bottle", player)) {
            return false;
        }
        beehive.setHoneyLevel(beehive.getHoneyLevel() - 1);
        plan.source().setBlockData(beehive, true);
        return true;
    }

    private boolean bottleBarrel(TapPlan plan, Player player) {
        if (plan.barrel() == null || plan.bottle() == null) {
            return false;
        }
        return stations.transferTapOutput(plan.barrel(), player,
                output -> replaceBottle(plan.bottle(), plan.destination(), output));
    }

    private boolean replaceBottle(BottleCarrier bottle, Block destination, String resultId, Player player) {
        Optional<ItemStack> result = items.build(resultId, player);
        return result.isPresent() && replaceBottle(bottle, destination, result.get());
    }

    private boolean replaceBottle(BottleCarrier bottle, Block destination, ItemStack result) {
        if (bottle == null) {
            return false;
        }
        if (bottle.furniture() != null) {
            return replacePlacedBottle(bottle.furniture(), result);
        }
        return replaceDroppedBottle(bottle.droppedItem(), destination, result);
    }

    private boolean replacePlacedBottle(BukkitFurniture bottle, ItemStack result) {
        if (bottle == null || !bottle.isValid()) {
            return false;
        }
        String resultId = items.id(result);
        if (CraftEngineFurniture.byId(Key.of(resultId)) == null) {
            return false;
        }
        Location location = bottle.location().clone();
        CraftEngineFurniture.remove(bottle, false, false);
        BukkitFurniture replacement = CraftEngineFurniture.place(location, Key.of(resultId), "ground", false);
        if (replacement == null) {
            CraftEngineFurniture.place(location, Key.of(EMPTY_BOTTLE), "ground", false);
            return false;
        }
        ItemStack source = result.clone();
        source.setAmount(1);
        initializeBottle(replacement, source);
        return true;
    }

    private boolean replaceDroppedBottle(org.bukkit.entity.Item bottle, Block destination, ItemStack result) {
        if (bottle == null || !bottle.isValid() || bottle.getItemStack().isEmpty()
                || !items.id(bottle.getItemStack()).equals(EMPTY_BOTTLE)) {
            return false;
        }

        ItemStack source = result.clone();
        source.setAmount(1);
        Key furnitureId = Key.of(items.id(source));
        boolean mayPlace = destination.getType() == Material.AIR
                && CraftEngineFurniture.byId(furnitureId) != null;

        consumeDroppedBottle(bottle);
        if (mayPlace) {
            Location location = destination.getLocation().add(0.5, 0, 0.5);
            location.setPitch(0F);
            location.setYaw(0F);
            BukkitFurniture replacement = CraftEngineFurniture.place(location, furnitureId, "ground", false);
            if (replacement != null) {
                try {
                    initializeBottle(replacement, source);
                    return true;
                } catch (RuntimeException exception) {
                    CraftEngineFurniture.remove(replacement, false, false);
                    plugin.getLogger().warning("Failed to initialize tap bottle furniture: "
                            + exception.getMessage());
                }
            }
        }

        destination.getWorld().dropItemNaturally(
                destination.getLocation().add(0.5, 0.5, 0.5), source);
        return true;
    }

    private void initializeBottle(BukkitFurniture bottle, ItemStack source) {
        bottle.setSourceItem(BukkitAdaptor.adapt(source));
        bottle.refreshElements();
        bottle.setUnsaved();
    }

    private static void consumeDroppedBottle(org.bukkit.entity.Item bottle) {
        ItemStack stack = bottle.getItemStack().clone();
        if (stack.getAmount() <= 1) {
            bottle.remove();
            return;
        }
        stack.setAmount(stack.getAmount() - 1);
        bottle.setItemStack(stack);
    }

    private static boolean canFillWaterCauldron(Block destination) {
        if (destination.getType() == Material.CAULDRON) {
            return true;
        }
        return destination.getType() == Material.WATER_CAULDRON
                && destination.getBlockData() instanceof Levelled levelled
                && levelled.getLevel() < levelled.getMaximumLevel();
    }

    private static boolean fillWaterCauldron(Block destination) {
        if (!canFillWaterCauldron(destination)) {
            return false;
        }
        Levelled state = (Levelled) Bukkit.createBlockData(Material.WATER_CAULDRON);
        state.setLevel(state.getMaximumLevel());
        destination.setBlockData(state, true);
        destination.getWorld().playSound(destination.getLocation().add(0.5, 0.5, 0.5), Sound.ENTITY_AXOLOTL_SPLASH,
                SoundCategory.BLOCKS, 1F, 1F);
        return true;
    }

    private boolean fillLavaCauldron(Block destination) {
        if (destination.getType() != Material.CAULDRON) {
            return false;
        }
        lavaCauldronLevels.reset(destination);
        destination.setType(Material.LAVA_CAULDRON, true);
        destination.getWorld().playSound(destination.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_LAVA_POP,
                SoundCategory.BLOCKS, 1F, 1F);
        return true;
    }

    private Optional<BottleCarrier> findPlacedBottleCarrier(Block block) {
        Optional<BukkitFurniture> placed = LifecycleFurnitureBehavior.atBlock(
                LifecycleFurnitureBehavior.Channel.TAP_BOTTLE, block);
        return placed.map(furniture -> new BottleCarrier(furniture, null));
    }

    private Optional<BottleCarrier> findDroppedBottleCarrier(Block block) {
        TapSemantics.BlockBounds bounds = TapSemantics.blockBounds(
                block.getX(), block.getY(), block.getZ());
        BoundingBox box = new BoundingBox(
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ());
        return block.getWorld().getNearbyEntities(box, entity ->
                        entity instanceof org.bukkit.entity.Item dropped
                                && dropped.isValid()
                                && !dropped.getItemStack().isEmpty()
                                && items.id(dropped.getItemStack()).equals(EMPTY_BOTTLE))
                .stream()
                .map(entity -> new BottleCarrier(null, (org.bukkit.entity.Item) entity))
                .findFirst();
    }

    private static Optional<BukkitFurniture> findAnyFurnitureAtBlock(Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        return center.getWorld().getNearbyEntities(center, 1.0, 1.0, 1.0).stream()
                .filter(CraftEngineFurniture::isFurniture)
                .map(CraftEngineFurniture::getLoadedFurnitureByMetaEntity)
                .filter(java.util.Objects::nonNull)
                .filter(BukkitFurniture::isValid)
                .filter(furniture -> furniture.location().getBlockX() == block.getX()
                        && furniture.location().getBlockY() == block.getY()
                        && furniture.location().getBlockZ() == block.getZ())
                .findFirst();
    }

    private static Optional<BukkitFurniture> findConnectedBarrel(
            Block tapBlock, BlockFace facing) {
        Location center = tapBlock.getLocation().add(0.5, 0.5, 0.5);
        int tapFacingX = facing.getModX();
        int tapFacingZ = facing.getModZ();
        Block source = tapBlock.getRelative(facing.getOppositeFace());
        return LifecycleFurnitureBehavior.nearby(
                        LifecycleFurnitureBehavior.Channel.BARREL,
                        center, 3.25, 3.25).stream()
                .filter(barrel -> {
                    Location origin = barrel.location();
                    Vector barrelDirection = horizontalCardinal(origin);
                    return TapSemantics.isBarrelConnection(
                            source.getX(), source.getY(), source.getZ(), tapFacingX, tapFacingZ,
                            origin.getBlockX(), origin.getBlockY(), origin.getBlockZ(),
                            (int) barrelDirection.getX(), (int) barrelDirection.getZ());
                })
                .findFirst();
    }

    private static Vector horizontalCardinal(Location location) {
        Vector direction = location.getDirection().setY(0);
        if (Math.abs(direction.getX()) >= Math.abs(direction.getZ())) {
            return new Vector(direction.getX() < 0 ? -1 : 1, 0, 0);
        }
        return new Vector(0, 0, direction.getZ() < 0 ? -1 : 1);
    }

    private enum Kind {
        FILL_WATER_CAULDRON,
        FILL_LAVA_CAULDRON,
        BOTTLE_WATER,
        BOTTLE_MOLOTOV,
        BOTTLE_HONEY,
        BOTTLE_DRAGON_BREATH,
        BOTTLE_WATERMELON,
        BOTTLE_BARREL
    }

    private record BottleCarrier(BukkitFurniture furniture, org.bukkit.entity.Item droppedItem) {
    }

    private record TapPlan(Kind kind, Block source, Block destination,
                           BukkitFurniture barrel, BottleCarrier bottle, boolean hot) {
        private int lavaSourceLevels() {
            return switch (kind) {
                case BOTTLE_MOLOTOV -> 1;
                case FILL_LAVA_CAULDRON -> TapSemantics.FULL_LAVA_CAULDRON_LEVEL;
                default -> 0;
            };
        }
    }
}
