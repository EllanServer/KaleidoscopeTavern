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
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets CraftEngine own the lifecycle and persistent edge state for furniture
 * whose gameplay reacts to redstone.
 */
public final class RedstoneFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:redstone_furniture";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final ConcurrentMap<Channel, Handler> HANDLERS = new ConcurrentHashMap<>();

    private final Channel channel;
    private final int interval;
    private final String dataKey;

    private RedstoneFurnitureBehavior(FurnitureDefinition furniture, ConfigSection section) {
        super(furniture);
        this.channel = parseChannel(section.getNonEmptyString("channel"), section);
        this.interval = Math.max(1, section.getInt("interval", 1));
        this.dataKey = section.getNonEmptyString("data_key");
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            FurnitureBehaviors.register(Key.of(TYPE), RedstoneFurnitureBehavior::new);
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
            throw new IllegalArgumentException("Redstone furniture requires BukkitFurniture");
        }
        return new Controller(bukkitFurniture, channel, interval, dataKey);
    }

    private static Channel parseChannel(String value, ConfigSection section) {
        try {
            return Channel.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unknown redstone furniture channel at " + section.assemblePath("channel")
                            + ": " + value,
                    exception);
        }
    }

    public enum Channel {
        INCENSE,
        TAP,
        STORAGE
    }

    @FunctionalInterface
    public interface Handler {
        default void onReady(BukkitFurniture furniture) {
        }

        void onPowerState(BukkitFurniture furniture, boolean powered, boolean initial);

        default void onUnload(BukkitFurniture furniture, boolean isStopping) {
        }
    }

    private static final class Controller extends FurnitureController {
        private static final String INITIALIZED = "initialized";
        private static final String POWERED = "powered";
        private static final FurnitureTicker<Controller> TICKER =
                (furniture, controller) -> controller.tickRedstone();

        private final BukkitFurniture bukkitFurniture;
        private final Channel channel;
        private final int interval;
        private final String dataKey;

        private int tickCounter;
        private boolean initialized;
        private boolean powered;
        private Handler deliveredHandler;

        private Controller(BukkitFurniture furniture, Channel channel, int interval, String dataKey) {
            super(furniture);
            this.bukkitFurniture = furniture;
            this.channel = channel;
            this.interval = interval;
            this.dataKey = dataKey;
        }

        @Override
        public void loadCustomData(CompoundTag data) {
            CompoundTag state = data.getCompound(dataKey);
            if (state == null) {
                return;
            }
            this.initialized = state.getBoolean(INITIALIZED, false);
            this.powered = state.getBoolean(POWERED, false);
        }

        @Override
        public void saveCustomData(CompoundTag data) {
            if (!initialized) {
                return;
            }
            CompoundTag state = new CompoundTag();
            state.putBoolean(INITIALIZED, true);
            state.putBoolean(POWERED, powered);
            data.put(dataKey, state);
        }

        @Override
        public <T extends FurnitureController> FurnitureTicker<T> createFurnitureTicker() {
            return FurnitureController.createTickerHelper(TICKER);
        }

        @Override
        public void onUnload(boolean isStopping) {
            Handler handler = HANDLERS.get(channel);
            if (handler != null) {
                handler.onUnload(bukkitFurniture, isStopping);
            }
            deliveredHandler = null;
        }

        private void tickRedstone() {
            Handler handler = HANDLERS.get(channel);
            if (handler == null) {
                deliveredHandler = null;
                tickCounter = 0;
                return;
            }
            if (++tickCounter < interval) {
                return;
            }
            tickCounter = 0;

            boolean current = samplePower();
            if (handler != deliveredHandler) {
                handler.onReady(bukkitFurniture);
                deliveredHandler = handler;
                if (!initialized) {
                    updatePersistedState(current);
                    handler.onPowerState(bukkitFurniture, current, true);
                } else if (current != powered) {
                    updatePersistedState(current);
                    handler.onPowerState(bukkitFurniture, current, false);
                }
                // Furniture reloads and handler rebinds deliver the handler
                // again.  An unchanged saved level is not another edge and
                // must remain silent (especially for taps).
            } else if (current != powered) {
                updatePersistedState(current);
                handler.onPowerState(bukkitFurniture, current, false);
            }
        }

        private void updatePersistedState(boolean current) {
            if (!initialized || powered != current) {
                initialized = true;
                powered = current;
                bukkitFurniture.setUnsaved();
            }
        }

        private boolean samplePower() {
            return switch (channel) {
                case INCENSE, STORAGE -> {
                    Block block = bukkitFurniture.location().getBlock();
                    yield block.isBlockPowered() || block.isBlockIndirectlyPowered();
                }
                case TAP -> {
                    Block block = tapBlock();
                    yield block.isBlockIndirectlyPowered()
                            || block.getRelative(BlockFace.UP).isBlockIndirectlyPowered();
                }
            };
        }

        private Block tapBlock() {
            Location origin = bukkitFurniture.location().clone();
            Vector outward = origin.getDirection().setY(0);
            if (outward.lengthSquared() < 0.001) {
                outward = new Vector(0, 0, 1);
            } else {
                outward.normalize();
            }
            return origin.add(outward.multiply(0.05)).getBlock();
        }
    }
}
