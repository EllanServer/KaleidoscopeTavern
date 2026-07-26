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

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Routes exact furniture load/place/remove/unload callbacks through CE controllers. */
public final class LifecycleFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:lifecycle_furniture";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final ConcurrentMap<Channel, Handler> HANDLERS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Channel, Set<Controller>> READY = new ConcurrentHashMap<>();

    private final Channel channel;

    private LifecycleFurnitureBehavior(FurnitureDefinition furniture, ConfigSection section) {
        super(furniture);
        this.channel = parseChannel(section.getNonEmptyString("channel"), section);
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            FurnitureBehaviors.register(Key.of(TYPE), LifecycleFurnitureBehavior::new);
        }
    }

    public static void bind(Channel channel, Handler handler) {
        Handler bound = Objects.requireNonNull(handler, "handler");
        HANDLERS.put(Objects.requireNonNull(channel, "channel"), bound);
        Set<Controller> controllers = READY.get(channel);
        if (controllers != null) {
            controllers.forEach(controller -> controller.deliver(bound));
        }
    }

    public static void unbind(Channel channel, Handler handler) {
        if (HANDLERS.remove(Objects.requireNonNull(channel, "channel"),
                Objects.requireNonNull(handler, "handler"))) {
            Set<Controller> controllers = READY.get(channel);
            if (controllers != null) {
                controllers.forEach(controller -> controller.forget(handler));
            }
        }
    }

    @Override
    public FurnitureController createController(Furniture furniture) {
        if (!(furniture instanceof BukkitFurniture bukkitFurniture)) {
            throw new IllegalArgumentException("Lifecycle furniture requires BukkitFurniture");
        }
        return new Controller(bukkitFurniture, channel);
    }

    private static Channel parseChannel(String value, ConfigSection section) {
        try {
            return Channel.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unknown lifecycle furniture channel at "
                            + section.assemblePath("channel") + ": " + value,
                    exception);
        }
    }

    public enum Channel {
        BAR_STOOL,
        BOARD,
        CONNECTION,
        SHAKER,
        STORAGE
    }

    public enum ReadyReason {
        LOAD,
        PLACE
    }

    @FunctionalInterface
    public interface Handler {
        void onReady(BukkitFurniture furniture, ReadyReason reason);

        default void onUnavailable(BukkitFurniture furniture, boolean removed, boolean stopping) {
        }
    }

    private static final class Controller extends FurnitureController {
        private final BukkitFurniture bukkitFurniture;
        private final Channel channel;
        private ReadyReason readyReason;
        private Handler deliveredHandler;
        private ReadyReason deliveredReason;

        private Controller(BukkitFurniture furniture, Channel channel) {
            super(furniture);
            this.bukkitFurniture = furniture;
            this.channel = channel;
        }

        @Override
        public void onPlace(Player player) {
            ready(ReadyReason.PLACE);
        }

        @Override
        public void onLoad() {
            ready(ReadyReason.LOAD);
        }

        @Override
        public void postRemove(Player player) {
            unavailable(true, false);
        }

        @Override
        public void onUnload(boolean isStopping) {
            unavailable(false, isStopping);
        }

        private void ready(ReadyReason reason) {
            readyReason = reason;
            READY.computeIfAbsent(channel,
                    ignored -> ConcurrentHashMap.<Controller>newKeySet()).add(this);
            deliver(HANDLERS.get(channel));
        }

        private void unavailable(boolean removed, boolean stopping) {
            if (readyReason == null) {
                return;
            }
            Set<Controller> controllers = READY.get(channel);
            if (controllers != null) {
                controllers.remove(this);
                if (controllers.isEmpty()) {
                    READY.remove(channel, controllers);
                }
            }
            Handler currentHandler = HANDLERS.get(channel);
            if (currentHandler != null && currentHandler == deliveredHandler) {
                currentHandler.onUnavailable(bukkitFurniture, removed, stopping);
            }
            readyReason = null;
            deliveredHandler = null;
            deliveredReason = null;
        }

        private void deliver(Handler handler) {
            if (handler == null || readyReason == null
                    || (handler == deliveredHandler && readyReason == deliveredReason)) {
                return;
            }
            deliveredHandler = handler;
            deliveredReason = readyReason;
            handler.onReady(bukkitFurniture, readyReason);
        }

        private void forget(Handler handler) {
            if (deliveredHandler == handler) {
                deliveredHandler = null;
                deliveredReason = null;
            }
        }
    }
}
