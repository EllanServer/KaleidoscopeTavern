package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调度器状态机行为测试：用可控时钟与假唤醒直接驱动 {@link TickingScheduler}，
 * 覆盖惰性失效的核心场景（generation、compare-and-complete、异常路径、
 * stale 队头清理、周期压缩、唤醒去抖与计数不变量）。
 */
class TickingSchedulerTest {

    private FakeClock clock;
    private FakeWake wake;
    private TickingScheduler core;

    @BeforeEach
    void setUp() {
        clock = new FakeClock();
        wake = new FakeWake();
        core = new TickingScheduler(clock, wake);
        core.start();
    }

    // ===== 1. 基本取消 =====

    @Test
    void canceledRunNeverTicksEvenWhenAnotherRunKeepsTheWake() {
        FakeHost a = host("A");
        FakeHost b = host("B");
        b.shouldScheduleResult = false;

        core.schedule("A", 100);
        core.cancel("A");
        core.schedule("B", 100);

        advanceTo(100);
        assertEquals(0, a.tickCount);
        assertEquals(1, b.tickCount);
        assertInvariant();
    }

    // ===== 2. 新 generation 覆盖旧 generation =====

    @Test
    void rescheduledGenerationRunsOnceAndOldGenerationIsSkipped() {
        FakeHost a = host("A");
        a.shouldScheduleResult = false;

        core.schedule("A", 100);   // generation 1
        core.schedule("A", 50);    // generation 2 覆盖

        advanceTo(50);
        assertEquals(1, a.tickCount);

        advanceTo(100);
        assertEquals(1, a.tickCount);   // 旧 generation 被跳过
        assertInvariant();
    }

    // ===== 3. 延后重调度 =====

    @Test
    void postponedRescheduleDoesNotRunAtTheOldTick() {
        FakeHost a = host("A");
        a.shouldScheduleResult = false;

        core.schedule("A", 50);
        core.schedule("A", 100);

        advanceTo(50);
        assertEquals(0, a.tickCount);

        advanceTo(100);
        assertEquals(1, a.tickCount);
        assertInvariant();
    }

    // ===== 4. 出队后、执行前取消 =====

    @Test
    void polledRunStillDispatchesWhenNotCancelled() {
        FakeHost a = host("A");
        a.shouldScheduleResult = false;

        core.schedule("A", 50);
        clock.tick = 50;
        List<TickingScheduler.ScheduledRun> due = core.collectDue();
        core.dispatchCollected(due);
        core.finishDispatch();

        assertEquals(1, a.tickCount);
    }

    @Test
    void cancelAfterPollBeforeDispatchSkipsTheRun() {
        FakeHost a = host("A");

        core.schedule("A", 50);
        clock.tick = 50;
        List<TickingScheduler.ScheduledRun> due = core.collectDue();   // 已出队
        core.cancel("A");                                             // 执行前取消
        core.dispatchCollected(due);
        core.finishDispatch();

        assertEquals(0, a.tickCount);
        assertInvariant();
    }

    // ===== 5. tick 内取消自身 =====

    @Test
    void tickCancellingItselfRunsOnceAndLeavesNoNextRun() {
        FakeHost a = host("A");
        a.tickAction = () -> core.cancel("A");

        core.schedule("A", 10);
        advanceTo(10);

        assertEquals(1, a.tickCount);
        assertEquals(0, a.shouldScheduleCalls);
        assertEquals(0, core.schedulerStats().liveQueuedRuns());
        assertInvariant();
    }

    // ===== 6. tick 内取消后重新创建 =====

    @Test
    void tickCancellingThenReschedulingKeepsTheNewRun() {
        FakeHost a = host("A");
        a.tickAction = () -> {
            core.cancel("A");
            core.schedule("A", 100);
        };

        core.schedule("A", 10);
        advanceTo(10);

        assertEquals(1, a.tickCount);
        assertEquals(1, core.schedulerStats().liveQueuedRuns());
        assertEquals(110, core.schedulerStats().nextLiveDueTick());

        advanceTo(110);
        assertEquals(2, a.tickCount);   // 新 run 保留且正常执行
        assertInvariant();
    }

    // ===== 7. Handler 解绑 / 替换 =====

    @Test
    void handlerReplacementStopsOldHandlerTicks() {
        FakeHost a = host("A");

        core.schedule("A", 10);
        a.handlerChanged = true;

        advanceTo(10);
        assertEquals(0, a.tickCount);
        assertEquals(1, a.onHandlerChangedCount);

        // 模拟 deliver(新 handler) 完成后再调度
        a.handlerChanged = false;
        core.schedule("A", 10);
        advanceTo(20);
        assertEquals(1, a.tickCount);
    }

    @Test
    void unbindClearsPendingRunAndLaterRebindRecovers() {
        FakeHost a = host("A");

        core.schedule("A", 10);
        a.handlerBound = false;

        advanceTo(10);
        assertEquals(0, a.tickCount);
        assertEquals(1, a.onHandlerMissingCount);

        a.handlerBound = true;
        core.setBound("A", true);
        core.schedule("A", 10);
        advanceTo(20);
        assertEquals(1, a.tickCount);
    }

    // ===== 8. 家具卸载 =====

    @Test
    void deactivateInvalidatesPendingRunUntilReactivated() {
        FakeHost a = host("A");

        core.schedule("A", 10);
        core.deactivate("A");

        advanceTo(10);
        assertEquals(0, a.tickCount);

        core.activate("A", a);
        core.setBound("A", true);
        core.schedule("A", 5);
        advanceTo(15);
        assertEquals(1, a.tickCount);
        assertInvariant();
    }

    // ===== 9. stop/start 隔离 =====

    @Test
    void stopThenDispatchOldPolledRunSkipsItViaGeneration() {
        FakeHost a = host("A");

        core.schedule("A", 10);
        clock.tick = 10;
        List<TickingScheduler.ScheduledRun> due = core.collectDue();   // 已出队

        core.stop();
        core.start();

        core.dispatchCollected(due);
        core.finishDispatch();

        assertEquals(0, a.tickCount);
        assertInvariant();
    }

    // ===== 10. 同一 tick 多任务按 sequence 稳定执行 =====

    @Test
    void runsDueOnTheSameTickFireInSequenceOrder() {
        FakeHost a = host("A");
        FakeHost b = host("B");
        FakeHost c = host("C");
        a.shouldScheduleResult = false;
        b.shouldScheduleResult = false;
        c.shouldScheduleResult = false;
        List<String> order = new ArrayList<>();
        a.tickAction = () -> order.add("A");
        b.tickAction = () -> order.add("B");
        c.tickAction = () -> order.add("C");

        core.schedule("A", 5);
        core.schedule("B", 5);
        core.schedule("C", 5);

        assertEquals(1, core.schedulerStats().dueBucketCount());

        advanceTo(5);

        assertEquals(List.of("A", "B", "C"), order);
        assertEquals(1, a.tickCount);
        assertEquals(1, b.tickCount);
        assertEquals(1, c.tickCount);
    }

    @Test
    void coalescesLargeSameTickBatchIntoOnePriorityQueueNode() {
        int furnitureCount = 2_000;
        List<FakeHost> hosts = new ArrayList<>(furnitureCount);
        for (int index = 0; index < furnitureCount; index++) {
            FakeHost furniture = host("batch-" + index);
            furniture.shouldScheduleResult = false;
            hosts.add(furniture);
            core.schedule(furniture.id, 50);
        }

        TickingScheduler.SchedulerStats queued = core.schedulerStats();
        assertEquals(furnitureCount, queued.queueSize());
        assertEquals(furnitureCount, queued.liveQueuedRuns());
        assertEquals(1, queued.dueBucketCount());

        advanceTo(50);

        for (FakeHost furniture : hosts) {
            assertEquals(1, furniture.tickCount);
        }
        assertEquals(0, core.schedulerStats().dueBucketCount());
        assertInvariant();
    }

    @Test
    void postTickDecisionAvoidsRepeatingAnExpensiveScheduleCheck() {
        FakeHost furniture = host("A");
        furniture.postTickScheduleDecision = true;
        furniture.nextDelay = 20;

        core.schedule("A", 5);
        advanceTo(5);

        assertEquals(1, furniture.tickCount);
        assertEquals(0, furniture.shouldScheduleCalls);
        assertEquals(25, core.schedulerStats().nextLiveDueTick());
        assertInvariant();
    }

    // ===== 11. 队头 stale 清理 =====

    @Test
    void staleHeadIsPrunedBeforeTheLiveHeadIsRead() {
        host("A");
        host("B");

        core.schedule("A", 10);
        core.schedule("A", 20);   // A@10 变为 stale
        core.schedule("B", 20);

        TickingScheduler.SchedulerStats stats = core.schedulerStats();
        assertEquals(2, stats.queueSize());
        assertEquals(2, stats.liveQueuedRuns());
        assertEquals(0, stats.staleQueuedRuns());
        assertEquals(20, stats.nextLiveDueTick());
    }

    // ===== 12. 队列压缩 =====

    @Test
    void compactKeepsOnlyLiveRunsAndRecountsCounters() {
        for (int index = 0; index < 700; index++) {
            String id = "stale" + index;
            host(id);
            core.schedule(id, 1000);
            core.cancel(id);
        }
        List<FakeHost> liveHosts = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            String id = "live" + index;
            FakeHost live = host(id);
            live.shouldScheduleResult = false;
            liveHosts.add(live);
            core.schedule(id, 100);
        }

        TickingScheduler.SchedulerStats stats = core.schedulerStats();
        assertEquals(300, stats.liveQueuedRuns());
        assertEquals(0, stats.staleQueuedRuns());
        assertEquals(300, stats.queueSize());

        advanceTo(100);
        for (FakeHost live : liveHosts) {
            assertEquals(1, live.tickCount);
        }
        assertInvariant();
    }

    // ===== 13. shouldSchedule() 抛异常 =====

    @Test
    void shouldScheduleFailureStillCompletesTheRunAndAllowsRecovery() {
        FakeHost a = host("A");
        a.shouldScheduleFailure = new RuntimeException("boom");

        core.schedule("A", 10);
        advanceTo(10);

        assertEquals(1, a.tickCount);
        assertEquals(1, a.failures.size());
        assertEquals(0, core.schedulerStats().liveQueuedRuns());

        a.shouldScheduleFailure = null;
        core.schedule("A", 10);
        advanceTo(20);
        assertEquals(2, a.tickCount);
        assertInvariant();
    }

    // ===== 审查补充 3: tick() 抛异常后恢复调度 =====

    @Test
    void tickFailureIsReportedAndReschedulingStillContinues() {
        FakeHost a = host("A");
        a.tickFailure = new IllegalStateException("tick boom");

        core.schedule("A", 10);
        advanceTo(10);

        assertEquals(1, a.tickCount);
        assertEquals(1, a.failures.size());
        assertEquals(1, core.schedulerStats().liveQueuedRuns());

        a.tickFailure = null;
        advanceTo(11);
        assertEquals(2, a.tickCount);
        assertInvariant();
    }

    // ===== 审查补充 4: nextDelay() 抛异常 =====

    @Test
    void nextDelayFailureStillCompletesTheRunAndAllowsRecovery() {
        FakeHost a = host("A");
        a.nextDelayFailure = new IllegalStateException("nextDelay boom");

        core.schedule("A", 10);
        advanceTo(10);

        // 任务已出队执行，但延迟计算失败：不得按重调度处理。
        assertEquals(1, a.tickCount);
        assertEquals(1, a.failures.size());
        assertEquals(0, core.schedulerStats().liveQueuedRuns());

        // scheduledRun 已被 finishRunIfCurrent 清除，reconcile 可以重建任务。
        a.nextDelayFailure = null;
        core.schedule("A", 10);
        advanceTo(20);
        assertEquals(2, a.tickCount);
        assertInvariant();
    }

    // ===== 审查补充 8: 取消最早任务不延迟后续 live 任务 =====

    @Test
    void cancelingTheEarliestRunDoesNotRebuildTheWakeNorDelayLaterRuns() {
        FakeHost a = host("A");
        FakeHost b = host("B");
        b.shouldScheduleResult = false;

        core.schedule("A", 50);
        core.schedule("B", 100);
        core.cancel("A");

        assertEquals(1, wake.scheduleCount);   // 队头取消不重建唤醒

        advanceTo(50);   // 提前的一次 wake：没有 live run 到期
        assertEquals(0, b.tickCount);

        advanceTo(100);
        assertEquals(1, b.tickCount);
        assertInvariant();
    }

    @Test
    void sameTickEarlierSchedulesShareOneProbeWake() {
        host("A");
        host("B");
        FakeHost c = host("C");
        c.shouldScheduleResult = false;

        core.schedule("A", 100);
        core.schedule("B", 80);
        core.schedule("C", 60);

        assertEquals(1, wake.scheduleCount);
        assertEquals(0, wake.cancelCount);
        assertEquals(1, wake.current.delay);

        advanceTo(1);
        assertEquals(2, wake.scheduleCount);
        assertEquals(59, wake.current.delay);

        advanceTo(60);
        assertEquals(1, c.tickCount);
        assertInvariant();
    }

    // ===== 审查补充 5: 计数不变量 =====

    @Test
    void countersStayConsistentThroughCancelRescheduleChurn() {
        FakeHost a = host("A");
        FakeHost b = host("B");
        b.shouldScheduleResult = false;

        core.schedule("A", 10); assertInvariant();
        core.schedule("A", 5); assertInvariant();    // 重新调度：旧任务变 stale
        core.cancel("A"); assertInvariant();
        core.schedule("A", 10); assertInvariant();
        clock.tick = 10;
        List<TickingScheduler.ScheduledRun> due = core.collectDue(); assertInvariant();
        core.cancel("A"); assertInvariant();          // in-flight 失效：计数不变
        core.dispatchCollected(due); assertInvariant();
        core.finishDispatch(); assertInvariant();

        // tick 内：先取消 A，再为 B 创建任务
        a.tickAction = () -> {
            core.cancel("A");
            core.schedule("B", 100);
        };
        core.schedule("A", 20); assertInvariant();
        advanceTo(40); assertInvariant();

        assertEquals(1, a.tickCount);
        assertEquals(1, core.schedulerStats().liveQueuedRuns());
        advanceTo(140);
        assertEquals(1, b.tickCount);
        assertInvariant();
    }

    // ===== 14. reconcile 两段式调度 =====

    @Test
    void reconcileFalseWithoutRunDoesNotTouchWakeOrQueue() {
        FakeHost a = host("A");
        a.shouldScheduleResult = false;   // desired=false

        int wakeSchedulesBefore = wake.scheduleCount;
        int queueSizeBefore = core.schedulerStats().queueSize();

        assertEquals(TickingScheduler.ReconcileResult.UNCHANGED,
                core.reconcile("A", false));

        assertEquals(wakeSchedulesBefore, wake.scheduleCount);
        assertEquals(queueSizeBefore, core.schedulerStats().queueSize());
        assertInvariant();
    }

    @Test
    void reconcileTrueWithExistingRunKeepsTheDueTickAndAddsNothing() {
        FakeHost a = host("A");

        core.schedule("A", 100);
        long dueBefore = core.schedulerStats().nextLiveDueTick();

        assertEquals(TickingScheduler.ReconcileResult.UNCHANGED,
                core.reconcile("A", true));

        assertEquals(dueBefore, core.schedulerStats().nextLiveDueTick());
        assertEquals(1, core.schedulerStats().liveQueuedRuns());
        assertInvariant();
    }

    @Test
    void needsScheduleThenScheduleIfAbsentCreatesExactlyOneRun() {
        FakeHost a = host("A");

        assertEquals(TickingScheduler.ReconcileResult.NEEDS_SCHEDULE,
                core.reconcile("A", true));
        core.scheduleIfAbsent("A", 100);
        core.scheduleIfAbsent("A", 50);   // 已有任务：忽略

        assertEquals(1, core.schedulerStats().liveQueuedRuns());
        assertEquals(100, core.schedulerStats().nextLiveDueTick());

        advanceTo(100);
        assertEquals(1, a.tickCount);
        assertInvariant();
    }

    @Test
    void reconcileFalseInvalidatesInFlightRunSoItNeverDispatches() {
        FakeHost a = host("A");
        a.shouldScheduleResult = false;

        core.schedule("A", 50);
        clock.tick = 50;
        List<TickingScheduler.ScheduledRun> due = core.collectDue();   // 已出队 in-flight

        assertEquals(TickingScheduler.ReconcileResult.CANCELLED,
                core.reconcile("A", false));

        core.dispatchCollected(due);
        core.finishDispatch();

        assertEquals(0, a.tickCount);
        assertInvariant();
    }

    // ===== 工具 =====

    private void advanceTo(long tick) {
        clock.tick = tick;
        wake.fire();
    }

    private FakeHost host(String id) {
        FakeHost host = new FakeHost(id);
        core.activate(id, host);
        core.setBound(id, true);
        return host;
    }

    /** live/stale/queueSize 三个计数必须始终一致。 */
    private void assertInvariant() {
        TickingScheduler.SchedulerStats stats = core.schedulerStats();
        assertTrue(stats.liveQueuedRuns() >= 0);
        assertTrue(stats.staleQueuedRuns() >= 0);
        assertTrue(stats.dueBucketCount() >= 0);
        assertTrue(stats.dueBucketCount() <= stats.queueSize());
        assertEquals(stats.queueSize(), stats.liveQueuedRuns() + stats.staleQueuedRuns());
    }

    private static final class FakeClock implements LongSupplier {
        long tick;

        @Override
        public long getAsLong() {
            return tick;
        }
    }

    private static final class FakeWake implements TickingScheduler.WakeTarget {
        int cancelCount;
        int scheduleCount;
        ScheduledWake current;

        @Override
        public void cancel() {
            if (current != null) {
                current.cancelled = true;
                current = null;
                cancelCount++;
            }
        }

        @Override
        public void schedule(long delayTicks, Runnable action) {
            current = new ScheduledWake(delayTicks, action);
            scheduleCount++;
        }

        void fire() {
            ScheduledWake wake = current;
            current = null;
            if (wake != null && !wake.cancelled) {
                wake.action.run();
            }
        }
    }

    private static final class ScheduledWake {
        final long delay;
        final Runnable action;
        boolean cancelled;

        ScheduledWake(long delay, Runnable action) {
            this.delay = delay;
            this.action = action;
        }
    }

    private static final class FakeHost implements TickingScheduler.Host {
        final String id;
        int tickCount;
        int shouldScheduleCalls;
        int onHandlerMissingCount;
        int onHandlerChangedCount;
        final List<Throwable> failures = new ArrayList<>();
        boolean handlerBound = true;
        boolean handlerChanged;
        boolean shouldScheduleResult = true;
        Boolean postTickScheduleDecision;
        int nextDelay = 1;
        Runnable tickAction = () -> {
        };
        RuntimeException tickFailure;
        RuntimeException shouldScheduleFailure;
        RuntimeException nextDelayFailure;

        FakeHost(String id) {
            this.id = id;
        }

        @Override
        public boolean isHandlerBound(String id) {
            return handlerBound;
        }

        @Override
        public boolean isHandlerChanged(String id) {
            return handlerChanged;
        }

        @Override
        public boolean shouldSchedule(String id) {
            shouldScheduleCalls++;
            if (shouldScheduleFailure != null) {
                throw shouldScheduleFailure;
            }
            return shouldScheduleResult;
        }

        @Override
        public void tick(String id) {
            tickCount++;
            tickAction.run();
            if (tickFailure != null) {
                throw tickFailure;
            }
        }

        @Override
        public Boolean postTickScheduleDecision(String id) {
            return postTickScheduleDecision;
        }

        @Override
        public void onHandlerMissing(String id) {
            onHandlerMissingCount++;
        }

        @Override
        public void onHandlerChanged(String id) {
            onHandlerChangedCount++;
        }

        @Override
        public int nextDelay(String id) {
            if (nextDelayFailure != null) {
                throw nextDelayFailure;
            }
            return nextDelay;
        }

        @Override
        public void onRunFailure(String id, Throwable failure) {
            failures.add(failure);
        }
    }
}
