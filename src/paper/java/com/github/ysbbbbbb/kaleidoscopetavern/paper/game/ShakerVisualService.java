package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.AnimatedItemFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.LifecycleFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.item.Item;
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

/** Recreates the source ShakerModel animation with CE packet-only elements. */
public final class ShakerVisualService {
    private static final String SHAKER = "kaleidoscope_tavern:shaker";
    private static final String BASE_ITEM = "kaleidoscope_tavern:_render/shaker_base";
    private static final String LID_ITEM = "kaleidoscope_tavern:_render/shaker_lid";
    private static final float LID_PIVOT_Y_PIXELS = 12.16667F;

    private final JavaPlugin plugin;
    private final ItemService items;
    private final Map<UUID, BukkitFurniture> loaded = new HashMap<>();
    private final Map<UUID, Float> animations = new HashMap<>();
    private final AnimatedItemFurnitureBehavior.Handler visualHandler = this::visuals;
    private final LifecycleFurnitureBehavior.Handler lifecycleHandler;
    private BukkitTask animationTask;
    private Item baseRender;
    private Item lidRender;
    private boolean missingRenderItemLogged;

    public ShakerVisualService(JavaPlugin plugin, ItemService items) {
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
                animations.remove(owner);
                stopAnimationTaskIfIdle();
            }
        };
    }

    public void start() {
        AnimatedItemFurnitureBehavior.bind(
                AnimatedItemFurnitureBehavior.Channel.SHAKER, visualHandler);
        LifecycleFurnitureBehavior.bind(
                LifecycleFurnitureBehavior.Channel.SHAKER, lifecycleHandler);
    }

    public void stop() {
        for (UUID owner : animations.keySet().stream().toList()) {
            BukkitFurniture furniture = loaded.get(owner);
            if (isShaker(furniture)) {
                animations.put(owner, ShakerAnimationSemantics.LENGTH_TICKS);
                AnimatedItemFurnitureBehavior.updateTransforms(furniture);
            }
        }
        animations.clear();
        if (animationTask != null) {
            animationTask.cancel();
            animationTask = null;
        }
        AnimatedItemFurnitureBehavior.unbind(
                AnimatedItemFurnitureBehavior.Channel.SHAKER, visualHandler);
        LifecycleFurnitureBehavior.unbind(
                LifecycleFurnitureBehavior.Channel.SHAKER, lifecycleHandler);
        loaded.clear();
        baseRender = null;
        lidRender = null;
    }

    void animatePut(BukkitFurniture furniture) {
        if (!isShaker(furniture) || !furniture.isValid()) {
            return;
        }
        animations.put(furniture.uuid(), 0F);
        AnimatedItemFurnitureBehavior.updateTransforms(furniture);
        ensureAnimationTask();
    }

    /** Stops animation state before the programmatic furniture pickup. */
    void removeFurnitureVisuals(BukkitFurniture furniture) {
        if (!isShaker(furniture)) {
            return;
        }
        animations.remove(furniture.uuid());
        stopAnimationTaskIfIdle();
    }

    private void ensureAnimationTask() {
        if (animationTask == null && !animations.isEmpty()) {
            animationTask = plugin.getServer().getScheduler().runTaskTimer(
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
        List<UUID> finished = null;
        for (Map.Entry<UUID, Float> entry : animations.entrySet()) {
            BukkitFurniture furniture = loaded.get(entry.getKey());
            if (!isShaker(furniture) || !furniture.isValid()) {
                if (finished == null) {
                    finished = new ArrayList<>();
                }
                finished.add(entry.getKey());
                continue;
            }
            float elapsed = entry.getValue() + 1F;
            entry.setValue(elapsed);
            AnimatedItemFurnitureBehavior.updateTransforms(furniture);
            if (elapsed >= ShakerAnimationSemantics.LENGTH_TICKS) {
                if (finished == null) {
                    finished = new ArrayList<>();
                }
                finished.add(entry.getKey());
            }
        }
        if (finished != null) {
            finished.forEach(animations::remove);
        }
        stopAnimationTaskIfIdle();
    }

    private List<AnimatedItemFurnitureBehavior.Visual> visuals(
            BukkitFurniture furniture) {
        if (!isShaker(furniture) || !ensureRenderItems()) {
            return List.of();
        }
        ShakerAnimationSemantics.Pose pose = ShakerAnimationSemantics.pose(
                animations.getOrDefault(furniture.uuid(), 0F));
        float rootY = (float) Math.toRadians(-pose.rootYDegrees());
        float lidX = (float) Math.toRadians(pose.lidXDegrees());
        Quaternionf rootRotation = new Quaternionf().rotateY(rootY);
        Quaternionf lidRotation = new Quaternionf().rotateY(rootY).rotateX(lidX);
        float pivotTranslation = 0.5F + (LID_PIVOT_Y_PIXELS - 8F) / 16F
                + pose.lidYOffsetPixels() / 16F;
        float yaw = furniture.location().getYaw();
        return List.of(
                new AnimatedItemFurnitureBehavior.Visual(
                        baseRender, yaw, 0F,
                        new Vector3f(0, 0.5F, 0), rootRotation,
                        1F, 1.5F, 1, 1),
                new AnimatedItemFurnitureBehavior.Visual(
                        lidRender, yaw, 0F,
                        new Vector3f(0, pivotTranslation, 0), lidRotation,
                        1F, 1.5F, 1, 1));
    }

    private boolean ensureRenderItems() {
        if (baseRender != null && lidRender != null) {
            return true;
        }
        ItemStack base = items.build(BASE_ITEM, null).orElse(null);
        ItemStack lid = items.build(LID_ITEM, null).orElse(null);
        if (base == null || lid == null) {
            if (!missingRenderItemLogged) {
                missingRenderItemLogged = true;
                plugin.getLogger().severe(
                        "Shaker body/lid render items are unavailable after CraftEngine loading");
            }
            return false;
        }
        baseRender = BukkitAdaptor.adapt(base);
        lidRender = BukkitAdaptor.adapt(lid);
        return true;
    }

    private static boolean isShaker(BukkitFurniture furniture) {
        return furniture != null && furniture.id().toString().equals(SHAKER);
    }
}
