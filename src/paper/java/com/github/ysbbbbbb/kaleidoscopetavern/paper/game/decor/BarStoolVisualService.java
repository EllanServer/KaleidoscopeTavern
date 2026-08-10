package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.decor;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.AnimatedItemFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.LifecycleFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.item.Item;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Recreates the passenger-following stool body with a CE packet-only element. */
public final class BarStoolVisualService {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final String SUFFIX = "_bar_stool";

    private final JavaPlugin plugin;
    private final ItemService items;
    private final Map<UUID, BukkitFurniture> loaded = new HashMap<>();
    private final Map<UUID, Occupancy> occupied = new HashMap<>();
    private final Map<UUID, Float> bodyYaws = new HashMap<>();
    private final Map<String, Item> renderItems = new HashMap<>();
    private final AnimatedItemFurnitureBehavior.Handler visualHandler = this::visuals;
    private final SeatEventListener seatEventListener = new SeatEventListener();
    private final LifecycleFurnitureBehavior.Handler lifecycleHandler;
    private BukkitTask rotationTask;
    private boolean seatEventsRegistered;
    private boolean missingRenderItemLogged;

    public BarStoolVisualService(JavaPlugin plugin, ItemService items) {
        this.plugin = plugin;
        this.items = items;
        this.lifecycleHandler = new LifecycleFurnitureBehavior.Handler() {
            @Override
            public void onReady(BukkitFurniture furniture,
                                LifecycleFurnitureBehavior.ReadyReason reason) {
                loaded.put(furniture.uuid(), furniture);
                ensureSeatEventsRegistered();
            }

            @Override
            public void onUnavailable(BukkitFurniture furniture, boolean removed) {
                UUID owner = furniture.uuid();
                loaded.remove(owner, furniture);
                bodyYaws.remove(owner);
                occupied.values().removeIf(occupancy -> owner.equals(occupancy.owner()));
                stopRotationTaskIfIdle();
                stopSeatEventsIfIdle();
            }
        };
    }

    public void start() {
        AnimatedItemFurnitureBehavior.bind(
                AnimatedItemFurnitureBehavior.Channel.BAR_STOOL, visualHandler);
        LifecycleFurnitureBehavior.bind(
                LifecycleFurnitureBehavior.Channel.BAR_STOOL, lifecycleHandler);
    }

    public void stop() {
        HandlerList.unregisterAll(seatEventListener);
        seatEventsRegistered = false;
        occupied.values().stream().map(Occupancy::owner).distinct().toList()
                .forEach(this::resetBody);
        if (rotationTask != null) {
            rotationTask.cancel();
            rotationTask = null;
        }
        occupied.clear();
        bodyYaws.clear();
        AnimatedItemFurnitureBehavior.unbind(
                AnimatedItemFurnitureBehavior.Channel.BAR_STOOL, visualHandler);
        LifecycleFurnitureBehavior.unbind(
                LifecycleFurnitureBehavior.Channel.BAR_STOOL, lifecycleHandler);
        loaded.clear();
        renderItems.clear();
    }

    private void onMount(EntityMountEvent event) {
        if (!(event.getEntity() instanceof LivingEntity rider)) {
            return;
        }
        Location mount = event.getMount().getLocation().clone();
        // CE finishes the seat relationship after the event.
        Bukkit.getScheduler().runTask(plugin, () -> activate(rider, mount));
    }

    private void onDismount(EntityDismountEvent event) {
        deactivate(event.getEntity().getUniqueId());
    }

    private void onQuit(PlayerQuitEvent event) {
        deactivate(event.getPlayer().getUniqueId());
    }

    private void ensureSeatEventsRegistered() {
        if (seatEventsRegistered || loaded.isEmpty()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(seatEventListener, plugin);
        seatEventsRegistered = true;
    }

    private void stopSeatEventsIfIdle() {
        if (!seatEventsRegistered || !loaded.isEmpty()) {
            return;
        }
        HandlerList.unregisterAll(seatEventListener);
        seatEventsRegistered = false;
    }

    private final class SeatEventListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onMount(EntityMountEvent event) {
            BarStoolVisualService.this.onMount(event);
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDismount(EntityDismountEvent event) {
            BarStoolVisualService.this.onDismount(event);
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            BarStoolVisualService.this.onQuit(event);
        }
    }

    private void activate(LivingEntity rider, Location mount) {
        if (!rider.isValid() || !rider.isInsideVehicle()) {
            return;
        }
        BukkitFurniture stool = findBarStool(mount);
        if (stool == null) {
            return;
        }
        UUID owner = stool.uuid();
        Occupancy previous = occupied.put(
                rider.getUniqueId(), new Occupancy(owner, rider));
        if (previous != null && !previous.owner().equals(owner)) {
            resetBody(previous.owner());
        }
        bodyYaws.put(owner, rider.getBodyYaw());
        AnimatedItemFurnitureBehavior.updatePosition(stool);
        ensureRotationTask();
    }

    private void deactivate(UUID riderId) {
        Occupancy occupancy = occupied.remove(riderId);
        if (occupancy != null) {
            resetBody(occupancy.owner());
        }
        stopRotationTaskIfIdle();
    }

    private void ensureRotationTask() {
        if (rotationTask == null && !occupied.isEmpty()) {
            rotationTask = Bukkit.getScheduler().runTaskTimer(
                    plugin, this::tickOccupied, 1L, 1L);
        }
    }

    private void stopRotationTaskIfIdle() {
        if (rotationTask != null && occupied.isEmpty()) {
            rotationTask.cancel();
            rotationTask = null;
        }
    }

    private void tickOccupied() {
        List<UUID> invalid = null;
        for (Map.Entry<UUID, Occupancy> entry : occupied.entrySet()) {
            Occupancy occupancy = entry.getValue();
            LivingEntity rider = occupancy.rider();
            BukkitFurniture stool = loaded.get(occupancy.owner());
            if (!rider.isValid()
                    || !rider.isInsideVehicle() || !isBarStool(stool)) {
                resetBody(occupancy.owner());
                if (invalid == null) {
                    invalid = new ArrayList<>();
                }
                invalid.add(entry.getKey());
                continue;
            }
            float yaw = rider.getBodyYaw();
            Float previous = bodyYaws.put(occupancy.owner(), yaw);
            if (previous == null || yawDistance(previous, yaw) >= 0.1F) {
                AnimatedItemFurnitureBehavior.updatePosition(stool);
            }
        }
        if (invalid != null) {
            invalid.forEach(occupied::remove);
        }
        stopRotationTaskIfIdle();
    }

    private BukkitFurniture findBarStool(Location mount) {
        BukkitFurniture closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (BukkitFurniture furniture : LifecycleFurnitureBehavior.nearby(
                LifecycleFurnitureBehavior.Channel.BAR_STOOL, mount, 1.5, 1.5)) {
            double distance = furniture.location().distanceSquared(mount);
            if (distance < closestDistance) {
                closest = furniture;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private void resetBody(UUID owner) {
        BukkitFurniture furniture = loaded.get(owner);
        bodyYaws.remove(owner);
        if (isBarStool(furniture)) {
            AnimatedItemFurnitureBehavior.updatePosition(furniture);
        }
    }

    private List<AnimatedItemFurnitureBehavior.Visual> visuals(
            BukkitFurniture furniture) {
        if (!isBarStool(furniture)) {
            return List.of();
        }
        String localId = furniture.id().toString().substring(PREFIX.length());
        String color = localId.substring(0, localId.length() - SUFFIX.length());
        Item render = renderItems.get(color);
        if (render == null) {
            ItemStack stack = items.buildVisual(PREFIX + "_render/bar_stool_body/" + color)
                    .orElse(null);
            if (stack == null) {
                if (!missingRenderItemLogged) {
                    missingRenderItemLogged = true;
                    plugin.getLogger().severe(
                            "Bar-stool body render items are unavailable after CraftEngine loading");
                }
                return List.of();
            }
            render = BukkitAdaptor.adapt(stack);
            renderItems.put(color, render);
        }
        float yaw = bodyYaws.getOrDefault(
                furniture.uuid(), furniture.location().getYaw());
        return List.of(new AnimatedItemFurnitureBehavior.Visual(
                render, yaw, 0F,
                new Vector3f(0, 0.5F, 0), new Quaternionf(),
                1.5F, 2F, 0, 1));
    }

    private static float yawDistance(float left, float right) {
        return Math.abs(Math.floorMod(Math.round(left - right) + 180, 360) - 180);
    }

    private record Occupancy(UUID owner, LivingEntity rider) {
    }

    private static boolean isBarStool(BukkitFurniture furniture) {
        return furniture != null && furniture.id().toString().startsWith(PREFIX)
                && furniture.id().toString().endsWith(SUFFIX);
    }
}
