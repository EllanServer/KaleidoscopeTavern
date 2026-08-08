package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.PressingTubService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.PressingTubState;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.PressingTubBlockBehavior;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
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
import net.momirealms.craftengine.libraries.nbt.ByteArrayTag;
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
 * ground/wall representation was split.
 *
 * <p>The legacy public furniture id is never mapped from an item and carries no
 * loot. Loaded ground variants move into the real CE block; loaded wall
 * variants move into the private wall-only CE furniture definition. This keeps
 * all new placement configuration-native while preserving the old
 * {@code press_*} business state.</p>
 */
public final class LegacyPressingTubMigrationFurnitureBehavior
        extends FurnitureBehaviorTemplate {
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
            FurnitureBehaviors.register(
                    Key.of(TYPE), LegacyPressingTubMigrationFurnitureBehavior::new);
        }
    }

    public static void bind(JavaPlugin plugin) {
        runtimePlugin = Objects.requireNonNull(plugin, "plugin");
        // CraftEngine can load spawn-chunk furniture before Tavern's onEnable.
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
            loaded();
        }

        @Override
        public void onPlace(Player player) {
            loaded();
        }

        @Override
        public void onUnload(boolean isStopping) {
            LOADED_CONTROLLERS.remove(this);
            migrationScheduled = false;
        }

        private void loaded() {
            LOADED_CONTROLLERS.add(this);
            if (!counted) {
                counted = true;
                LOADED_TUBS.incrementAndGet();
            }
            schedule();
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
            if (origin == null || origin.getWorld() == null
                    || !origin.getWorld().isChunkLoaded(
                    origin.getBlockX() >> 4, origin.getBlockZ() >> 4)) {
                return;
            }
            try {
                StateFurnitureBehavior.StateController sourceController =
                        furniture.controller.get(
                                StateFurnitureBehavior.StateController.class, 0);
                if (sourceController == null) {
                    FAILURES.incrementAndGet();
                    return;
                }
                PressingTubState oldState = readState(sourceController.data());
                if ("wall".equals(bukkitFurniture.currentVariant().name())) {
                    migrateWall(origin, oldState);
                } else {
                    migrateGround(origin, oldState);
                }
            } catch (RuntimeException exception) {
                FAILURES.incrementAndGet();
                JavaPlugin owner = runtimePlugin;
                if (owner != null) {
                    owner.getLogger().log(Level.WARNING,
                            "压榨桶旧家具迁移失败："
                                    + origin.getWorld().getName() + " "
                                    + origin.getBlockX() + ',' + origin.getBlockY()
                                    + ',' + origin.getBlockZ(), exception);
                }
            }
        }

        private void migrateWall(Location origin, PressingTubState oldState) {
            BukkitFurniture replacement = CraftEngineFurniture.place(
                    origin.clone(), PressingTubService.WALL_FURNITURE_ID,
                    "wall", false);
            if (replacement == null) {
                FAILURES.incrementAndGet();
                return;
            }
            try {
                StateFurnitureBehavior.StateController targetController =
                        StateFurnitureBehavior.state(replacement);
                writeState(targetController, oldState);
                StationVisualFurnitureBehavior.refresh(replacement);
            } catch (RuntimeException exception) {
                CraftEngineFurniture.remove(replacement, false, false);
                throw exception;
            }
            removeLegacy();
            MIGRATED.incrementAndGet();
        }

        private void migrateGround(Location origin, PressingTubState oldState) {
            Direction facing = facingFromYaw(origin.getYaw());
            BlockPos target = groundTarget(origin);
            CEWorld ceWorld = BukkitAdaptor.adapt(origin.getWorld()).storageWorld();
            PressingTubBlockBehavior.Controller controller =
                    PressingTubBlockBehavior.findController(ceWorld, target);
            if (controller == null) {
                Block block = origin.getWorld().getBlockAt(
                        target.x(), target.y(), target.z());
                if (!(block.getType().isAir() || block.isLiquid())) {
                    CONFLICTS.incrementAndGet();
                    return;
                }
                CompoundTag properties = new CompoundTag();
                properties.putString(
                        "facing", facing.name().toLowerCase(Locale.ROOT));
                properties.putBoolean("waterlogged", isWaterlogged(block));
                Location placeAt = new Location(origin.getWorld(),
                        target.x() + 0.5, target.y(), target.z() + 0.5);
                boolean placed = CraftEngineBlocks.place(
                        placeAt, PressingTubBlockBehavior.BLOCK_ID,
                        properties, false);
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
            switch (decideMigration(
                    oldState, facing, current, controller.facing())) {
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
            // The target block or wall furniture owns the resulting item loot.
            CraftEngineFurniture.remove(furniture, false, false);
        }
    }

    /** Ground migration decision, kept pure for focused unit tests. */
    enum MigrationAction {
        DELETE_LEGACY,
        TRANSFER_AND_DELETE,
        CONFLICT
    }

    static MigrationAction decideMigration(
            PressingTubState oldState, Direction oldFacing,
            PressingTubState current, Direction currentFacing) {
        if (sameState(current, oldState) && currentFacing == oldFacing) {
            return MigrationAction.DELETE_LEGACY;
        }
        if (isEmpty(current)) {
            return currentFacing == oldFacing
                    ? MigrationAction.TRANSFER_AND_DELETE
                    : MigrationAction.CONFLICT;
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
                ? ingredient.copyWithCount(pressCount) : null;

        Key fluid = null;
        String fluidId = data.getString("press_fluid", null);
        if (fluidId != null && !fluidId.isEmpty()) {
            try {
                fluid = Key.of(fluidId);
            } catch (RuntimeException ignored) {
                fluid = null;
            }
        }
        return new PressingTubState(
                ingredient, fluid, data.getInt("press_amount", 0));
    }

    private static void writeState(
            StateFurnitureBehavior.StateController target,
            PressingTubState state
    ) {
        CompoundTag data = target.data();
        data.remove("press_item");
        data.remove("press_count");
        data.remove("press_fluid");
        data.remove("press_amount");

        Item ingredient = state.ingredient();
        if (ingredient != null) {
            if (!(ingredient instanceof BukkitItem bukkitItem)) {
                throw new IllegalStateException(
                        "Legacy pressing-tub ingredient is not a Bukkit item");
            }
            ItemStack stack = bukkitItem.getBukkitItem().clone();
            stack.setAmount(1);
            data.put("press_item", new ByteArrayTag(stack.serializeAsBytes()));
            data.putInt("press_count", ingredient.count());
        }
        if (state.fluid() != null) {
            data.putString("press_fluid", state.fluid().toString());
        }
        if (state.fluidAmount() > 0) {
            data.putInt("press_amount", state.fluidAmount());
        }
        target.markChanged();
    }

    /** Old ground-furniture yaw collapses onto the new horizontal facing. */
    private static Direction facingFromYaw(float yaw) {
        return switch (Math.floorMod(Math.round(yaw), 360)) {
            case 90 -> Direction.WEST;
            case 180 -> Direction.NORTH;
            case 270 -> Direction.EAST;
            default -> Direction.SOUTH;
        };
    }

    private static BlockPos groundTarget(Location origin) {
        return new BlockPos(
                origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
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

    private static boolean sameState(
            PressingTubState left, PressingTubState right) {
        return left.fluidAmount() == right.fluidAmount()
                && sameKey(left.fluid(), right.fluid())
                && sameItem(left.ingredient(), right.ingredient());
    }

    /** Item similarity alone ignores count; migration must compare both. */
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
