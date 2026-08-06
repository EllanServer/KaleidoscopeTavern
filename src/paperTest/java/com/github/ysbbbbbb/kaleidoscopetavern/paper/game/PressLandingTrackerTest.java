package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压榨桶落地追踪状态机的纯逻辑测试：候选过滤先于 fallDistance、已追踪实体
 * 跳过空间查询、0.5 格阈值、跨桶下落、摔落伤害兜底与两 tick 落地冷却。
 */
class PressLandingTrackerTest {
    private static final UUID WORLD = UUID.randomUUID();

    private FakeClock clock;
    private FakeProbe probe;
    private FakeAction action;
    private PressLandingTracker tracker;
    private int trackedNotifications;
    private int untrackedNotifications;

    @BeforeEach
    void setUp() {
        clock = new FakeClock();
        probe = new FakeProbe();
        action = new FakeAction();
        trackedNotifications = 0;
        untrackedNotifications = 0;
        tracker = new PressLandingTracker(
                clock::get,
                probe::hasPotentialBelow,
                action::tryPress,
                () -> trackedNotifications++,
                () -> untrackedNotifications++);
    }

    private void move(UUID id, float fallDistance) {
        tracker.onMove(id, WORLD, 10, 64.5, -2, () -> fallDistance);
    }

    private void move(UUID id, double x, double y, double z, float fallDistance) {
        tracker.onMove(id, WORLD, x, y, z, () -> fallDistance);
    }

    @Test
    void fallingEntityAwayFromTubsIsFilteredBeforeReadingFallDistance() {
        UUID id = UUID.randomUUID();
        probe.covered = false;
        int[] fallDistanceReads = {0};
        tracker.onMove(id, WORLD, 100, 64, 100,
                () -> {
                    fallDistanceReads[0]++;
                    return 1.0F;
                });
        assertEquals(0, fallDistanceReads[0]);
        assertFalse(tracker.isTracked(id));
        assertEquals(0, trackedNotifications);
        assertTrue(tracker.isEmpty());
    }

    @Test
    void groundMovementOverTubIsIgnoredWhenNotFalling() {
        UUID id = UUID.randomUUID();
        probe.covered = true;
        move(id, 0F);
        assertFalse(tracker.isTracked(id));
        assertEquals(0, trackedNotifications);
    }

    @Test
    void fallingOverTubStartsTrackingAndKeepsTheMaximumDistance() {
        UUID id = UUID.randomUUID();
        probe.covered = true;
        move(id, 0.4F);
        move(id, 0.9F);
        move(id, 0.6F); // 不回落
        assertTrue(tracker.isTracked(id));
        assertEquals(1, trackedNotifications);

        move(id, 0F); // 落地边缘，tracked = 0.9 >= 0.5
        assertEquals(1, action.pressCount);
        assertFalse(tracker.isTracked(id));
        assertEquals(1, untrackedNotifications);
    }

    @Test
    void trackedEntityLeavingTheTubDoesNotPressWhenNothingIsBelow() {
        UUID id = UUID.randomUUID();
        probe.covered = true;
        move(id, 0.8F);

        // 已追踪实体不再查询空间索引，即使水平移动离开桶。
        int probeCallsBefore = probe.calls;
        move(id, 100, 64.5, 100, 0.9F);
        assertEquals(probeCallsBefore, probe.calls);

        // 落点没有桶：action 拒绝压榨（对应 findBelow 返回空）。
        action.pressed = false;
        move(id, 100, 64, 100, 0F);
        assertEquals(1, action.pressCount);
        assertFalse(tracker.isTracked(id));
        assertEquals(1, untrackedNotifications);
    }

    @Test
    void fallingFromOneTubToAnotherStillLandsOnTheFinalCell() {
        UUID id = UUID.randomUUID();
        probe.covered = true;
        move(id, 9.5, 64.5, -2, 0.5F);

        // 下落途中水平移动到另一个桶上方：只更新最大距离，不重复探测。
        int probeCallsBefore = probe.calls;
        move(id, 10.5, 63.5, -2, 0.9F);
        assertEquals(probeCallsBefore, probe.calls);

        move(id, 10.5, 62.5, -2, 0F);
        assertEquals(1, action.pressCount);
        assertEquals(10.5, action.lastX, 1.0E-9);
        assertEquals(62.5, action.lastY, 1.0E-9);
    }

    @Test
    void minimumHalfBlockFallThresholdTriggersExactlyAtTheBoundary() {
        UUID id = UUID.randomUUID();
        probe.covered = true;
        move(id, 0.5F);
        move(id, 0F);
        assertEquals(1, action.pressCount);

        UUID tooShort = UUID.randomUUID();
        move(tooShort, 0.4999F);
        move(tooShort, 0F);
        assertEquals(1, action.pressCount);
    }

    @Test
    void playerAndNonPlayerPathsShareTheSameMovePipeline() {
        // 玩家与非玩家路径都通过 onMove 进入同一状态机。
        UUID player = UUID.randomUUID();
        UUID villager = UUID.randomUUID();
        probe.covered = true;
        move(player, 0.7F);
        move(villager, 0.6F);
        move(player, 0F);
        move(villager, 0F);
        assertEquals(2, action.pressCount);
        assertFalse(tracker.isTracked(player));
        assertFalse(tracker.isTracked(villager));
    }

    @Test
    void fallDamageIsCancelledWhenTheTrackedFallPresses() {
        UUID id = UUID.randomUUID();
        probe.covered = true;
        move(id, 0.6F);
        assertTrue(tracker.onFallDamage(id, WORLD, 10, 64, -2, 0F));
        assertFalse(tracker.isTracked(id));
        assertEquals(1, action.pressCount);
    }

    @Test
    void fallDamageBelowTheThresholdIsNotCancelled() {
        UUID id = UUID.randomUUID();
        assertFalse(tracker.onFallDamage(id, WORLD, 10, 64, -2, 0.1F));
        assertEquals(0, action.pressCount);
        assertFalse(tracker.isTracked(id));
    }

    @Test
    void duplicateLandingWithinTwoTicksDoesNotPressTwice() {
        UUID id = UUID.randomUUID();
        probe.covered = true;
        clock.tick = 100;
        move(id, 0.7F);
        move(id, 0F); // tick 100 落地 → 压榨一次
        assertEquals(1, action.pressCount);

        // 下一 tick 再次落地：命中两 tick 冷却，返回缓存结果。
        move(id, 0.8F);
        clock.tick = 101;
        move(id, 0F);
        assertEquals(1, action.pressCount);

        // 超过冷却窗口后允许再次压榨。
        move(id, 0.8F);
        clock.tick = 103;
        move(id, 0F);
        assertEquals(2, action.pressCount);
    }

    @Test
    void cleanupDropsDespawnedEntitiesAndNotifiesIdle() {
        UUID alive = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        probe.covered = true;
        move(alive, 0.5F);
        move(gone, 0.5F);
        assertEquals(2, trackedNotifications);

        tracker.removeTrackedIf(id -> id.equals(gone));
        assertFalse(tracker.isTracked(gone));
        assertTrue(tracker.isTracked(alive));
        assertEquals(1, untrackedNotifications);
    }

    private static final class FakeClock {
        private long tick;

        private long get() {
            return tick;
        }
    }

    private static final class FakeProbe implements PressLandingTracker.LandingProbe {
        private boolean covered;
        private int calls;

        @Override
        public boolean hasPotentialBelow(UUID worldId,
                                         double feetX, double feetY, double feetZ) {
            calls++;
            return covered;
        }
    }

    private static final class FakeAction implements PressLandingTracker.LandingAction {
        private boolean pressed = true;
        private int pressCount;
        private double lastX;
        private double lastY;
        private double lastZ;

        @Override
        public boolean tryPress(UUID worldId, double feetX, double feetY, double feetZ) {
            pressCount++;
            lastX = feetX;
            lastY = feetY;
            lastZ = feetZ;
            return pressed;
        }
    }
}
