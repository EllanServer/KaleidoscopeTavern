package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviorTemplate;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviors;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets CraftEngine own the lifecycle and persistent edge state for furniture
 * whose gameplay reacts to redstone.
 */
public final class RedstoneFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:redstone_furniture";
    private static final long FALLBACK_INTERVAL_TICKS = 20L;

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final ConcurrentMap<UUID, ConcurrentMap<Long, Set<Controller>>> POWER_INDEX =
            new ConcurrentHashMap<>();
    private static final Set<PowerLocation> PENDING_POWER_CHANGES =
            ConcurrentHashMap.newKeySet();
    private static final Object RUNTIME_LOCK = new Object();

    private static JavaPlugin runtimePlugin;
    private static PowerListener powerListener;
    private static BukkitTask fallbackTask;
    private static boolean flushScheduled;

    private final Channel channel;
    private final String dataKey;

    private RedstoneFurnitureBehavior(FurnitureDefinition furniture, ConfigSection section) {
        super(furniture);
        this.channel = parseChannel(section.getNonEmptyString("channel"), section);
        this.dataKey = section.getNonEmptyString("data_key");
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            FurnitureBehaviors.register(Key.of(TYPE), RedstoneFurnitureBehavior::new);
        }
    }

    /** Starts the event bridge and one low-frequency parity fallback. */
    public static void start(JavaPlugin plugin) {
        JavaPlugin owner = Objects.requireNonNull(plugin, "plugin");
        synchronized (RUNTIME_LOCK) {
            if (runtimePlugin == owner && powerListener != null && fallbackTask != null) {
                return;
            }
            stopLocked();
            runtimePlugin = owner;
            powerListener = new PowerListener();
            Bukkit.getPluginManager().registerEvents(powerListener, owner);
            fallbackTask = Bukkit.getScheduler().runTaskTimer(owner,
                    RedstoneFurnitureBehavior::pollActiveControllers,
                    FALLBACK_INTERVAL_TICKS, FALLBACK_INTERVAL_TICKS);
        }
    }

    public static void stop() {
        synchronized (RUNTIME_LOCK) {
            stopLocked();
        }
    }

    private static void stopLocked() {
        if (powerListener != null) {
            HandlerList.unregisterAll(powerListener);
            powerListener = null;
        }
        if (fallbackTask != null) {
            fallbackTask.cancel();
            fallbackTask = null;
        }
        runtimePlugin = null;
        flushScheduled = false;
        PENDING_POWER_CHANGES.clear();
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

    public static void bindInteraction(Channel channel, InteractionHandler handler) {
        Channel boundChannel = Objects.requireNonNull(channel, "channel");
        InteractionHandler boundHandler = Objects.requireNonNull(handler, "handler");
        synchronized (boundChannel) {
            boundChannel.interactionHandler = boundHandler;
        }
    }

    public static void unbindInteraction(Channel channel, InteractionHandler handler) {
        Channel boundChannel = Objects.requireNonNull(channel, "channel");
        InteractionHandler boundHandler = Objects.requireNonNull(handler, "handler");
        synchronized (boundChannel) {
            if (boundChannel.interactionHandler == boundHandler) {
                boundChannel.interactionHandler = null;
            }
        }
    }

    @Override
    public FurnitureController createController(Furniture furniture) {
        if (!(furniture instanceof BukkitFurniture bukkitFurniture)) {
            throw new IllegalArgumentException("Redstone furniture requires BukkitFurniture");
        }
        return new Controller(bukkitFurniture, channel, dataKey);
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
        STORAGE;

        private final Set<Controller> activeControllers = ConcurrentHashMap.newKeySet();
        private volatile Handler handler;
        private volatile InteractionHandler interactionHandler;
    }

    @FunctionalInterface
    public interface Handler {
        default void onReady(BukkitFurniture furniture) {
        }

        void onPowerState(BukkitFurniture furniture, boolean powered, boolean initial);

        default void onUnload(BukkitFurniture furniture, boolean isStopping) {
        }

        default void onRemove(BukkitFurniture furniture) {
        }
    }

    @FunctionalInterface
    public interface InteractionHandler {
        InteractionResult interact(BukkitFurniture furniture,
                                   InteractEntityContext context);
    }

    private static final class Controller extends FurnitureController {
        private static final String INITIALIZED = "initialized";
        private static final String POWERED = "powered";

        private final BukkitFurniture bukkitFurniture;
        private final Channel channel;
        private final String dataKey;

        private boolean initialized;
        private boolean powered;
        private boolean active;
        private UUID worldId;
        private long[] indexedPowerChanges;
        private Block primaryPowerBlock;
        private Block secondaryPowerBlock;
        private Handler deliveredHandler;

        private Controller(BukkitFurniture furniture, Channel channel, String dataKey) {
            super(furniture);
            this.bukkitFurniture = furniture;
            this.channel = channel;
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
        public void onPlace(Player player) {
            activate();
        }

        @Override
        public void onLoad() {
            activate();
        }

        @Override
        public InteractionResult useOnFurniture(FurnitureHitBox hitBox,
                                                InteractEntityContext context) {
            InteractionHandler handler = channel.interactionHandler;
            return handler == null
                    ? InteractionResult.PASS
                    : handler.interact(bukkitFurniture, context);
        }

        @Override
        public void onUnload(boolean isStopping) {
            Handler handler = channel.handler;
            deactivate();
            if (handler != null) {
                handler.onUnload(bukkitFurniture, isStopping);
            }
            deliveredHandler = null;
            primaryPowerBlock = null;
            secondaryPowerBlock = null;
        }

        @Override
        public void preRemove(Player player) {
            Handler handler = channel.handler;
            if (handler != null) {
                handler.onRemove(bukkitFurniture);
            }
        }

        private void activate() {
            if (active) {
                deliver(channel.handler);
                return;
            }
            cachePowerProbe();
            worldId = primaryPowerBlock.getWorld().getUID();
            Set<Long> keys = new HashSet<>();
            addProbeChanges(keys, primaryPowerBlock);
            if (secondaryPowerBlock != null) {
                addProbeChanges(keys, secondaryPowerBlock);
            }
            indexedPowerChanges = keys.stream().mapToLong(Long::longValue).toArray();
            ConcurrentMap<Long, Set<Controller>> worldIndex = POWER_INDEX.computeIfAbsent(
                    worldId, ignored -> new ConcurrentHashMap<>());
            for (long key : indexedPowerChanges) {
                worldIndex.computeIfAbsent(key,
                        ignored -> ConcurrentHashMap.<Controller>newKeySet()).add(this);
            }
            active = true;
            channel.activeControllers.add(this);
            deliver(channel.handler);
        }

        private void deactivate() {
            if (!active) {
                return;
            }
            channel.activeControllers.remove(this);
            ConcurrentMap<Long, Set<Controller>> worldIndex = POWER_INDEX.get(worldId);
            if (worldIndex != null) {
                for (long key : indexedPowerChanges) {
                    Set<Controller> controllers = worldIndex.get(key);
                    if (controllers == null) {
                        continue;
                    }
                    controllers.remove(this);
                    if (controllers.isEmpty()) {
                        worldIndex.remove(key, controllers);
                    }
                }
                if (worldIndex.isEmpty()) {
                    POWER_INDEX.remove(worldId, worldIndex);
                }
            }
            active = false;
            worldId = null;
            indexedPowerChanges = null;
        }

        private void refreshPower() {
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
            boolean current = samplePower();
            if (current != powered) {
                updatePersistedState(current);
                handler.onPowerState(bukkitFurniture, current, false);
            }
        }

        private void deliver(Handler handler) {
            if (!active || handler == null || handler == deliveredHandler) {
                return;
            }
            handler.onReady(bukkitFurniture);
            deliveredHandler = handler;
            boolean current = samplePower();
            if (!initialized) {
                updatePersistedState(current);
                handler.onPowerState(bukkitFurniture, current, true);
            } else if (current != powered) {
                updatePersistedState(current);
                handler.onPowerState(bukkitFurniture, current, false);
            }
            // Furniture reloads and handler rebinds deliver the handler again.
            // An unchanged saved level is not another edge (especially for taps).
        }

        private void forget(Handler handler) {
            if (deliveredHandler == handler) {
                deliveredHandler = null;
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
            if (primaryPowerBlock == null) {
                cachePowerProbe();
            }
            return switch (channel) {
                // Every source block calls Level#hasNeighborSignal. CraftBlock
                // exposes that exact NMS query as isBlockIndirectlyPowered();
                // isBlockPowered() performs a second, stronger-signal scan and
                // would both widen the source behavior and duplicate hot-path
                // work for every loaded incense and launcher each tick.
                case INCENSE, STORAGE -> primaryPowerBlock.isBlockIndirectlyPowered();
                case TAP -> primaryPowerBlock.isBlockIndirectlyPowered()
                        || secondaryPowerBlock.isBlockIndirectlyPowered();
            };
        }

        private void cachePowerProbe() {
            if (primaryPowerBlock != null) {
                return;
            }
            if (channel != Channel.TAP) {
                primaryPowerBlock = bukkitFurniture.location().getBlock();
                return;
            }
            Location origin = bukkitFurniture.location().clone();
            Vector outward = origin.getDirection().setY(0);
            if (outward.lengthSquared() < 0.001) {
                outward = new Vector(0, 0, 1);
            } else {
                outward.normalize();
            }
            primaryPowerBlock = origin.add(outward.multiply(0.05)).getBlock();
            secondaryPowerBlock = primaryPowerBlock.getRelative(BlockFace.UP);
        }
    }

    private static void addProbeChanges(Set<Long> keys, Block probe) {
        int x = probe.getX();
        int y = probe.getY();
        int z = probe.getZ();
        // hasNeighborSignal(probe) reads each adjacent block's output. That
        // output can itself change because of a lever or other source on the
        // far side of a solid block, so source neighborChanged parity needs a
        // Manhattan radius of two rather than only the six direct neighbors.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 2) {
                        keys.add(packBlock(x + dx, y + dy, z + dz));
                    }
                }
            }
        }
    }

    private static long packBlock(int x, int y, int z) {
        return ((long) x & 0x3ffffffL) << 38
                | ((long) z & 0x3ffffffL) << 12
                | ((long) y & 0xfffL);
    }

    private static void queuePowerChange(Block block) {
        UUID worldId = block.getWorld().getUID();
        long key = packBlock(block.getX(), block.getY(), block.getZ());
        ConcurrentMap<Long, Set<Controller>> worldIndex = POWER_INDEX.get(worldId);
        if (worldIndex == null || !worldIndex.containsKey(key)) {
            return;
        }
        PENDING_POWER_CHANGES.add(new PowerLocation(worldId, key));
        synchronized (RUNTIME_LOCK) {
            if (flushScheduled || runtimePlugin == null) {
                return;
            }
            flushScheduled = true;
            Bukkit.getScheduler().runTask(runtimePlugin,
                    RedstoneFurnitureBehavior::flushPowerChanges);
        }
    }

    private static void flushPowerChanges() {
        Set<PowerLocation> changed = new HashSet<>(PENDING_POWER_CHANGES);
        PENDING_POWER_CHANGES.removeAll(changed);
        synchronized (RUNTIME_LOCK) {
            flushScheduled = false;
        }
        Set<Controller> affected = new HashSet<>();
        for (PowerLocation location : changed) {
            ConcurrentMap<Long, Set<Controller>> worldIndex = POWER_INDEX.get(location.worldId());
            if (worldIndex == null) {
                continue;
            }
            Set<Controller> controllers = worldIndex.get(location.blockKey());
            if (controllers != null) {
                affected.addAll(controllers);
            }
        }
        affected.forEach(Controller::refreshPower);
    }

    private static void pollActiveControllers() {
        for (Channel channel : Channel.values()) {
            channel.activeControllers.forEach(Controller::refreshPower);
        }
    }

    private record PowerLocation(UUID worldId, long blockKey) {
    }

    private static final class PowerListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onRedstone(BlockRedstoneEvent event) {
            queuePowerChange(event.getBlock());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPlace(BlockPlaceEvent event) {
            queuePowerChange(event.getBlockPlaced());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBreak(BlockBreakEvent event) {
            queuePowerChange(event.getBlock());
        }
    }
}
