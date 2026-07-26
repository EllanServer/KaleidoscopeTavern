package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.PressingTubSemantics;
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
import org.bukkit.World;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets CraftEngine own the loaded pressing-tub lifecycle and spatial lookup.
 *
 * <p>Paper has no block-style {@code fallOn} callback for furniture, so the
 * landing event remains in StationService. The expensive furniture discovery
 * does not: controllers register their stationary columns when CE loads or
 * places them and unregister on removal or chunk unload.</p>
 */
public final class PressingTubFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:pressing_tub_furniture";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final ConcurrentMap<UUID, WorldIndex> WORLD_INDEX =
            new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Controller> LOADED =
            new ConcurrentHashMap<>();
    private static volatile Handler handler;

    private PressingTubFurnitureBehavior(FurnitureDefinition furniture, ConfigSection section) {
        super(furniture);
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            FurnitureBehaviors.register(Key.of(TYPE), PressingTubFurnitureBehavior::new);
        }
    }

    public static void bind(Handler newHandler) {
        handler = Objects.requireNonNull(newHandler, "newHandler");
        LOADED.values().forEach(controller -> controller.deliver(newHandler));
    }

    public static void unbind(Handler oldHandler) {
        if (handler == oldHandler) {
            handler = null;
            LOADED.values().forEach(controller -> controller.forget(oldHandler));
        }
    }

    public static boolean hasLoadedInWorld(World world) {
        WorldIndex index = WORLD_INDEX.get(world.getUID());
        return index != null && !index.columns.isEmpty();
    }

    /** Whether a falling entity is horizontally above any loaded ground tub. */
    public static boolean hasPotentialBelow(Location feet) {
        WorldIndex index = WORLD_INDEX.get(feet.getWorld().getUID());
        if (index == null) {
            return false;
        }
        int minX = floor(feet.getX() - 0.5);
        int maxX = floor(feet.getX() + 0.5);
        int minZ = floor(feet.getZ() - 0.5);
        int maxZ = floor(feet.getZ() + 0.5);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Set<Controller> controllers = index.columns.get(packColumn(x, z));
                if (controllers == null) {
                    continue;
                }
                for (Controller controller : controllers) {
                    BukkitFurniture furniture = controller.bukkitFurniture;
                    if (!furniture.isValid() || !isGround(furniture)) {
                        continue;
                    }
                    Location base = furniture.location();
                    if (PressingTubSemantics.isAboveColumn(
                            feet.getX(), feet.getY(), feet.getZ(),
                            base.getX(), base.getY(), base.getZ())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Finds the closest ground tub whose source block owns this landing. */
    public static Optional<BukkitFurniture> findBelow(Location feet) {
        WorldIndex index = WORLD_INDEX.get(feet.getWorld().getUID());
        if (index == null) {
            return Optional.empty();
        }
        BukkitFurniture closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        int minX = floor(feet.getX() - 0.5);
        int maxX = floor(feet.getX() + 0.5);
        int minZ = floor(feet.getZ() - 0.5);
        int maxZ = floor(feet.getZ() + 0.5);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Set<Controller> controllers = index.columns.get(packColumn(x, z));
                if (controllers == null) {
                    continue;
                }
                for (Controller controller : controllers) {
                    BukkitFurniture furniture = controller.bukkitFurniture;
                    if (!furniture.isValid() || !isGround(furniture)) {
                        continue;
                    }
                    Location base = furniture.location();
                    if (!PressingTubSemantics.isLandingPosition(
                            feet.getX(), feet.getY(), feet.getZ(),
                            base.getX(), base.getY(), base.getZ())) {
                        continue;
                    }
                    double dx = feet.getX() - base.getX();
                    double dy = feet.getY() - base.getY();
                    double dz = feet.getZ() - base.getZ();
                    double distance = dx * dx + dy * dy + dz * dz;
                    if (distance < closestDistance) {
                        closest = furniture;
                        closestDistance = distance;
                    }
                }
            }
        }
        return Optional.ofNullable(closest);
    }

    @Override
    public FurnitureController createController(Furniture furniture) {
        if (!(furniture instanceof BukkitFurniture bukkitFurniture)) {
            throw new IllegalArgumentException("Pressing-tub furniture requires BukkitFurniture");
        }
        return new Controller(bukkitFurniture);
    }

    @FunctionalInterface
    public interface Handler {
        void onReady(BukkitFurniture furniture);
    }

    private static boolean isGround(BukkitFurniture furniture) {
        return furniture.currentVariant().name().equals("ground");
    }

    private static int floor(double coordinate) {
        return (int) Math.floor(coordinate);
    }

    private static long packColumn(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static final class Controller extends FurnitureController {
        private final BukkitFurniture bukkitFurniture;
        private UUID worldId;
        private long column;
        private boolean indexed;
        private Handler deliveredHandler;

        private Controller(BukkitFurniture furniture) {
            super(furniture);
            this.bukkitFurniture = furniture;
        }

        @Override
        public void onPlace(Player player) {
            index();
        }

        @Override
        public void onLoad() {
            index();
        }

        @Override
        public void postRemove(Player player) {
            unindex();
        }

        @Override
        public void onUnload(boolean isStopping) {
            unindex();
        }

        private void index() {
            if (indexed) {
                deliver(handler);
                return;
            }
            Location location = bukkitFurniture.location();
            worldId = location.getWorld().getUID();
            column = packColumn(location.getBlockX(), location.getBlockZ());
            WorldIndex worldIndex = WORLD_INDEX.computeIfAbsent(worldId,
                    ignored -> new WorldIndex());
            worldIndex.columns.computeIfAbsent(column,
                    ignored -> ConcurrentHashMap.<Controller>newKeySet()).add(this);
            indexed = true;
            Controller replaced = LOADED.put(bukkitFurniture.uuid(), this);
            if (replaced != null && replaced != this) {
                replaced.unindex();
            }
            deliver(handler);
        }

        private void unindex() {
            if (!indexed) {
                return;
            }
            WorldIndex worldIndex = WORLD_INDEX.get(worldId);
            if (worldIndex != null) {
                Set<Controller> controllers = worldIndex.columns.get(column);
                if (controllers != null) {
                    controllers.remove(this);
                    if (controllers.isEmpty()) {
                        worldIndex.columns.remove(column, controllers);
                    }
                }
                if (worldIndex.columns.isEmpty()) {
                    WORLD_INDEX.remove(worldId, worldIndex);
                }
            }
            LOADED.remove(bukkitFurniture.uuid(), this);
            indexed = false;
            deliveredHandler = null;
        }

        private void deliver(Handler currentHandler) {
            if (currentHandler == null || currentHandler == deliveredHandler) {
                return;
            }
            deliveredHandler = currentHandler;
            currentHandler.onReady(bukkitFurniture);
        }

        private void forget(Handler oldHandler) {
            if (deliveredHandler == oldHandler) {
                deliveredHandler = null;
            }
        }
    }

    private static final class WorldIndex {
        private final ConcurrentMap<Long, Set<Controller>> columns =
                new ConcurrentHashMap<>();
    }
}
