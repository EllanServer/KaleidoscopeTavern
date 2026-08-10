package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.FurnitureVariant;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviorTemplate;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviors;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Routes exact furniture load/place/remove/unload callbacks through CE controllers. */
public final class LifecycleFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:lifecycle_furniture";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Map<Channel, Handler> HANDLERS = new EnumMap<>(Channel.class);
    private static final Map<Channel, Set<Controller>> READY = new EnumMap<>(Channel.class);
    private static final Map<Channel, Map<UUID, WorldIndex>> SPATIAL =
            new EnumMap<>(Channel.class);

    private final Channel channel;

    private LifecycleFurnitureBehavior(FurnitureDefinition furniture, ConfigSection section) {
        super(furniture);
        this.channel = parseChannel(section.getNonEmptyString("channel"), section);
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            FurnitureBehaviors.register(Key.of(TYPE), LifecycleFurnitureBehavior::new);
        }
    }

    public static void bind(Channel channel, Handler handler) {
        Handler bound = Objects.requireNonNull(handler, "handler");
        HANDLERS.put(Objects.requireNonNull(channel, "channel"), bound);
        Set<Controller> controllers = READY.get(channel);
        if (controllers != null) {
            for (Controller controller : List.copyOf(controllers)) {
                controller.deliver(bound);
            }
        }
    }

    public static void unbind(Channel channel, Handler handler) {
        if (HANDLERS.remove(Objects.requireNonNull(channel, "channel"),
                Objects.requireNonNull(handler, "handler"))) {
            Set<Controller> controllers = READY.get(channel);
            if (controllers != null) {
                for (Controller controller : List.copyOf(controllers)) {
                    controller.forget(handler);
                }
            }
        }
    }

    /** Returns only loaded furniture registered for this channel inside the box. */
    public static List<BukkitFurniture> nearby(Channel channel, Location center,
                                               double horizontalRadius,
                                               double verticalRadius) {
        Map<UUID, WorldIndex> channelWorlds = SPATIAL.get(channel);
        if (channelWorlds == null) {
            return List.of();
        }
        WorldIndex index = channelWorlds.get(center.getWorld().getUID());
        if (index == null) {
            return List.of();
        }
        int minX = FurnitureSpatialSemantics.minimumColumn(
                center.getX(), horizontalRadius);
        int maxX = FurnitureSpatialSemantics.maximumColumn(
                center.getX(), horizontalRadius);
        int minZ = FurnitureSpatialSemantics.minimumColumn(
                center.getZ(), horizontalRadius);
        int maxZ = FurnitureSpatialSemantics.maximumColumn(
                center.getZ(), horizontalRadius);
        List<BukkitFurniture> result = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Set<Controller> controllers = index.columns.get(packColumn(x, z));
                if (controllers == null) {
                    continue;
                }
                for (Controller controller : controllers) {
                    BukkitFurniture furniture = controller.bukkitFurniture;
                    Location location = furniture.location();
                    if (FurnitureSpatialSemantics.insideBox(
                            location.getX(), location.getY(), location.getZ(),
                            center.getX(), center.getY(), center.getZ(),
                            horizontalRadius, verticalRadius)) {
                        result.add(furniture);
                    }
                }
            }
        }
        return result;
    }

    /** Allocation-free existence check for hot paths that do not need a result list. */
    public static boolean hasNearby(Channel channel, World world,
                                    double centerX, double centerY, double centerZ,
                                    double horizontalRadius, double verticalRadius) {
        return hasNearby(channel, world, centerX, centerY, centerZ,
                horizontalRadius, verticalRadius, null);
    }

    /**
     * Allocation-free existence check restricted to an already-qualified owner set.
     * A {@code null} owner set accepts every furniture; an empty set accepts none.
     */
    public static boolean hasNearby(Channel channel, World world,
                                    double centerX, double centerY, double centerZ,
                                    double horizontalRadius, double verticalRadius,
                                    Set<UUID> allowedOwners) {
        if (allowedOwners != null && allowedOwners.isEmpty()) {
            return false;
        }
        Map<UUID, WorldIndex> channelWorlds = SPATIAL.get(channel);
        if (channelWorlds == null) {
            return false;
        }
        WorldIndex index = channelWorlds.get(world.getUID());
        if (index == null) {
            return false;
        }
        int minX = FurnitureSpatialSemantics.minimumColumn(
                centerX, horizontalRadius);
        int maxX = FurnitureSpatialSemantics.maximumColumn(
                centerX, horizontalRadius);
        int minZ = FurnitureSpatialSemantics.minimumColumn(
                centerZ, horizontalRadius);
        int maxZ = FurnitureSpatialSemantics.maximumColumn(
                centerZ, horizontalRadius);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Set<Controller> controllers = index.columns.get(packColumn(x, z));
                if (controllers == null) {
                    continue;
                }
                for (Controller controller : controllers) {
                    BukkitFurniture furniture = controller.bukkitFurniture;
                    if (allowedOwners != null
                            && !allowedOwners.contains(furniture.uuid())) {
                        continue;
                    }
                    Location location = furniture.location();
                    if (FurnitureSpatialSemantics.insideBox(
                            location.getX(), location.getY(), location.getZ(),
                            centerX, centerY, centerZ,
                            horizontalRadius, verticalRadius)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns loaded furniture in this channel whose CE origin occupies the block.
     * The block already pins the exact column, so no nearby box scan is needed.
     */
    public static Optional<BukkitFurniture> atBlock(Channel channel, Block block) {
        Map<UUID, WorldIndex> channelWorlds = SPATIAL.get(channel);
        if (channelWorlds == null) {
            return Optional.empty();
        }
        WorldIndex index = channelWorlds.get(block.getWorld().getUID());
        if (index == null) {
            return Optional.empty();
        }
        Set<Controller> controllers =
                index.columns.get(packColumn(block.getX(), block.getZ()));
        if (controllers == null) {
            return Optional.empty();
        }
        for (Controller controller : controllers) {
            BukkitFurniture furniture = controller.bukkitFurniture;
            Location location = furniture.location();
            if (FurnitureSpatialSemantics.insideBlock(
                    location.getX(), location.getY(), location.getZ(),
                    block.getX(), block.getY(), block.getZ())) {
                return Optional.of(furniture);
            }
        }
        return Optional.empty();
    }

    @Override
    public FurnitureController createController(Furniture furniture) {
        if (!(furniture instanceof BukkitFurniture bukkitFurniture)) {
            throw new IllegalArgumentException("Lifecycle furniture requires BukkitFurniture");
        }
        return new Controller(bukkitFurniture, channel);
    }

    private static Channel parseChannel(String value, ConfigSection section) {
        try {
            return Channel.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unknown lifecycle furniture channel at "
                            + section.assemblePath("channel") + ": " + value,
                    exception);
        }
    }

    private static long packColumn(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    public enum Channel {
        BAR_STOOL,
        BOARD,
        CONNECTION,
        BARREL,
        SHAKER,
        STORAGE,
        TAP_BOTTLE
    }

    public enum ReadyReason {
        LOAD,
        PLACE
    }

    @FunctionalInterface
    public interface Handler {
        void onReady(BukkitFurniture furniture, ReadyReason reason);

        default void onReady(BukkitFurniture furniture, ReadyReason reason, Player placingPlayer) {
            onReady(furniture, reason);
        }

        default void onUnavailable(BukkitFurniture furniture, boolean removed) {
        }
    }

    private static final class Controller extends FurnitureController {
        private final BukkitFurniture bukkitFurniture;
        private final Channel channel;
        private UUID worldId;
        private long column;
        private ReadyReason readyReason;
        private Handler deliveredHandler;
        private ReadyReason deliveredReason;

        private Controller(BukkitFurniture furniture, Channel channel) {
            super(furniture);
            this.bukkitFurniture = furniture;
            this.channel = channel;
        }

        @Override
        public void onPlace(Player player) {
            ready(ReadyReason.PLACE, player);
        }

        @Override
        public void onLoad() {
            ready(ReadyReason.LOAD, null);
        }

        @Override
        public void preRemove(Player player) {
            unavailable(true);
        }

        @Override
        public void postRemove(Player player) {
            unavailable(true);
        }

        @Override
        public void onUnload() {
            unavailable(false);
        }

        private void ready(ReadyReason reason, Player placingPlayer) {
            if (readyReason == null) {
                Location location = bukkitFurniture.location();
                worldId = location.getWorld().getUID();
                column = packColumn(location.getBlockX(), location.getBlockZ());
                addToSpatialIndex();
            }
            readyReason = reason;
            READY.computeIfAbsent(channel,
                    ignored -> new HashSet<>()).add(this);
            deliver(HANDLERS.get(channel), placingPlayer);
        }

        private void unavailable(boolean removed) {
            if (readyReason == null) {
                return;
            }
            Set<Controller> controllers = READY.get(channel);
            if (controllers != null) {
                controllers.remove(this);
                if (controllers.isEmpty()) {
                    READY.remove(channel, controllers);
                }
            }
            removeFromSpatialIndex();
            Handler currentHandler = HANDLERS.get(channel);
            if (currentHandler != null && currentHandler == deliveredHandler) {
                currentHandler.onUnavailable(bukkitFurniture, removed);
            }
            readyReason = null;
            deliveredHandler = null;
            deliveredReason = null;
        }

        /**
         * CraftEngine 的 {@code moveTo} 只更新 location 并重建变体，不触发
         * onLoad/onUnload；这里在 onVariantChange 中按新位置重索引，否则
         * {@link #atBlock} 的精确列查询会在家具移动后查不到它。
         */
        @Override
        public void onVariantChange(FurnitureVariant previousVariant) {
            reindexIfMoved();
        }

        private void reindexIfMoved() {
            if (readyReason == null) {
                return;
            }
            Location location = bukkitFurniture.location();
            UUID newWorldId = location.getWorld().getUID();
            long newColumn = packColumn(location.getBlockX(), location.getBlockZ());
            if (newWorldId.equals(worldId) && newColumn == column) {
                return;
            }
            removeFromSpatialIndex();
            worldId = newWorldId;
            column = newColumn;
            addToSpatialIndex();
        }

        private void addToSpatialIndex() {
            Map<UUID, WorldIndex> channelWorlds = SPATIAL.computeIfAbsent(
                    channel, ignored -> new HashMap<>());
            WorldIndex worldIndex = channelWorlds.computeIfAbsent(
                    worldId, ignored -> new WorldIndex());
            worldIndex.columns.computeIfAbsent(column,
                    ignored -> new HashSet<>()).add(this);
        }

        private void removeFromSpatialIndex() {
            Map<UUID, WorldIndex> channelWorlds = SPATIAL.get(channel);
            if (channelWorlds == null) {
                return;
            }
            WorldIndex worldIndex = channelWorlds.get(worldId);
            if (worldIndex != null) {
                Set<Controller> columnControllers = worldIndex.columns.get(column);
                if (columnControllers != null) {
                    columnControllers.remove(this);
                    if (columnControllers.isEmpty()) {
                        worldIndex.columns.remove(column, columnControllers);
                    }
                }
                if (worldIndex.columns.isEmpty()) {
                    channelWorlds.remove(worldId, worldIndex);
                }
            }
            if (channelWorlds.isEmpty()) {
                SPATIAL.remove(channel, channelWorlds);
            }
        }

        private void deliver(Handler handler) {
            deliver(handler, null);
        }

        private void deliver(Handler handler, Player placingPlayer) {
            if (handler == null || readyReason == null
                    || (handler == deliveredHandler && readyReason == deliveredReason)) {
                return;
            }
            deliveredHandler = handler;
            deliveredReason = readyReason;
            handler.onReady(bukkitFurniture, readyReason, placingPlayer);
        }

        private void forget(Handler handler) {
            if (deliveredHandler == handler) {
                deliveredHandler = null;
                deliveredReason = null;
            }
        }
    }

    private static final class WorldIndex {
        private final Map<Long, Set<Controller>> columns = new HashMap<>();
    }
}
