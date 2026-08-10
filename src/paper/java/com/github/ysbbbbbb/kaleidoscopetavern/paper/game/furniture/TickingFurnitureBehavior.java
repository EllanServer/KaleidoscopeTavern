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
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Lets CraftEngine own loaded-furniture lifecycle while one due-time queue
 * drives sparse fixed and random gameplay ticks.
 *
 * <p>The queue state machine itself lives in the pure, testable
 * {@link TickingScheduler}; this class only bridges it to Bukkit (current tick
 * and wake tasks) and to the CraftEngine furniture lifecycle.</p>
 */
public final class TickingFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:ticking_furniture";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Object RUNTIME_LOCK = new Object();

    private static JavaPlugin runtimePlugin;
    private static BukkitTask schedulerTask;

    private static final TickingScheduler SCHEDULER = new TickingScheduler(
            TickingFurnitureBehavior::currentServerTick,
            new TickingScheduler.WakeTarget() {
                @Override
                public void cancel() {
                    if (schedulerTask != null) {
                        schedulerTask.cancel();
                        schedulerTask = null;
                    }
                }

                @Override
                public void schedule(long delayTicks, Runnable action) {
                    JavaPlugin owner = runtimePlugin;
                    if (owner == null) {
                        return;
                    }
                    // dispatchDue replaces this reference with the next wake,
                    // or leaves a harmless completed task while idle. Passing
                    // the cached action directly removes one wrapper/lambda
                    // allocation and the hot stack frame on every wake.
                    schedulerTask = Bukkit.getScheduler().runTaskLater(
                            owner, action, delayTicks);
                }
            });

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
            SCHEDULER.start();
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
        SCHEDULER.stop();
        runtimePlugin = null;
        if (schedulerTask != null) {
            schedulerTask.cancel();
            schedulerTask = null;
        }
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

        /**
         * Returns a post-tick scheduling decision when the tick already has
         * the required state in hand. Null asks the scheduler to call
         * {@link #shouldSchedule(BukkitFurniture)} separately.
         */
        default Boolean tickAndScheduleDecision(BukkitFurniture furniture) {
            tick(furniture);
            return null;
        }

        default void onReady(BukkitFurniture furniture) {
        }

        default boolean shouldSchedule(BukkitFurniture furniture) {
            return true;
        }

        default void onUnload(BukkitFurniture furniture) {
        }

        default void onRemove(BukkitFurniture furniture) {
        }
    }

    private static final class Controller extends FurnitureController implements TickingScheduler.Host {
        private final BukkitFurniture bukkitFurniture;
        private final Channel channel;
        private final Schedule schedule;
        private final String schedulerId;

        private boolean active;
        private Handler deliveredHandler;
        private Boolean postTickScheduleDecision;

        private Controller(BukkitFurniture furniture, Channel channel, Schedule schedule) {
            super(furniture);
            this.bukkitFurniture = furniture;
            this.channel = channel;
            this.schedule = schedule;
            this.schedulerId = furniture.uuid().toString();
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
        public void onUnload() {
            Handler handler = deliveredHandler;
            deactivate();
            if (handler != null) {
                handler.onUnload(bukkitFurniture);
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
                SCHEDULER.activate(schedulerId, this);
            }
            deliver(channel.handler);
        }

        private void deactivate() {
            if (!active) {
                return;
            }
            active = false;
            channel.activeControllers.remove(bukkitFurniture.uuid(), this);
            SCHEDULER.deactivate(schedulerId);
            deliveredHandler = null;
        }

        private void deliver(Handler handler) {
            if (!active || handler == null || handler == deliveredHandler) {
                return;
            }
            // 使旧 run 失效，再交付新 handler。
            SCHEDULER.cancel(schedulerId);
            handler.onReady(bukkitFurniture);
            deliveredHandler = handler;
            SCHEDULER.setBound(schedulerId, true);
            refreshSchedule();
        }

        private void forget(Handler handler) {
            if (deliveredHandler == handler) {
                SCHEDULER.cancel(schedulerId);
                deliveredHandler = null;
                SCHEDULER.setBound(schedulerId, false);
            }
        }

        private void restartSchedule() {
            refreshSchedule();
        }

        private void refreshSchedule() {
            Handler handler = deliveredHandler;
            boolean desired = active
                    && handler != null
                    && handler.shouldSchedule(bukkitFurniture);
            // shouldSchedule 在锁外决策；只有确实缺少任务时才计算昂贵的 firstDelay。
            TickingScheduler.ReconcileResult result =
                    SCHEDULER.reconcile(schedulerId, desired);
            if (result == TickingScheduler.ReconcileResult.NEEDS_SCHEDULE) {
                SCHEDULER.scheduleIfAbsent(
                        schedulerId,
                        schedule.firstDelay(bukkitFurniture));
            }
        }

        // ===== TickingScheduler.Host =====

        @Override
        public boolean isHandlerBound(String id) {
            return channel.handler != null;
        }

        @Override
        public boolean isHandlerChanged(String id) {
            return channel.handler != deliveredHandler;
        }

        @Override
        public boolean shouldSchedule(String id) {
            Handler handler = deliveredHandler;
            return handler != null && handler.shouldSchedule(bukkitFurniture);
        }

        @Override
        public void tick(String id) {
            postTickScheduleDecision = null;
            Handler handler = deliveredHandler;
            if (handler != null) {
                postTickScheduleDecision =
                        handler.tickAndScheduleDecision(bukkitFurniture);
            }
        }

        @Override
        public Boolean postTickScheduleDecision(String id) {
            Boolean decision = postTickScheduleDecision;
            postTickScheduleDecision = null;
            return decision;
        }

        @Override
        public void onHandlerMissing(String id) {
            deliveredHandler = null;
            SCHEDULER.setBound(id, false);
        }

        @Override
        public void onHandlerChanged(String id) {
            deliver(channel.handler);
        }

        @Override
        public int nextDelay(String id) {
            return schedule.nextDelay();
        }

        @Override
        public void onRunFailure(String id, Throwable failure) {
            JavaPlugin owner = runtimePlugin;
            if (owner != null) {
                owner.getLogger().log(Level.SEVERE,
                        "Scheduled furniture tick failed for " + bukkitFurniture.id(), failure);
            }
        }
    }

    static TickingScheduler.SchedulerStats schedulerStats() {
        return SCHEDULER.schedulerStats();
    }

    private static long currentServerTick() {
        return Integer.toUnsignedLong(Bukkit.getCurrentTick());
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
