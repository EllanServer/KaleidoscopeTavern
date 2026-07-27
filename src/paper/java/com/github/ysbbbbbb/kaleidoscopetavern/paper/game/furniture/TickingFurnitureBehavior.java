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
        for (Channel channel : Channel.values()) {
            for (Controller controller : channel.snapshot()) {
                controller.scheduledRun = null;
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

        private void scheduleNext() {
            schedule(schedule.nextDelay());
        }

        private void schedule(int delay) {
            synchronized (RUNTIME_LOCK) {
                if (!active || deliveredHandler == null || runtimePlugin == null) {
                    return;
                }
                if (scheduledRun != null) {
                    DUE.remove(scheduledRun);
                }
                ScheduledRun run = new ScheduledRun(this,
                        currentServerTick() + Math.max(1, delay), nextSequence++);
                scheduledRun = run;
                DUE.add(run);
                scheduleWakeLocked();
            }
        }

        private void cancelSchedule() {
            synchronized (RUNTIME_LOCK) {
                if (scheduledRun != null) {
                    DUE.remove(scheduledRun);
                    scheduledRun = null;
                    scheduleWakeLocked();
                }
            }
        }

        private void runScheduled() {
            if (!active) {
                return;
            }
            Handler handler = channel.handler;
            if (handler == null) {
                deliveredHandler = null;
                return;
            }
            if (handler != deliveredHandler) {
                deliver(handler);
                return;
            }
            try {
                handler.tick(bukkitFurniture);
            } finally {
                if (active && deliveredHandler == handler) {
                    if (handler.shouldSchedule(bukkitFurniture)) {
                        scheduleNext();
                    } else {
                        cancelSchedule();
                    }
                }
            }
        }
    }

    private static void runDueControllers() {
        List<Controller> due = null;
        synchronized (RUNTIME_LOCK) {
            schedulerTask = null;
            scheduledWakeTick = Long.MAX_VALUE;
            dispatchingDue = true;
            long currentTick = currentServerTick();
            while (!DUE.isEmpty() && DUE.peek().dueTick <= currentTick) {
                ScheduledRun run = DUE.poll();
                if (run.controller.scheduledRun == run) {
                    run.controller.scheduledRun = null;
                    if (due == null) {
                        due = new ArrayList<>();
                    }
                    due.add(run.controller);
                }
            }
        }
        try {
            if (due != null) {
                for (Controller controller : due) {
                    try {
                        controller.runScheduled();
                    } catch (RuntimeException | LinkageError exception) {
                        JavaPlugin owner = runtimePlugin;
                        if (owner != null) {
                            owner.getLogger().log(Level.SEVERE,
                                    "Scheduled furniture tick failed for "
                                            + controller.bukkitFurniture.id(), exception);
                        }
                    }
                }
            }
        } finally {
            synchronized (RUNTIME_LOCK) {
                dispatchingDue = false;
                scheduleWakeLocked();
            }
        }
    }

    /** Schedules exactly one callback for the earliest live controller. */
    private static void scheduleWakeLocked() {
        if (dispatchingDue || runtimePlugin == null) {
            return;
        }
        ScheduledRun next = DUE.peek();
        if (next == null) {
            if (schedulerTask != null) {
                schedulerTask.cancel();
                schedulerTask = null;
            }
            scheduledWakeTick = Long.MAX_VALUE;
            return;
        }
        if (schedulerTask != null && scheduledWakeTick == next.dueTick) {
            return;
        }
        if (schedulerTask != null) {
            schedulerTask.cancel();
        }
        long delay = Math.max(1L, next.dueTick - currentServerTick());
        scheduledWakeTick = next.dueTick;
        schedulerTask = Bukkit.getScheduler().runTaskLater(
                runtimePlugin, TickingFurnitureBehavior::runDueControllers, delay);
    }

    private static long currentServerTick() {
        return Integer.toUnsignedLong(Bukkit.getCurrentTick());
    }

    private static final class ScheduledRun implements Comparable<ScheduledRun> {
        private final Controller controller;
        private final long dueTick;
        private final long sequence;

        private ScheduledRun(Controller controller, long dueTick, long sequence) {
            this.controller = controller;
            this.dueTick = dueTick;
            this.sequence = sequence;
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
