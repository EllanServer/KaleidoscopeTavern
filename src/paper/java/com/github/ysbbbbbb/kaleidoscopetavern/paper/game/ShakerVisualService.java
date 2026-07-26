package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
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

/** Recreates the source ShakerModel root/bone2 insertion animation. */
public final class ShakerVisualService implements Listener {
    private static final String SHAKER = "kaleidoscope_tavern:shaker";
    private static final String BASE_ITEM = "kaleidoscope_tavern:_render/shaker_base";
    private static final String LID_ITEM = "kaleidoscope_tavern:_render/shaker_lid";
    private static final String BASE_ROLE = "base";
    private static final String LID_ROLE = "lid";
    private static final float LID_PIVOT_Y_PIXELS = 12.16667F;

    private final JavaPlugin plugin;
    private final ItemService items;
    private final NamespacedKey ownerKey;
    private final NamespacedKey roleKey;
    private final Map<UUID, VisualIds> visuals = new HashMap<>();
    private final Map<UUID, Float> animations = new HashMap<>();
    private BukkitTask animationTask;
    private boolean missingRenderItemLogged;

    public ShakerVisualService(JavaPlugin plugin, ItemService items) {
        this.plugin = plugin;
        this.items = items;
        this.ownerKey = new NamespacedKey(plugin, "shaker_visual_owner");
        this.roleKey = new NamespacedKey(plugin, "shaker_visual_role");
    }

    public void start() {
        Bukkit.getScheduler().runTask(plugin, this::bootstrap);
    }

    public void stop() {
        animations.keySet().stream().toList().forEach(this::reset);
        animations.clear();
        if (animationTask != null) {
            animationTask.cancel();
            animationTask = null;
        }
        visuals.clear();
    }

    void animatePut(BukkitFurniture furniture) {
        if (!isShaker(furniture) || furniture.bukkitEntity() == null) {
            return;
        }
        UUID owner = furniture.bukkitEntity().getUniqueId();
        VisualPair pair = visualPair(owner);
        if (pair == null) {
            pair = refreshVisuals(furniture);
        }
        if (pair == null) {
            return;
        }
        animations.put(owner, 0F);
        applyPose(pair, ShakerAnimationSemantics.pose(0F));
        ensureAnimationTask();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(FurniturePlaceEvent event) {
        if (isShaker(event.furniture())) {
            Bukkit.getScheduler().runTask(plugin, () -> refreshVisuals(event.furniture()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(FurnitureBreakEvent event) {
        BukkitFurniture furniture = event.furniture();
        if (!isShaker(furniture) || furniture.bukkitEntity() == null) {
            return;
        }
        UUID owner = furniture.bukkitEntity().getUniqueId();
        Location location = furniture.location().clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled()) {
                return;
            }
            animations.remove(owner);
            removeVisuals(owner, location);
            stopAnimationTaskIfIdle();
        });
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        List<BukkitFurniture> shakers = new ArrayList<>();
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ItemDisplay display && CraftEngineFurniture.isFurniture(display)) {
                BukkitFurniture furniture =
                        CraftEngineFurniture.getLoadedFurnitureByMetaEntity(display);
                if (isShaker(furniture)) {
                    shakers.add(furniture);
                }
            }
        }
        if (!shakers.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> shakers.forEach(this::refreshVisuals));
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            UUID entityId = entity.getUniqueId();
            visuals.entrySet().removeIf(entry -> entry.getValue().contains(entityId));
            if (entity instanceof ItemDisplay display && CraftEngineFurniture.isFurniture(display)) {
                animations.remove(entityId);
            }
        }
        stopAnimationTaskIfIdle();
    }

    private void ensureAnimationTask() {
        if (animationTask == null && !animations.isEmpty()) {
            animationTask = Bukkit.getScheduler().runTaskTimer(
                    plugin, this::tickAnimations, 1L, 1L);
        }
    }

    private void stopAnimationTaskIfIdle() {
        if (animationTask != null && animations.isEmpty()) {
            animationTask.cancel();
            animationTask = null;
        }
    }

    private void tickAnimations() {
        List<UUID> finished = new ArrayList<>();
        for (Map.Entry<UUID, Float> entry : animations.entrySet()) {
            BukkitFurniture furniture = loadedFurniture(entry.getKey());
            VisualPair pair = visualPair(entry.getKey());
            if (!isShaker(furniture) || pair == null) {
                finished.add(entry.getKey());
                continue;
            }
            float elapsed = entry.getValue() + 1F;
            applyPose(pair, ShakerAnimationSemantics.pose(elapsed));
            if (elapsed >= ShakerAnimationSemantics.LENGTH_TICKS) {
                finished.add(entry.getKey());
            } else {
                entry.setValue(elapsed);
            }
        }
        finished.forEach(animations::remove);
        stopAnimationTaskIfIdle();
    }

    private VisualPair refreshVisuals(BukkitFurniture furniture) {
        if (!isShaker(furniture) || !furniture.isValid() || furniture.bukkitEntity() == null) {
            return null;
        }
        UUID owner = furniture.bukkitEntity().getUniqueId();
        ItemDisplay base = null;
        ItemDisplay lid = null;
        for (Entity entity : furniture.location().getWorld().getNearbyEntities(
                furniture.location(), 2, 2, 2,
                candidate -> candidate instanceof ItemDisplay)) {
            ItemDisplay candidate = (ItemDisplay) entity;
            if (!owner.equals(owner(candidate))) {
                continue;
            }
            String role = role(candidate);
            if (BASE_ROLE.equals(role)) {
                if (base == null) {
                    base = candidate;
                } else {
                    candidate.remove();
                }
            } else if (LID_ROLE.equals(role)) {
                if (lid == null) {
                    lid = candidate;
                } else {
                    candidate.remove();
                }
            } else {
                candidate.remove();
            }
        }
        if (base == null) {
            base = spawnVisual(furniture, owner, BASE_ROLE);
        }
        if (lid == null) {
            lid = spawnVisual(furniture, owner, LID_ROLE);
        }
        if (!configureItems(base, lid)) {
            return null;
        }
        Location location = furniture.location().clone();
        location.setPitch(0);
        base.teleport(location);
        lid.teleport(location);
        VisualIds ids = new VisualIds(base.getUniqueId(), lid.getUniqueId());
        visuals.put(owner, ids);
        VisualPair pair = new VisualPair(base, lid);
        applyPose(pair, ShakerAnimationSemantics.pose(0F));
        return pair;
    }

    private ItemDisplay spawnVisual(BukkitFurniture furniture, UUID owner, String role) {
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
            display.setDisplayWidth(1F);
            display.setDisplayHeight(1.5F);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(1);
            display.setTeleportDuration(1);
            display.getPersistentDataContainer().set(
                    ownerKey, PersistentDataType.STRING, owner.toString());
            display.getPersistentDataContainer().set(
                    roleKey, PersistentDataType.STRING, role);
        });
    }

    private boolean configureItems(ItemDisplay base, ItemDisplay lid) {
        ItemStack baseItem = items.build(BASE_ITEM, null).orElse(null);
        ItemStack lidItem = items.build(LID_ITEM, null).orElse(null);
        if (baseItem == null || lidItem == null) {
            if (!missingRenderItemLogged) {
                missingRenderItemLogged = true;
                plugin.getLogger().severe(
                        "Shaker body/lid render items are unavailable after CraftEngine loading");
            }
            return false;
        }
        base.setItemStack(baseItem);
        lid.setItemStack(lidItem);
        base.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        lid.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        return true;
    }

    private static void applyPose(VisualPair pair, ShakerAnimationSemantics.Pose pose) {
        // The source renderer flips model Y/Z before drawing. That reverses
        // root yaw, preserves bone2 X rotation, and turns positive model-space
        // lid Y into a downward world-space offset.
        float rootY = (float) Math.toRadians(-pose.rootYDegrees());
        float lidX = (float) Math.toRadians(pose.lidXDegrees());
        Quaternionf rootRotation = new Quaternionf().rotateY(rootY);
        pair.base().setTransformation(new Transformation(
                new Vector3f(0, 0.5F, 0), rootRotation,
                new Vector3f(1), new Quaternionf()));
        Quaternionf lidRotation = new Quaternionf().rotateY(rootY).rotateX(lidX);
        float pivotTranslation = 0.5F + (LID_PIVOT_Y_PIXELS - 8F) / 16F
                - pose.lidYOffsetPixels() / 16F;
        pair.lid().setTransformation(new Transformation(
                new Vector3f(0, pivotTranslation, 0), lidRotation,
                new Vector3f(1), new Quaternionf()));
    }

    private void reset(UUID owner) {
        VisualPair pair = visualPair(owner);
        if (pair != null) {
            applyPose(pair, ShakerAnimationSemantics.pose(0F));
        }
    }

    private VisualPair visualPair(UUID owner) {
        VisualIds ids = visuals.get(owner);
        if (ids == null) {
            return null;
        }
        Entity baseEntity = Bukkit.getEntity(ids.base());
        Entity lidEntity = Bukkit.getEntity(ids.lid());
        if (baseEntity instanceof ItemDisplay base && base.isValid()
                && lidEntity instanceof ItemDisplay lid && lid.isValid()
                && owner.equals(owner(base)) && owner.equals(owner(lid))) {
            return new VisualPair(base, lid);
        }
        visuals.remove(owner);
        return null;
    }

    private void removeVisuals(UUID owner, Location origin) {
        VisualPair cached = visualPair(owner);
        if (cached != null) {
            cached.base().remove();
            cached.lid().remove();
        }
        for (Entity entity : origin.getWorld().getNearbyEntities(
                origin, 2, 2, 2, candidate -> candidate instanceof ItemDisplay)) {
            ItemDisplay display = (ItemDisplay) entity;
            if (owner.equals(owner(display))) {
                display.remove();
            }
        }
        visuals.remove(owner);
    }

    private UUID owner(ItemDisplay display) {
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

    private String role(ItemDisplay display) {
        return display.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
    }

    private static BukkitFurniture loadedFurniture(UUID owner) {
        Entity entity = Bukkit.getEntity(owner);
        return entity == null ? null
                : CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
    }

    private static boolean isShaker(BukkitFurniture furniture) {
        return furniture != null && furniture.id().toString().equals(SHAKER);
    }

    private void bootstrap() {
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (!CraftEngineFurniture.isFurniture(display)) {
                    continue;
                }
                BukkitFurniture furniture =
                        CraftEngineFurniture.getLoadedFurnitureByMetaEntity(display);
                if (isShaker(furniture)) {
                    refreshVisuals(furniture);
                }
            }
        }
    }

    private record VisualIds(UUID base, UUID lid) {
        boolean contains(UUID id) {
            return base.equals(id) || lid.equals(id);
        }
    }

    private record VisualPair(ItemDisplay base, ItemDisplay lid) {
    }
}
