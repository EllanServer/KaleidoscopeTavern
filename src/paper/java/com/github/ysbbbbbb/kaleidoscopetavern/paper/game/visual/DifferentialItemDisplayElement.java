package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.visual;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.VirtualEntityIdentity;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypesProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 中立差量显示元素：把「0..N 个动态内容视觉」渲染为 packet-only 的 ItemDisplay
 * 实体集合，同时供 CE 家具（FurnitureElement 包装）与 CE 自定义方块
 * （BlockEntityElement 包装）使用。
 *
 * <p>CE 负责何时调用 {@link #show}/{@link #hide}/{@link #update} 与玩家追踪，
 * 本类只负责内容差量：实体 ID 按需延迟分配并稳定复用（最多 {@code maxElements}
 * 个，空桶不分配 ID），刷新只重发变化的 metadata / 移动 / 新增 / 删除，而不是
 * 销毁重建整组视觉。无 SPAWN 的代共享同一份 packet 列表，跨玩家零重复构建。</p>
 *
 * <p>实体 ID 在代间复用（原料视觉可能变为液体视觉），因此 metadata 中的
 * ItemTransform / Scale / LeftRotation 必须无条件写回，不能用 IfNotDefaultValue
 * 省略，否则客户端会残留上一状态的旧变换。</p>
 */
public final class DifferentialItemDisplayElement {

    /** 当前视觉快照的提供者；每次内容失效后重新查询一次。 */
    @FunctionalInterface
    public interface VisualProvider {
        List<DisplayVisual> visuals(int limit);
    }

    private final VisualProvider provider;
    private final int maxElements;
    private final float viewRange;

    private int[] entityIds = new int[8];
    private UUID[] entityUuids = new UUID[8];
    private int allocated;
    private final Map<Player, ViewerState> viewerStates = new IdentityHashMap<>();
    private PreparedVisual[] prepared = new PreparedVisual[0];
    private long preparedGeneration = -1;

    // 自包含的快照/代模型：invalidate 后下一次查询推进 generation。
    private long generation;
    private List<DisplayVisual> previous = List.of();
    private List<DisplayVisual> current = List.of();
    private boolean dirty = true;

    private final DisplayVisualDiff.GenerationDiff generationDiff =
            new DisplayVisualDiff.GenerationDiff();
    // 本代无 SPAWN 时整代共享的 packet 列表（null 表示含 SPAWN，需按玩家构建）。
    private List<Object> sharedPackets;
    private long sharedPacketsGeneration = -1;

    public DifferentialItemDisplayElement(VisualProvider provider, int maxElements,
                                          float viewRange) {
        this.provider = provider;
        this.maxElements = maxElements;
        this.viewRange = viewRange;
    }

    /**
     * Initializes the packet-visual implementation during plugin loading.
     *
     * <p>The first real block entity must not pay for loading this class, its
     * diff state, collection implementations, and nested array classes on the
     * server tick thread. The throwaway instance deliberately follows the
     * normal constructor path without allocating entity ids or sending any
     * packets.</p>
     */
    public static void prewarm() {
        new DifferentialItemDisplayElement(
                EmptyVisualProvider.INSTANCE, 0, 0.0F);
    }

    private static final class EmptyVisualProvider implements VisualProvider {
        private static final EmptyVisualProvider INSTANCE =
                new EmptyVisualProvider();

        private EmptyVisualProvider() {
        }

        @Override
        public List<DisplayVisual> visuals(int limit) {
            return List.of();
        }
    }

    /** 内容已变化：下一次 {@link #show}/{@link #update} 会重新查询视觉列表并推进
     * 一代。保持上一代的列表用于差量比较。 */
    public void invalidate() {
        previous = current;
        current = List.of();
        dirty = true;
    }

    public void show(Player player) {
        List<DisplayVisual> snapshot = current();
        PreparedVisual[] currentPrepared = prepared(snapshot);
        sendFullResync(player, currentPrepared, null, generation);
    }

    public void hide(Player player) {
        ViewerState state = viewerStates.remove(player);
        if (state != null && state.visibleCount > 0) {
            player.sendPacket(removePacket(0, state.visibleCount), false);
        }
    }

    public void update(Player player) {
        List<DisplayVisual> snapshot = current();
        PreparedVisual[] currentPrepared = prepared(snapshot);
        ViewerState state = viewerStates.get(player);
        if (state == null) {
            sendFullResync(player, currentPrepared, null, generation);
            return;
        }
        if (state.generation == generation - 1) {
            // 恰好落后一代：对上一代做增量差量。
            sendIncremental(player, currentPrepared, generation);
            return;
        }
        if (state.generation != generation) {
            // 落后一代以上：旧实体必须显式清除后再整体重建。
            sendFullResync(player, currentPrepared, state, generation);
        }
        // state.generation == generation：观察者已是最新，无需任何 packet。
    }

    private List<DisplayVisual> current() {
        if (dirty) {
            current = List.copyOf(provider.visuals(maxElements));
            generation++;
            dirty = false;
        }
        return current;
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

    private PreparedVisual[] prepared(List<DisplayVisual> snapshot) {
        if (preparedGeneration != generation) {
            int count = Math.min(maxElements, snapshot.size());
            ensureIdentityCapacity(count);
            PreparedVisual[] result = new PreparedVisual[count];
            for (int index = 0; index < count; index++) {
                result[index] = new PreparedVisual(this, index, snapshot.get(index));
            }
            prepared = result;
            preparedGeneration = generation;
        }
        return prepared;
    }

    private Object spawnPacket(int index, DisplayVisual visual) {
        return ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
                entityIds[index], entityUuids[index],
                visual.x(), visual.y(), visual.z(),
                visual.xRot(), visual.yRot(),
                EntityTypesProxy.ITEM_DISPLAY, 0, Vec3Proxy.ZERO, 0);
    }

    private Object staticMetadataPacket(int index, DisplayVisual visual) {
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

    private Object positionPacket(int index, DisplayVisual visual) {
        return EntityUtils.createUpdatePosPacket(
                entityIds[index],
                visual.x(), visual.y(), visual.z(),
                visual.yRot(), visual.xRot(), false);
    }

    private Object viewRangePacket(int index, Player player) {
        List<Object> metadata = new ArrayList<>(1);
        // ViewRange 只在首次 show / 新增实体时随玩家当前的
        // displayEntityViewDistance() 一起烘焙发送，后续差量刷新不再重发。
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

    /** 落后一代以上或首次：按玩家单独 full resync。 */
    private void sendFullResync(Player player, PreparedVisual[] currentPrepared,
                                ViewerState state, long generation) {
        int lastCount = state == null ? 0 : state.visibleCount;
        sendOps(player, currentPrepared,
                DisplayVisualDiff.fullResync(lastCount, currentPrepared.length),
                generation, currentPrepared.length);
    }

    /**
     * 跟上版本的观察者共享同一份增量 diff；本代无 SPAWN 时连 packet 列表
     * 也整代共享，只有含 SPAWN 的代才退回按玩家构建（需逐玩家 ViewRange）。
     */
    private void sendIncremental(Player player, PreparedVisual[] currentPrepared,
                                 long generation) {
        List<DisplayVisualDiff.Op> ops = prepareIncrementalOps(currentPrepared);
        viewerStates.put(player, new ViewerState(generation, currentPrepared.length));
        if (ops.isEmpty()) {
            return;
        }
        if (sharedPackets != null) {
            player.sendPackets(sharedPackets, false);
            return;
        }
        sendOps(player, currentPrepared, ops, generation, currentPrepared.length);
    }

    /**
     * 返回本代增量 diff（按 generation 缓存，所有跟上版本观察者共享同一
     * 实例），并预构建无 SPAWN 时整代共享的 packet 列表。
     */
    private List<DisplayVisualDiff.Op> prepareIncrementalOps(
            PreparedVisual[] currentPrepared) {
        List<DisplayVisualDiff.Op> ops = generationDiff.forGeneration(
                generation, previous, current, maxElements, currentPrepared.length);
        if (!generationDiff.isSharedEligible()) {
            // 含 SPAWN：需逐玩家 ViewRange，退回 per-player 构建。
            sharedPackets = null;
            return ops;
        }
        if (sharedPacketsGeneration != generation) {
            sharedPacketsGeneration = generation;
            sharedPackets = buildSharedPackets(currentPrepared, ops);
        }
        return ops;
    }

    private List<Object> buildSharedPackets(PreparedVisual[] currentPrepared,
                                            List<DisplayVisualDiff.Op> ops) {
        if (ops.isEmpty()) {
            return List.of();
        }
        List<Object> packets = new ArrayList<>(ops.size() * 2 + 1);
        for (DisplayVisualDiff.Op op : ops) {
            packets.add(packetFor(currentPrepared, op));
        }
        return packets;
    }

    private Object packetFor(PreparedVisual[] currentPrepared,
                             DisplayVisualDiff.Op op) {
        return switch (op.type()) {
            case METADATA -> currentPrepared[op.index()].metadataPacket();
            case POSITION -> currentPrepared[op.index()].positionPacket();
            case REMOVE -> removePacket(op.index(), op.to());
            case SPAWN -> throw new AssertionError(
                    "SPAWN packets are built per player for ViewRange");
        };
    }

    private void sendOps(Player player, PreparedVisual[] currentPrepared,
                         List<DisplayVisualDiff.Op> ops,
                         long generation, int visibleCount) {
        viewerStates.put(player, new ViewerState(generation, visibleCount));
        if (ops.isEmpty()) {
            return;
        }
        List<Object> packets = new ArrayList<>(ops.size() * 2 + 1);
        for (DisplayVisualDiff.Op op : ops) {
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

    private record ViewerState(long generation, int visibleCount) {
    }

    /**
     * Viewer-independent packets are created on first use and then shared by
     * every tracking player. A differential refresh therefore only allocates
     * the packets it actually sends.
     */
    private static final class PreparedVisual {
        private final DifferentialItemDisplayElement element;
        private final int index;
        private final DisplayVisual visual;
        private Object spawnPacket;
        private Object metadataPacket;
        private Object positionPacket;

        private PreparedVisual(DifferentialItemDisplayElement element, int index,
                               DisplayVisual visual) {
            this.element = element;
            this.index = index;
            this.visual = visual;
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
