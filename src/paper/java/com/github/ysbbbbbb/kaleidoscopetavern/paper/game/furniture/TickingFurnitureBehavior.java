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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private static long schedulerTick;
    private static long nextSequence;

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

    /** Starts one scheduler instead of one CE ticker callback per furniture. */
    public static void start(JavaPlugin plugin) {
        JavaPlugin owner = Objects.requireNonNull(plugin, "plugin");
        synchronized (RUNTIME_LOCK) {
            if (runtimePlugin == owner && schedulerTask != null) {
                return;
            }
            stopLocked();
            runtimePlugin = owner;
            schedulerTask = Bukkit.getScheduler().runTaskTimer(owner,
                    TickingFurnitureBehavior::runDueControllers, 1L, 1L);
            for (Channel channel : Channel.values()) {
                channel.activeControllers.forEach(Controller::restartSchedule);
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
        runtimePlugin = null;
        DUE.clear();
        for (Channel channel : Channel.values()) {
            channel.activeControllers.forEach(controller -> controller.scheduledRun = null);
        }
        schedulerTick = 0;
        nextSequence = 0;
    }

    public static void bind(Channel channel, Handler handler) {
        Channel boundChannel = Objects.requireNonNull(channel, "channel");
        Handler boundHandler = Objects.requireNonNull(handler, "handler");
        synchronized (boundChannel) {
            boundChannel.handler = boundHandler;
        }
        boundChannel.activeControllers.forEach(controller -> controller.deliver(boundHandler));
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
        boundChannel.activeControllers.forEach(controller -> controller.forget(boundHandler));
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
        INCENSE_EFFECT,
        INCENSE_PARTICLE,
        MYSTERY_PARTICLE,
        RACK_PARTICLE,
        BARREL;

        private final Set<Controller> activeControllers = ConcurrentHashMap.newKeySet();
        private volatile Handler handler;
    }

    @FunctionalInterface
    public interface Handler {
        void tick(BukkitFurniture furniture);

        default void onReady(BukkitFurniture furniture) {
        }

        default void onUnload(BukkitFurniture furniture, boolean isStopping) {
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

        private void activate() {
            if (!active) {
                active = true;
                channel.activeControllers.add(this);
            }
            deliver(channel.handler);
        }

        private void deactivate() {
            if (!active) {
                return;
            }
            active = false;
            channel.activeControllers.remove(this);
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
            scheduleInitial();
        }

        private void forget(Handler handler) {
            if (deliveredHandler == handler) {
                cancelSchedule();
                deliveredHandler = null;
            }
        }

        private void restartSchedule() {
            if (active && deliveredHandler != null && scheduledRun == null) {
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
                if (!active || deliveredHandler == null || schedulerTask == null) {
                    return;
                }
                if (scheduledRun != null) {
                    DUE.remove(scheduledRun);
                }
                ScheduledRun run = new ScheduledRun(this,
                        schedulerTick + Math.max(1, delay), nextSequence++);
                scheduledRun = run;
                DUE.add(run);
            }
        }

        private void cancelSchedule() {
            synchronized (RUNTIME_LOCK) {
                if (scheduledRun != null) {
                    DUE.remove(scheduledRun);
                    scheduledRun = null;
                }
            }
        }

        private void runScheduled() {
            if (!active || !bukkitFurniture.isValid()) {
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
                    scheduleNext();
                }
            }
        }
    }

    private static void runDueControllers() {
        List<Controller> due = null;
        synchronized (RUNTIME_LOCK) {
            schedulerTick++;
            while (!DUE.isEmpty() && DUE.peek().dueTick <= schedulerTick) {
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
        if (due == null) {
            return;
        }
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
