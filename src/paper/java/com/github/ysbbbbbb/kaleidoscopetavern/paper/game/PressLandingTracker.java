package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * 压榨桶落地追踪状态机（纯逻辑，不依赖 Bukkit）。
 *
 * <p>移动事件先按「是否已追踪 / 脚下是否有地面桶」过滤：绝大多数无关实体在
 * 读取 fallDistance 之前就返回；已追踪实体不再重复查询空间索引，只更新最大
 * 下落距离。真实落地点再交给 {@link LandingAction} 做实际压榨，并在两 tick
 * 内缓存结果避免重复压榨。</p>
 */
public final class PressLandingTracker {
    public static final int LANDING_COOLDOWN_TICKS = 2;

    private final Object2FloatOpenHashMap<UUID> falling = new Object2FloatOpenHashMap<>();
    private final Object2ObjectOpenHashMap<UUID, LandingStamp> recentLandings =
            new Object2ObjectOpenHashMap<>();
    private final LongSupplier tickSource;
    private final LandingProbe probe;
    private final LandingAction action;
    private final Runnable onTracked;
    private final Runnable onUntracked;

    /** 空间探测：脚下当前列是否有地面桶可能接住下落。 */
    @FunctionalInterface
    public interface LandingProbe {
        boolean hasPotentialBelow(UUID worldId, double feetX, double feetY, double feetZ);
    }

    /** 真实落地：尝试在脚下压榨，返回是否成功触发。 */
    @FunctionalInterface
    public interface LandingAction {
        boolean tryPress(UUID worldId, double feetX, double feetY, double feetZ);
    }

    /**
     * 惰性读取实体当前下落距离：候选过滤通过后才调用，无关移动事件
     * 不触碰 {@code CraftEntity.getFallDistance()}。
     */
    @FunctionalInterface
    public interface FallDistanceSource {
        float get();
    }

    public PressLandingTracker(LongSupplier tickSource,
                               LandingProbe probe,
                               LandingAction action,
                               Runnable onTracked,
                               Runnable onUntracked) {
        this.tickSource = Objects.requireNonNull(tickSource, "tickSource");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.action = Objects.requireNonNull(action, "action");
        this.onTracked = Objects.requireNonNull(onTracked, "onTracked");
        this.onUntracked = Objects.requireNonNull(onUntracked, "onUntracked");
        falling.defaultReturnValue(Float.NaN);
    }

    /**
     * 每次移动事件调用（玩家与非玩家共用同一路径）。只有已追踪实体或脚下
     * 可能有地面桶时才读取 fallDistance。
     */
    public void onMove(UUID entityId, UUID worldId,
                       double feetX, double feetY, double feetZ,
                       FallDistanceSource fallDistance) {
        float tracked = falling.getFloat(entityId);
        boolean isTracked = !Float.isNaN(tracked);
        if (!isTracked && !probe.hasPotentialBelow(worldId, feetX, feetY, feetZ)) {
            return;
        }
        float currentFallDistance = fallDistance.get();
        if (currentFallDistance > 0F) {
            if (!isTracked) {
                falling.put(entityId, currentFallDistance);
                onTracked.run();
            } else if (currentFallDistance > tracked) {
                falling.put(entityId, currentFallDistance);
            }
            return;
        }
        if (!isTracked) {
            return;
        }
        falling.removeFloat(entityId);
        onUntracked.run();
        if (tracked >= PressingTubSemantics.MIN_FALL_DISTANCE) {
            handleLanding(entityId, worldId, feetX, feetY, feetZ);
        }
    }

    /** 原版摔落伤害兜底：共享落地冷却与压榨动作，返回是否应取消伤害。 */
    public boolean onFallDamage(UUID entityId, UUID worldId,
                                double feetX, double feetY, double feetZ,
                                float fallDistance) {
        float tracked = falling.removeFloat(entityId);
        onUntracked.run();
        float effective = Float.isNaN(tracked)
                ? fallDistance
                : Math.max(fallDistance, tracked);
        if (effective < PressingTubSemantics.MIN_FALL_DISTANCE) {
            return false;
        }
        return handleLanding(entityId, worldId, feetX, feetY, feetZ);
    }

    public boolean isTracked(UUID entityId) {
        return !Float.isNaN(falling.getFloat(entityId));
    }

    public boolean isEmpty() {
        return falling.isEmpty();
    }

    /** 实体下线 / 摔落但世界无桶：清理追踪状态。 */
    public void untrack(UUID entityId) {
        if (!Float.isNaN(falling.removeFloat(entityId))) {
            onUntracked.run();
        }
    }

    /** 清理落地冷却缓存（实体下线时）。 */
    public void forgetLanding(UUID entityId) {
        recentLandings.remove(entityId);
    }

    /** 清理已消失实体（entity 已不存在时）。 */
    public void removeTrackedIf(Predicate<UUID> shouldDrop) {
        if (falling.keySet().removeIf(shouldDrop)) {
            onUntracked.run();
        }
    }

    public void clear() {
        falling.clear();
        recentLandings.clear();
    }

    private boolean handleLanding(UUID entityId, UUID worldId,
                                  double feetX, double feetY, double feetZ) {
        long now = tickSource.getAsLong();
        LandingStamp stamp = recentLandings.get(entityId);
        if (stamp != null && now - stamp.tick < LANDING_COOLDOWN_TICKS) {
            return stamp.pressed;
        }
        recentLandings.remove(entityId);
        boolean pressed = action.tryPress(worldId, feetX, feetY, feetZ);
        recentLandings.put(entityId, new LandingStamp(now, pressed));
        return pressed;
    }

    private record LandingStamp(long tick, boolean pressed) {
    }
}
