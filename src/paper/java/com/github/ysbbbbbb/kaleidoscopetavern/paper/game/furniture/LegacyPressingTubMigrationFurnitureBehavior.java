package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.PressingTubState;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.PressingTubBlockBehavior;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
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
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * One-release migration for pressing tubs placed as CE furniture before the
 * tub became a server-side custom block.
 *
 * <p>The furniture definition is kept only so old saves can still load:
 * nothing maps an item to it (the tub item is a {@code block_item}), so it can
 * no longer be placed, and it carries no loot. On load/place this behavior
 * schedules a next-tick attempt to convert the furniture to the CE block,
 * copying the old {@code press_*} state into the block entity. CraftEngine
 * owns the placement, block-entity lifecycle and removal; this class only
 * decides the target cell, derives the facing from the legacy yaw and performs
 * the idempotent state hand-over.</p>
 *
 * <p>Migration must be safe to repeat after a crash:
 * <ul>
 *   <li>old furniture present + no block → create and migrate</li>
 *   <li>old furniture present + empty block → write state, remove furniture</li>
 *   <li>old furniture present + identical state → remove furniture</li>
 *   <li>old furniture present + different non-empty state → keep both, count a
 *   conflict</li>
 * </ul></p>
 */
public final class LegacyPressingTubMigrationFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:legacy_pressing_tub_migration";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static volatile JavaPlugin runtimePlugin;
    /** Controllers alive right now; bind() replays them when it runs late. */
    private static final Set<Controller> LOADED_CONTROLLERS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final AtomicLong LOADED_TUBS = new AtomicLong();
    private static final AtomicLong MIGRATED = new AtomicLong();
    private static final AtomicLong CONFLICTS = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();

    private LegacyPressingTubMigrationFurnitureBehavior(
            FurnitureDefinition furniture, ConfigSection section) {
        super(furniture);
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            FurnitureBehaviors.register(Key.of(TYPE), LegacyPressingTubMigrationFurnitureBehavior::new);
        }
    }

    public static void bind(JavaPlugin plugin) {
        runtimePlugin = Objects.requireNonNull(plugin, "plugin");
        // Furniture controllers can be created before this behavior is bound
        // (CraftEngine loads spawn chunks and their furniture during its own
        // onEnable, before Tavern's onEnable runs). Catch those up so an old
        // tub in a preloaded chunk still migrates without waiting for an
        // unload/reload cycle.
        for (Controller controller : List.copyOf(LOADED_CONTROLLERS)) {
            controller.schedule();
        }
    }

    public static void unbind(JavaPlugin plugin) {
        if (runtimePlugin == plugin) {
            runtimePlugin = null;
        }
    }

    /** Cumulative migration statistics for {@code /kt status}. */
    public static MigrationStats stats() {
        return new MigrationStats(
                LOADED_TUBS.get(), MIGRATED.get(), CONFLICTS.get(), FAILURES.get());
    }

    public record MigrationStats(long loaded, long migrated, long conflicts, long failures) {
    }

    @Override
    public FurnitureController createController(Furniture furniture) {
        if (!(furniture instanceof BukkitFurniture bukkitFurniture)) {
            throw new IllegalArgumentException(
                    "Legacy pressing-tub furniture requires BukkitFurniture");
        }
        return new Controller(bukkitFurniture);
    }

    private static final class Controller extends FurnitureController {
        private final BukkitFurniture bukkitFurniture;
        private boolean migrationScheduled;
        private boolean counted;

        private Controller(BukkitFurniture furniture) {
            super(furniture);
            this.bukkitFurniture = furniture;
        }

        @Override
        public void onLoad() {
            LOADED_CONTROLLERS.add(this);
            if (!counted) {
                counted = true;
                LOADED_TUBS.incrementAndGet();
            }
            schedule();
        }

        @Override
        public void onPlace(Player player) {
            LOADED_CONTROLLERS.add(this);
            if (!counted) {
                counted = true;
                LOADED_TUBS.incrementAndGet();
            }
            schedule();
        }

        @Override
        public void onUnload(boolean isStopping) {
            LOADED_CONTROLLERS.remove(this);
            // A task already dispatched still validates the furniture and chunk;
            // this flag only prevents double scheduling while still loaded.
            migrationScheduled = false;
        }

        private void schedule() {
            if (migrationScheduled) {
                return;
            }
            JavaPlugin owner = runtimePlugin;
            if (owner == null) {
                return;
            }
            migrationScheduled = true;
            Bukkit.getScheduler().runTask(owner, () -> {
                migrationScheduled = false;
                tryMigrate();
            });
        }

        private void tryMigrate() {
            if (!bukkitFurniture.isValid()) {
                return;
            }
            Location origin = bukkitFurniture.location();
            // 用 world.isChunkLoaded 判断：取得 Chunk 对象本身可能触发加载。
            if (origin == null || origin.getWorld() == null
                    || !origin.getWorld().isChunkLoaded(
                    origin.getBlockX() >> 4, origin.getBlockZ() >> 4)) {
                // Chunk unloaded before the task ran; the next onLoad retries.
                return;
            }
            try {
                migrateOnce(origin);
            } catch (RuntimeException exception) {
                FAILURES.incrementAndGet();
                JavaPlugin owner = runtimePlugin;
                if (owner != null) {
                    owner.getLogger().log(Level.WARNING,
                            "压榨桶旧家具迁移失败："
                                    + origin.getWorld().getName()
                                    + " " + origin.getBlockX() + ',' + origin.getBlockY() + ',' + origin.getBlockZ(),
                            exception);
                }
            }
        }

        private void migrateOnce(Location origin) {
            StateFurnitureBehavior.StateController stateController =
                    furniture.controller.get(StateFurnitureBehavior.StateController.class, 0);
            if (stateController == null) {
                FAILURES.incrementAndGet();
                return;
            }
            PressingTubState oldState = readState(stateController.data());
            boolean tilted = "wall".equals(bukkitFurniture.currentVariant().name());
            Direction facing = facingFromYaw(origin.getYaw());
            BlockPos target = tilted ? wallTarget(origin, facing) : groundTarget(origin);

            CEWorld ceWorld = BukkitAdaptor.adapt(origin.getWorld()).storageWorld();
            PressingTubBlockBehavior.Controller controller =
                    PressingTubBlockBehavior.findController(ceWorld, target);
            if (controller == null) {
                Block block = origin.getWorld().getBlockAt(
                        target.x(), target.y(), target.z());
                if (!(block.getType().isAir() || block.isLiquid())) {
                    // A different block now occupies the tub's cell; never
                    // overwrite it. The furniture stays for manual handling.
                    CONFLICTS.incrementAndGet();
                    return;
                }
                CompoundTag properties = new CompoundTag();
                properties.putString("facing", facing.name().toLowerCase(Locale.ROOT));
                properties.putBoolean("tilt", tilted);
                properties.putBoolean("waterlogged", isWaterlogged(block));
                Location placeAt = new Location(origin.getWorld(),
                        target.x() + 0.5, target.y(), target.z() + 0.5);
                boolean placed = CraftEngineBlocks.place(
                        placeAt, PressingTubBlockBehavior.BLOCK_ID, properties, false);
                if (!placed) {
                    FAILURES.incrementAndGet();
                    return;
                }
                controller = PressingTubBlockBehavior.findController(ceWorld, target);
                if (controller == null) {
                    FAILURES.incrementAndGet();
                    return;
                }
            }

            PressingTubState current = controller.snapshot();
            switch (decideMigration(oldState, tilted, facing,
                    current, controller.isTilted(), controller.facing())) {
                case DELETE_LEGACY -> {
                    removeLegacy();
                    MIGRATED.incrementAndGet();
                }
                case TRANSFER_AND_DELETE -> {
                    boolean applied = controller.updateState(ignored -> oldState);
                    if (!applied && !sameState(controller.snapshot(), oldState)) {
                        FAILURES.incrementAndGet();
                        return;
                    }
                    removeLegacy();
                    MIGRATED.incrementAndGet();
                }
                case CONFLICT -> CONFLICTS.incrementAndGet();
            }
        }

        private void removeLegacy() {
            // dropLoot=false: the migrating furniture must never drop the tub
            // item; the new block owns its own loot.
            CraftEngineFurniture.remove(furniture, false, false);
        }
    }

    /** 迁移决策结果（纯函数，便于单元测试）。 */
    enum MigrationAction {
        /** 目标状态与旧家具完全一致：删除旧家具即可。 */
        DELETE_LEGACY,
        /** 目标为空桶且方向/墙面一致：写入旧状态后删除旧家具。 */
        TRANSFER_AND_DELETE,
        /** 方向/墙面不一致或内容冲突：保留旧家具，记录冲突。 */
        CONFLICT
    }

    /**
     * 纯迁移决策，不执行任何副作用。
     *
     * <p>「相同内容、不同数量 / facing / tilt」都必须落入 CONFLICT，而不是被
     * 误判为已完成迁移后删除旧家具：isSimilar() 忽略 count，64 个葡萄与 1 个
     * 葡萄元数据相同；目标位置已有一个方向/墙面不同的空桶也不能当作迁移完成。
     * 因此这里同时比较 ingredient 数量与目标方块的 facing / tilt。</p>
     */
    static MigrationAction decideMigration(
            PressingTubState oldState, boolean oldTilted, Direction oldFacing,
            PressingTubState current, boolean currentTilted, Direction currentFacing) {
        if (sameState(current, oldState)
                && currentTilted == oldTilted
                && currentFacing == oldFacing) {
            return MigrationAction.DELETE_LEGACY;
        }
        if (isEmpty(current)) {
            if (currentTilted != oldTilted || currentFacing != oldFacing) {
                return MigrationAction.CONFLICT;
            }
            return MigrationAction.TRANSFER_AND_DELETE;
        }
        return MigrationAction.CONFLICT;
    }

    private static PressingTubState readState(CompoundTag data) {
        Item ingredient = null;
        byte[] encoded = data.getByteArray("press_item");
        if (encoded != null) {
            try {
                ItemStack stack = ItemStack.deserializeBytes(encoded);
                if (!stack.isEmpty()) {
                    ingredient = BukkitAdaptor.adapt(stack);
                }
            } catch (RuntimeException ignored) {
                ingredient = null;
            }
        }
        int pressCount = data.getInt("press_count", 0);
        ingredient = ingredient != null && pressCount > 0
                ? ingredient.copyWithCount(pressCount)
                : null;
        Key fluid = null;
        String fluidId = data.getString("press_fluid", null);
        if (fluidId != null && !fluidId.isEmpty()) {
            try {
                fluid = Key.of(fluidId);
            } catch (RuntimeException ignored) {
                fluid = null;
            }
        }
        int pressAmount = data.getInt("press_amount", 0);
        // The record's compact constructor clamps corrupt saves.
        return new PressingTubState(ingredient, fluid, pressAmount);
    }

    /**
     * Legacy furniture yaw → new block facing. The old furniture placement set
     * {@code yaw = Direction.getYaw(clickedFace)} (wall) or
     * {@code 180 + player yaw} (ground); both collapse onto the same cardinal
     * mapping, and the new block's facing equals the clicked face (tilt) or
     * the player's opposite direction (ground).
     */
    private static Direction facingFromYaw(float yaw) {
        return switch (Math.floorMod(Math.round(yaw), 360)) {
            case 90 -> Direction.WEST;
            case 180 -> Direction.NORTH;
            case 270 -> Direction.EAST;
            default -> Direction.SOUTH;
        };
    }

    private static BlockPos groundTarget(Location origin) {
        return new BlockPos(origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
    }

    /** The wall furniture origin sits on the clicked face plane; the block
     *  belongs in the cell on the face normal side. */
    private static BlockPos wallTarget(Location origin, Direction facing) {
        int x = origin.getBlockX();
        int y = origin.getBlockY();
        int z = origin.getBlockZ();
        if (facing == Direction.WEST) {
            x -= 1;
        } else if (facing == Direction.NORTH) {
            z -= 1;
        }
        return new BlockPos(x, y, z);
    }

    private static boolean isWaterlogged(Block block) {
        return block.isLiquid()
                || (block.getBlockData() instanceof Waterlogged waterlogged
                && waterlogged.isWaterlogged());
    }

    private static boolean isEmpty(PressingTubState state) {
        return state.ingredient() == null && state.fluid() == null
                && state.fluidAmount() == 0;
    }

    private static boolean sameState(PressingTubState left, PressingTubState right) {
        if (left.fluidAmount() != right.fluidAmount()) {
            return false;
        }
        if (!sameKey(left.fluid(), right.fluid())) {
            return false;
        }
        return sameItem(left.ingredient(), right.ingredient());
    }

    /** 迁移必须比较数量：isSimilar() 忽略 count，否则 64 个葡萄会与 1 个
     *  葡萄判为相同，删除旧家具时吞掉剩余 63 个。 */
    private static boolean sameItem(Item left, Item right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.count() == right.count() && left.isSimilar(right);
    }

    private static boolean sameKey(Key left, Key right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.toString().equals(right.toString());
    }
}
