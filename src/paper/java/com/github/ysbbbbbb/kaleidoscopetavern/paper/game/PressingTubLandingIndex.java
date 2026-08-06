package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

/**
 * 压榨桶落地反向空间索引（纯数据状态机，不依赖 Bukkit / CraftEngine）。
 *
 * <p>{@code originColumns}：家具 origin 所在列 → 条目，用于 {@code occupiesBlock}
 * 的精确列查询；{@code landingCells}：实体脚部可能所在的 block X/Z → 能覆盖该
 * 位置的地面压榨桶，是实体移动事件热路径使用的索引。每个地面桶的水平有效范围
 * 只有 baseX ± 0.5 / baseZ ± 0.5，因此最多登记四个落脚单元。</p>
 *
 * <p>落脚单元是一个保守超集：{@code floor(feet) ∈ [floor(base-0.5), floor(base+0.5)]}
 * 包含所有 {@code |feet - base| <= 0.5} 的位置，也会包含少量不满足精确几何的位置；
 * 精确判定（{@link PressingTubSemantics#isAboveColumn} / {@code isLandingPosition}）
 * 由调用方在拿到候选集合后完成。</p>
 */
public final class PressingTubLandingIndex<E> {
    private final Long2ObjectOpenHashMap<ReferenceOpenHashSet<E>> originColumns =
            new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<ReferenceOpenHashSet<E>> landingCells =
            new Long2ObjectOpenHashMap<>();
    private int groundTubCount;

    /**
     * 登记条目；ground 为 true 时同时登记其落脚单元并计入地面桶数。
     * 返回地面桶计数是否变化（重复登记同一 entry 返回 false）。
     */
    public boolean add(E entry, boolean ground,
                       int originBlockX, int originBlockZ,
                       int landingMinX, int landingMaxX,
                       int landingMinZ, int landingMaxZ) {
        addController(originColumns, packColumn(originBlockX, originBlockZ), entry);
        if (!ground) {
            return false;
        }
        boolean added = false;
        for (int x = landingMinX; x <= landingMaxX; x++) {
            for (int z = landingMinZ; z <= landingMaxZ; z++) {
                added |= addController(landingCells, packColumn(x, z), entry);
            }
        }
        if (added) {
            groundTubCount++;
            return true;
        }
        return false;
    }

    /**
     * 与 {@link #add} 使用相同参数移除条目。
     * 返回地面桶计数是否变化（重复移除同一 entry 返回 false）。
     */
    public boolean remove(E entry, boolean ground,
                          int originBlockX, int originBlockZ,
                          int landingMinX, int landingMaxX,
                          int landingMinZ, int landingMaxZ) {
        removeController(originColumns, packColumn(originBlockX, originBlockZ), entry);
        if (!ground) {
            return false;
        }
        boolean removedAny = false;
        for (int x = landingMinX; x <= landingMaxX; x++) {
            for (int z = landingMinZ; z <= landingMaxZ; z++) {
                removedAny |= removeController(landingCells, packColumn(x, z), entry);
            }
        }
        if (removedAny) {
            groundTubCount--;
            return true;
        }
        return false;
    }

    public boolean hasGroundTubs() {
        return groundTubCount > 0;
    }

    public int groundTubCount() {
        return groundTubCount;
    }

    /** 该世界索引中不再有任何桶（含墙面版）时可由外部整体移除。 */
    public boolean isEmpty() {
        return originColumns.isEmpty() && landingCells.isEmpty();
    }

    /** 实体脚部所在列的可能地面桶；无则 null。 */
    public ReferenceOpenHashSet<E> landingCandidatesAt(int blockX, int blockZ) {
        return landingCells.get(packColumn(blockX, blockZ));
    }

    /** 家具 origin 所在列的条目；无则 null。 */
    public ReferenceOpenHashSet<E> originCandidatesAt(int blockX, int blockZ) {
        return originColumns.get(packColumn(blockX, blockZ));
    }

    /** 返回集合是否真实新增了该 entry。 */
    private static <E> boolean addController(
            Long2ObjectOpenHashMap<ReferenceOpenHashSet<E>> map,
            long key, E entry) {
        return map.computeIfAbsent(
                key, ignored -> new ReferenceOpenHashSet<>()).add(entry);
    }

    /** 返回集合是否真实移除了该 entry。 */
    private static <E> boolean removeController(
            Long2ObjectOpenHashMap<ReferenceOpenHashSet<E>> map,
            long key, E entry) {
        ReferenceOpenHashSet<E> entries = map.get(key);
        if (entries == null) {
            return false;
        }
        boolean removed = entries.remove(entry);
        if (entries.isEmpty()) {
            map.remove(key);
        }
        return removed;
    }

    private static long packColumn(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
