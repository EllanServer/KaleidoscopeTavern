package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.Messages;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.BarrelRecipe;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.EffectSpec;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.PressingRecipe;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.LifecycleFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.PressingTubFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.RedstoneFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.StationInteractionFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.StationVisualFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.TickingFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.behavior.ShakerItemBehavior;
import io.papermc.paper.event.entity.EntityMoveEvent;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Implements the former Forge block-entity gameplay on CraftEngine furniture entities. */
public final class StationService implements Listener {
    private static final String NAMESPACE = "kaleidoscope_tavern:";
    private static final String PRESSING_TUB = NAMESPACE + "pressing_tub";
    private static final String BARREL = NAMESPACE + "barrel";
    private static final String SHAKER = NAMESPACE + "shaker";
    private static final String EMPTY_GLASSWARE = NAMESPACE + "empty_glassware";
    private static final Key BARREL_KEY = Key.of(BARREL);
    private static final int PRESS_CAPACITY = 1_000;
    private static final int BARREL_CAPACITY = 4_000;
    private static final int MAX_BARREL_SLOTS = 4;
    private static final int MAX_BARREL_STACK = 16;

    private final JavaPlugin plugin;
    private final ContentCatalog catalog;
    private final ItemService items;
    private final Messages messages;
    private final ShakerVisualService shakerVisuals;
    private final Map<UUID, Float> falling = new HashMap<>();
    private final Map<UUID, Boolean> recentLandings = new HashMap<>();
    private final Map<UUID, PortableShakerUse> portableShakers = new HashMap<>();
    private final Set<UUID> pendingVanillaBucketEmpty = new HashSet<>();
    private BukkitTask portableShakerTask;
    private BukkitTask fallingCleanupTask;
    private final PressLandingListener pressLandingListener = new PressLandingListener();
    private final Consumer<Boolean> pressLandingAvailabilityHandler =
            this::setPressLandingEventsActive;
    private boolean pressLandingEventsActive;
    private final RedstoneFurnitureBehavior.Handler incenseRedstoneHandler =
            (furniture, powered, initial) -> setIncenseActive(furniture, powered, !initial);
    private final StationVisualFurnitureBehavior.Handler stationVisualHandler =
            this::stationVisuals;
    private final StationInteractionFurnitureBehavior.Handler stationInteractionHandler =
            this::interactStation;
    private final StationInteractionFurnitureBehavior.PlacementHandler stationPlacementHandler =
            this::onStationPlaced;
    private final ShakerItemBehavior.Handler shakerItemHandler =
            this::usePortableShaker;
    private final TickingFurnitureBehavior.Handler barrelTickingHandler =
            new TickingFurnitureBehavior.Handler() {
                @Override
                public void tick(BukkitFurniture furniture) {
                    tickBarrel(furniture);
                }

                @Override
                public void onReady(BukkitFurniture furniture) {
                    syncBarrelState(furniture);
                }
            };

    public StationService(JavaPlugin plugin, ContentCatalog catalog, ItemService items,
                          Messages messages, ShakerVisualService shakerVisuals) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.items = items;
        this.messages = messages;
        this.shakerVisuals = shakerVisuals;
    }

    public void start() {
        StationInteractionFurnitureBehavior.bind(stationInteractionHandler);
        StationInteractionFurnitureBehavior.bindPlacement(stationPlacementHandler);
        ShakerItemBehavior.bind(shakerItemHandler);
        StationVisualFurnitureBehavior.bind(stationVisualHandler);
        RedstoneFurnitureBehavior.bind(
                RedstoneFurnitureBehavior.Channel.INCENSE, incenseRedstoneHandler);
        TickingFurnitureBehavior.bind(
                TickingFurnitureBehavior.Channel.BARREL, barrelTickingHandler);
        PressingTubFurnitureBehavior.bindAvailability(pressLandingAvailabilityHandler);
    }

    public void stop() {
        StationInteractionFurnitureBehavior.unbindPlacement(stationPlacementHandler);
        StationInteractionFurnitureBehavior.unbind(stationInteractionHandler);
        ShakerItemBehavior.unbind(shakerItemHandler);
        StationVisualFurnitureBehavior.unbind(stationVisualHandler);
        RedstoneFurnitureBehavior.unbind(
                RedstoneFurnitureBehavior.Channel.INCENSE, incenseRedstoneHandler);
        TickingFurnitureBehavior.unbind(
                TickingFurnitureBehavior.Channel.BARREL, barrelTickingHandler);
        PressingTubFurnitureBehavior.unbindAvailability(
                pressLandingAvailabilityHandler);
        setPressLandingEventsActive(false);
        if (portableShakerTask != null) {
            portableShakerTask.cancel();
            portableShakerTask = null;
        }
        if (fallingCleanupTask != null) {
            fallingCleanupTask.cancel();
            fallingCleanupTask = null;
        }
        falling.clear();
        recentLandings.clear();
        portableShakers.clear();
        pendingVanillaBucketEmpty.clear();
    }

    private InteractionResult interactStation(BukkitFurniture furniture,
                                              InteractEntityContext context) {
        String id = furniture.id().toString();
        if (!id.equals(PRESSING_TUB) && !id.equals(EMPTY_GLASSWARE)
                && context.getHand() != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        Player player = (Player) context.getPlayer().platformPlayer();
        if (id.equals(BARREL) && TapSemantics.shouldDelegateBarrelTapPlacement(
                context.isSecondaryUseActive(),
                items.id(player.getInventory().getItemInMainHand()))) {
            // Yield to CE's furniture_item behavior.  The barrel hitboxes have
            // can_use_item_on enabled, so CE keeps ownership of collision,
            // placement events, item consumption, hand swing and place sound.
            return InteractionResult.PASS;
        }
        boolean handled = switch (id) {
            case PRESSING_TUB -> interactPress(player, furniture, context.getHand());
            case BARREL -> {
                Vec3d click = context.getClickLocation();
                Location interactionPoint = new Location(
                        furniture.location().getWorld(), click.x, click.y, click.z);
                yield interactBarrel(player, furniture, interactionPoint);
            }
            case SHAKER -> interactShaker(player, furniture);
            case EMPTY_GLASSWARE -> pourPortableShaker(
                    player, furniture, context.getHand());
            // Incense toggling lives in the generated CE furniture events.
            default -> false;
        };
        return handled ? InteractionResult.SUCCESS_AND_CANCEL : InteractionResult.PASS;
    }

    private void onStationPlaced(BukkitFurniture furniture) {
        if (furniture.id().toString().equals(BARREL)) {
            Bukkit.getScheduler().runTask(
                    plugin, () -> setBarrelOpen(furniture, true, false));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent event) {
        BukkitFurniture furniture = event.furniture();
        String id = furniture.id().toString();
        Location dropLocation = event.location().clone();
        switch (id) {
            case PRESSING_TUB -> {
                FurnitureState state = new FurnitureState(furniture);
                ItemStack storedIngredient = pressingItem(state);
                ItemStack ingredient = storedIngredient == null ? null : storedIngredient.clone();
                int ingredientCount = ingredient == null ? 0 : state.integer("press_count");
                deferFurnitureBreak(event, () -> {
                    if (event.dropItems() && ingredient != null) {
                        dropStored(dropLocation, ingredient, ingredientCount);
                    }
                });
                // PressingTubBlock#getDrops restores only the ingredient;
                // finished tank fluid is deliberately lost on break.
            }
            // Forge only drops the barrel itself. Its internal ingredients,
            // fluid and finished output are deliberately lost on destruction;
            // CE removes its packet-only visual element with the furniture.
            case BARREL -> {
            }
            case SHAKER -> {
                Optional<ItemStack> shaker = event.dropItems()
                        ? shakerItem(furniture, event.player())
                        : Optional.empty();
                if (shaker.isPresent()) {
                    event.setDropItems(false);
                    ItemStack drop = shaker.get().clone();
                    deferFurnitureBreak(event, () -> {
                        if (!event.dropItems()) {
                            dropLocation.getWorld().dropItemNaturally(dropLocation, drop);
                        }
                    });
                }
            }
            default -> {
            }
        }
    }

    private void deferFurnitureBreak(FurnitureBreakEvent event, Runnable action) {
        // EventPriority does not define registration order within a priority,
        // and even a MONITOR listener can technically cancel the event. Wait
        // until dispatch is over before producing drops or deleting helpers.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!event.isCancelled()) {
                action.run();
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL
                || !(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        if (!PressingTubFurnitureBehavior.hasLoadedInWorld(living.getWorld())) {
            falling.remove(living.getUniqueId());
            stopFallingCleanupTaskIfIdle();
            return;
        }
        UUID id = living.getUniqueId();
        Float tracked = falling.remove(id);
        stopFallingCleanupTaskIfIdle();
        float fallDistance = tracked == null
                ? living.getFallDistance()
                : Math.max(living.getFallDistance(), tracked);
        if (fallDistance >= PressingTubSemantics.MIN_FALL_DISTANCE
                && handlePressLanding(living, living.getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * Stops a bucket from also emptying into the world when it feeds a station.
     *
     * <p>Filling a barrel or pressing tub is driven by the CE furniture
     * controller, which only governs CraftEngine's own interaction flow. Vanilla still runs
     * its bucket placement for the same right-click, so the fluid was recorded on
     * the furniture *and* spilled as a real block beside it. Interaction hitboxes
     * are not blocks, so vanilla picks the air the player aimed through.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        // The CE furniture controller already consumed this bucket into the station.
        // The vanilla target can be a block behind the furniture because its
        // hitboxes are entities, so the exact interaction is more reliable than
        // trying to infer the station from that target block.
        if (pendingVanillaBucketEmpty.remove(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        // Read the bucket from the hand the event reports, so an off-hand pour is
        // recognised too, and keep using the item id because pressing recipes are
        // keyed by custom juice buckets rather than vanilla materials.
        ItemStack bucket = event.getHand() == EquipmentSlot.OFF_HAND
                ? event.getPlayer().getInventory().getItemInOffHand()
                : event.getPlayer().getInventory().getItemInMainHand();
        if (fluidFromBucket(items.id(bucket)).isEmpty()) {
            return;
        }
        if (fluidStationAt(event.getBlock()) || fluidStationAt(event.getBlockClicked())) {
            event.setCancelled(true);
        }
    }

    /** Whether a station that consumes bucket fluids occupies this block. */
    private boolean fluidStationAt(Block block) {
        if (block == null) {
            return false;
        }
        if (PressingTubFurnitureBehavior.occupiesBlock(block)) {
            return true;
        }
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        // CE supplies loaded barrel origins; Paper only preserves the source
        // multiblock's exact 3x3x3 bucket-interaction footprint.
        for (BukkitFurniture furniture : LifecycleFurnitureBehavior.nearby(
                LifecycleFurnitureBehavior.Channel.BARREL, center, 3.0, 3.0)) {
            Block origin = furniture.location().getBlock();
            int dx = block.getX() - origin.getX();
            int dy = block.getY() - origin.getY();
            int dz = block.getZ() - origin.getZ();
            if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dy >= 0 && dy <= 2) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        falling.remove(event.getPlayer().getUniqueId());
        stopFallingCleanupTaskIfIdle();
        recentLandings.remove(event.getPlayer().getUniqueId());
        portableShakers.remove(event.getPlayer().getUniqueId());
        stopPortableShakerTaskIfIdle();
        pendingVanillaBucketEmpty.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClearPortableShaker(PlayerInteractEvent event) {
        // ClearShakerC2SMessage: sneaking and swinging at the air dumps the
        // shaker's ingredients and result outright with the bottle-fill blip.
        if (event.getAction() != Action.LEFT_CLICK_AIR || event.getHand() == null
                || event.getItem() == null || !items.id(event.getItem()).equals(SHAKER)
                || !event.getPlayer().isSneaking()) {
            return;
        }
        ItemStack shaker = event.getItem();
        if (items.shakerIngredients(shaker).isEmpty() && items.shakerResult(shaker) == null) {
            return;
        }
        items.withShakerState(shaker, List.of(), null);
        event.getPlayer().getWorld().playSound(event.getPlayer().getLocation(),
                "minecraft:item.bottle.fill", SoundCategory.PLAYERS, 1.0F, 1.0F);
    }

    private InteractionResult usePortableShaker(
            net.momirealms.craftengine.core.entity.player.Player cePlayer,
            InteractionHand interactionHand) {
        if (!(cePlayer.platformPlayer() instanceof Player player)) {
            return InteractionResult.PASS;
        }
        EquipmentSlot hand = interactionHand == InteractionHand.MAIN_HAND
                ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        ItemStack shaker = handItem(player, hand);
        if (!items.id(shaker).equals(SHAKER)) {
            return InteractionResult.PASS;
        }
        // The migrated shaker uses a long consumable component solely to
        // expose the original brush-style use animation. Always suppress the
        // vanilla item use; only a valid three-ingredient shaker is started
        // explicitly below.
        if (items.shakerResult(shaker) != null) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        int ingredientCount = items.shakerIngredients(shaker).size();
        if (ingredientCount != 3) {
            if (ingredientCount > 0) {
                player.sendActionBar(net.kyori.adventure.text.Component.translatable(
                        "message.kaleidoscope_tavern.shaker.amount_too_low"));
            }
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack current = handItem(player, hand);
            if (!player.isOnline() || !items.id(current).equals(SHAKER)
                    || items.shakerResult(current) != null
                    || items.shakerIngredients(current).size() != 3) {
                return;
            }
            portableShakers.put(
                    player.getUniqueId(), new PortableShakerUse(player, hand, 0));
            ensurePortableShakerTask();
            player.startUsingItem(hand);
            player.setActiveItemRemainingTime(72_000);
        });
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStopPortableShaker(PlayerStopUsingItemEvent event) {
        Player player = event.getPlayer();
        PortableShakerUse use = portableShakers.remove(player.getUniqueId());
        if (use == null) {
            return;
        }
        stopPortableShakerTaskIfIdle();
        finishPortableShaker(player, use.hand(), Math.max(use.ticks(), event.getTicksHeldFor()));
    }

    private void finishPortableShaker(Player player, EquipmentSlot hand, int ticks) {
        ItemStack shaker = handItem(player, hand);
        if (!items.id(shaker).equals(SHAKER) || items.shakerResult(shaker) != null) {
            return;
        }
        List<ItemStack> ingredients = items.shakerIngredients(shaker);
        if (ingredients.size() != 3) {
            return;
        }
        ticks = Math.max(0, ticks);
        if (ShakerSemantics.resultBand(ticks) == ShakerSemantics.ResultBand.NONE) {
            return;
        }
        Optional<ItemStack> result = buildShakerResult(player, ingredients, ticks);
        if (result.isEmpty()) {
            messages.send(player, "pack-missing");
            return;
        }
        items.withShakerState(shaker, ingredients, result.get());
        setHandItem(player, hand, shaker);
        player.getWorld().playSound(player.getLocation(),
                "kaleidoscope_tavern:item.shaker.end", SoundCategory.PLAYERS, 1.0F, 1.0F);
    }

    private void tickPortableShakers() {
        var iterator = portableShakers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PortableShakerUse> entry = iterator.next();
            PortableShakerUse use = entry.getValue();
            Player player = use.player();
            if (!player.isOnline()
                    || !player.isHandRaised() || !items.id(handItem(player, use.hand())).equals(SHAKER)) {
                iterator.remove();
                continue;
            }
            int ticks = use.ticks();
            if (ShakerSemantics.playsShakeSound(ticks)) {
                float volume = 0.75F + ThreadLocalRandom.current().nextFloat() * 0.2F;
                float pitch = 0.8F + ThreadLocalRandom.current().nextFloat() * 0.2F;
                player.getWorld().playSound(player.getLocation(),
                        "kaleidoscope_tavern:item.shaker.shaking",
                        SoundCategory.PLAYERS, volume, pitch);
            }
            if (ShakerSemantics.shouldAutoRelease(ticks)) {
                iterator.remove();
                player.clearActiveItem();
                finishPortableShaker(player, use.hand(), ticks);
            } else {
                entry.setValue(new PortableShakerUse(player, use.hand(), ticks + 1));
            }
        }
        stopPortableShakerTaskIfIdle();
    }

    private void ensurePortableShakerTask() {
        if (portableShakerTask == null) {
            portableShakerTask = Bukkit.getScheduler().runTaskTimer(
                    plugin, this::tickPortableShakers, 1L, 1L);
        }
    }

    private void stopPortableShakerTaskIfIdle() {
        if (portableShakerTask != null && portableShakers.isEmpty()) {
            portableShakerTask.cancel();
            portableShakerTask = null;
        }
    }

    private boolean interactPress(Player player, BukkitFurniture furniture, InteractionHand usedHand) {
        FurnitureState state = new FurnitureState(furniture);
        ItemStack hand = usedHand == InteractionHand.MAIN_HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        String handId = items.id(hand);
        int storedCount = state.integer("press_count");
        if (hand.isEmpty() && storedCount > 0) {
            int removeCount = player.isSneaking() ? Math.min(64, storedCount) : 1;
            ItemStack stored = pressingItem(state);
            if (stored != null) {
                giveStored(player, stored, removeCount);
                int remaining = storedCount - removeCount;
                state.integer("press_count", remaining);
                if (remaining == 0) {
                    state.clear("press_item");
                }
                playItemFrameSound(furniture.location(),
                        "minecraft:entity.item_frame.remove_item");
                refreshStationVisuals(furniture);
                return true;
            }
        }

        if (handId.equals("minecraft:bucket") && state.integer("press_amount") >= PRESS_CAPACITY) {
            String fluid = state.string("press_fluid");
            Optional<PressingRecipe> recipe = catalog.pressingByFluid(fluid == null ? "" : fluid);
            Optional<ItemStack> result = recipe.flatMap(value -> items.build(value.bucket(), player));
            if (result.isPresent()) {
                if (player.getGameMode() != GameMode.CREATIVE) {
                    hand.subtract(1);
                }
                items.give(player, result.get());
                state.clear("press_amount", "press_fluid");
                furniture.location().getWorld().playSound(furniture.location(),
                        "minecraft:item.bucket.fill", SoundCategory.BLOCKS, 1.0F, 1.0F);
                refreshStationVisuals(furniture);
                return true;
            }
        }

        if (!hand.isEmpty()) {
            ItemStack current = state.item("press_item");
            int count = state.integer("press_count");
            int capacity = Math.min(64,
                    current == null ? hand.getMaxStackSize() : current.getMaxStackSize());
            if ((current == null || current.isSimilar(hand)) && count < capacity) {
                int inserted = Math.min(hand.getAmount(), capacity - count);
                ItemStack template = hand.clone();
                template.setAmount(1);
                // PressingTubBlockEntity inserts the carried stack directly;
                // unlike most interactions this also shrinks creative stacks.
                hand.subtract(inserted);
                state.item("press_item", template);
                state.integer("press_count", count + inserted);
                playItemFrameSound(furniture.location(),
                        "minecraft:entity.item_frame.add_item");
                refreshStationVisuals(furniture);
                return true;
            }
        }
        return false;
    }

    private boolean pressOne(BukkitFurniture furniture) {
        if (!furniture.currentVariant().name().equals("ground")) {
            return false;
        }
        FurnitureState state = new FurnitureState(furniture);
        int count = state.integer("press_count");
        ItemStack ingredient = pressingItem(state);
        if (ingredient == null || count <= 0) {
            if (state.integer("press_amount") > 0) {
                playSuccessfulPress(furniture, null);
            } else {
                playFailedPress(furniture, null);
            }
            return false;
        }
        Optional<PressingRecipe> optional = catalog.pressing(items.id(ingredient));
        if (optional.isEmpty()) {
            playFailedPress(furniture, ingredient);
            ejectInvalidPressContents(furniture, state, ingredient, count);
            return false;
        }
        PressingRecipe recipe = optional.get();
        String currentFluid = state.string("press_fluid");
        int amount = state.integer("press_amount");
        if (currentFluid != null && !currentFluid.equals(recipe.fluid())) {
            playFailedPress(furniture, ingredient);
            ejectInvalidPressContents(furniture, state, ingredient, count);
            return false;
        }
        if (amount >= PRESS_CAPACITY) {
            playFinishedPress(furniture);
            return false;
        }
        state.integer("press_count", count - 1);
        if (count == 1) {
            state.clear("press_item");
        }
        state.putString("press_fluid", recipe.fluid());
        state.integer("press_amount", Math.min(PRESS_CAPACITY, amount + recipe.amount()));
        refreshStationVisuals(furniture);
        playSuccessfulPress(furniture, ingredient);
        return true;
    }

    private void playSuccessfulPress(BukkitFurniture furniture, ItemStack ingredient) {
        Location location = furniture.location().clone().add(0, 0.5, 0);
        playPressSound(location, "minecraft:block.slime_block.fall");
        if (ingredient == null) {
            location.getWorld().spawnParticle(Particle.RAIN, location, 10,
                    0.25, 0.2, 0.25, 0.05);
        } else {
            location.getWorld().spawnParticle(Particle.ITEM, location, 10,
                    0.25, 0.2, 0.25, 0.05, ingredient);
        }
    }

    private void playFailedPress(BukkitFurniture furniture, ItemStack ingredient) {
        Location location = furniture.location().clone().add(0, 0.5, 0);
        playPressSound(location, "minecraft:block.wood.fall");
        if (ingredient == null) {
            location.getWorld().spawnParticle(Particle.BLOCK, location, 10,
                    0.25, 0.2, 0.25, 0.05, Material.OAK_PLANKS.createBlockData());
        } else {
            location.getWorld().spawnParticle(Particle.ITEM, location, 10,
                    0.25, 0.2, 0.25, 0.05, ingredient);
        }
    }

    private void playFinishedPress(BukkitFurniture furniture) {
        Location location = furniture.location().clone().add(0, 0.5, 0);
        playPressSound(location, "minecraft:block.honey_block.hit");
        location.getWorld().spawnParticle(Particle.RAIN, location, 10,
                0.25, 0.2, 0.25, 0.05);
    }

    private static void playPressSound(Location location, String sound) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        location.getWorld().playSound(location, sound,
                SoundCategory.BLOCKS,
                0.5F + random.nextFloat(), random.nextFloat() * 0.3F + 0.7F);
    }

    private static void playItemFrameSound(Location location, String sound) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        location.getWorld().playSound(location, sound,
                SoundCategory.BLOCKS,
                0.5F + random.nextFloat(), random.nextFloat() * 0.7F + 0.6F);
    }

    private void ejectInvalidPressContents(BukkitFurniture furniture, FurnitureState state,
                                            ItemStack template, int totalCount) {
        if (!plugin.getConfig().getBoolean("stations.press-eject-invalid", true) || totalCount <= 0) {
            return;
        }
        state.clear("press_count", "press_item");
        refreshStationVisuals(furniture);
        int directionCount = Math.min(8, totalCount);
        double diagonal = 1.0 / Math.sqrt(2.0);
        double[][] directions = {
                {1, 0}, {diagonal, diagonal}, {0, 1}, {-diagonal, diagonal},
                {-1, 0}, {-diagonal, -diagonal}, {0, -1}, {diagonal, -diagonal},
        };
        int base = totalCount / directionCount;
        int remainder = totalCount % directionCount;
        Boolean drops = furniture.location().getWorld().getGameRuleValue(GameRule.DO_TILE_DROPS);
        if (Boolean.FALSE.equals(drops)) {
            return;
        }
        Location origin = furniture.location();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < directionCount; index++) {
            ItemStack dropped = template.clone();
            dropped.setAmount(base + (index < remainder ? 1 : 0));
            double[] direction = directions[index];
            Location spawn = origin.clone().add(
                    direction[0] * 0.3 + random.nextDouble(-0.05, 0.05),
                    0.5 + random.nextDouble(0, 0.1),
                    direction[1] * 0.3 + random.nextDouble(-0.05, 0.05));
            origin.getWorld().dropItem(spawn, dropped, entity -> entity.setVelocity(new Vector(
                    direction[0] * 0.15 + random.nextDouble(-0.02, 0.02),
                    0.1 + random.nextDouble(-0.02, 0.02),
                    direction[1] * 0.15 + random.nextDouble(-0.02, 0.02))));
        }
    }

    private void trackPressLanding(LivingEntity living, Location feet,
                                   float currentFallDistance) {
        if (currentFallDistance > 0) {
            if (PressingTubFurnitureBehavior.hasPotentialBelow(feet)) {
                falling.merge(living.getUniqueId(), currentFallDistance, Math::max);
                ensureFallingCleanupTask();
            }
            return;
        }
        // The listener already filters untracked ground movement. Retain this
        // guard for lifecycle races that clear the map between that filter and
        // the landing edge.
        if (falling.isEmpty()) {
            return;
        }
        Float trackedFallDistance = falling.remove(living.getUniqueId());
        stopFallingCleanupTaskIfIdle();
        if (trackedFallDistance != null
                && trackedFallDistance >= PressingTubSemantics.MIN_FALL_DISTANCE) {
            handlePressLanding(living, feet);
        }
    }

    private boolean handlePressLanding(LivingEntity living, Location feet) {
        UUID id = living.getUniqueId();
        Boolean recent = recentLandings.get(id);
        if (recent != null) {
            return recent;
        }
        boolean pressed = PressingTubFurnitureBehavior.findBelow(feet)
                .map(this::pressOne)
                .orElse(false);
        recentLandings.put(id, pressed);
        Bukkit.getScheduler().runTaskLater(plugin, () -> recentLandings.remove(id), 2L);
        return pressed;
    }

    private boolean interactBarrel(Player player, BukkitFurniture furniture, Location interactionPoint) {
        FurnitureState state = new FurnitureState(furniture);
        boolean open = isBarrelOpen(furniture);
        BarrelSemantics.Hit hit = barrelHit(furniture, interactionPoint);
        ItemStack hand = player.getInventory().getItemInMainHand();
        String handId = items.id(hand);

        // BarrelBlock only delegates the top nine multiblock cells to the lid
        // and inventory logic. Every lower cell is informational.
        if (hit == BarrelSemantics.Hit.BODY) {
            showBarrelBrewInfo(player, state);
            return true;
        }

        // A closed lid is always opened first, regardless of the held item.
        // Brewing barrels reject opening, and output remains accessible only
        // through TapService just like IBarrel.doTapExtract in the source.
        if (!open) {
            if (isBarrelBrewing(state)) {
                messages.send(player, "barrel-brewing-cannot-open");
                return false;
            }
            setBarrelOpen(furniture, true, true);
            return true;
        }

        // With an open barrel, an empty-hand click on any non-centre top cell
        // closes the lid. The source's next 97-tick check decides whether a
        // recipe can start; the CE furniture ticker deliberately preserves that delay.
        if (hit == BarrelSemantics.Hit.TOP_RIM) {
            if (hand.isEmpty()) {
                setBarrelOpen(furniture, false, true);
                return true;
            }
            return false;
        }

        if (isBarrelBrewing(state)) {
            // This only repairs legacy/corrupt state where a brewing barrel
            // was persisted open; normal gameplay is caught by the branch
            // above and can never reach this combination.
            setBarrelOpen(furniture, false, false);
            messages.send(player, "barrel-brewing-cannot-open");
            return true;
        }

        if (hand.isEmpty()) {
            List<ItemStack> stored = barrelIngredients(state);
            if (!stored.isEmpty()) {
                ItemStack removed = stored.removeLast();
                items.give(player, removed);
                state.items("barrel_items", stored);
                refreshStationVisuals(furniture);
                player.getWorld().playSound(player.getLocation(),
                        "minecraft:entity.item_frame.remove_item",
                        SoundCategory.PLAYERS, 1.0F, 1.0F);
                return true;
            }
            return false;
        }

        Optional<String> fluid = fluidFromBucket(handId);
        if (fluid.isPresent()) {
            List<ItemStack> stored = barrelIngredients(state);
            String current = state.string("barrel_fluid");
            int amount = state.integer("barrel_amount");
            if (stored.isEmpty() && amount <= BARREL_CAPACITY - 1_000
                    && (current == null || current.equals(fluid.get()))) {
                if (player.getGameMode() != GameMode.CREATIVE) {
                    hand.subtract(1);
                }
                state.putString("barrel_fluid", fluid.get());
                state.integer("barrel_amount", amount + 1_000);
                items.build("minecraft:bucket", player).ifPresent(bucket -> items.give(player, bucket));
                suppressVanillaBucketEmpty(player);
                furniture.location().getWorld().playSound(furniture.location(),
                        "minecraft:item.bucket.empty", SoundCategory.BLOCKS, 0.9F, 1.0F);
                refreshStationVisuals(furniture);
                return true;
            }
            return false;
        }

        if (handId.equals("minecraft:bucket") && state.integer("barrel_amount") >= 1_000
                && barrelIngredients(state).isEmpty()) {
            String storedFluid = state.string("barrel_fluid");
            Optional<String> bucketId = bucketForFluid(storedFluid);
            if (bucketId.isPresent()) {
                if (player.getGameMode() != GameMode.CREATIVE) {
                    hand.subtract(1);
                }
                items.build(bucketId.get(), player).ifPresent(result -> items.give(player, result));
                // FluidUtils#fillItem plays the fluid's bucket-fill sound.
                player.getWorld().playSound(player.getLocation(),
                        "minecraft:item.bucket.fill", SoundCategory.PLAYERS, 1.0F, 1.0F);
                int remaining = state.integer("barrel_amount") - 1_000;
                state.integer("barrel_amount", remaining);
                if (remaining == 0) {
                    state.clear("barrel_fluid");
                }
                refreshStationVisuals(furniture);
                return true;
            }
            return false;
        }

        // Forge FluidUtil treats bucket-like capability containers as fluid
        // containers even when this standalone server has no mapping for the
        // contained mod fluid. Such a container must never become an ingredient.
        if (hand.getType().name().endsWith("_BUCKET")) {
            return false;
        }

        if (state.integer("barrel_amount") < BARREL_CAPACITY) {
            messages.send(player, "barrel-fluid-not-full");
            return false;
        }

        // ItemStackHandler accepts as much as fits in one matching/empty slot
        // (up to sixteen), and deliberately accepts non-recipe ingredients;
        // an unmatched closed barrel becomes vinegar.
        List<ItemStack> stored = barrelIngredients(state);
        // addIngredientOnce: try every slot that can still merge, then fall
        // back to an empty slot; the slot cap is min(16, item max stack).
        int stackLimit = Math.min(MAX_BARREL_STACK, hand.getMaxStackSize());
        int matching = -1;
        for (int index = 0; index < stored.size(); index++) {
            if (stored.get(index).isSimilar(hand) && stored.get(index).getAmount() < stackLimit) {
                matching = index;
                break;
            }
        }
        int room = matching >= 0 ? stackLimit - stored.get(matching).getAmount()
                : stored.size() < MAX_BARREL_SLOTS ? stackLimit : 0;
        int inserted = Math.min(room, hand.getAmount());
        if (inserted <= 0) {
            messages.send(player, "barrel-no-space");
            return false;
        }
        if (matching >= 0) {
            ItemStack existing = stored.get(matching);
            existing.setAmount(existing.getAmount() + inserted);
        } else {
            ItemStack added = hand.clone();
            added.setAmount(inserted);
            stored.add(added);
        }
        hand.subtract(inserted);
        state.items("barrel_items", stored);
        refreshStationVisuals(furniture);
        player.getWorld().playSound(player.getLocation(),
                "minecraft:entity.item_frame.add_item", SoundCategory.PLAYERS, 1.0F, 1.0F);
        return true;
    }

    /** Mirrors {@code BarrelBlockEntity.tipBrewInfo} from the archived Forge source. */
    private void showBarrelBrewInfo(Player player, FurnitureState state) {
        if (!isBarrelBrewing(state)) {
            player.sendActionBar(net.kyori.adventure.text.Component.translatable(
                    "message.kaleidoscope_tavern.barrel.not_brewing"));
            return;
        }

        String resultId = state.string("barrel_result", "");
        net.kyori.adventure.text.Component resultName = items.build(resultId, player)
                .map(ItemStack::effectiveName)
                .orElseGet(() -> net.kyori.adventure.text.Component.text(resultId));
        net.kyori.adventure.text.Component count = net.kyori.adventure.text.Component.text(
                Math.max(0, state.integer("barrel_output")));
        int level = brewLevel(state);
        net.kyori.adventure.text.Component quality = net.kyori.adventure.text.Component.translatable(
                "message.kaleidoscope_tavern.barrel.brew_level." + level);

        if (level >= 6) {
            player.sendActionBar(net.kyori.adventure.text.Component.translatable(
                    "message.kaleidoscope_tavern.barrel.brew_info.full",
                    resultName, count, quality));
            return;
        }

        net.kyori.adventure.text.Component remaining = net.kyori.adventure.text.Component.text(
                BarrelSemantics.formatTickDuration(state.integer("barrel_time")));
        player.sendActionBar(net.kyori.adventure.text.Component.translatable(
                "message.kaleidoscope_tavern.barrel.brew_info.next",
                resultName, count, quality, remaining));
    }

    private void suppressVanillaBucketEmpty(Player player) {
        UUID playerId = player.getUniqueId();
        pendingVanillaBucketEmpty.add(playerId);
        Bukkit.getScheduler().runTask(plugin,
                () -> pendingVanillaBucketEmpty.remove(playerId));
    }

    private void syncBarrelState(BukkitFurniture furniture) {
        if (furniture == null || !furniture.isValid() || furniture.bukkitEntity() == null) {
            return;
        }
        FurnitureState state = new FurnitureState(furniture);
        if (isBarrelBrewing(state) && isBarrelOpen(furniture)) {
            setBarrelOpen(furniture, false, false);
        }
    }

    private void setBarrelOpen(BukkitFurniture furniture, boolean open, boolean playSound) {
        if (furniture == null || !furniture.isValid() || furniture.bukkitEntity() == null) {
            return;
        }
        boolean variantChanged = furniture.setVariant(
                open ? "ground_open" : "ground", true);
        if (playSound) {
            // The Forge implementation intentionally uses BARREL_OPEN for
            // both transitions and plays it at the lid, two blocks above.
            furniture.location().getWorld().playSound(
                    furniture.location().clone().add(0, 2, 0),
                    "minecraft:block.barrel.open", SoundCategory.BLOCKS, 1.0F, 1.0F);
        }
        // setVariant already rebuilds and redistributes CE elements. Refresh
        // only when the requested variant was already active.
        if (!variantChanged) {
            refreshStationVisuals(furniture);
        }
    }

    private static boolean isBarrelOpen(BukkitFurniture furniture) {
        return furniture.currentVariant().name().endsWith("_open");
    }

    private static BarrelSemantics.Hit barrelHit(BukkitFurniture furniture, Location point) {
        if (point == null) {
            return BarrelSemantics.Hit.BODY;
        }
        Location origin = furniture.location();
        Vec3d xEnd = furniture.getRelativePosition(new Vector3f(1, 0, 0));
        Vec3d zEnd = furniture.getRelativePosition(new Vector3f(0, 0, -1));
        double deltaX = point.getX() - origin.getX();
        double deltaZ = point.getZ() - origin.getZ();
        double sourceX = deltaX * (xEnd.x - origin.getX())
                + deltaZ * (xEnd.z - origin.getZ());
        double sourceZ = deltaX * (zEnd.x - origin.getX())
                + deltaZ * (zEnd.z - origin.getZ());
        return BarrelSemantics.classify(sourceX, point.getY() - origin.getY(), sourceZ);
    }

    private List<ItemStack> barrelIngredients(FurnitureState state) {
        return state.items("barrel_items");
    }

    private boolean beginBrewing(BukkitFurniture furniture, FurnitureState state) {
        if (state.integer("barrel_amount") < BARREL_CAPACITY) {
            return false;
        }
        String fluid = state.string("barrel_fluid", "");
        List<ItemStack> stored = barrelIngredients(state);
        List<String> ingredientIds = stored.stream().map(items::id).toList();
        Optional<BarrelRecipe> optional = catalog.barrel(fluid, ingredientIds);
        String result;
        String recipeId;
        int unitTicks;
        int outputCount;
        if (optional.isPresent()) {
            BarrelRecipe recipe = optional.get();
            result = recipe.result();
            recipeId = recipe.id();
            unitTicks = recipe.unitTicks();
            outputCount = stored.isEmpty() ? MAX_BARREL_STACK
                    : Math.min(MAX_BARREL_STACK, stored.stream()
                    .mapToInt(ItemStack::getAmount).min().orElse(1));
        } else {
            result = NAMESPACE + "vinegar";
            recipeId = NAMESPACE + "empty";
            unitTicks = 2400;
            outputCount = MAX_BARREL_STACK;
        }
        state.putString("barrel_recipe", recipeId);
        state.putString("barrel_result", result);
        state.integer("barrel_output", outputCount);
        state.integer("barrel_unit", Math.max(1, unitTicks));
        state.integer("barrel_level", 1);
        state.integer("barrel_time", Math.max(1, unitTicks));
        state.clear("barrel_fluid", "barrel_amount", "barrel_items");
        return true;
    }

    private int brewLevel(FurnitureState state) {
        return Math.clamp(state.integer("barrel_level"), 0, 6);
    }

    private boolean isBarrelBrewing(FurnitureState state) {
        return brewLevel(state) >= 1;
    }

    private boolean interactShaker(Player player, BukkitFurniture furniture) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        String handId = items.id(hand);
        // ShakerBlock only accepts ingredients while placed. Empty hand picks
        // up the entire shaker; mixing itself belongs exclusively to the item.
        if (hand.isEmpty()) {
            return takeShaker(player, furniture,
                    shakerItem(furniture, player).orElse(null));
        }
        if (!catalog.tag(NAMESPACE + "cocktail_ingredient").contains(handId)) {
            return false;
        }
        Optional<ItemStack> source = shakerItem(furniture, player);
        if (source.isEmpty()) {
            messages.send(player, "pack-missing");
            return true;
        }
        ItemStack shaker = source.get();
        List<ItemStack> ingredients = new ArrayList<>(items.shakerIngredients(shaker));
        if (items.shakerResult(shaker) != null || ingredients.size() >= 3) {
            return true;
        }
        if (catalog.hasDrinkEffects(handId) && items.brewLevel(hand) < 4) {
            player.sendMessage(net.kyori.adventure.text.Component.translatable(
                    "message.kaleidoscope_tavern.shaker.brew_level_too_low"));
            return true;
        }
        ItemStack captured = hand.clone();
        captured.setAmount(1);
        if (!items.consumeOne(player, hand)) {
            return true;
        }
        ingredients.add(captured);
        updateShakerSource(furniture, shaker, ingredients, null);
        shakerVisuals.animatePut(furniture);
        if (catalog.hasDrinkEffects(handId)) {
            items.returnedContainer(handId, catalog.isCocktail(handId)).flatMap(id -> items.build(id, player))
                    .ifPresent(container -> items.give(player, container));
            furniture.location().getWorld().playSound(furniture.location(),
                    "minecraft:item.bottle.empty", SoundCategory.BLOCKS, 0.75F, 1.0F);
        } else if (captured.getItemMeta() instanceof PotionMeta) {
            items.give(player, new ItemStack(org.bukkit.Material.GLASS_BOTTLE));
            furniture.location().getWorld().playSound(furniture.location(),
                    "minecraft:item.bottle.empty", SoundCategory.BLOCKS, 0.75F, 1.0F);
        } else {
            furniture.location().getWorld().playSound(furniture.location(),
                    "minecraft:entity.item_frame.add_item", SoundCategory.BLOCKS, 0.75F, 1.0F);
        }
        furniture.location().getWorld().spawnParticle(Particle.BUBBLE_POP,
                furniture.location().clone().add(0, 0.75, 0), 8, 0.2, 0.3, 0.2, 0);
        return true;
    }

    private Optional<ItemStack> buildShakerResult(Player player, List<ItemStack> ingredients, int ticks) {
        String resultId;
        boolean signature = false;
        List<String> ids = ingredients.stream().map(items::id).toList();
        switch (ShakerSemantics.resultBand(ticks)) {
            case MYSTERY -> resultId = NAMESPACE + "mystery_cocktail";
            case SIGNATURE -> {
                resultId = NAMESPACE + "signature_cocktail";
                signature = true;
            }
            case HAND_RECIPE -> {
                Optional<ContentCatalog.ShakerRecipe> recipe = catalog.shaker(ids);
                if (recipe.isPresent()) {
                    resultId = recipe.get().result();
                } else {
                    resultId = NAMESPACE + "signature_cocktail";
                    signature = true;
                }
            }
            case NONE -> {
                return Optional.empty();
            }
            default -> throw new IllegalStateException("Unexpected shaker timing band");
        }
        Optional<ItemStack> built = items.build(resultId, player);
        if (built.isEmpty()) {
            return Optional.empty();
        }
        ItemStack result = built.get();
        if (signature) {
            List<List<EffectSpec>> sources = ingredients.stream().map(this::effectsOfIngredient).toList();
            int color = averageColor(ingredients);
            result = items.withSignature(result, items.mergeEffects(sources), color);
        }
        return Optional.of(result);
    }

    private boolean takeShaker(Player player, BukkitFurniture furniture, ItemStack portable) {
        if (portable == null) {
            messages.send(player, "pack-missing");
            return true;
        }
        Location location = furniture.location().clone();
        items.give(player, portable);
        // Programmatic removal does not emit FurnitureBreakEvent, so the
        // split base/lid ItemDisplays must be removed explicitly on pickup.
        shakerVisuals.removeFurnitureVisuals(furniture);
        // Mute CE's own break sound: the explicit lantern break below is
        // the source's single pickup cue, not a doubled pair.
        CraftEngineFurniture.remove(furniture, player, false, false);
        location.getWorld().playSound(location, "minecraft:block.lantern.break",
                SoundCategory.BLOCKS, 1.0F, 1.0F);
        return true;
    }

    private Optional<ItemStack> shakerItem(BukkitFurniture furniture, Player context) {
        Item source = furniture.sourceItem();
        if (source instanceof BukkitItem bukkitItem && !source.isEmpty()) {
            ItemStack stack = bukkitItem.getBukkitItem().clone();
            stack.setAmount(1);
            return Optional.of(stack);
        }
        return items.build(SHAKER, context);
    }

    private void updateShakerSource(BukkitFurniture furniture, ItemStack shaker,
                                    List<ItemStack> ingredients, ItemStack result) {
        items.withShakerState(shaker, ingredients, result);
        shaker.setAmount(1);
        furniture.setSourceItem(BukkitAdaptor.adapt(shaker));
        furniture.setUnsaved();
    }

    private boolean pourPortableShaker(Player player, BukkitFurniture glassware, InteractionHand interactionHand) {
        EquipmentSlot hand = interactionHand == InteractionHand.OFF_HAND
                ? EquipmentSlot.OFF_HAND
                : EquipmentSlot.HAND;
        ItemStack shaker = handItem(player, hand);
        if (!items.id(shaker).equals(SHAKER)) {
            return false;
        }
        ItemStack result = items.shakerResult(shaker);
        if (result == null) {
            return false;
        }
        String resultId = items.id(result);
        Key furnitureId = Key.of(resultId);
        if (CraftEngineFurniture.byId(furnitureId) == null) {
            messages.send(player, "pack-missing");
            return true;
        }

        Location location = glassware.location().clone();
        CraftEngineFurniture.remove(glassware, player, false, false);
        BukkitFurniture placed = CraftEngineFurniture.place(location, furnitureId, "ground", true);
        if (placed == null) {
            CraftEngineFurniture.place(location, Key.of(EMPTY_GLASSWARE), "ground", false);
            messages.send(player, "pack-missing");
            return true;
        }
        ItemStack source = result.clone();
        source.setAmount(1);
        placed.setSourceItem(BukkitAdaptor.adapt(source));
        placed.refreshElements();
        placed.setUnsaved();
        items.withShakerState(shaker, List.of(), null);
        setHandItem(player, hand, shaker);
        location.getWorld().spawnParticle(Particle.ENTITY_EFFECT,
                location.clone().add(0, 0.5, 0), 20, 0.1, 0.1, 0.1, 0.5);
        location.getWorld().playSound(location, "minecraft:item.bottle.fill",
                SoundCategory.BLOCKS, 1.0F, 1.0F);
        return true;
    }

    private List<EffectSpec> effectsOfIngredient(ItemStack ingredient) {
        String id = items.id(ingredient);
        List<EffectSpec> result = new ArrayList<>(catalog.effects(id, items.brewLevel(ingredient)));
        if (ingredient.getItemMeta() instanceof PotionMeta potion) {
            for (PotionEffect effect : potion.getAllEffects()) {
                result.add(new EffectSpec(effect.getType().getKey().asString(),
                        ShakerSemantics.normalizePotionDurationTicks(effect.getDuration()),
                        effect.getAmplifier(), 1.0));
            }
        }
        return result;
    }

    private int averageColor(List<ItemStack> ingredients) {
        List<Integer> colors = new ArrayList<>();
        for (ItemStack ingredient : ingredients) {
            String itemId = items.id(ingredient);
            ShakerSemantics.ingredientColor(itemId, catalog.cocktailColor(itemId))
                    .ifPresent(colors::add);
        }
        return ShakerSemantics.mixIngredientColors(
                colors.stream().mapToInt(Integer::intValue).toArray());
    }

    boolean canTapExtract(BukkitFurniture barrel) {
        return tapExtractStatus(barrel) == BarrelSemantics.TapExtractStatus.READY;
    }

    BarrelSemantics.TapExtractStatus tapExtractStatus(BukkitFurniture barrel) {
        if (barrel == null || !barrel.isValid() || !barrel.id().equals(BARREL_KEY)) {
            return BarrelSemantics.TapExtractStatus.INVALID_CONTAINER;
        }
        FurnitureState state = new FurnitureState(barrel);
        String recipeId = state.string("barrel_recipe");
        boolean carrierRecipeValid = state.string("barrel_result") != null
                && (recipeId == null || recipeId.equals(NAMESPACE + "empty")
                || catalog.barrelById(recipeId).isPresent());
        return BarrelSemantics.tapExtractStatus(
                isBarrelBrewing(state), state.integer("barrel_output"), carrierRecipeValid);
    }

    boolean isTapOutputHot(BukkitFurniture barrel) {
        return barrel != null && barrel.isValid() && barrel.id().equals(BARREL_KEY)
                && TapSemantics.isHotBarrelOutput(
                        new FurnitureState(barrel).string("barrel_result"));
    }

    boolean transferTapOutput(BukkitFurniture barrel, Player context,
                              java.util.function.Predicate<ItemStack> receiver) {
        if (!canTapExtract(barrel)) {
            return false;
        }
        FurnitureState state = new FurnitureState(barrel);
        String resultId = state.string("barrel_result");
        int remaining = state.integer("barrel_output");
        Optional<ItemStack> built = items.build(resultId, context)
                .map(result -> items.withBrewLevel(result, brewLevel(state)));
        if (built.isEmpty() || !receiver.test(built.get())) {
            return false;
        }
        state.integer("barrel_output", remaining - 1);
        if (remaining == 1) {
            state.clear("barrel_recipe", "barrel_result", "barrel_output", "barrel_unit",
                    "barrel_started", "barrel_level", "barrel_time");
        }
        return true;
    }

    /**
     * Manual toggling now lives in the generated CE furniture events; the
     * {@code *_open} furniture variant is the single source of truth for a
     * lit incense. This setter remains for the redstone edge toggle and the
     * placement initializer, both of which write the same variant.
     */
    private void setIncenseActive(BukkitFurniture furniture, boolean active, boolean playSound) {
        String current = furniture.currentVariant().name();
        boolean wasActive = current.endsWith("_open");
        if (wasActive != active) {
            String base = wasActive ? current.substring(0, current.length() - 5) : current;
            furniture.setVariant(active ? base + "_open" : base, true);
        }
        if (playSound && wasActive != active) {
            furniture.location().getWorld().playSound(furniture.location(),
                    active ? "minecraft:block.stone_button.click_on"
                            : "minecraft:block.stone_button.click_off",
                    1.0F, 1.0F);
        }
    }

    private void cleanupFalling() {
        // Entities despawned mid-air never fire a landing; this low-frequency
        // cleanup is unrelated to furniture ticking and prevents stale UUIDs.
        falling.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
        stopFallingCleanupTaskIfIdle();
    }

    private void ensureFallingCleanupTask() {
        if (fallingCleanupTask == null && !falling.isEmpty()) {
            fallingCleanupTask = Bukkit.getScheduler().runTaskTimer(
                    plugin, this::cleanupFalling, 600L, 600L);
        }
    }

    private void stopFallingCleanupTaskIfIdle() {
        if (fallingCleanupTask != null && falling.isEmpty()) {
            fallingCleanupTask.cancel();
            fallingCleanupTask = null;
        }
    }

    private void setPressLandingEventsActive(boolean active) {
        if (active == pressLandingEventsActive) {
            return;
        }
        pressLandingEventsActive = active;
        if (active) {
            Bukkit.getPluginManager().registerEvents(pressLandingListener, plugin);
            return;
        }
        HandlerList.unregisterAll(pressLandingListener);
        falling.clear();
        recentLandings.clear();
        stopFallingCleanupTaskIfIdle();
    }

    private final class PressLandingListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onMove(PlayerMoveEvent event) {
            Player player = event.getPlayer();
            float fallDistance = player.getFallDistance();
            boolean trackingPlayer = fallDistance <= 0 && !falling.isEmpty()
                    && falling.containsKey(player.getUniqueId());
            if (!PressingTubSemantics.needsMovementInspection(
                    fallDistance, trackingPlayer)) {
                return;
            }
            Location to = event.getTo();
            if (to == null || (event.getFrom().getX() == to.getX()
                    && event.getFrom().getY() == to.getY()
                    && event.getFrom().getZ() == to.getZ())) {
                return;
            }
            trackPressLanding(player, to, fallDistance);
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onEntityMove(EntityMoveEvent event) {
            LivingEntity living = event.getEntity();
            if (living instanceof Player || !event.hasExplicitlyChangedPosition()) {
                return;
            }
            float fallDistance = living.getFallDistance();
            boolean trackingEntity = fallDistance <= 0 && !falling.isEmpty()
                    && falling.containsKey(living.getUniqueId());
            if (!PressingTubSemantics.needsMovementInspection(
                    fallDistance, trackingEntity)) {
                return;
            }
            trackPressLanding(living, event.getTo(), fallDistance);
        }
    }

    private void tickBarrel(BukkitFurniture furniture) {
        FurnitureState state = new FurnitureState(furniture);
        if (isBarrelOpen(furniture)) {
            return;
        }
        int level = state.integer("barrel_level");
        if (level >= 6) {
            return;
        }
        if (level >= 1) {
            BarrelSemantics.BrewState next = BarrelSemantics.advance(
                    level, state.integer("barrel_time"), state.integer("barrel_unit"));
            state.integer("barrel_level", next.level());
            state.integer("barrel_time", next.remainingTicks());
            return;
        }
        beginBrewing(furniture, state);
    }

    private void refreshStationVisuals(BukkitFurniture furniture) {
        if (furniture != null && furniture.isValid()) {
            furniture.refreshElements();
        }
    }

    private List<StationVisualFurnitureBehavior.Visual> stationVisuals(
            BukkitFurniture furniture) {
        if (furniture == null || !furniture.isValid()) {
            return List.of();
        }
        return switch (furniture.id().toString()) {
            case PRESSING_TUB -> pressingTubVisuals(furniture);
            case BARREL -> barrelVisuals(furniture);
            default -> List.of();
        };
    }

    /** Mirrors BarrelBlockEntityRender's open-only fluid and ingredient layer. */
    private List<StationVisualFurnitureBehavior.Visual> barrelVisuals(
            BukkitFurniture furniture) {
        if (!isBarrelOpen(furniture)) {
            return List.of();
        }
        FurnitureState state = new FurnitureState(furniture);
        List<StationVisualFurnitureBehavior.Visual> result = new ArrayList<>();
        long seed = blockPositionSeed(furniture.location());
        int globalIndex = 0;
        List<ItemStack> ingredients = barrelIngredients(state);
        for (int slot = 0; slot < ingredients.size(); slot++) {
            ItemStack ingredient = ingredients.get(slot);
            int visualCount = ingredient.isEmpty() ? 0 : ingredient.getAmount() / 2 + 1;
            for (int index = 0; index < visualCount; index++) {
                float x = stableRandom(seed, globalIndex, slot + 1) * 0.4F;
                float z = stableRandom(seed, globalIndex, slot + 2) * 0.4F;
                float y = globalIndex / 4 * 0.025F
                        + stableRandom(seed, globalIndex, slot + 3) * 0.05F;
                float yRotation = stableRandom(seed, globalIndex, slot + 4) * 5F;
                float zRotation = stableRandom(seed, globalIndex, slot + 5) * 360F;
                Quaternionf rotation = new Quaternionf()
                        .rotateX((float) Math.toRadians(-90))
                        .rotateY((float) Math.toRadians(-yRotation))
                        .rotateZ((float) Math.toRadians(-zRotation));
                ItemStack shown = ingredient.clone();
                shown.setAmount(1);
                Location origin = furniture.location();
                result.add(new StationVisualFurnitureBehavior.Visual(
                        BukkitAdaptor.adapt(shown),
                        origin.getX() + x, origin.getY() + 2.7 + y, origin.getZ() + z,
                        0, 0, 0.5F, rotation,
                        StationVisualFurnitureBehavior.ITEM_TRANSFORM_FIXED));
                globalIndex++;
            }
        }

        int amount = Math.max(0, state.integer("barrel_amount"));
        String fluid = state.string("barrel_fluid");
        if (amount > 0 && fluid != null) {
            items.build(NAMESPACE + "_render/barrel_fluid/" + path(fluid), null)
                    .ifPresent(renderItem -> {
                        renderItem.setAmount(1);
                        Location origin = furniture.location();
                        result.add(new StationVisualFurnitureBehavior.Visual(
                                BukkitAdaptor.adapt(renderItem),
                                origin.getX(),
                                origin.getY() + 2
                                        + Math.min(BARREL_CAPACITY, amount)
                                        / (float) BARREL_CAPACITY * 0.65F,
                                origin.getZ(),
                                0, 0, 1, new Quaternionf(),
                                StationVisualFurnitureBehavior.ITEM_TRANSFORM_NONE));
                    });
        }
        return result;
    }

    /** Mirrors PressingTubBlockEntityRender's item pile and fluid plane. */
    private List<StationVisualFurnitureBehavior.Visual> pressingTubVisuals(
            BukkitFurniture furniture) {
        FurnitureState state = new FurnitureState(furniture);
        List<StationVisualFurnitureBehavior.Visual> result = new ArrayList<>();
        ItemStack ingredient = pressingItem(state);
        int count = ingredient == null ? 0
                : Math.min(64, Math.max(0, state.integer("press_count")));
        if (ingredient != null && count > 0) {
            long seed = blockPositionSeed(furniture.location());
            boolean tilted = furniture.currentVariant().name().equals("wall");
            Location origin = furniture.location();
            for (int index = 0; index < count; index++) {
                float x = index % 4 % 2 == 0
                        ? -0.15F : 0.15F + stableRandom(seed, index, 1) * 0.0625F;
                float z = index % 4 / 2 == 0
                        ? -0.15F : 0.15F + stableRandom(seed, index, 2) * 0.0625F;
                float y = index / 4 * 0.03125F
                        + stableRandom(seed, index, 3) * 0.05F;
                float yRotation = stableRandom(seed, index, 4) * count / 10F;
                float zRotation = stableRandom(seed, index, 5) * 360F;
                double displayX;
                double displayY;
                double displayZ;
                float displayYaw;
                Quaternionf rotation;
                if (tilted) {
                    PressingTubSemantics.Point point = PressingTubSemantics.tiltSouth(
                            0.5 + x, 0.2 + y, 0.5 + z);
                    Vec3d worldPoint = furniture.getRelativePosition(new Vector3f(
                            (float) (point.x() - 0.5), 0, (float) -point.z()));
                    displayX = worldPoint.x;
                    displayY = origin.getY() - 0.5 + point.y();
                    displayZ = worldPoint.z;
                    displayYaw = origin.getYaw() + 180F;
                    rotation = new Quaternionf()
                            .rotateX((float) Math.toRadians(
                                    PressingTubSemantics.TILT_X_DEGREES))
                            .rotateX((float) Math.toRadians(
                                    PressingTubSemantics.ITEM_X_DEGREES))
                            .rotateY((float) Math.toRadians(-yRotation))
                            .rotateZ((float) Math.toRadians(-zRotation));
                } else {
                    displayX = origin.getX() + x;
                    displayY = origin.getY() + 0.2 + y;
                    displayZ = origin.getZ() + z;
                    displayYaw = 0;
                    rotation = new Quaternionf()
                            .rotateX((float) Math.toRadians(
                                    PressingTubSemantics.ITEM_X_DEGREES))
                            .rotateY((float) Math.toRadians(-yRotation))
                            .rotateZ((float) Math.toRadians(-zRotation));
                }
                ItemStack shown = ingredient.clone();
                shown.setAmount(1);
                result.add(new StationVisualFurnitureBehavior.Visual(
                        BukkitAdaptor.adapt(shown),
                        displayX, displayY, displayZ, displayYaw, 0, 0.5F, rotation,
                        StationVisualFurnitureBehavior.ITEM_TRANSFORM_FIXED));
            }
        }

        int amount = Math.max(0, state.integer("press_amount"));
        String fluid = state.string("press_fluid");
        if (amount > 0 && fluid != null) {
            items.build(NAMESPACE + "_render/pressing_fluid/" + path(fluid), null)
                    .ifPresent(renderItem -> {
                        renderItem.setAmount(1);
                        float y = 0.125F + Math.min(PRESS_CAPACITY, amount)
                                / (float) PRESS_CAPACITY * 0.25F;
                        Location origin = furniture.location();
                        double displayX = origin.getX();
                        double displayY = origin.getY() + y;
                        double displayZ = origin.getZ();
                        if (furniture.currentVariant().name().equals("wall")) {
                            Vec3d center = furniture.getRelativePosition(
                                    new Vector3f(0, 0, -0.5F));
                            displayX = center.x;
                            displayY = origin.getY() - 0.5 + y;
                            displayZ = center.z;
                        }
                        result.add(new StationVisualFurnitureBehavior.Visual(
                                BukkitAdaptor.adapt(renderItem),
                                displayX, displayY, displayZ,
                                0, 0, 1, new Quaternionf(),
                                StationVisualFurnitureBehavior.ITEM_TRANSFORM_NONE));
                    });
        }
        return result;
    }

    private static String path(String resourceId) {
        int separator = resourceId.indexOf(':');
        return separator < 0 ? resourceId : resourceId.substring(separator + 1);
    }

    private static long blockPositionSeed(Location location) {
        return ((long) location.getBlockX() & 0x3FFFFFFL) << 38
                | ((long) location.getBlockZ() & 0x3FFFFFFL) << 12
                | (long) location.getBlockY() & 0xFFFL;
    }

    private static float stableRandom(long positionSeed, int index, int channel) {
        long hash = positionSeed ^ (long) index * 0x9e3779b97f4a7c15L
                ^ (long) channel * 0x6c62272e07bb0142L;
        hash = (hash ^ hash >>> 30) * 0xbf58476d1ce4e5b9L;
        hash = (hash ^ hash >>> 27) * 0x94d049bb133111ebL;
        hash ^= hash >>> 31;
        return (float) (int) hash / (float) Integer.MAX_VALUE;
    }

    private Optional<String> fluidFromBucket(String bucketId) {
        if (bucketId.equals("minecraft:water_bucket")) {
            return Optional.of("minecraft:water");
        }
        if (bucketId.equals("minecraft:lava_bucket")) {
            return Optional.of("minecraft:lava");
        }
        return catalog.pressingByBucket(bucketId).map(PressingRecipe::fluid);
    }

    private Optional<String> bucketForFluid(String fluid) {
        if ("minecraft:water".equals(fluid)) {
            return Optional.of("minecraft:water_bucket");
        }
        if ("minecraft:lava".equals(fluid)) {
            return Optional.of("minecraft:lava_bucket");
        }
        return catalog.pressingByFluid(fluid == null ? "" : fluid).map(PressingRecipe::bucket);
    }

    private void giveAmount(Player player, String id, int amount) {
        for (int remaining = amount; remaining > 0; ) {
            int count = Math.min(64, remaining);
            Optional<ItemStack> built = items.build(id, player);
            if (built.isEmpty()) {
                return;
            }
            ItemStack stack = built.get();
            stack.setAmount(Math.min(count, stack.getMaxStackSize()));
            items.give(player, stack);
            remaining -= stack.getAmount();
        }
    }

    private static ItemStack pressingItem(FurnitureState state) {
        return state.item("press_item");
    }

    private void giveStored(Player player, ItemStack template, int amount) {
        for (int remaining = amount; remaining > 0; ) {
            ItemStack stack = template.clone();
            stack.setAmount(Math.min(remaining, stack.getMaxStackSize()));
            items.give(player, stack);
            remaining -= stack.getAmount();
        }
    }

    private static void dropStored(Location location, ItemStack template, int amount) {
        for (int remaining = amount; remaining > 0; ) {
            ItemStack stack = template.clone();
            stack.setAmount(Math.min(remaining, stack.getMaxStackSize()));
            location.getWorld().dropItemNaturally(location, stack);
            remaining -= stack.getAmount();
        }
    }

    private static ItemStack handItem(Player player, EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
    }

    private record PortableShakerUse(Player player, EquipmentSlot hand, int ticks) {
    }

    private static void setHandItem(Player player, EquipmentSlot hand, ItemStack stack) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(stack);
        } else {
            player.getInventory().setItemInMainHand(stack);
        }
    }

    private void dropAmount(Location location, String id, int amount, int batchSize, int brewLevel) {
        for (int remaining = amount; remaining > 0; ) {
            Optional<ItemStack> built = items.build(id, null);
            if (built.isEmpty()) {
                return;
            }
            ItemStack stack = items.withBrewLevel(built.get(), brewLevel);
            stack.setAmount(Math.min(Math.min(batchSize, remaining), stack.getMaxStackSize()));
            location.getWorld().dropItemNaturally(location, stack);
            remaining -= stack.getAmount();
        }
    }
}
