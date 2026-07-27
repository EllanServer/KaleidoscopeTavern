package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.FurnitureSpatialSemantics;
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
import org.bukkit.block.Block;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    private static final Map<UUID, WorldIndex> WORLD_INDEX = new HashMap<>();
    private static boolean available;
    private static Consumer<Boolean> availabilityHandler;

    private PressingTubFurnitureBehavior(FurnitureDefinition furniture, ConfigSection section) {
        super(furniture);
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            FurnitureBehaviors.register(Key.of(TYPE), PressingTubFurnitureBehavior::new);
        }
    }

    public static boolean hasLoadedInWorld(World world) {
        WorldIndex index = WORLD_INDEX.get(world.getUID());
        return index != null && !index.columns.isEmpty();
    }

    /** Lets CE-loaded tubs enable the otherwise-global Paper fall bridge on demand. */
    public static void bindAvailability(Consumer<Boolean> handler) {
        availabilityHandler = Objects.requireNonNull(handler, "handler");
        available = !WORLD_INDEX.isEmpty();
        handler.accept(available);
    }

    public static void unbindAvailability(Consumer<Boolean> handler) {
        if (availabilityHandler == handler) {
            availabilityHandler = null;
        }
    }

    /** Whether a loaded pressing-tub origin occupies this exact block. */
    public static boolean occupiesBlock(Block block) {
        WorldIndex index = WORLD_INDEX.get(block.getWorld().getUID());
        if (index == null) {
            return false;
        }
        Set<Controller> controllers = index.columns.get(
                packColumn(block.getX(), block.getZ()));
        if (controllers == null) {
            return false;
        }
        for (Controller controller : controllers) {
            BukkitFurniture furniture = controller.bukkitFurniture;
            Location location = furniture.location();
            if (FurnitureSpatialSemantics.insideBlock(
                    location.getX(), location.getY(), location.getZ(),
                    block.getX(), block.getY(), block.getZ())) {
                return true;
            }
        }
        return false;
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
                    if (!isGround(furniture)) {
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
                    if (!isGround(furniture)) {
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
        public void preRemove(Player player) {
            unindex();
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
                return;
            }
            Location location = bukkitFurniture.location();
            worldId = location.getWorld().getUID();
            column = packColumn(location.getBlockX(), location.getBlockZ());
            WorldIndex worldIndex = WORLD_INDEX.computeIfAbsent(worldId,
                    ignored -> new WorldIndex());
            worldIndex.columns.computeIfAbsent(column,
                    ignored -> new HashSet<>()).add(this);
            indexed = true;
            updateAvailability();
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
            indexed = false;
            updateAvailability();
        }
    }

    private static void updateAvailability() {
        boolean current = !WORLD_INDEX.isEmpty();
        boolean previous = available;
        available = current;
        Consumer<Boolean> handler = availabilityHandler;
        if (previous != current && handler != null) {
            handler.accept(current);
        }
    }

    private static final class WorldIndex {
        private final Map<Long, Set<Controller>> columns = new HashMap<>();
    }
}
