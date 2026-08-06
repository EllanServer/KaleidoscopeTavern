package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.StationVisualFurnitureBehavior.Visual;
import net.momirealms.craftengine.core.item.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure differential state machine for {@link StationVisualFurnitureBehavior}.
 *
 * <p>Given the previously shown visuals of one viewer and the current snapshot,
 * {@link #compute} returns the minimal set of operations needed to bring the
 * viewer up to date without destroying and recreating unchanged entities.
 * {@link #fullResync} is the fallback used on first sight or when a viewer
 * missed a generation. No CraftEngine packet types are involved here, so the
 * whole state machine is unit-testable without a running server.</p>
 */
final class StationVisualDiff {
    enum OpType {
        /** Spawn a previously missing entity and send its full metadata. */
        SPAWN,
        /** Re-send static metadata (item / scale / rotation / transform). */
        METADATA,
        /** Move an existing entity. */
        POSITION,
        /** Remove the entity ids in {@code [index, to)}. */
        REMOVE
    }

    record Op(OpType type, int index, int to) {
        Op(OpType type, int index) {
            this(type, index, index + 1);
        }
    }

    private StationVisualDiff() {
    }

    /**
     * Computes the operations that bring a viewer from the previous snapshot to
     * the current one. {@code previousCount} is the number of entities the
     * viewer currently shows (bounded by the snapshot size), {@code currentCount}
     * the number shown by the new snapshot.
     */
    static List<Op> compute(List<Visual> previous, List<Visual> current,
                            int previousCount, int currentCount) {
        List<Op> ops = new ArrayList<>(currentCount + 1);
        int common = Math.min(previousCount, currentCount);
        for (int index = 0; index < common; index++) {
            Visual oldVisual = previous.get(index);
            Visual newVisual = current.get(index);
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
    static List<Op> fullResync(int previousCount, int currentCount) {
        List<Op> ops = new ArrayList<>(currentCount + 1);
        if (previousCount > 0) {
            ops.add(new Op(OpType.REMOVE, 0, previousCount));
        }
        for (int index = 0; index < currentCount; index++) {
            ops.add(new Op(OpType.SPAWN, index));
        }
        return ops;
    }

    static boolean positionChanged(Visual oldVisual, Visual newVisual) {
        return oldVisual.x() != newVisual.x()
                || oldVisual.y() != newVisual.y()
                || oldVisual.z() != newVisual.z()
                || oldVisual.yRot() != newVisual.yRot()
                || oldVisual.xRot() != newVisual.xRot();
    }

    static boolean metadataChanged(Visual oldVisual, Visual newVisual) {
        // 先比较便宜的 float/byte 字段；旋转稳定后这些字段大多数情况下不变，
        // 能短路跳过昂贵的 Item.isSimilar 内容比较。
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
    static boolean itemChanged(Item oldItem, Item newItem) {
        return oldItem.count() != newItem.count() || !oldItem.isSimilar(newItem);
    }
}
