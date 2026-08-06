package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.LongSupplier;

/**
 * 纯调度状态机：与 Bukkit / CraftEngine 完全解耦，当前 tick 由 {@link LongSupplier}
 * 注入，唤醒任务由 {@link WakeTarget} 注入，因此可以在不启动服务器的情况下精确验证
 * generation 惰性失效、compare-and-complete、stale 队头清理与周期压缩等行为。
 *
 * <p>每个家具通过唯一 id 注册一个 {@link Host}。取消/重调度只推进 generation 并把
 * 仍在队列中的旧任务标记为 stale（O(1)），不再对 {@link PriorityQueue} 做 O(n) 的
 * {@code remove(Object)}；stale 节点在队头或周期压缩时清理。</p>
 */
final class TickingScheduler {
    /** 宿主（家具 Controller）提供给调度内核的执行与决策回调。 */
    interface Host {
        /** channel.handler != null。 */
        boolean isHandlerBound(String id);

        /** channel.handler != deliveredHandler，即需要重新 deliver。 */
        boolean isHandlerChanged(String id);

        boolean shouldSchedule(String id);

        void tick(String id);

        /** 派发时发现 channel.handler 已为空：清空 deliveredHandler。 */
        void onHandlerMissing(String id);

        /** 派发时发现 handler 已更换：由宿主执行 deliver(新 handler)。 */
        void onHandlerChanged(String id);

        int nextDelay(String id);

        /** tick/shouldSchedule 抛出的 RuntimeException 或 LinkageError。 */
        void onRunFailure(String id, Throwable failure);
    }

    /** 抽象唤醒：宿主把它桥接到 Bukkit 调度器或测试中的假调度器。 */
    interface WakeTarget {
        void cancel();

        void schedule(long delayTicks, Runnable action);
    }

    private final LongSupplier tickSource;
    private final WakeTarget wakeTarget;
    private final Object lock = new Object();
    private final Map<String, IdentityState> states = new HashMap<>();
    private final PriorityQueue<ScheduledRun> queue = new PriorityQueue<>();

    private boolean started;
    private boolean dispatching;
    private boolean wakeScheduled;
    private long scheduledWakeTick = Long.MAX_VALUE;
    private long nextSequence;
    private int liveQueuedRuns;
    private int staleQueuedRuns;

    private static final int COMPACT_MIN_QUEUE_SIZE = 512;
    private static final int COMPACT_MIN_STALE_RUNS = 256;

    TickingScheduler(LongSupplier tickSource, WakeTarget wakeTarget) {
        this.tickSource = tickSource;
        this.wakeTarget = wakeTarget;
    }

    // ===== 生命周期 =====

    void start() {
        synchronized (lock) {
            started = true;
        }
    }

    /** 清空队列并推进所有 generation，让已出队未执行的任务也失效。 */
    void stop() {
        synchronized (lock) {
            if (wakeScheduled) {
                wakeTarget.cancel();
                wakeScheduled = false;
            }
            scheduledWakeTick = Long.MAX_VALUE;
            started = false;
            queue.clear();
            liveQueuedRuns = 0;
            staleQueuedRuns = 0;
            for (IdentityState state : states.values()) {
                state.scheduledRun = null;
                state.scheduleGeneration++;
            }
            nextSequence = 0;
            dispatching = false;
        }
    }

    // ===== 注册与状态推送 =====

    void activate(String id, Host host) {
        synchronized (lock) {
            IdentityState state = states.get(id);
            if (state == null) {
                state = new IdentityState(id, host);
                states.put(id, state);
            }
            state.active = true;
        }
    }

    /** 家具卸载/移除：失效当前任务并从状态表移除。 */
    void deactivate(String id) {
        synchronized (lock) {
            IdentityState state = states.get(id);
            if (state == null) {
                return;
            }
            invalidateLocked(state);
            states.remove(id);
        }
    }

    void setBound(String id, boolean bound) {
        synchronized (lock) {
            IdentityState state = states.get(id);
            if (state != null) {
                state.bound = bound;
            }
        }
    }

    // ===== 调度 =====

    void schedule(String id, int delay) {
        synchronized (lock) {
            IdentityState state = states.get(id);
            if (state == null || !state.active || !state.bound || !started) {
                return;
            }
            // 旧任务如果仍在队列，只标记为 stale，不做 O(n) 删除。
            invalidateLocked(state);
            ScheduledRun run = new ScheduledRun(id, state.scheduleGeneration,
                    tickSource.getAsLong() + Math.max(1, delay), nextSequence++);
            state.scheduledRun = run;
            queue.add(run);
            liveQueuedRuns++;
            maybeCompactQueueLocked();
            scheduleWakeLocked();
        }
    }

    void cancel(String id) {
        synchronized (lock) {
            IdentityState state = states.get(id);
            if (state == null || state.scheduledRun == null) {
                return;
            }
            invalidateLocked(state);
            pruneStaleHeadLocked();
            maybeCompactQueueLocked();
            scheduleWakeLocked();
        }
    }

    /**
     * 一次锁内完成「取消或调度」决策，合并原先 refreshSchedule 里
     * isStarted/cancel/hasScheduledRun/schedule 的多次锁进入与重复队头检查。
     *
     * <p>这是两段式调度的第一步：只返回状态，不在锁内计算延迟。调用方在
     * 结果仍为 {@link ReconcileResult#NEEDS_SCHEDULE} 时，用
     * {@link #scheduleIfAbsent} 补充延迟参数——这样已存在任务的常见路径
     * 不会在锁外无条件执行昂贵的 firstDelay（随机采样 / gameTime / hash）。
     * host 的 shouldSchedule 决策必须在锁外完成（Bukkit、NBT 或家具逻辑
     * 不能进入全局调度锁）。</p>
     */
    ReconcileResult reconcile(String id, boolean desired) {
        synchronized (lock) {
            IdentityState state = states.get(id);
            if (state == null) {
                return ReconcileResult.UNCHANGED;
            }
            if (!started || !state.active || !state.bound || !desired) {
                if (state.scheduledRun == null) {
                    return ReconcileResult.UNCHANGED;
                }
                invalidateLocked(state);
                pruneStaleHeadLocked();
                maybeCompactQueueLocked();
                scheduleWakeLocked();
                return ReconcileResult.CANCELLED;
            }
            if (state.scheduledRun != null) {
                return ReconcileResult.UNCHANGED;
            }
            return ReconcileResult.NEEDS_SCHEDULE;
        }
    }

    /** 两段式调度的第二步：仅在仍无任务时创建，避免重复排队。 */
    void scheduleIfAbsent(String id, int delay) {
        synchronized (lock) {
            IdentityState state = states.get(id);
            if (state == null || !state.active || !state.bound || !started) {
                return;
            }
            if (state.scheduledRun != null) {
                return;
            }
            ScheduledRun run = new ScheduledRun(id, state.scheduleGeneration,
                    tickSource.getAsLong() + Math.max(1, delay), nextSequence++);
            state.scheduledRun = run;
            queue.add(run);
            liveQueuedRuns++;
            maybeCompactQueueLocked();
            scheduleWakeLocked();
        }
    }

    /** reconcile 的决策结果：调用方据此决定是否补充 firstDelay。 */
    enum ReconcileResult {
        /** 状态未变化（已有任务或无需取消）。 */
        UNCHANGED,
        /** 已有任务被取消。 */
        CANCELLED,
        /** 缺少任务，需要调用方以 {@link #scheduleIfAbsent} 补建。 */
        NEEDS_SCHEDULE
    }

    /** 每次失效都推进 generation；仍在队列的旧节点只标记为 stale。 */
    private void invalidateLocked(IdentityState state) {
        ScheduledRun oldRun = state.scheduledRun;
        state.scheduleGeneration++;
        state.scheduledRun = null;
        if (oldRun != null && oldRun.queued && !oldRun.stale) {
            oldRun.stale = true;
            liveQueuedRuns--;
            staleQueuedRuns++;
        }
    }

    /** {@code run} 仍是该 Controller 当前持有的 live 任务才返回 true。 */
    boolean isCurrentRun(ScheduledRun run) {
        synchronized (lock) {
            IdentityState state = states.get(run.id);
            return started
                    && state != null
                    && state.active
                    && state.scheduledRun == run
                    && state.scheduleGeneration == run.generation;
        }
    }

    /**
     * 只有当 Controller 当前仍指向刚执行的 {@code completedRun} 时，才允许完成或
     * 重调度；host.tick() 内部的重入调度不会被覆盖。
     */
    void finishRunIfCurrent(ScheduledRun completedRun, boolean scheduleAgain, int nextDelay) {
        synchronized (lock) {
            IdentityState state = states.get(completedRun.id);
            if (state == null
                    || state.scheduledRun != completedRun
                    || state.scheduleGeneration != completedRun.generation) {
                // tick() 期间已经取消或重新调度。
                return;
            }
            invalidateLocked(state);
            if (scheduleAgain && state.active && state.bound && started) {
                ScheduledRun nextRun = new ScheduledRun(completedRun.id,
                        state.scheduleGeneration,
                        tickSource.getAsLong() + Math.max(1, nextDelay),
                        nextSequence++);
                state.scheduledRun = nextRun;
                queue.add(nextRun);
                liveQueuedRuns++;
            }
            maybeCompactQueueLocked();
            // dispatching=true 时这里不会立即创建唤醒任务，整批 due 执行完成后统一安排。
            scheduleWakeLocked();
        }
    }

    // ===== 派发 =====

    void dispatchDue() {
        List<ScheduledRun> due;
        synchronized (lock) {
            if (!started) {
                return;
            }
            dispatching = true;
            wakeScheduled = false;
            scheduledWakeTick = Long.MAX_VALUE;
            due = collectDueLocked();
        }
        try {
            dispatchCollected(due);
        } finally {
            finishDispatch();
        }
    }

    /**
     * 测试缝隙：只取出到期任务，不执行。配合 {@link #dispatchCollected(List)} 与
     * {@link #finishDispatch()} 可验证「出队后、执行前取消」这一 generation 关键场景。
     */
    List<ScheduledRun> collectDue() {
        synchronized (lock) {
            dispatching = true;
            wakeScheduled = false;
            scheduledWakeTick = Long.MAX_VALUE;
            return collectDueLocked();
        }
    }

    /** 测试缝隙：执行已取出的任务，单个任务的异常由宿主日志处理。 */
    void dispatchCollected(List<ScheduledRun> due) {
        if (due == null) {
            return;
        }
        for (ScheduledRun run : due) {
            try {
                runHandlerAndComplete(run);
            } catch (RuntimeException | LinkageError failure) {
                IdentityState state = states.get(run.id);
                if (state != null) {
                    state.host.onRunFailure(run.id, failure);
                }
            }
        }
    }

    /** 测试缝隙：派发结束后清理队头 stale、压缩并重排唤醒。 */
    void finishDispatch() {
        synchronized (lock) {
            dispatching = false;
            pruneStaleHeadLocked();
            maybeCompactQueueLocked();
            scheduleWakeLocked();
        }
    }

    private List<ScheduledRun> collectDueLocked() {
        List<ScheduledRun> due = null;
        long currentTick = tickSource.getAsLong();
        while (true) {
            ScheduledRun run = peekLiveRunLocked();
            if (run == null || run.dueTick > currentTick) {
                break;
            }
            queue.poll();
            run.queued = false;
            liveQueuedRuns--;
            if (due == null) {
                due = new ArrayList<>();
            }
            // scheduledRun 仍指向 run：它表示该任务已出队、正在等待执行，
            // 执行前的 generation 复检与 compare-and-complete 都依赖它。
            due.add(run);
        }
        return due;
    }

    private void runHandlerAndComplete(ScheduledRun run) {
        IdentityState state;
        synchronized (lock) {
            state = states.get(run.id);
        }
        if (state == null || !isCurrentRun(run)) {
            return;
        }
        Host host = state.host;

        if (!host.isHandlerBound(run.id)) {
            finishRunIfCurrent(run, false, 0);
            host.onHandlerMissing(run.id);
            return;
        }
        if (host.isHandlerChanged(run.id)) {
            // deliver() 内部会取消旧 run，并创建属于新 handler 的任务。
            host.onHandlerChanged(run.id);
            return;
        }

        boolean scheduleAgain = false;
        boolean schedulingDecisionCompleted = false;
        Throwable primaryFailure = null;

        try {
            host.tick(run.id);
        } catch (Throwable failure) {
            primaryFailure = failure;
        }

        // 无论 tick() 是否抛异常，都必须完成调度决策并清理 in-flight run；
        // shouldSchedule() 再抛异常时作为 suppressed 附加到主异常，避免覆盖。
        try {
            if (isCurrentRun(run)
                    && host.isHandlerBound(run.id)
                    && !host.isHandlerChanged(run.id)) {
                scheduleAgain = host.shouldSchedule(run.id);
            }
            schedulingDecisionCompleted = true;
        } catch (Throwable decisionFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(decisionFailure);
            } else {
                primaryFailure = decisionFailure;
            }
        } finally {
            int nextDelay = scheduleAgain ? host.nextDelay(run.id) : 0;
            finishRunIfCurrent(run, schedulingDecisionCompleted && scheduleAgain, nextDelay);
        }

        if (primaryFailure != null) {
            rethrow(primaryFailure);
        }
    }

    // ===== 队头清理与周期压缩 =====

    /** 先删除队头连续的 stale 节点，再返回真正的 live 队头。 */
    private ScheduledRun peekLiveRunLocked() {
        pruneStaleHeadLocked();
        return queue.peek();
    }

    private void pruneStaleHeadLocked() {
        while (true) {
            ScheduledRun run = queue.peek();
            if (run == null || run.isCurrent()) {
                return;
            }
            queue.poll();
            run.queued = false;
            if (run.stale) {
                staleQueuedRuns--;
            }
        }
    }

    private boolean shouldCompactQueueLocked() {
        return queue.size() >= COMPACT_MIN_QUEUE_SIZE
                && staleQueuedRuns >= COMPACT_MIN_STALE_RUNS
                && staleQueuedRuns > liveQueuedRuns;
    }

    /** stale 节点较多时做一次 O(n) 重建，成本被数百次 O(1) 取消摊销。 */
    private void maybeCompactQueueLocked() {
        if (!shouldCompactQueueLocked()) {
            return;
        }
        PriorityQueue<ScheduledRun> rebuilt =
                new PriorityQueue<>(Math.max(11, liveQueuedRuns));
        for (ScheduledRun run : queue) {
            if (run.isCurrent()) {
                rebuilt.add(run);
            } else {
                run.queued = false;
            }
        }
        queue.clear();
        queue.addAll(rebuilt);
        // 压缩后不依赖之前的增减结果，直接重算。
        liveQueuedRuns = queue.size();
        staleQueuedRuns = 0;
    }

    // ===== 唤醒 =====

    /** 为最早的 live 任务安排恰好一个唤醒回调。 */
    private void scheduleWakeLocked() {
        if (dispatching || !started) {
            return;
        }
        ScheduledRun next = peekLiveRunLocked();
        if (next == null) {
            if (wakeScheduled) {
                wakeTarget.cancel();
                wakeScheduled = false;
            }
            scheduledWakeTick = Long.MAX_VALUE;
            return;
        }
        if (wakeScheduled) {
            /*
             * 已有任务比新队头更早或相同：保留它即可，它不会让 live run 迟到。
             * 旧任务可能提前唤醒一次，但避免每次取消队头都 cancel + 重新创建。
             */
            if (scheduledWakeTick <= next.dueTick) {
                return;
            }
            // 新任务更早，必须提前唤醒。
            wakeTarget.cancel();
        }
        long delay = Math.max(1L, next.dueTick - tickSource.getAsLong());
        scheduledWakeTick = next.dueTick;
        wakeTarget.schedule(delay, this::dispatchDue);
        wakeScheduled = true;
    }

    // ===== 统计 =====

    /** 只读的调度器快照，主要用于测试与临时 debug 命令。 */
    record SchedulerStats(
            int queueSize,
            int liveQueuedRuns,
            int staleQueuedRuns,
            long nextLiveDueTick,
            long scheduledWakeTick,
            boolean dispatching
    ) {
    }

    SchedulerStats schedulerStats() {
        synchronized (lock) {
            ScheduledRun next = peekLiveRunLocked();
            return new SchedulerStats(
                    queue.size(),
                    liveQueuedRuns,
                    staleQueuedRuns,
                    next == null ? Long.MAX_VALUE : next.dueTick,
                    scheduledWakeTick,
                    dispatching
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable throwable) throws T {
        throw (T) throwable;
    }

    private static final class IdentityState {
        final String id;
        final Host host;
        boolean active;
        boolean bound;
        long scheduleGeneration;
        ScheduledRun scheduledRun;

        IdentityState(String id, Host host) {
            this.id = id;
            this.host = host;
        }
    }

    final class ScheduledRun implements Comparable<ScheduledRun> {
        final String id;
        final long generation;
        final long dueTick;
        final long sequence;

        // 只允许在 lock 内修改
        boolean queued = true;
        boolean stale;

        ScheduledRun(String id, long generation, long dueTick, long sequence) {
            this.id = id;
            this.generation = generation;
            this.dueTick = dueTick;
            this.sequence = sequence;
        }

        boolean isCurrent() {
            IdentityState state = states.get(id);
            return queued
                    && !stale
                    && state != null
                    && state.scheduledRun == this
                    && state.scheduleGeneration == generation;
        }

        @Override
        public int compareTo(ScheduledRun other) {
            int byTick = Long.compare(dueTick, other.dueTick);
            return byTick != 0 ? byTick : Long.compare(sequence, other.sequence);
        }
    }
}
