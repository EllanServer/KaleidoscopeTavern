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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lets CraftEngine drive periodic gameplay directly from loaded furniture. */
public final class TickingFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:ticking_furniture";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final ConcurrentMap<Channel, Handler> HANDLERS = new ConcurrentHashMap<>();

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
        HANDLERS.put(Objects.requireNonNull(channel, "channel"),
                Objects.requireNonNull(handler, "handler"));
    }

    public static void unbind(Channel channel, Handler handler) {
        HANDLERS.remove(Objects.requireNonNull(channel, "channel"),
                Objects.requireNonNull(handler, "handler"));
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
        BARREL
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
        }

        private void tickManagedFurniture() {
            Handler handler = HANDLERS.get(channel);
            if (handler == null) {
                deliveredHandler = null;
                return;
            }
            if (handler != deliveredHandler) {
                handler.onReady(bukkitFurniture);
                deliveredHandler = handler;
            }
            if (interval > 1) {
                long phase = Math.floorMod(bukkitFurniture.uuid().hashCode(), interval);
                long gameTime = bukkitFurniture.location().getWorld().getGameTime();
                if (Math.floorMod(gameTime + phase, interval) != 0) {
                    return;
                }
            }
            handler.tick(bukkitFurniture);
        }
    }
}
