package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.FurnitureSpatialSemantics;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviorTemplate;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviors;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;
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

    /** Returns loaded furniture in this channel whose CE origin occupies the block. */
    public static Optional<BukkitFurniture> atBlock(Channel channel, Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        return nearby(channel, center, 1.0, 1.0).stream()
                .filter(furniture -> {
                    Location location = furniture.location();
                    return FurnitureSpatialSemantics.insideBlock(
                            location.getX(), location.getY(), location.getZ(),
                            block.getX(), block.getY(), block.getZ());
                })
                .findFirst();
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

        default void onUnavailable(BukkitFurniture furniture, boolean removed, boolean stopping) {
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
            unavailable(true, false);
        }

        @Override
        public void postRemove(Player player) {
            unavailable(true, false);
        }

        @Override
        public void onUnload(boolean isStopping) {
            unavailable(false, isStopping);
        }

        private void ready(ReadyReason reason, Player placingPlayer) {
            if (readyReason == null) {
                Location location = bukkitFurniture.location();
                worldId = location.getWorld().getUID();
                column = packColumn(location.getBlockX(), location.getBlockZ());
                Map<UUID, WorldIndex> channelWorlds = SPATIAL.computeIfAbsent(
                        channel, ignored -> new HashMap<>());
                WorldIndex worldIndex = channelWorlds.computeIfAbsent(
                        worldId, ignored -> new WorldIndex());
                worldIndex.columns.computeIfAbsent(column,
                        ignored -> new HashSet<>()).add(this);
            }
            readyReason = reason;
            READY.computeIfAbsent(channel,
                    ignored -> new HashSet<>()).add(this);
            deliver(HANDLERS.get(channel), placingPlayer);
        }

        private void unavailable(boolean removed, boolean stopping) {
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
            Map<UUID, WorldIndex> channelWorlds = SPATIAL.get(channel);
            if (channelWorlds != null) {
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
            Handler currentHandler = HANDLERS.get(channel);
            if (currentHandler != null && currentHandler == deliveredHandler) {
                currentHandler.onUnavailable(bukkitFurniture, removed, stopping);
            }
            readyReason = null;
            deliveredHandler = null;
            deliveredReason = null;
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
