package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.FurnitureSpatialSemantics;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.PressingTubLandingIndex;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.PressingTubSemantics;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 *
 * <p>实体移动事件热路径只查地面桶的落脚单元反向索引（一次 primitive long
 * 查询），不再对四列逐个扫描；墙面桶不登记落脚单元，也不启用全局落地监听器。</p>
 */
public final class PressingTubFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:pressing_tub_furniture";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Map<UUID, PressingTubLandingIndex<Controller>> WORLD_INDEX =
            new HashMap<>();
    private static int loadedGroundTubCount;
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

    /** 该世界是否已加载地面版压榨桶（墙面版不会让摔落处理继续进入）。 */
    public static boolean hasGroundTubInWorld(World world) {
        PressingTubLandingIndex<Controller> index = WORLD_INDEX.get(world.getUID());
        return index != null && index.hasGroundTubs();
    }

    /** Lets CE-loaded ground tubs enable the otherwise-global Paper fall bridge on demand. */
    public static void bindAvailability(Consumer<Boolean> handler) {
        availabilityHandler = Objects.requireNonNull(handler, "handler");
        available = loadedGroundTubCount > 0;
        handler.accept(available);
    }

    public static void unbindAvailability(Consumer<Boolean> handler) {
        if (availabilityHandler == handler) {
            availabilityHandler = null;
        }
    }

    /** Whether a loaded pressing-tub origin occupies this exact block. */
    public static boolean occupiesBlock(Block block) {
        PressingTubLandingIndex<Controller> index = WORLD_INDEX.get(block.getWorld().getUID());
        if (index == null) {
            return false;
        }
        ReferenceOpenHashSet<Controller> controllers =
                index.originCandidatesAt(block.getX(), block.getZ());
        if (controllers == null) {
            return false;
        }
        for (Controller controller : controllers) {
            if (FurnitureSpatialSemantics.insideBlock(
                    controller.baseX, controller.baseY, controller.baseZ,
                    block.getX(), block.getY(), block.getZ())) {
                return true;
            }
        }
        return false;
    }

    /** 移动事件热路径：单个落脚单元查询，不做四列扫描。 */
    public static boolean hasPotentialBelow(UUID worldId,
                                            double feetX, double feetY, double feetZ) {
        PressingTubLandingIndex<Controller> index = WORLD_INDEX.get(worldId);
        if (index == null || !index.hasGroundTubs()) {
            return false;
        }
        ReferenceOpenHashSet<Controller> candidates = index.landingCandidatesAt(
                (int) Math.floor(feetX), (int) Math.floor(feetZ));
        if (candidates == null) {
            return false;
        }
        for (Controller controller : candidates) {
            if (PressingTubSemantics.isAboveColumn(
                    feetX, feetY, feetZ,
                    controller.baseX, controller.baseY, controller.baseZ)) {
                return true;
            }
        }
        return false;
    }

    /** Finds the closest ground tub whose source block owns this landing. */
    public static Optional<BukkitFurniture> findBelow(UUID worldId,
                                                      double feetX, double feetY, double feetZ) {
        PressingTubLandingIndex<Controller> index = WORLD_INDEX.get(worldId);
        if (index == null || !index.hasGroundTubs()) {
            return Optional.empty();
        }
        ReferenceOpenHashSet<Controller> candidates = index.landingCandidatesAt(
                (int) Math.floor(feetX), (int) Math.floor(feetZ));
        if (candidates == null) {
            return Optional.empty();
        }
        List<PressingTubSemantics.LandingTarget> targets =
                new ArrayList<>(candidates.size());
        List<Controller> ordered = new ArrayList<>(candidates.size());
        for (Controller controller : candidates) {
            ordered.add(controller);
            targets.add(new PressingTubSemantics.LandingTarget(
                    controller.baseX, controller.baseY, controller.baseZ));
        }
        int best = PressingTubSemantics.nearestLanding(feetX, feetY, feetZ, targets);
        if (best < 0) {
            return Optional.empty();
        }
        return Optional.of(ordered.get(best).bukkitFurniture);
    }

    @Override
    public FurnitureController createController(Furniture furniture) {
        if (!(furniture instanceof BukkitFurniture bukkitFurniture)) {
            throw new IllegalArgumentException("Pressing-tub furniture requires BukkitFurniture");
        }
        return new Controller(bukkitFurniture);
    }

    private static final class Controller extends FurnitureController {
        private final BukkitFurniture bukkitFurniture;
        private UUID worldId;
        private double baseX;
        private double baseY;
        private double baseZ;
        private int originBlockX;
        private int originBlockZ;
        private int landingMinX;
        private int landingMaxX;
        private int landingMinZ;
        private int landingMaxZ;
        private boolean ground;
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

        @Override
        public void onVariantChange(FurnitureVariant previousVariant) {
            if (!indexed) {
                return;
            }
            /*
             * CE 的 moveTo / 变体切换只更新 location 并重建变体，不触发
             * onLoad/onUnload；这里直接重索引。不要分别调用 unindex/index：
             * 那样可能瞬间把 loadedGroundTubCount 降为 0，触发监听器注销并
             * 清空 falling。
             */
            removeFromSpatialIndex();
            addToSpatialIndex();
            updateAvailability();
        }

        private void index() {
            if (indexed) {
                return;
            }
            addToSpatialIndex();
            updateAvailability();
        }

        private void unindex() {
            if (!indexed) {
                return;
            }
            removeFromSpatialIndex();
            updateAvailability();
        }

        private void addToSpatialIndex() {
            Location location = bukkitFurniture.location();
            worldId = location.getWorld().getUID();
            baseX = location.getX();
            baseY = location.getY();
            baseZ = location.getZ();
            originBlockX = location.getBlockX();
            originBlockZ = location.getBlockZ();
            ground = bukkitFurniture.currentVariant().name().equals("ground");
            landingMinX = FurnitureSpatialSemantics.minimumColumn(baseX, 0.5);
            landingMaxX = FurnitureSpatialSemantics.maximumColumn(baseX, 0.5);
            landingMinZ = FurnitureSpatialSemantics.minimumColumn(baseZ, 0.5);
            landingMaxZ = FurnitureSpatialSemantics.maximumColumn(baseZ, 0.5);

            PressingTubLandingIndex<Controller> index =
                    WORLD_INDEX.computeIfAbsent(worldId, ignored -> new PressingTubLandingIndex<>());
            index.add(this, ground,
                    originBlockX, originBlockZ,
                    landingMinX, landingMaxX,
                    landingMinZ, landingMaxZ);
            if (ground) {
                loadedGroundTubCount++;
            }
            indexed = true;
        }

        private void removeFromSpatialIndex() {
            PressingTubLandingIndex<Controller> index = WORLD_INDEX.get(worldId);
            if (index != null) {
                // 移除时使用缓存的边界与列，不需要重新计算。
                index.remove(this, ground,
                        originBlockX, originBlockZ,
                        landingMinX, landingMaxX,
                        landingMinZ, landingMaxZ);
                if (ground) {
                    loadedGroundTubCount--;
                }
                if (index.isEmpty()) {
                    WORLD_INDEX.remove(worldId, index);
                }
            }
            indexed = false;
        }
    }

    private static void updateAvailability() {
        boolean current = loadedGroundTubCount > 0;
        boolean previous = available;
        available = current;
        Consumer<Boolean> handler = availabilityHandler;
        if (previous != current && handler != null) {
            handler.accept(current);
        }
    }
}
