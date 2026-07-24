package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Recreates BarStoolBlockEntityRender's passenger-following upholstered body. */
public final class BarStoolVisualService implements Listener {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final String SUFFIX = "_bar_stool";

    private final JavaPlugin plugin;
    private final ItemService items;
    private final NamespacedKey ownerKey;
    private final Map<UUID, UUID> bodyVisuals = new HashMap<>();
    private final Map<UUID, UUID> occupied = new HashMap<>();
    private BukkitTask rotationTask;
    private boolean missingRenderItemLogged;

    public BarStoolVisualService(JavaPlugin plugin, ItemService items) {
        this.plugin = plugin;
        this.items = items;
        this.ownerKey = new NamespacedKey(plugin, "bar_stool_body_owner");
    }

    public void start() {
        Bukkit.getScheduler().runTask(plugin, this::bootstrap);
    }

    public void stop() {
        occupied.values().stream().distinct().forEach(this::resetBody);
        if (rotationTask != null) {
            rotationTask.cancel();
            rotationTask = null;
        }
        occupied.clear();
        bodyVisuals.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(FurniturePlaceEvent event) {
        BukkitFurniture furniture = event.furniture();
        if (isBarStool(furniture)) {
            Bukkit.getScheduler().runTask(plugin, () -> refreshBody(furniture));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(FurnitureBreakEvent event) {
        BukkitFurniture furniture = event.furniture();
        if (!isBarStool(furniture) || furniture.bukkitEntity() == null) {
            return;
        }
        UUID owner = furniture.bukkitEntity().getUniqueId();
        removeBody(owner, furniture.location());
        occupied.values().removeIf(owner::equals);
        stopRotationTaskIfIdle();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        UUID rider = event.getEntity().getUniqueId();
        Location mount = event.getMount().getLocation().clone();
        // CraftEngine finishes the seat relationship after the event. Resolve
        // the nearby furniture on the next server tick, once it is stable.
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

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        List<BukkitFurniture> stools = new ArrayList<>();
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ItemDisplay display) {
                UUID owner = bodyOwner(display);
                if (owner != null) {
                    bodyVisuals.putIfAbsent(owner, display.getUniqueId());
                }
                if (CraftEngineFurniture.isFurniture(display)) {
                    BukkitFurniture furniture =
                            CraftEngineFurniture.getLoadedFurnitureByMetaEntity(display);
                    if (isBarStool(furniture)) {
                        stools.add(furniture);
                    }
                }
            }
        }
        if (!stools.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> stools.forEach(this::refreshBody));
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (!(entity instanceof ItemDisplay display)) {
                continue;
            }
            UUID owner = bodyOwner(display);
            if (owner != null) {
                bodyVisuals.remove(owner, display.getUniqueId());
            }
            if (CraftEngineFurniture.isFurniture(display)) {
                occupied.values().removeIf(display.getUniqueId()::equals);
            }
        }
        stopRotationTaskIfIdle();
    }

    private void activate(UUID riderId, Location mount) {
        Entity entity = Bukkit.getEntity(riderId);
        if (!(entity instanceof LivingEntity rider) || !rider.isValid()
                || !rider.isInsideVehicle()) {
            return;
        }
        BukkitFurniture stool = findBarStool(mount);
        if (stool == null || stool.bukkitEntity() == null) {
            return;
        }
        UUID owner = stool.bukkitEntity().getUniqueId();
        UUID previous = occupied.put(riderId, owner);
        if (previous != null && !previous.equals(owner)) {
            resetBody(previous);
        }
        ItemDisplay body = refreshBody(stool);
        if (body != null) {
            rotateBody(body, rider.getBodyYaw());
        }
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
            // This task exists only while at least one stool is occupied. The
            // source renderer also updates only visible occupied stool bodies.
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
        List<UUID> invalid = new ArrayList<>();
        for (Map.Entry<UUID, UUID> entry : occupied.entrySet()) {
            Entity entity = Bukkit.getEntity(entry.getKey());
            BukkitFurniture stool = loadedFurniture(entry.getValue());
            if (!(entity instanceof LivingEntity rider) || !rider.isValid()
                    || !rider.isInsideVehicle() || !isBarStool(stool)) {
                resetBody(entry.getValue());
                invalid.add(entry.getKey());
                continue;
            }
            ItemDisplay body = bodyVisual(entry.getValue());
            if (body == null) {
                body = refreshBody(stool);
            }
            if (body != null) {
                rotateBody(body, rider.getBodyYaw());
            }
        }
        invalid.forEach(occupied::remove);
        stopRotationTaskIfIdle();
    }

    private BukkitFurniture findBarStool(Location mount) {
        BukkitFurniture closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Entity entity : mount.getWorld().getNearbyEntities(
                mount, 1.5, 1.5, 1.5,
                candidate -> candidate instanceof ItemDisplay
                        && CraftEngineFurniture.isFurniture(candidate))) {
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (!isBarStool(furniture)) {
                continue;
            }
            double distance = furniture.location().distanceSquared(mount);
            if (distance < closestDistance) {
                closest = furniture;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private ItemDisplay refreshBody(BukkitFurniture furniture) {
        if (!isBarStool(furniture) || !furniture.isValid() || furniture.bukkitEntity() == null) {
            return null;
        }
        UUID owner = furniture.bukkitEntity().getUniqueId();
        ItemDisplay body = null;
        for (Entity entity : furniture.location().getWorld().getNearbyEntities(
                furniture.location(), 2, 2, 2,
                candidate -> candidate instanceof ItemDisplay)) {
            ItemDisplay candidate = (ItemDisplay) entity;
            if (!owner.equals(bodyOwner(candidate))) {
                continue;
            }
            if (body == null) {
                body = candidate;
            } else {
                candidate.remove();
            }
        }
        if (body == null) {
            body = spawnBody(furniture, owner);
        }
        bodyVisuals.put(owner, body.getUniqueId());
        configureBody(furniture, body);
        return body;
    }

    private ItemDisplay spawnBody(BukkitFurniture furniture, UUID owner) {
        Location location = furniture.location().clone();
        location.setPitch(0);
        return location.getWorld().spawn(location, ItemDisplay.class, display -> {
            display.setPersistent(true);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setBillboard(Display.Billboard.FIXED);
            display.setShadowRadius(0F);
            display.setViewRange(1.25F);
            display.setDisplayWidth(1.5F);
            display.setDisplayHeight(2F);
            display.setTeleportDuration(1);
            display.getPersistentDataContainer().set(
                    ownerKey, PersistentDataType.STRING, owner.toString());
        });
    }

    private void configureBody(BukkitFurniture furniture, ItemDisplay body) {
        String localId = furniture.id().toString().substring(PREFIX.length());
        String color = localId.substring(0, localId.length() - SUFFIX.length());
        ItemStack render = items.build(PREFIX + "_render/bar_stool_body/" + color, null)
                .orElse(null);
        if (render == null) {
            if (!missingRenderItemLogged) {
                missingRenderItemLogged = true;
                plugin.getLogger().severe(
                        "Bar-stool body render items are unavailable after CraftEngine loading");
            }
            return;
        }
        body.setItemStack(render);
        body.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        body.setTransformation(new Transformation(
                new Vector3f(0, 0.5F, 0), new Quaternionf(),
                new Vector3f(1), new Quaternionf()));
        rotateBody(body, furniture.location().getYaw());
    }

    private static void rotateBody(ItemDisplay body, float yaw) {
        Location current = body.getLocation();
        float delta = Math.abs(Math.floorMod(Math.round(current.getYaw() - yaw) + 180, 360) - 180);
        if (delta < 0.1F) {
            return;
        }
        current.setYaw(yaw);
        current.setPitch(0);
        body.teleport(current);
    }

    private void resetBody(UUID owner) {
        BukkitFurniture furniture = loadedFurniture(owner);
        ItemDisplay body = bodyVisual(owner);
        if (isBarStool(furniture) && body != null) {
            rotateBody(body, furniture.location().getYaw());
        }
    }

    private ItemDisplay bodyVisual(UUID owner) {
        UUID visualId = bodyVisuals.get(owner);
        Entity entity = visualId == null ? null : Bukkit.getEntity(visualId);
        if (entity instanceof ItemDisplay display && display.isValid()
                && owner.equals(bodyOwner(display))) {
            return display;
        }
        bodyVisuals.remove(owner);
        return null;
    }

    private void removeBody(UUID owner, Location origin) {
        ItemDisplay cached = bodyVisual(owner);
        if (cached != null) {
            cached.remove();
        }
        for (Entity entity : origin.getWorld().getNearbyEntities(
                origin, 2, 2, 2, candidate -> candidate instanceof ItemDisplay)) {
            ItemDisplay display = (ItemDisplay) entity;
            if (owner.equals(bodyOwner(display))) {
                display.remove();
            }
        }
        bodyVisuals.remove(owner);
    }

    private UUID bodyOwner(ItemDisplay display) {
        String value = display.getPersistentDataContainer().get(
                ownerKey, PersistentDataType.STRING);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static BukkitFurniture loadedFurniture(UUID owner) {
        Entity entity = Bukkit.getEntity(owner);
        return entity == null ? null
                : CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
    }

    private static boolean isBarStool(BukkitFurniture furniture) {
        return furniture != null && furniture.id().toString().startsWith(PREFIX)
                && furniture.id().toString().endsWith(SUFFIX);
    }

    private void bootstrap() {
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                UUID owner = bodyOwner(display);
                if (owner != null) {
                    bodyVisuals.putIfAbsent(owner, display.getUniqueId());
                }
            }
        }
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (!CraftEngineFurniture.isFurniture(display)) {
                    continue;
                }
                BukkitFurniture furniture =
                        CraftEngineFurniture.getLoadedFurnitureByMetaEntity(display);
                if (isBarStool(furniture)) {
                    refreshBody(furniture);
                }
            }
        }
    }
}
