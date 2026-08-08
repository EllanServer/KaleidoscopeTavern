package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.visual;

import net.momirealms.craftengine.core.item.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * 中立差量状态机，同时供 CE 家具与 CE 自定义方块的动态视觉使用。
 *
 * <p>给定某观察者之前显示的视觉列表与当前快照，{@link #compute} 返回把观察者
 * 带到最新状态所需的最小操作集，而不必销毁重建未变化的实体；{@link #fullResync}
 * 是首次见到或落后一代以上时的回退。这里不涉及任何 CraftEngine packet 类型，
 * 因此整个状态机可在无服务器环境下单元测试。</p>
 */
public final class DisplayVisualDiff {
    public enum OpType {
        /** Spawn a previously missing entity and send its full metadata. */
        SPAWN,
        /** Re-send static metadata (item / scale / rotation / transform). */
        METADATA,
        /** Move an existing entity. */
        POSITION,
        /** Remove the entity ids in {@code [index, to)}. */
        REMOVE
    }

    public record Op(OpType type, int index, int to) {
        public Op(OpType type, int index) {
            this(type, index, index + 1);
        }
    }

    private DisplayVisualDiff() {
    }

    /**
     * Computes the operations that bring a viewer from the previous snapshot to
     * the current one. {@code previousCount} is the number of entities the
     * viewer currently shows (bounded by the snapshot size), {@code currentCount}
     * the number shown by the new snapshot.
     */
    public static List<Op> compute(List<DisplayVisual> previous, List<DisplayVisual> current,
                                   int previousCount, int currentCount) {
        List<Op> ops = new ArrayList<>(currentCount + 1);
        int common = Math.min(previousCount, currentCount);
        for (int index = 0; index < common; index++) {
            DisplayVisual oldVisual = previous.get(index);
            DisplayVisual newVisual = current.get(index);
            if (positionChanged(oldVisual, newVisual)) {
                ops.add(new Op(OpType.POSITION, index));
            }
            if (metadataChanged(oldVisual, newVisual)) {
                ops.add(new Op(OpType.METADATA, index));
            }
        }
        for (int index = common; index < currentCount; index++) {
            ops.add(new Op(OpType.SPAWN, index));
        }
        if (previousCount > currentCount) {
            ops.add(new Op(OpType.REMOVE, currentCount, previousCount));
        }
        return ops;
    }

    /**
     * Full resync: drop everything the viewer currently shows, then spawn the
     * new list from scratch. Used on first sight and when a viewer missed a
     * generation, because then an incremental diff cannot be reconstructed and
     * the old excess entities must be removed explicitly.
     */
    public static List<Op> fullResync(int previousCount, int currentCount) {
        List<Op> ops = new ArrayList<>(currentCount + 1);
        if (previousCount > 0) {
            ops.add(new Op(OpType.REMOVE, 0, previousCount));
        }
        for (int index = 0; index < currentCount; index++) {
            ops.add(new Op(OpType.SPAWN, index));
        }
        return ops;
    }

    public static boolean positionChanged(DisplayVisual oldVisual, DisplayVisual newVisual) {
        return oldVisual.x() != newVisual.x()
                || oldVisual.y() != newVisual.y()
                || oldVisual.z() != newVisual.z()
                || oldVisual.yRot() != newVisual.yRot()
                || oldVisual.xRot() != newVisual.xRot();
    }

    public static boolean metadataChanged(DisplayVisual oldVisual, DisplayVisual newVisual) {
        // 变换字段已经变化时，metadata packet 必然需要重发，无需再比较 Item 内容；
        // 变换字段不变时，才需要继续比较 Item（Item.isSimilar）判断内容是否变化。
        return oldVisual.itemTransform() != newVisual.itemTransform()
                || oldVisual.scale() != newVisual.scale()
                || oldVisual.rotX() != newVisual.rotX()
                || oldVisual.rotY() != newVisual.rotY()
                || oldVisual.rotZ() != newVisual.rotZ()
                || oldVisual.rotW() != newVisual.rotW()
                || itemChanged(oldVisual.item(), newVisual.item());
    }

    /**
     * Item wrappers are re-adapted on every refresh, so object identity changes
     * even when the content stays identical. Compare by content instead.
     */
    public static boolean itemChanged(Item oldItem, Item newItem) {
        return oldItem.count() != newItem.count() || !oldItem.isSimilar(newItem);
    }

    /**
     * 每代只计算一次的增量 diff 缓存。所有跟上版本的观察者共享同一份
     * {@link List} 实例；并记录本代是否可整代共享 packet 列表
     * （无 SPAWN 时 true，含 SPAWN 时需按玩家附加 ViewRange）。
     */
    public static final class GenerationDiff {
        private long generation = -1;
        private List<Op> ops = List.of();
        private boolean sharedEligible = true;

        /**
         * 首次遇到该 generation 时计算一次并缓存；重复调用返回同一份列表实例，
         * 因此所有跟上版本的观察者共享同一个 diff。
         */
        public List<Op> forGeneration(long generation,
                                      List<DisplayVisual> previous,
                                      List<DisplayVisual> current,
                                      int maxElements,
                                      int currentCount) {
            if (this.generation != generation) {
                this.generation = generation;
                ops = DisplayVisualDiff.compute(previous, current,
                        Math.min(maxElements, previous.size()), currentCount);
                sharedEligible = !containsSpawn(ops);
            }
            return ops;
        }

        /** 本代无 SPAWN 时 true：packet 列表可整代共享。 */
        public boolean isSharedEligible() {
            return sharedEligible;
        }

        private static boolean containsSpawn(List<Op> ops) {
            for (Op op : ops) {
                if (op.type() == OpType.SPAWN) {
                    return true;
                }
            }
            return false;
        }
    }
}
