package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviorTemplate;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviors;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.furniture.element.FurnitureElement;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypesProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Renders the dynamic contents of pressing tubs and barrels as one CE-managed
 * packet-only furniture element.
 *
 * <p>The source block-entity renderers can show dozens of ingredient copies
 * with arbitrary rotations. CE's static furniture variants cannot express a
 * runtime-sized list, so this behavior keeps CE responsible for tracking and
 * culling while Tavern supplies only the exact source transforms. No Bukkit
 * display entity or recovery PDC is created.</p>
 *
 * <p>Refreshes are differential: the packet-only entities keep their ids
 * between versions, so a content change only re-sends changed metadata,
 * moves moved entities, spawns new slots and removes dropped ones instead of
 * destroying and recreating the whole pile for every tracking player. The
 * operation list is computed by the pure {@link StationVisualDiff} state
 * machine; packets are created lazily, once per snapshot, and shared across
 * players. Only the first-time view-range metadata stays per-player.</p>
 */
public final class StationVisualFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:station_visual_furniture";
    public static final byte ITEM_TRANSFORM_NONE = 0;
    public static final byte ITEM_TRANSFORM_FIXED = 8;

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final ConcurrentMap<UUID, Controller> LOADED = new ConcurrentHashMap<>();
    private static volatile Handler handler;

    private final int maxElements;
    private final float viewRange;

    private StationVisualFurnitureBehavior(FurnitureDefinition furniture, ConfigSection section) {
        super(furniture);
        this.maxElements = Math.max(1, section.getInt("max_elements", 1));
        this.viewRange = Math.max(0.1F, section.getFloat("view_range", 1.25F));
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            VirtualEntityIdentity.prewarm();
            StationVisualElement.prewarm();
            FurnitureBehaviors.register(Key.of(TYPE), StationVisualFurnitureBehavior::new);
        }
    }

    public static void bind(Handler newHandler) {
        handler = Objects.requireNonNull(newHandler, "newHandler");
        LOADED.values().forEach(Controller::refresh);
    }

    public static void unbind(Handler oldHandler) {
        if (handler == oldHandler) {
            handler = null;
            LOADED.values().forEach(Controller::refresh);
        }
    }

    /** Invalidates player-independent visual content before CE redistributes it. */
    public static void refresh(BukkitFurniture furniture) {
        Objects.requireNonNull(furniture, "furniture");
        Controller controller = LOADED.get(furniture.uuid());
        if (controller != null) {
            controller.refresh();
        } else {
            furniture.refreshElements();
        }
    }

    @Override
    public FurnitureController createController(Furniture furniture) {
        if (!(furniture instanceof BukkitFurniture bukkitFurniture)) {
            throw new IllegalArgumentException("Station visuals require BukkitFurniture");
        }
        return new Controller(bukkitFurniture, maxElements, viewRange);
    }

    @FunctionalInterface
    public interface Handler {
        /** Builds at most {@code limit} visuals (including the fluid slot). */
        List<Visual> visuals(BukkitFurniture furniture, int limit);
    }

    /**
     * Immutable visual description. The left rotation is stored as four floats
     * in JOML order (x, y, z, w) so snapshots can share one record across every
     * tracking player without copying a mutable {@link Quaternionf} per visual
     * per refresh. Empty items are rejected: a station visual must always carry
     * a real display item, which keeps the diff state machine free of
     * empty-slot transitions.
     */
    public record Visual(Item item, double x, double y, double z,
                         float yRot, float xRot, float scale,
                         float rotX, float rotY, float rotZ, float rotW,
                         byte itemTransform) {
        public Visual {
            Objects.requireNonNull(item, "item");
            if (item.isEmpty()) {
                throw new IllegalArgumentException("Visual item cannot be empty");
            }
        }

        public static Visual of(Item item, double x, double y, double z,
                                float yRot, float xRot, float scale,
                                Quaternionf leftRotation, byte itemTransform) {
            return new Visual(item, x, y, z, yRot, xRot, scale,
                    leftRotation.x, leftRotation.y, leftRotation.z, leftRotation.w,
                    itemTransform);
        }

        public Quaternionf leftRotation() {
            return new Quaternionf(rotX, rotY, rotZ, rotW);
        }
    }

    private record VisualSnapshot(long generation, List<Visual> visuals) {
    }

    private record ViewerState(long generation, int visibleCount) {
    }

    private static final class Controller extends FurnitureController {
        private final BukkitFurniture bukkitFurniture;
        private final int maxElements;
        private final float viewRange;
        private VisualSnapshot previousSnapshot;
        private VisualSnapshot currentSnapshot;
        private boolean visualsDirty = true;
        private long generation;

        private Controller(BukkitFurniture furniture, int maxElements, float viewRange) {
            super(furniture);
            this.bukkitFurniture = furniture;
            this.maxElements = maxElements;
            this.viewRange = viewRange;
        }

        @Override
        public void gatherElements(Consumer<FurnitureElement> consumer) {
            invalidateVisuals();
            consumer.accept(new StationVisualElement(
                    this, maxElements, viewRange));
        }

        @Override
        public void onPlace(Player player) {
            LOADED.put(bukkitFurniture.uuid(), this);
        }

        @Override
        public void onLoad() {
            LOADED.put(bukkitFurniture.uuid(), this);
        }

        @Override
        public void postRemove(Player player) {
            LOADED.remove(bukkitFurniture.uuid(), this);
        }

        @Override
        public void onUnload(boolean isStopping) {
            LOADED.remove(bukkitFurniture.uuid(), this);
        }

        private void refresh() {
            invalidateVisuals();
            bukkitFurniture.refreshElements();
        }

        private void invalidateVisuals() {
            previousSnapshot = currentSnapshot;
            currentSnapshot = null;
            visualsDirty = true;
        }

        /** Lazily builds the current snapshot and returns it. */
        private VisualSnapshot currentSnapshot() {
            if (visualsDirty) {
                Handler currentHandler = handler;
                List<Visual> visuals = currentHandler == null
                        ? List.of()
                        : List.copyOf(currentHandler.visuals(bukkitFurniture, maxElements));
                currentSnapshot = new VisualSnapshot(++generation, visuals);
                visualsDirty = false;
            }
            return currentSnapshot;
        }

        private VisualSnapshot previousSnapshot() {
            return previousSnapshot;
        }
    }

    private static final class StationVisualElement implements FurnitureElement {
        private final Controller controller;
        private final BukkitFurniture furniture;
        private final int maxElements;
        private final float viewRange;
        private int[] entityIds = new int[8];
        private UUID[] entityUuids = new UUID[8];
        private int allocated;
        private final Map<Player, ViewerState> viewerStates = new IdentityHashMap<>();
        private long preparedGeneration = -1;
        private PreparedVisual[] prepared = new PreparedVisual[0];
        // 每代只计算一次的增量 diff；本代无 SPAWN 时 sharedPackets 为整代共享的
        // packet 列表（null 表示含 SPAWN，需按玩家构建以附加 ViewRange）。
        private long opsGeneration = -1;
        private List<StationVisualDiff.Op> incrementalOps;
        private List<Object> sharedPackets;

        private StationVisualElement(Controller controller, int maxElements,
                                     float viewRange) {
            this.controller = controller;
            this.furniture = controller.bukkitFurniture;
            this.maxElements = maxElements;
            this.viewRange = viewRange;
        }

        private static void prewarm() {
        }

        private void ensureIdentityCapacity(int required) {
            if (required > entityIds.length) {
                int capacity = Math.max(required, entityIds.length << 1);
                entityIds = Arrays.copyOf(entityIds, capacity);
                entityUuids = Arrays.copyOf(entityUuids, capacity);
            }
            while (allocated < required) {
                int id = EntityUtils.ENTITY_COUNTER.incrementAndGet();
                entityIds[allocated] = id;
                entityUuids[allocated] = VirtualEntityIdentity.fromEntityId(id);
                allocated++;
            }
        }

        /**
         * Reserves identities and prepares the visual list for a snapshot. The
         * actual packet objects are created lazily by {@link PreparedVisual},
         * so an incremental refresh only allocates the packets it really sends;
         * already-created packets are shared by every tracking player.
         */
        private PreparedVisual[] prepared(VisualSnapshot snapshot) {
            if (preparedGeneration != snapshot.generation()) {
                List<Visual> visuals = snapshot.visuals();
                int count = Math.min(maxElements, visuals.size());
                ensureIdentityCapacity(count);
                PreparedVisual[] result = new PreparedVisual[count];
                for (int index = 0; index < count; index++) {
                    result[index] = new PreparedVisual(this, index, visuals.get(index));
                }
                prepared = result;
                preparedGeneration = snapshot.generation();
            }
            return prepared;
        }

        private Object spawnPacket(int index, Visual visual) {
            return ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
                    entityIds[index], entityUuids[index],
                    visual.x(), visual.y(), visual.z(),
                    visual.xRot(), visual.yRot(),
                    EntityTypesProxy.ITEM_DISPLAY, 0, Vec3Proxy.ZERO, 0);
        }

        private Object staticMetadataPacket(int index, Visual visual) {
            List<Object> metadata = new ArrayList<>(4);
            DisplayData.ItemDisplayData.ItemStack.addEntityData(
                    visual.item().minecraftItem(), metadata);
            // 实体 ID 会在代间复用（原料视觉可能变为液体视觉），差量更新时
            // 必须无条件写回变换字段：FIXED/NONE、scale、四元数一旦等于客户端
            // 默认值，IfNotDefaultValue 会省略，客户端会残留上一状态的旧变换。
            DisplayData.ItemDisplayData.ItemTransform.addEntityData(
                    visual.itemTransform(), metadata);
            DisplayData.Scale.addEntityData(
                    new Vector3f(visual.scale()), metadata);
            DisplayData.LeftRotation.addEntityData(
                    visual.leftRotation(), metadata);
            return ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(
                    entityIds[index], metadata);
        }

        private Object positionPacket(int index, Visual visual) {
            return EntityUtils.createUpdatePosPacket(
                    entityIds[index],
                    visual.x(), visual.y(), visual.z(),
                    visual.yRot(), visual.xRot(), false);
        }

        private Object viewRangePacket(int index, Player player) {
            List<Object> metadata = new ArrayList<>(1);
            // ViewRange 只在首次 show / 新增实体时随玩家当前的
            // displayEntityViewDistance() 一起烘焙发送，后续差量刷新不再重发。
            // 这与 CraftEngine 原生元素（ItemDisplayFurnitureElementConfig 等）
            // 的行为一致：setDisplayEntityViewDistanceScale 只更新字段与 PDC，
            // 不会统一重发已存在实体的 ViewRange，玩家改设置后需重新 show 才生效。
            DisplayData.ViewRange.addEntityDataIfNotDefaultValue(
                    (float) (viewRange * player.displayEntityViewDistance()), metadata);
            return metadata.isEmpty() ? null
                    : ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(
                            entityIds[index], metadata);
        }

        private Object removePacket(int from, int to) {
            IntArrayList ids = new IntArrayList(to - from);
            for (int index = from; index < to; index++) {
                ids.add(entityIds[index]);
            }
            return ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance(ids);
        }

        @Override
        public void gatherInteractableEntityId(Consumer<Integer> collector) {
        }

        @Override
        public void show(Player player) {
            VisualSnapshot snapshot = controller.currentSnapshot();
            PreparedVisual[] currentPrepared = prepared(snapshot);
            sendFullResync(player, currentPrepared, null, snapshot.generation());
        }

        @Override
        public void hide(Player player) {
            ViewerState state = viewerStates.remove(player);
            if (state != null && state.visibleCount > 0) {
                player.sendPacket(removePacket(0, state.visibleCount), false);
            }
        }

        @Override
        public void update(Player player) {
            VisualSnapshot current = controller.currentSnapshot();
            VisualSnapshot previous = controller.previousSnapshot();
            PreparedVisual[] currentPrepared = prepared(current);
            ViewerState state = viewerStates.get(player);
            if (state == null || previous == null
                    || previous.generation() != state.generation) {
                // First sight or more than one version behind: the old entities
                // must be dropped explicitly before respawning the new list.
                sendFullResync(player, currentPrepared, state, current.generation());
                return;
            }
            sendIncremental(player, current, previous, currentPrepared);
        }

        /** 落后一代以上或首次：按玩家单独 full resync。 */
        private void sendFullResync(Player player, PreparedVisual[] currentPrepared,
                                    ViewerState state, long generation) {
            int lastCount = state == null ? 0 : state.visibleCount;
            sendOps(player, currentPrepared,
                    StationVisualDiff.fullResync(lastCount, currentPrepared.length),
                    generation, currentPrepared.length);
        }

        /**
         * 跟上版本的观察者共享同一份增量 diff；本代无 SPAWN 时连 packet 列表
         * 也整代共享，只有含 SPAWN 的代才退回按玩家构建（需逐玩家 ViewRange）。
         */
        private void sendIncremental(Player player, VisualSnapshot current,
                                     VisualSnapshot previous,
                                     PreparedVisual[] currentPrepared) {
            prepareIncrementalOps(current, previous, currentPrepared);
            viewerStates.put(player, new ViewerState(
                    current.generation(), currentPrepared.length));
            if (incrementalOps.isEmpty()) {
                return;
            }
            if (sharedPackets != null) {
                player.sendPackets(sharedPackets, false);
                return;
            }
            sendOps(player, currentPrepared, incrementalOps,
                    current.generation(), currentPrepared.length);
        }

        private void prepareIncrementalOps(VisualSnapshot current,
                                           VisualSnapshot previous,
                                           PreparedVisual[] currentPrepared) {
            if (opsGeneration == current.generation()) {
                return;
            }
            opsGeneration = current.generation();
            // 跟上版本的观察者 visibleCount 都等于上一代的 prepared 长度。
            int previousCount = Math.min(maxElements, previous.visuals().size());
            incrementalOps = StationVisualDiff.compute(
                    previous.visuals(), current.visuals(),
                    previousCount, currentPrepared.length);
            if (incrementalOps.isEmpty()) {
                sharedPackets = List.of();
                return;
            }
            List<Object> packets = new ArrayList<>(incrementalOps.size() * 2 + 1);
            for (StationVisualDiff.Op op : incrementalOps) {
                if (op.type() == StationVisualDiff.OpType.SPAWN) {
                    // SPAWN 需要逐玩家的 ViewRange，本代退回 per-player 构建。
                    sharedPackets = null;
                    return;
                }
                packets.add(packetFor(currentPrepared, op));
            }
            sharedPackets = packets;
        }

        private Object packetFor(PreparedVisual[] currentPrepared,
                                 StationVisualDiff.Op op) {
            return switch (op.type()) {
                case METADATA -> currentPrepared[op.index()].metadataPacket();
                case POSITION -> currentPrepared[op.index()].positionPacket();
                case REMOVE -> removePacket(op.index(), op.to());
                case SPAWN -> throw new AssertionError(
                        "SPAWN packets are built per player for ViewRange");
            };
        }

        private void sendOps(Player player, PreparedVisual[] currentPrepared,
                             List<StationVisualDiff.Op> ops,
                             long generation, int visibleCount) {
            viewerStates.put(player, new ViewerState(generation, visibleCount));
            if (ops.isEmpty()) {
                return;
            }
            List<Object> packets = new ArrayList<>(ops.size() * 2 + 1);
            for (StationVisualDiff.Op op : ops) {
                switch (op.type()) {
                    case SPAWN -> {
                        PreparedVisual entry = currentPrepared[op.index()];
                        packets.add(entry.spawnPacket());
                        packets.add(entry.metadataPacket());
                        Object viewRange = viewRangePacket(op.index(), player);
                        if (viewRange != null) {
                            packets.add(viewRange);
                        }
                    }
                    case METADATA -> packets.add(
                            currentPrepared[op.index()].metadataPacket());
                    case POSITION -> packets.add(
                            currentPrepared[op.index()].positionPacket());
                    case REMOVE -> packets.add(removePacket(op.index(), op.to()));
                    default -> throw new AssertionError("unreachable op type");
                }
            }
            player.sendPackets(packets, false);
        }
    }

    /**
     * Viewer-independent packets are created on first use and then shared by
     * every tracking player. A differential refresh therefore only allocates
     * the packets it actually sends.
     */
    private static final class PreparedVisual {
        private final StationVisualElement element;
        private final int index;
        private final Visual visual;
        private Object spawnPacket;
        private Object metadataPacket;
        private Object positionPacket;

        private PreparedVisual(StationVisualElement element, int index, Visual visual) {
            this.element = element;
            this.index = index;
            this.visual = visual;
        }

        private Visual visual() {
            return visual;
        }

        private Object spawnPacket() {
            if (spawnPacket == null) {
                spawnPacket = element.spawnPacket(index, visual);
            }
            return spawnPacket;
        }

        private Object metadataPacket() {
            if (metadataPacket == null) {
                metadataPacket = element.staticMetadataPacket(index, visual);
            }
            return metadataPacket;
        }

        private Object positionPacket() {
            if (positionPacket == null) {
                positionPacket = element.positionPacket(index, visual);
            }
            return positionPacket;
        }
    }
}
