package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.StorageBlockBehavior;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.behavior.DisplayItemFurnitureBehaviorTemplate.DisplayItemFurnitureController;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviorTemplate;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviors;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * One-release migration from the former connected CE furniture definitions to
 * real CE custom blocks. Static placement/render/collision/loot is entirely
 * configuration-owned; this controller only transfers persisted legacy state.
 */
public final class LegacyConnectedBlockMigrationFurnitureBehavior
        extends FurnitureBehaviorTemplate {
    public static final Key TYPE =
            Key.of("kaleidoscope_tavern", "legacy_connected_block_migration");

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Set<Controller> LOADED =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final AtomicLong MIGRATED = new AtomicLong();
    private static final AtomicLong CONFLICTS = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();
    private static final Field DISPLAY_ITEM_FIELD = findDisplayItemField();
    private static volatile JavaPlugin plugin;

    private LegacyConnectedBlockMigrationFurnitureBehavior(
            FurnitureDefinition furniture, ConfigSection section) {
        super(furniture);
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            FurnitureBehaviors.register(
                    TYPE, LegacyConnectedBlockMigrationFurnitureBehavior::new);
        }
    }

    public static void bind(JavaPlugin owner) {
        plugin = owner;
        for (Controller controller : List.copyOf(LOADED)) {
            controller.schedule();
        }
    }

    public static void unbind(JavaPlugin owner) {
        if (plugin == owner) {
            plugin = null;
        }
    }

    public static MigrationStats stats() {
        return new MigrationStats(
                MIGRATED.get(), CONFLICTS.get(), FAILURES.get());
    }

    @Override
    public FurnitureController createController(Furniture furniture) {
        if (!(furniture instanceof BukkitFurniture bukkitFurniture)) {
            throw new IllegalArgumentException(
                    "Connected-block migration requires BukkitFurniture");
        }
        return new Controller(bukkitFurniture);
    }

    public record MigrationStats(long migrated, long conflicts, long failures) {
    }

    private static final class Controller extends FurnitureController {
        private final BukkitFurniture bukkitFurniture;
        private boolean scheduled;

        private Controller(BukkitFurniture furniture) {
            super(furniture);
            this.bukkitFurniture = furniture;
        }

        @Override
        public void onLoad() {
            loaded();
        }

        @Override
        public void onPlace(Player player) {
            loaded();
        }

        @Override
        public void onUnload(boolean isStopping) {
            LOADED.remove(this);
            scheduled = false;
        }

        private void loaded() {
            LOADED.add(this);
            schedule();
        }

        private void schedule() {
            JavaPlugin owner = plugin;
            if (owner == null || scheduled) {
                return;
            }
            scheduled = true;
            Bukkit.getScheduler().runTask(owner, () -> {
                scheduled = false;
                migrate();
            });
        }

        private void migrate() {
            if (!bukkitFurniture.isValid()) {
                return;
            }
            Location origin = bukkitFurniture.location();
            if (origin == null || origin.getWorld() == null
                    || !origin.getWorld().isChunkLoaded(
                    origin.getBlockX() >> 4, origin.getBlockZ() >> 4)) {
                return;
            }

            boolean placedHere = false;
            Block target = origin.getBlock();
            StorageBlockBehavior.Controller storageTarget = null;
            try {
                Key targetId = bukkitFurniture.id();
                boolean storageCabinet = isStorageCabinet(targetId);
                FurnitureContents contents = storageCabinet
                        ? readFurnitureContents() : FurnitureContents.EMPTY;
                CompoundTag properties = properties(
                        targetId, bukkitFurniture.currentVariant().name(),
                        origin.getYaw(), target);

                ImmutableBlockState existing =
                        CraftEngineBlocks.getCustomBlockState(target);
                if (existing != null) {
                    if (!existing.owner().value().id().equals(targetId)) {
                        CONFLICTS.incrementAndGet();
                        return;
                    }
                    if (storageCabinet) {
                        storageTarget = storageController(origin);
                        if (storageTarget == null
                                || !reconcileContents(storageTarget, contents)) {
                            CONFLICTS.incrementAndGet();
                            return;
                        }
                        clearFurnitureContents();
                    }
                    removeLegacy();
                    MIGRATED.incrementAndGet();
                    return;
                }

                if (!(target.getType().isAir() || target.isLiquid())) {
                    CONFLICTS.incrementAndGet();
                    return;
                }
                Location placeAt = new Location(
                        origin.getWorld(), target.getX() + 0.5,
                        target.getY(), target.getZ() + 0.5);
                if (!CraftEngineBlocks.place(
                        placeAt, targetId, properties, false)) {
                    FAILURES.incrementAndGet();
                    return;
                }
                placedHere = true;

                ImmutableBlockState placed =
                        CraftEngineBlocks.getCustomBlockState(target);
                if (placed == null
                        || !placed.owner().value().id().equals(targetId)) {
                    rollbackPlacedBlock(target, null);
                    FAILURES.incrementAndGet();
                    return;
                }
                if (storageCabinet) {
                    storageTarget = storageController(origin);
                    if (storageTarget == null
                            || !transferIntoEmpty(storageTarget, contents)) {
                        rollbackPlacedBlock(target, storageTarget);
                        FAILURES.incrementAndGet();
                        return;
                    }
                    clearFurnitureContents();
                }

                removeLegacy();
                MIGRATED.incrementAndGet();
            } catch (ReflectiveOperationException | RuntimeException exception) {
                if (placedHere) {
                    rollbackPlacedBlock(target, storageTarget);
                }
                FAILURES.incrementAndGet();
                JavaPlugin owner = plugin;
                if (owner != null) {
                    owner.getLogger().log(Level.WARNING,
                            "连接家具迁移为 CE 方块失败：" + origin, exception);
                }
            }
        }

        private FurnitureContents readFurnitureContents()
                throws IllegalAccessException {
            if (DISPLAY_ITEM_FIELD == null) {
                throw new IllegalStateException(
                        "CraftEngine display-item migration bridge is unavailable");
            }
            Item left = Item.empty();
            Item right = Item.empty();
            int ordinal = 0;
            for (int index = 0;
                 index < bukkitFurniture.config.behaviors().size(); index++) {
                DisplayItemFurnitureController candidate =
                        furniture.controller.get(
                                DisplayItemFurnitureController.class, index);
                if (candidate == null) {
                    continue;
                }
                Item item = (Item) DISPLAY_ITEM_FIELD.get(candidate);
                if (ordinal == 0) {
                    left = copy(item);
                } else if (ordinal == 1) {
                    right = copy(item);
                    break;
                }
                ordinal++;
            }
            if (ordinal < 2) {
                throw new IllegalStateException(
                        "Legacy bar cabinet is missing its two CE display slots");
            }
            return new FurnitureContents(left, right);
        }

        private void clearFurnitureContents() throws IllegalAccessException {
            if (DISPLAY_ITEM_FIELD == null) {
                throw new IllegalStateException(
                        "CraftEngine display-item migration bridge is unavailable");
            }
            for (int index = 0;
                 index < bukkitFurniture.config.behaviors().size(); index++) {
                DisplayItemFurnitureController candidate =
                        furniture.controller.get(
                                DisplayItemFurnitureController.class, index);
                if (candidate != null) {
                    DISPLAY_ITEM_FIELD.set(candidate, Item.empty());
                }
            }
            bukkitFurniture.setUnsaved();
        }

        private void removeLegacy() {
            CraftEngineFurniture.remove(furniture, false, false);
            LOADED.remove(this);
        }
    }

    private static StorageBlockBehavior.Controller storageController(
            Location origin) {
        return StorageBlockBehavior.findController(
                BukkitAdaptor.adapt(origin.getWorld()).storageWorld(),
                new BlockPos(origin.getBlockX(), origin.getBlockY(),
                        origin.getBlockZ()));
    }

    private static boolean transferIntoEmpty(
            StorageBlockBehavior.Controller target, FurnitureContents source) {
        if (!isEmpty(target.item(0)) || !isEmpty(target.item(1))) {
            return sameContents(target, source);
        }
        if (!isEmpty(source.left()) && !target.put(0, source.left())) {
            return false;
        }
        if (!isEmpty(source.right()) && !target.put(1, source.right())) {
            target.take(0);
            return false;
        }
        return true;
    }

    private static boolean reconcileContents(
            StorageBlockBehavior.Controller target, FurnitureContents source) {
        if (sameContents(target, source)) {
            return true;
        }
        if (isEmpty(target.item(0)) && isEmpty(target.item(1))) {
            return transferIntoEmpty(target, source);
        }
        return false;
    }

    private static boolean sameContents(
            StorageBlockBehavior.Controller target, FurnitureContents source) {
        return sameItem(target.item(0), source.left())
                && sameItem(target.item(1), source.right());
    }

    private static void rollbackPlacedBlock(
            Block target, StorageBlockBehavior.Controller controller) {
        if (controller != null) {
            for (int slot = 0; slot < controller.slots(); slot++) {
                controller.take(slot);
            }
        }
        CraftEngineBlocks.remove(target, false);
    }

    static CompoundTag properties(
            Key id, String variant, float yaw, Block target) {
        CompoundTag properties = new CompoundTag();
        String path = id.value();
        if (path.endsWith("_sofa") || path.equals("bar_counter")) {
            properties.putString("facing",
                    facingFromYaw(yaw).name().toLowerCase(Locale.ROOT));
            properties.putString("connection", connection(variant));
        } else if (path.equals("table")) {
            LegacyConnectedBlockMigrationSemantics.TableProperties table =
                    LegacyConnectedBlockMigrationSemantics.tableProperties(
                            variant, facingFromYaw(yaw));
            properties.putString("axis", table.axis());
            properties.putInt("position", table.position());
        } else if (isStorageCabinet(id)) {
            properties.putString("facing",
                    facingFromYaw(yaw).name().toLowerCase(Locale.ROOT));
            properties.putString("position", cabinetPosition(variant));
        } else {
            throw new IllegalArgumentException(
                    "Unsupported connected migration id: " + id);
        }
        if (path.endsWith("_sofa") || path.equals("table")) {
            properties.putBoolean("waterlogged", isWaterlogged(target));
        }
        return properties;
    }

    private static String connection(String variant) {
        String prefix = "ground_connection_";
        return variant.startsWith(prefix)
                ? variant.substring(prefix.length()) : "single";
    }

    private static String cabinetPosition(String variant) {
        String prefix = "ground_position_";
        return variant.startsWith(prefix)
                ? variant.substring(prefix.length()) : "single";
    }

    private static Direction facingFromYaw(float yaw) {
        return switch (Math.floorMod(Math.round(yaw), 360)) {
            case 90 -> Direction.WEST;
            case 180 -> Direction.NORTH;
            case 270 -> Direction.EAST;
            default -> Direction.SOUTH;
        };
    }

    private static boolean isStorageCabinet(Key id) {
        return id.value().equals("bar_cabinet")
                || id.value().equals("glass_bar_cabinet");
    }

    private static boolean isWaterlogged(Block block) {
        return block.isLiquid()
                || block.getBlockData() instanceof Waterlogged waterlogged
                && waterlogged.isWaterlogged();
    }

    private static Field findDisplayItemField() {
        try {
            Field field = DisplayItemFurnitureController.class
                    .getDeclaredField("savedItem");
            return field.trySetAccessible() ? field : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Item copy(Item item) {
        return isEmpty(item) ? Item.empty() : item.copy();
    }

    private static boolean isEmpty(Item item) {
        return item == null || item.isEmpty();
    }

    private static boolean sameItem(Item left, Item right) {
        if (isEmpty(left) || isEmpty(right)) {
            return isEmpty(left) && isEmpty(right);
        }
        return left.count() == right.count() && left.isSimilar(right);
    }


    private record FurnitureContents(Item left, Item right) {
        private static final FurnitureContents EMPTY =
                new FurnitureContents(Item.empty(), Item.empty());
    }
}
