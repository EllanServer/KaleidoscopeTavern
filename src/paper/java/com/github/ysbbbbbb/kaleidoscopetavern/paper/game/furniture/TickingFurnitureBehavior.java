package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviorTemplate;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviors;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Lets CraftEngine own loaded-furniture lifecycle while one due-time queue
 * drives sparse fixed and random gameplay ticks.
 */
public final class TickingFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:ticking_furniture";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Object RUNTIME_LOCK = new Object();
    private static final PriorityQueue<ScheduledRun> DUE = new PriorityQueue<>();

    private static JavaPlugin runtimePlugin;
    private static BukkitTask schedulerTask;
    private static long scheduledWakeTick = Long.MAX_VALUE;
    private static long nextSequence;
    private static boolean dispatchingDue;

    /*
     * 惰性失效的队列账本：取消/重新调度只把节点标记为 stale，
     * 队头清理只负责连续的 stale 节点，剩余的在周期压缩时一并剔除。
     */
    private static int liveQueuedRuns;
    private static int staleQueuedRuns;

    private static final int COMPACT_MIN_QUEUE_SIZE = 512;
    private static final int COMPACT_MIN_STALE_RUNS = 256;

    private final Channel channel;
    private final Schedule schedule;

    private TickingFurnitureBehavior(FurnitureDefinition furniture, ConfigSection section) {
        super(furniture);
        this.channel = parseChannel(section.getNonEmptyString("channel"), section);
        this.schedule = Schedule.parse(section);
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            FurnitureBehaviors.register(Key.of(TYPE), TickingFurnitureBehavior::new);
        }
    }

    /** Starts one due-time queue instead of one CE ticker callback per furniture. */
    public static void start(JavaPlugin plugin) {
        JavaPlugin owner = Objects.requireNonNull(plugin, "plugin");
        synchronized (RUNTIME_LOCK) {
            if (runtimePlugin == owner) {
                return;
            }
            stopLocked();
            runtimePlugin = owner;
            for (Channel channel : Channel.values()) {
                for (Controller controller : channel.snapshot()) {
                    controller.restartSchedule();
                }
            }
        }
    }

    public static void stop() {
        synchronized (RUNTIME_LOCK) {
            stopLocked();
        }
    }

    private static void stopLocked() {
        if (schedulerTask != null) {
            schedulerTask.cancel();
            schedulerTask = null;
        }
        scheduledWakeTick = Long.MAX_VALUE;
        runtimePlugin = null;
        DUE.clear();
        liveQueuedRuns = 0;
        staleQueuedRuns = 0;
        for (Channel channel : Channel.values()) {
            for (Controller controller : channel.snapshot()) {
                // 推进 generation，让已被 runDueControllers() 取出、
                // 但尚未执行的旧任务也失效。
                controller.scheduledRun = null;
                controller.scheduleGeneration++;
            }
        }
        nextSequence = 0;
        dispatchingDue = false;
    }

    public static void bind(Channel channel, Handler handler) {
        Channel boundChannel = Objects.requireNonNull(channel, "channel");
        Handler boundHandler = Objects.requireNonNull(handler, "handler");
        synchronized (boundChannel) {
            boundChannel.handler = boundHandler;
        }
        for (Controller controller : boundChannel.snapshot()) {
            controller.deliver(boundHandler);
        }
    }

    public static void unbind(Channel channel, Handler handler) {
        Channel boundChannel = Objects.requireNonNull(channel, "channel");
        Handler boundHandler = Objects.requireNonNull(handler, "handler");
        synchronized (boundChannel) {
            if (boundChannel.handler != boundHandler) {
                return;
            }
            boundChannel.handler = null;
        }
        for (Controller controller : boundChannel.snapshot()) {
            controller.forget(boundHandler);
        }
    }

    /** Re-evaluates one furniture after gameplay changes its scheduling state. */
    public static void refreshSchedule(Channel channel, BukkitFurniture furniture) {
        Channel targetChannel = Objects.requireNonNull(channel, "channel");
        BukkitFurniture targetFurniture = Objects.requireNonNull(furniture, "furniture");
        Controller controller = targetChannel.activeControllers.get(targetFurniture.uuid());
        if (controller != null) {
            controller.refreshSchedule();
        }
    }

    @Override
    public FurnitureController createController(Furniture furniture) {
        if (!(furniture instanceof BukkitFurniture bukkitFurniture)) {
            throw new IllegalArgumentException("Ticking furniture requires BukkitFurniture");
        }
        return new Controller(bukkitFurniture, channel, schedule);
    }

    private static Channel parseChannel(String value, ConfigSection section) {
        try {
            return Channel.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unknown ticking furniture channel at " + section.assemblePath("channel")
                            + ": " + value,
                    exception);
        }
    }

    public enum Channel {
        MYSTERY_PARTICLE,
        BARREL;

        private final Map<UUID, Controller> activeControllers = new HashMap<>();
        private volatile Handler handler;

        private List<Controller> snapshot() {
            return new ArrayList<>(activeControllers.values());
        }
    }

    @FunctionalInterface
    public interface Handler {
        void tick(BukkitFurniture furniture);

        default void onReady(BukkitFurniture furniture) {
        }

        default boolean shouldSchedule(BukkitFurniture furniture) {
            return true;
        }

        default void onUnload(BukkitFurniture furniture, boolean isStopping) {
        }

        default void onRemove(BukkitFurniture furniture) {
        }
    }

    private static final class Controller extends FurnitureController {
        private final BukkitFurniture bukkitFurniture;
        private final Channel channel;
        private final Schedule schedule;

        private boolean active;
        private Handler deliveredHandler;
        private ScheduledRun scheduledRun;
        private long scheduleGeneration;

        private Controller(BukkitFurniture furniture, Channel channel, Schedule schedule) {
            super(furniture);
            this.bukkitFurniture = furniture;
            this.channel = channel;
            this.schedule = schedule;
        }

        @Override
        public void onPlace(Player player) {
            activate();
        }

        @Override
        public void onLoad() {
            activate();
        }

        @Override
        public void onUnload(boolean isStopping) {
            Handler handler = deliveredHandler;
            deactivate();
            if (handler != null) {
                handler.onUnload(bukkitFurniture, isStopping);
            }
        }

        @Override
        public void preRemove(Player player) {
            Handler handler = deliveredHandler;
            deactivate();
            if (handler != null) {
                handler.onRemove(bukkitFurniture);
            }
        }

        private void activate() {
            if (!active) {
                active = true;
                channel.activeControllers.put(bukkitFurniture.uuid(), this);
            }
            deliver(channel.handler);
        }

        private void deactivate() {
            if (!active) {
                return;
            }
            active = false;
            channel.activeControllers.remove(bukkitFurniture.uuid(), this);
            cancelSchedule();
            deliveredHandler = null;
        }

        private void deliver(Handler handler) {
            if (!active || handler == null || handler == deliveredHandler) {
                return;
            }
            cancelSchedule();
            handler.onReady(bukkitFurniture);
            deliveredHandler = handler;
            refreshSchedule();
        }

        private void forget(Handler handler) {
            if (deliveredHandler == handler) {
                cancelSchedule();
                deliveredHandler = null;
            }
        }

        private void restartSchedule() {
            refreshSchedule();
        }

        private void refreshSchedule() {
            Handler handler = deliveredHandler;
            if (!active || handler == null || runtimePlugin == null
                    || !handler.shouldSchedule(bukkitFurniture)) {
                cancelSchedule();
                return;
            }
            if (scheduledRun == null) {
                scheduleInitial();
            }
        }

        private void scheduleInitial() {
            schedule(schedule.firstDelay(bukkitFurniture));
        }

        private void schedule(int delay) {
            synchronized (RUNTIME_LOCK) {
                if (!active || deliveredHandler == null || runtimePlugin == null) {
                    return;
                }
                // 旧任务如果仍在队列，只标记为 stale，不做 O(n) 删除。
                invalidateCurrentRunLocked();
                ScheduledRun run = new ScheduledRun(this,
                        scheduleGeneration, currentServerTick() + Math.max(1, delay),
                        nextSequence++);
                scheduledRun = run;
                DUE.add(run);
                liveQueuedRuns++;
                maybeCompactQueueLocked();
                scheduleWakeLocked();
            }
        }

        private void cancelSchedule() {
            synchronized (RUNTIME_LOCK) {
                if (scheduledRun == null) {
                    return;
                }
                invalidateCurrentRunLocked();
                pruneStaleHeadLocked();
                maybeCompactQueueLocked();
                scheduleWakeLocked();
            }
        }

        /** 每次失效都推进 generation；仍在队列的旧节点只标记为 stale。 */
        private void invalidateCurrentRunLocked() {
            ScheduledRun oldRun = scheduledRun;
            scheduleGeneration++;
            scheduledRun = null;
            if (oldRun != null && oldRun.queued && !oldRun.stale) {
                oldRun.stale = true;
                liveQueuedRuns--;
                staleQueuedRuns++;
            }
        }

        /** {@code run} 仍是该 Controller 当前持有的 live 任务才返回 true。 */
        private boolean isCurrentRun(ScheduledRun run) {
            synchronized (RUNTIME_LOCK) {
                return active
                        && runtimePlugin != null
                        && scheduledRun == run
                        && scheduleGeneration == run.generation;
            }
        }

        private void runScheduled(ScheduledRun run) {
            if (!isCurrentRun(run)) {
                return;
            }
            Handler handler = channel.handler;
            if (handler == null) {
                clearRunIfCurrent(run);
                deliveredHandler = null;
                return;
            }
            if (handler != deliveredHandler) {
                // deliver() 内部会取消旧 run，并创建属于新 handler 的任务。
                deliver(handler);
                return;
            }
            runHandlerAndComplete(run, handler);
        }

        private void clearRunIfCurrent(ScheduledRun run) {
            finishRunIfCurrent(run, false, 0);
        }

        private void runHandlerAndComplete(ScheduledRun run, Handler handler) {
            boolean scheduleAgain = false;
            boolean schedulingDecisionCompleted = false;
            try {
                handler.tick(bukkitFurniture);
            } finally {
                try {
                    if (isCurrentRun(run) && active && deliveredHandler == handler) {
                        scheduleAgain = handler.shouldSchedule(bukkitFurniture);
                    }
                    schedulingDecisionCompleted = true;
                } finally {
                    // shouldSchedule 抛异常时也通过这一层 finally 清掉 in-flight run，
                    // 否则 scheduledRun 会永远指向已执行结束的任务。
                    int nextDelay = scheduleAgain ? schedule.nextDelay() : 0;
                    finishRunIfCurrent(
                            run,
                            schedulingDecisionCompleted && scheduleAgain,
                            nextDelay
                    );
                }
            }
        }

        /**
         * 只有当 Controller 当前仍指向刚执行的 {@code completedRun} 时，
         * 才允许完成或重调度；handler.tick() 内部的重入调度不会被覆盖。
         */
        private void finishRunIfCurrent(
                ScheduledRun completedRun,
                boolean scheduleAgain,
                int nextDelay
        ) {
            synchronized (RUNTIME_LOCK) {
                if (scheduledRun != completedRun
                        || scheduleGeneration != completedRun.generation) {
                    // handler.tick() 期间已经取消或重新调度。
                    return;
                }
                invalidateCurrentRunLocked();
                if (scheduleAgain
                        && active
                        && deliveredHandler != null
                        && runtimePlugin != null) {
                    ScheduledRun nextRun = new ScheduledRun(this,
                            scheduleGeneration,
                            currentServerTick() + Math.max(1, nextDelay),
                            nextSequence++);
                    scheduledRun = nextRun;
                    DUE.add(nextRun);
                    liveQueuedRuns++;
                }
                maybeCompactQueueLocked();
                // dispatchingDue=true 时这里不会立即创建 BukkitTask，
                // 整批 due 执行完成后统一安排。
                scheduleWakeLocked();
            }
        }
    }

    private static void runDueControllers() {
        List<ScheduledRun> due = null;
        synchronized (RUNTIME_LOCK) {
            schedulerTask = null;
            scheduledWakeTick = Long.MAX_VALUE;
            dispatchingDue = true;
            long currentTick = currentServerTick();
            while (true) {
                ScheduledRun run = peekLiveRunLocked();
                if (run == null || run.dueTick > currentTick) {
                    break;
                }
                DUE.poll();
                run.queued = false;
                liveQueuedRuns--;
                if (due == null) {
                    due = new ArrayList<>();
                }
                // 保留 ScheduledRun，而不是只保留 Controller。
                // scheduledRun 仍指向 run：它表示该任务已出队、正在等待执行，
                // 执行前的 generation 复检与 compare-and-complete 都依赖它。
                due.add(run);
            }
        }
        try {
            if (due != null) {
                for (ScheduledRun run : due) {
                    try {
                        run.controller.runScheduled(run);
                    } catch (RuntimeException | LinkageError exception) {
                        JavaPlugin owner = runtimePlugin;
                        if (owner != null) {
                            owner.getLogger().log(Level.SEVERE,
                                    "Scheduled furniture tick failed for "
                                            + run.controller.bukkitFurniture.id(), exception);
                        }
                    }
                }
            }
        } finally {
            synchronized (RUNTIME_LOCK) {
                dispatchingDue = false;
                pruneStaleHeadLocked();
                maybeCompactQueueLocked();
                scheduleWakeLocked();
            }
        }
    }

    /** 为最早的 live 任务安排恰好一个唤醒回调。 */
    private static void scheduleWakeLocked() {
        if (dispatchingDue || runtimePlugin == null) {
            return;
        }
        ScheduledRun next = peekLiveRunLocked();
        if (next == null) {
            if (schedulerTask != null) {
                schedulerTask.cancel();
                schedulerTask = null;
            }
            scheduledWakeTick = Long.MAX_VALUE;
            return;
        }
        if (schedulerTask != null) {
            /*
             * 已有任务比新队头更早或相同：保留它即可，它不会让 live run 迟到。
             * 旧任务可能提前唤醒一次，但避免每次取消队头都 cancel + runTaskLater。
             */
            if (scheduledWakeTick <= next.dueTick) {
                return;
            }
            // 新任务更早，必须提前唤醒。
            schedulerTask.cancel();
        }
        long delay = Math.max(1L, next.dueTick - currentServerTick());
        scheduledWakeTick = next.dueTick;
        schedulerTask = Bukkit.getScheduler().runTaskLater(
                runtimePlugin, TickingFurnitureBehavior::runDueControllers, delay);
    }

    /** 先删除队头连续的 stale 节点，再返回真正的 live 队头。 */
    private static ScheduledRun peekLiveRunLocked() {
        pruneStaleHeadLocked();
        return DUE.peek();
    }

    private static void pruneStaleHeadLocked() {
        while (true) {
            ScheduledRun run = DUE.peek();
            if (run == null || run.isCurrent()) {
                return;
            }
            DUE.poll();
            run.queued = false;
            if (run.stale) {
                staleQueuedRuns--;
            }
        }
    }

    private static boolean shouldCompactQueueLocked() {
        return DUE.size() >= COMPACT_MIN_QUEUE_SIZE
                && staleQueuedRuns >= COMPACT_MIN_STALE_RUNS
                && staleQueuedRuns > liveQueuedRuns;
    }

    /** stale 节点较多时做一次 O(n) 重建，成本被数百次 O(1) 取消摊销。 */
    private static void maybeCompactQueueLocked() {
        if (!shouldCompactQueueLocked()) {
            return;
        }
        PriorityQueue<ScheduledRun> rebuilt =
                new PriorityQueue<>(Math.max(11, liveQueuedRuns));
        for (ScheduledRun run : DUE) {
            if (run.isCurrent()) {
                rebuilt.add(run);
            } else {
                run.queued = false;
            }
        }
        DUE.clear();
        DUE.addAll(rebuilt);
        liveQueuedRuns = DUE.size();
        staleQueuedRuns = 0;
    }

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

    static SchedulerStats schedulerStats() {
        synchronized (RUNTIME_LOCK) {
            ScheduledRun next = peekLiveRunLocked();
            return new SchedulerStats(
                    DUE.size(),
                    liveQueuedRuns,
                    staleQueuedRuns,
                    next == null ? Long.MAX_VALUE : next.dueTick,
                    scheduledWakeTick,
                    dispatchingDue
            );
        }
    }

    private static long currentServerTick() {
        return Integer.toUnsignedLong(Bukkit.getCurrentTick());
    }

    private static final class ScheduledRun implements Comparable<ScheduledRun> {
        private final Controller controller;
        private final long generation;
        private final long dueTick;
        private final long sequence;

        // 只允许在 RUNTIME_LOCK 内修改
        private boolean queued = true;
        private boolean stale;

        private ScheduledRun(
                Controller controller,
                long generation,
                long dueTick,
                long sequence
        ) {
            this.controller = controller;
            this.generation = generation;
            this.dueTick = dueTick;
            this.sequence = sequence;
        }

        private boolean isCurrent() {
            return queued
                    && !stale
                    && controller.scheduledRun == this
                    && controller.scheduleGeneration == generation;
        }

        @Override
        public int compareTo(ScheduledRun other) {
            int byTick = Long.compare(dueTick, other.dueTick);
            return byTick != 0 ? byTick : Long.compare(sequence, other.sequence);
        }
    }

    private static final class Schedule {
        private final int interval;
        private final int chance;
        private final boolean identityPhase;

        private Schedule(int interval, int chance, boolean identityPhase) {
            this.interval = interval;
            this.chance = chance;
            this.identityPhase = identityPhase;
        }

        private static Schedule parse(ConfigSection section) {
            boolean hasInterval = section.containsKey("interval");
            boolean hasChance = section.containsKey("chance");
            if (hasInterval == hasChance) {
                throw new IllegalArgumentException(
                        "Ticking furniture requires exactly one of "
                                + section.assemblePath("interval") + " or "
                                + section.assemblePath("chance"));
            }
            if (hasChance) {
                int chance = section.getInt("chance", 0);
                if (chance < 1) {
                    throw new IllegalArgumentException(
                            "Ticking furniture chance must be positive at "
                                    + section.assemblePath("chance"));
                }
                if (section.containsKey("phase")) {
                    throw new IllegalArgumentException(
                            "Random ticking furniture cannot define "
                                    + section.assemblePath("phase"));
                }
                return new Schedule(0, chance, false);
            }
            int interval = section.getInt("interval", 0);
            if (interval < 1) {
                throw new IllegalArgumentException(
                        "Ticking furniture interval must be positive at "
                                + section.assemblePath("interval"));
            }
            String phase = section.getString("phase", "global");
            boolean identityPhase = switch (phase.toLowerCase(Locale.ROOT)) {
                case "global" -> false;
                case "identity" -> true;
                default -> throw new IllegalArgumentException(
                        "Unknown ticking furniture phase at " + section.assemblePath("phase")
                                + ": " + phase);
            };
            return new Schedule(interval, 0, identityPhase);
        }

        private int firstDelay(BukkitFurniture furniture) {
            if (chance > 0) {
                return geometricDelay(chance, ThreadLocalRandom.current().nextDouble());
            }
            int identityHash = identityPhase ? furniture.uuid().hashCode() : 0;
            return firstFutureDelay(furniture.location().getWorld().getGameTime(),
                    identityHash, interval);
        }

        private int nextDelay() {
            return chance > 0
                    ? geometricDelay(chance, ThreadLocalRandom.current().nextDouble())
                    : interval;
        }
    }

    /** Returns the first future tick (never the load tick) matching the phase. */
    static int firstFutureDelay(long gameTime, int identityHash, int interval) {
        return 1 + initialDelay(gameTime + 1, identityHash, interval);
    }

    /** Returns the delay to the globally phased source-modulo tick. */
    static int initialDelay(long gameTime, int identityHash, int interval) {
        if (interval <= 1) {
            return 0;
        }
        long phase = Math.floorMod(identityHash, interval);
        long remainder = Math.floorMod(gameTime + phase, interval);
        return Math.floorMod(-remainder, interval);
    }

    /** Inverse-CDF sample for independent one-in-{@code chance} trials. */
    static int geometricDelay(int chance, double uniform) {
        if (chance < 1) {
            throw new IllegalArgumentException("chance must be positive");
        }
        if (!(uniform >= 0.0 && uniform < 1.0)) {
            throw new IllegalArgumentException("uniform must be in [0, 1)");
        }
        if (chance == 1) {
            return 1;
        }
        double sampled = Math.floor(
                Math.log1p(-uniform) / Math.log1p(-1.0 / chance)) + 1.0;
        return sampled >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sampled;
    }
}
