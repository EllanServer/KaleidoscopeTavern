package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviorTemplate;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviors;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.furniture.tick.FurnitureTicker;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lets CraftEngine drive periodic gameplay directly from loaded furniture. */
public final class TickingFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:ticking_furniture";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private final Channel channel;
    private final int interval;

    private TickingFurnitureBehavior(FurnitureDefinition furniture, ConfigSection section) {
        super(furniture);
        this.channel = parseChannel(section.getNonEmptyString("channel"), section);
        this.interval = Math.max(1, section.getInt("interval", 1));
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            FurnitureBehaviors.register(Key.of(TYPE), TickingFurnitureBehavior::new);
        }
    }

    public static void bind(Channel channel, Handler handler) {
        Channel boundChannel = Objects.requireNonNull(channel, "channel");
        Handler boundHandler = Objects.requireNonNull(handler, "handler");
        synchronized (boundChannel) {
            boundChannel.handler = boundHandler;
        }
    }

    public static void unbind(Channel channel, Handler handler) {
        Channel boundChannel = Objects.requireNonNull(channel, "channel");
        Handler boundHandler = Objects.requireNonNull(handler, "handler");
        synchronized (boundChannel) {
            if (boundChannel.handler == boundHandler) {
                boundChannel.handler = null;
            }
        }
    }

    @Override
    public FurnitureController createController(Furniture furniture) {
        if (!(furniture instanceof BukkitFurniture bukkitFurniture)) {
            throw new IllegalArgumentException("Ticking furniture requires BukkitFurniture");
        }
        return new Controller(bukkitFurniture, channel, interval);
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
        AMBIENT,
        BARREL;

        // Channels are a closed enum and plugin lifecycle writes happen on
        // the main thread. A volatile slot avoids a ConcurrentHashMap lookup
        // for every loaded ticking furniture on every server tick while still
        // making reload-time handler replacement visible.
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
        private static final FurnitureTicker<Controller> TICKER =
                (furniture, controller) -> controller.tickManagedFurniture();

        private final BukkitFurniture bukkitFurniture;
        private final Channel channel;
        private final int interval;
        private int ticksUntilRun = -1;
        private Handler deliveredHandler;

        private Controller(BukkitFurniture furniture, Channel channel, int interval) {
            super(furniture);
            this.bukkitFurniture = furniture;
            this.channel = channel;
            this.interval = interval;
        }

        @Override
        public <T extends FurnitureController> FurnitureTicker<T> createFurnitureTicker() {
            return FurnitureController.createTickerHelper(TICKER);
        }

        @Override
        public void onUnload(boolean isStopping) {
            Handler handler = deliveredHandler;
            if (handler != null) {
                handler.onUnload(bukkitFurniture, isStopping);
            }
            deliveredHandler = null;
            ticksUntilRun = -1;
        }

        private void tickManagedFurniture() {
            Handler handler = channel.handler;
            if (handler == null) {
                deliveredHandler = null;
                ticksUntilRun = -1;
                return;
            }
            if (handler != deliveredHandler) {
                handler.onReady(bukkitFurniture);
                deliveredHandler = handler;
            }
            if (interval > 1) {
                if (ticksUntilRun < 0) {
                    ticksUntilRun = initialDelay(
                            bukkitFurniture.location().getWorld().getGameTime(),
                            bukkitFurniture.uuid().hashCode(), interval);
                }
                if (ticksUntilRun > 0) {
                    ticksUntilRun--;
                    return;
                }
                ticksUntilRun = interval - 1;
            }
            handler.tick(bukkitFurniture);
        }
    }

    /**
     * Reconstructs the source block entity's globally phased interval once.
     * Subsequent ticks use a decrementing counter instead of two floorMod
     * operations, a UUID hash and a world-time lookup per furniture per tick.
     */
    static int initialDelay(long gameTime, int identityHash, int interval) {
        if (interval <= 1) {
            return 0;
        }
        long phase = Math.floorMod(identityHash, interval);
        long remainder = Math.floorMod(gameTime + phase, interval);
        return (int) Math.floorMod(-remainder, interval);
    }
}
