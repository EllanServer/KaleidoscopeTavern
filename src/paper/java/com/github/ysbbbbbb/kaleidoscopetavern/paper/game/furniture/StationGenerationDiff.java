package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import java.util.List;

/**
 * 每代只计算一次的增量 diff 缓存。所有跟上版本的观察者共享同一份
 * {@link List} 实例；并记录本代是否可整代共享 packet 列表
 * （无 SPAWN 时 true，含 SPAWN 时需按玩家附加 ViewRange）。
 */
final class StationGenerationDiff {
    private long generation = -1;
    private List<StationVisualDiff.Op> ops = List.of();
    private boolean sharedEligible = true;

    /**
     * 首次遇到该 generation 时计算一次并缓存；重复调用返回同一份列表实例，
     * 因此所有跟上版本的观察者共享同一个 diff。
     */
    List<StationVisualDiff.Op> forGeneration(long generation,
                                             List<StationVisualFurnitureBehavior.Visual> previous,
                                             List<StationVisualFurnitureBehavior.Visual> current,
                                             int maxElements,
                                             int currentCount) {
        if (this.generation != generation) {
            this.generation = generation;
            ops = StationVisualDiff.compute(previous, current,
                    Math.min(maxElements, previous.size()), currentCount);
            sharedEligible = !containsSpawn(ops);
        }
        return ops;
    }

    /** 本代无 SPAWN 时 true：packet 列表可整代共享。 */
    boolean isSharedEligible() {
        return sharedEligible;
    }

    private static boolean containsSpawn(List<StationVisualDiff.Op> ops) {
        for (StationVisualDiff.Op op : ops) {
            if (op.type() == StationVisualDiff.OpType.SPAWN) {
                return true;
            }
        }
        return false;
    }
}
