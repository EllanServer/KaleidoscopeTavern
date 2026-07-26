package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.AnimatedItemFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.LifecycleFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.item.Item;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
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
public final class BarStoolVisualService implements Listener {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final String SUFFIX = "_bar_stool";

    private final JavaPlugin plugin;
    private final ItemService items;
    private final Map<UUID, BukkitFurniture> loaded = new HashMap<>();
    private final Map<UUID, UUID> occupied = new HashMap<>();
    private final Map<UUID, Float> bodyYaws = new HashMap<>();
    private final Map<String, Item> renderItems = new HashMap<>();
    private final AnimatedItemFurnitureBehavior.Handler visualHandler = this::visuals;
    private final LifecycleFurnitureBehavior.Handler lifecycleHandler;
    private BukkitTask rotationTask;
    private boolean missingRenderItemLogged;

    public BarStoolVisualService(JavaPlugin plugin, ItemService items) {
        this.plugin = plugin;
        this.items = items;
        this.lifecycleHandler = new LifecycleFurnitureBehavior.Handler() {
            @Override
            public void onReady(BukkitFurniture furniture,
                                LifecycleFurnitureBehavior.ReadyReason reason) {
                loaded.put(furniture.uuid(), furniture);
            }

            @Override
            public void onUnavailable(BukkitFurniture furniture,
                                      boolean removed, boolean stopping) {
                UUID owner = furniture.uuid();
                loaded.remove(owner, furniture);
                bodyYaws.remove(owner);
                occupied.values().removeIf(owner::equals);
                stopRotationTaskIfIdle();
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
        occupied.values().stream().distinct().toList().forEach(this::resetBody);
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        UUID rider = event.getEntity().getUniqueId();
        Location mount = event.getMount().getLocation().clone();
        // CE finishes the seat relationship after the event.
        Bukkit.getScheduler().runTask(plugin, () -> activate(rider, mount));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        deactivate(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        deactivate(event.getPlayer().getUniqueId());
    }

    private void activate(UUID riderId, Location mount) {
        Entity entity = Bukkit.getEntity(riderId);
        if (!(entity instanceof LivingEntity rider) || !rider.isValid()
                || !rider.isInsideVehicle()) {
            return;
        }
        BukkitFurniture stool = findBarStool(mount);
        if (stool == null) {
            return;
        }
        UUID owner = stool.uuid();
        UUID previous = occupied.put(riderId, owner);
        if (previous != null && !previous.equals(owner)) {
            resetBody(previous);
        }
        bodyYaws.put(owner, rider.getBodyYaw());
        AnimatedItemFurnitureBehavior.updatePosition(stool);
        ensureRotationTask();
    }

    private void deactivate(UUID riderId) {
        UUID owner = occupied.remove(riderId);
        if (owner != null) {
            resetBody(owner);
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
        for (Map.Entry<UUID, UUID> entry : occupied.entrySet()) {
            Entity entity = Bukkit.getEntity(entry.getKey());
            BukkitFurniture stool = loaded.get(entry.getValue());
            if (!(entity instanceof LivingEntity rider) || !rider.isValid()
                    || !rider.isInsideVehicle() || !isBarStool(stool)) {
                resetBody(entry.getValue());
                if (invalid == null) {
                    invalid = new ArrayList<>();
                }
                invalid.add(entry.getKey());
                continue;
            }
            float yaw = rider.getBodyYaw();
            Float previous = bodyYaws.put(entry.getValue(), yaw);
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
            ItemStack stack = items.build(PREFIX + "_render/bar_stool_body/" + color, null)
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

    private static boolean isBarStool(BukkitFurniture furniture) {
        return furniture != null && furniture.id().toString().startsWith(PREFIX)
                && furniture.id().toString().endsWith(SUFFIX);
    }
}
