package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.AnimatedItemFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.LifecycleFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.core.item.Item;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Recreates the source shaker model animation and client HUD. */
public final class ShakerVisualService {
    private static final String SHAKER = "kaleidoscope_tavern:shaker";
    private static final String BASE_ITEM = "kaleidoscope_tavern:_render/shaker_base";
    private static final String LID_ITEM = "kaleidoscope_tavern:_render/shaker_lid";
    private static final float LID_PIVOT_Y_PIXELS = 12.16667F;
    private static final Title.Times HUD_TIMES = Title.Times.times(
            Duration.ZERO, Duration.ofMillis(250), Duration.ZERO);

    private final JavaPlugin plugin;
    private final ContentCatalog catalog;
    private final ItemService items;
    private final Map<UUID, BukkitFurniture> loaded = new HashMap<>();
    private final Map<UUID, Float> animations = new HashMap<>();
    private final Set<UUID> mixingHudViewers = new HashSet<>();
    private final Set<UUID> ingredientHudViewers = new HashSet<>();
    private final AnimatedItemFurnitureBehavior.Handler visualHandler = this::visuals;
    private final LifecycleFurnitureBehavior.Handler lifecycleHandler;
    private BukkitTask animationTask;
    private BukkitTask ingredientHudTask;
    private Item baseRender;
    private Item lidRender;
    private boolean missingRenderItemLogged;

    public ShakerVisualService(JavaPlugin plugin, ContentCatalog catalog, ItemService items) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.items = items;
        this.lifecycleHandler = new LifecycleFurnitureBehavior.Handler() {
            @Override
            public void onReady(BukkitFurniture furniture,
                                LifecycleFurnitureBehavior.ReadyReason reason) {
                loaded.put(furniture.uuid(), furniture);
                ensureIngredientHudTask();
            }

            @Override
            public void onUnavailable(BukkitFurniture furniture,
                                      boolean removed, boolean stopping) {
                UUID owner = furniture.uuid();
                loaded.remove(owner, furniture);
                animations.remove(owner);
                stopAnimationTaskIfIdle();
                stopIngredientHudTaskIfIdle();
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
        if (ingredientHudTask != null) {
            ingredientHudTask.cancel();
            ingredientHudTask = null;
        }
        clearAllHud();
        AnimatedItemFurnitureBehavior.unbind(
                AnimatedItemFurnitureBehavior.Channel.SHAKER, visualHandler);
        LifecycleFurnitureBehavior.unbind(
                LifecycleFurnitureBehavior.Channel.SHAKER, lifecycleHandler);
        loaded.clear();
        baseRender = null;
        lidRender = null;
    }

    void beginMix(Player player) {
        UUID playerId = player.getUniqueId();
        ingredientHudViewers.remove(playerId);
        mixingHudViewers.add(playerId);
        showHud(player, ShakerHudSemantics.progressSubtitle(0));
    }

    void updateMix(Player player, int ticks) {
        if (!player.isOnline()) {
            mixingHudViewers.remove(player.getUniqueId());
            return;
        }
        mixingHudViewers.add(player.getUniqueId());
        showHud(player, ShakerHudSemantics.progressSubtitle(ticks));
    }

    void endMix(Player player) {
        if (mixingHudViewers.remove(player.getUniqueId()) && player.isOnline()) {
            player.clearTitle();
        }
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

    private void ensureIngredientHudTask() {
        if (ingredientHudTask == null && !loaded.isEmpty()) {
            ingredientHudTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin, this::tickIngredientHud, 1L, 2L);
        }
    }

    private void stopIngredientHudTaskIfIdle() {
        if (ingredientHudTask != null && loaded.isEmpty()) {
            ingredientHudTask.cancel();
            ingredientHudTask = null;
            clearIngredientHud();
        }
    }

    private void tickIngredientHud() {
        Set<UUID> shownNow = new HashSet<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            if (mixingHudViewers.contains(playerId)) {
                continue;
            }
            BukkitFurniture shaker = targetedShaker(player);
            if (shaker == null) {
                continue;
            }
            List<Integer> colors = ingredientColors(shaker);
            if (colors.isEmpty()) {
                continue;
            }
            showHud(player, ShakerHudSemantics.ingredientSubtitle(colors));
            shownNow.add(playerId);
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            if (ingredientHudViewers.contains(playerId) && !shownNow.contains(playerId)) {
                player.clearTitle();
            }
        }
        ingredientHudViewers.clear();
        ingredientHudViewers.addAll(shownNow);
    }

    private BukkitFurniture targetedShaker(Player player) {
        Entity target = player.getTargetEntity(5);
        if (target == null) {
            return null;
        }
        BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByCollider(target);
        if (furniture == null) {
            furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(target);
        }
        return isShaker(furniture) && loaded.containsKey(furniture.uuid()) ? furniture : null;
    }

    private List<Integer> ingredientColors(BukkitFurniture furniture) {
        Item source = furniture.sourceItem();
        if (!(source instanceof BukkitItem bukkitItem) || source.isEmpty()) {
            return List.of();
        }
        ItemStack shaker = bukkitItem.getBukkitItem();
        List<Integer> colors = new ArrayList<>(3);
        for (ItemStack ingredient : items.shakerIngredients(shaker)) {
            String itemId = items.id(ingredient);
            colors.add(ShakerSemantics.ingredientColor(
                    itemId, catalog.cocktailColor(itemId)).orElse(0xFFFFFF));
        }
        return colors;
    }

    private static void showHud(Player player, Component subtitle) {
        player.showTitle(Title.title(Component.empty(), subtitle, HUD_TIMES));
    }

    private void clearIngredientHud() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (ingredientHudViewers.contains(player.getUniqueId())) {
                player.clearTitle();
            }
        }
        ingredientHudViewers.clear();
    }

    private void clearAllHud() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            if (ingredientHudViewers.contains(playerId) || mixingHudViewers.contains(playerId)) {
                player.clearTitle();
            }
        }
        ingredientHudViewers.clear();
        mixingHudViewers.clear();
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
