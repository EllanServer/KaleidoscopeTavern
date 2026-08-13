package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.shaker;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.AnimatedItemFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.LifecycleFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.core.item.Item;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Recreates the source shaker model animation and client HUD. */
public final class ShakerVisualService {
    private static final String SHAKER = "kaleidoscope_tavern:shaker";
    private static final String BASE_ITEM = "kaleidoscope_tavern:_render/shaker_base";
    private static final String LID_ITEM = "kaleidoscope_tavern:_render/shaker_lid";
    private static final float LID_PIVOT_Y_PIXELS = 12.16667F;
    private static final Title.Times MIX_HUD_TIMES = Title.Times.times(
            Duration.ZERO, Duration.ofMillis(250), Duration.ZERO);

    private final JavaPlugin plugin;
    private final ContentCatalog catalog;
    private final ItemService items;
    private final Map<UUID, BukkitFurniture> loaded = new HashMap<>();
    private final Map<UUID, Float> animations = new HashMap<>();
    private final Map<UUID, Optional<Component>> ingredientHudSubtitles = new HashMap<>();
    private final Set<UUID> mixingHudViewers = new HashSet<>();
    private final ShakerIngredientHudController ingredientHud;
    private final AnimatedItemFurnitureBehavior.Handler visualHandler = this::visuals;
    private final LifecycleFurnitureBehavior.Handler lifecycleHandler;
    private BukkitTask animationTask;
    private Item baseRender;
    private Item lidRender;
    private boolean missingRenderItemLogged;

    public ShakerVisualService(JavaPlugin plugin, ContentCatalog catalog, ItemService items) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.items = items;
        this.ingredientHud = new ShakerIngredientHudController(
                plugin, loaded, new ShakerHudTargetResolver(SHAKER),
                this::hasIngredients,
                this::ingredientSubtitle);
        this.lifecycleHandler = new LifecycleFurnitureBehavior.Handler() {
            @Override
            public void onReady(BukkitFurniture furniture,
                                LifecycleFurnitureBehavior.ReadyReason reason) {
                loaded.put(furniture.uuid(), furniture);
                ingredientHudSubtitles.remove(furniture.uuid());
                ingredientHud.furnitureAvailable(furniture);
            }

            @Override
            public void onUnavailable(BukkitFurniture furniture, boolean removed) {
                UUID owner = furniture.uuid();
                loaded.remove(owner, furniture);
                animations.remove(owner);
                ingredientHudSubtitles.remove(owner);
                ingredientHud.furnitureUnavailable(owner);
                stopAnimationTaskIfIdle();
            }
        };
    }

    public void start() {
        ingredientHud.start();
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
        ingredientHud.stop();
        clearMixingHud();
        loaded.clear();
        ingredientHudSubtitles.clear();
        baseRender = null;
        lidRender = null;
    }

    public void beginMix(Player player) {
        UUID playerId = player.getUniqueId();
        ingredientHud.suppress(player);
        mixingHudViewers.add(playerId);
        showMixHud(player, ShakerHudSemantics.progressSubtitle(0));
    }

    public void updateMix(Player player, int ticks) {
        if (!player.isOnline()) {
            mixingHudViewers.remove(player.getUniqueId());
            return;
        }
        mixingHudViewers.add(player.getUniqueId());
        showMixHud(player, ShakerHudSemantics.progressSubtitle(ticks));
    }

    public void endMix(Player player) {
        if (mixingHudViewers.remove(player.getUniqueId()) && player.isOnline()) {
            player.clearTitle();
        }
        ingredientHud.resume(player);
    }

    public void animatePut(BukkitFurniture furniture) {
        if (!isShaker(furniture) || !furniture.isValid()) {
            return;
        }
        ingredientHudSubtitles.remove(furniture.uuid());
        ingredientHud.furnitureChanged(furniture);
        animations.put(furniture.uuid(), 0F);
        AnimatedItemFurnitureBehavior.updateTransforms(furniture);
        ensureAnimationTask();
    }

    /** Stops animation state before the programmatic furniture pickup. */
    public void removeFurnitureVisuals(BukkitFurniture furniture) {
        if (!isShaker(furniture)) {
            return;
        }
        ingredientHudSubtitles.remove(furniture.uuid());
        ingredientHud.furnitureUnavailable(furniture.uuid());
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

    private Optional<Component> ingredientSubtitle(BukkitFurniture furniture) {
        UUID owner = furniture.uuid();
        Optional<Component> cached = ingredientHudSubtitles.get(owner);
        if (cached != null) {
            return cached;
        }
        List<Integer> colors = ingredientColors(furniture);
        Optional<Component> subtitle = colors.isEmpty()
                ? Optional.empty()
                : Optional.of(ShakerHudSemantics.ingredientSubtitle(colors));
        ingredientHudSubtitles.put(owner, subtitle);
        return subtitle;
    }

    private boolean hasIngredients(BukkitFurniture furniture) {
        Item source = furniture.sourceItem();
        return source instanceof BukkitItem bukkitItem && !source.isEmpty()
                && items.hasShakerIngredients(bukkitItem.getBukkitItem());
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

    private static void showMixHud(Player player, Component subtitle) {
        player.showTitle(Title.title(Component.empty(), subtitle, MIX_HUD_TIMES));
    }

    private void clearMixingHud() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (mixingHudViewers.contains(player.getUniqueId())) {
                player.clearTitle();
            }
        }
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
        ItemStack base = items.buildVisual(BASE_ITEM).orElse(null);
        ItemStack lid = items.buildVisual(LID_ITEM).orElse(null);
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
