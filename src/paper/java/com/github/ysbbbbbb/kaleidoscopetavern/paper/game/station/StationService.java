package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.station;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.Messages;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.BarrelRecipe;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.EffectSpec;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.PressingRecipe;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.FurnitureState;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.LifecycleFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.TickingFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.pressing.PressingTubService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.shaker.ShakerItemBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.shaker.ShakerSemantics;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.shaker.ShakerVisualService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.tap.TapSemantics;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.visual.DisplayVisual;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.recipe.StationRecipeRegistry;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.recipe.StationRecipeSet.BarrelFallback;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.recipe.StationRecipeSet.ShakerSpecialResults;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.behavior.BlockItem;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockHitResult;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Implements the former Forge block-entity gameplay on CraftEngine furniture entities. */
public final class StationService implements Listener {
    private static final String NAMESPACE = "kaleidoscope_tavern:";
    private static final String BARREL = NAMESPACE + "barrel";
    private static final String SHAKER = NAMESPACE + "shaker";
    private static final String EMPTY_GLASSWARE = NAMESPACE + "empty_glassware";
    private static final Key BARREL_KEY = Key.of(BARREL);
    private static final int BARREL_CAPACITY = 4_000;
    private static final int MAX_BARREL_SLOTS = 4;
    private static final int MAX_BARREL_STACK = 16;
    // One logical ingredient pile is not shown as one display entity per item:
    // a bounded visual pool keeps station refresh packets cheap at high counts.
    private static final int MAX_STATION_ITEM_VISUALS = 16;
    private static final int MAX_STATION_MATERIAL_VISUALS = 4;

    /**
     * Paper transforms plugin classes lazily when they are first resolved.
     * Resolve the barrel state/semantics classes with StationService during
     * plugin enable so a loaded barrel's shouldSchedule callback never owns
     * that one-time Commodore/ClassLoader work.
     */
    @SuppressWarnings("unused")
    private static final Class<?>[] PRELINKED_BARREL_TICK_CLASSES = {
            FurnitureState.class,
            BarrelSemantics.class,
            BarrelSemantics.BrewState.class
    };

    private final JavaPlugin plugin;
    private final ContentCatalog catalog;
    private final StationRecipeRegistry recipes;
    private final ItemService items;
    private final Messages messages;
    private final ShakerVisualService shakerVisuals;
    private final PressingTubService pressingTubs;
    private final Map<UUID, PortableShakerUse> portableShakers = new HashMap<>();
    private final Set<UUID> pendingVanillaBucketEmpty = new HashSet<>();
    private BukkitTask portableShakerTask;
    private final StationVisualFurnitureBehavior.Handler stationVisualHandler =
            this::stationVisuals;
    private final StationInteractionFurnitureBehavior.Handler stationInteractionHandler =
            this::interactStation;
    private final ShakerItemBehavior.Handler shakerItemHandler =
            this::usePortableShaker;
    private final TickingFurnitureBehavior.Handler barrelTickingHandler =
            new TickingFurnitureBehavior.Handler() {
                @Override
                public void tick(BukkitFurniture furniture) {
                    tickBarrel(furniture);
                }

                @Override
                public Boolean tickAndScheduleDecision(BukkitFurniture furniture) {
                    return tickBarrel(furniture);
                }

                @Override
                public void onReady(BukkitFurniture furniture) {
                    syncBarrelState(furniture);
                }

                @Override
                public boolean shouldSchedule(BukkitFurniture furniture) {
                    return shouldTickBarrel(furniture);
                }
            };

    public StationService(JavaPlugin plugin, ContentCatalog catalog,
                          StationRecipeRegistry recipes, ItemService items,
                          Messages messages, ShakerVisualService shakerVisuals,
                          PressingTubService pressingTubs) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.recipes = recipes;
        this.items = items;
        this.messages = messages;
        this.shakerVisuals = shakerVisuals;
        this.pressingTubs = pressingTubs;
    }

    public void start() {
        StationInteractionFurnitureBehavior.bind(stationInteractionHandler);
        ShakerItemBehavior.bind(shakerItemHandler);
        StationVisualFurnitureBehavior.bind(stationVisualHandler);
        TickingFurnitureBehavior.bind(
                TickingFurnitureBehavior.Channel.BARREL, barrelTickingHandler);
    }

    public void stop() {
        StationInteractionFurnitureBehavior.unbind(stationInteractionHandler);
        ShakerItemBehavior.unbind(shakerItemHandler);
        StationVisualFurnitureBehavior.unbind(stationVisualHandler);
        TickingFurnitureBehavior.unbind(
                TickingFurnitureBehavior.Channel.BARREL, barrelTickingHandler);
        if (portableShakerTask != null) {
            portableShakerTask.cancel();
            portableShakerTask = null;
        }
        portableShakers.values().forEach(use -> shakerVisuals.endMix(use.player()));
        portableShakers.clear();
        pendingVanillaBucketEmpty.clear();
    }

    private InteractionResult interactStation(BukkitFurniture furniture,
                                              InteractEntityContext context) {
        String id = furniture.id().toString();
        if (PressingTubService.WALL_FURNITURE_ID.equals(furniture.id())) {
            return pressingTubs.interactFurniture(furniture, context);
        }
        if (!id.equals(EMPTY_GLASSWARE)
                && context.getHand() != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        Player player = (Player) context.getPlayer().platformPlayer();
        if (id.equals(BARREL) && TapSemantics.shouldDelegateBarrelTapPlacement(
                context.isSecondaryUseActive(),
                context.getItem().id().toString())) {
            return placeHeldTapBlockWithCraftEngine(furniture, context);
        }
        boolean handled = switch (id) {
            case BARREL -> {
                Vec3d click = context.getClickLocation();
                Location interactionPoint = new Location(
                        furniture.location().getWorld(), click.x, click.y, click.z);
                yield interactBarrel(player, furniture, interactionPoint);
            }
            case SHAKER -> interactShaker(player, furniture);
            case EMPTY_GLASSWARE -> pourPortableShaker(
                    player, furniture, context.getHand());
            // Incense and tap interactions live on their generated CE blocks.
            default -> false;
        };
        if (!handled) {
            return InteractionResult.PASS;
        }

        // Furniture interaction is dispatched from CE's packet listener on
        // the main-thread scheduler. By then vanilla may already have started
        // the held milk-bucket/potion use animation. A successful source block
        // interaction owns that same hand, so explicitly cancel the predicted
        // consume state; otherwise pouring grape juice into an open barrel can
        // visibly (and, under latency, functionally) turn into drinking it.
        EquipmentSlot usedHand = context.getHand() == InteractionHand.MAIN_HAND
                ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        if (player.hasActiveItem() && player.getActiveItemHand() == usedHand) {
            player.clearActiveItem();
        }
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    /**
     * Places the CE tap block at the source multiblock's canonical front-centre
     * cell. A furniture hit has no real support block for CE's ordinary block
     * fallback, so the barrel controller supplies the target grid position.
     */
    private static InteractionResult placeHeldTapBlockWithCraftEngine(
            BukkitFurniture barrel, InteractEntityContext context) {
        ItemBehavior behavior = context.getItem().getBehavior().orElse(null);
        BlockItem blockItem = behavior == null
                ? null : behavior.getFirst(BlockItem.class);
        if (!(blockItem instanceof ItemBehavior placementBehavior)) {
            return InteractionResult.FAIL;
        }

        Location origin = barrel.location();
        Vector horizontal = origin.getDirection().setY(0);
        int x;
        int z;
        if (Math.abs(horizontal.getX()) >= Math.abs(horizontal.getZ())) {
            x = horizontal.getX() < 0 ? -1 : 1;
            z = 0;
        } else {
            x = 0;
            z = horizontal.getZ() < 0 ? -1 : 1;
        }
        Direction facing = x < 0 ? Direction.WEST : x > 0 ? Direction.EAST
                : z < 0 ? Direction.NORTH : Direction.SOUTH;
        BlockPos target = new BlockPos(
                origin.getBlockX() + x * 2,
                origin.getBlockY() + 1,
                origin.getBlockZ() + z * 2);
        BlockHitResult hit = new BlockHitResult(
                new Vec3d(target.x() + 0.5, target.y() + 0.5, target.z() + 0.5),
                facing, target, false);
        InteractionResult result = placementBehavior.useOnBlock(new UseOnContext(
                context.getPlayer(), context.getHand(), context.getItem(), hit));
        // Sneaking skipped BarrelBlock.use in Forge. A rejected placement must
        // therefore not fall back to opening or querying the barrel.
        return result == InteractionResult.PASS ? InteractionResult.FAIL : result;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent event) {
        BukkitFurniture furniture = event.furniture();
        String id = furniture.id().toString();
        Location dropLocation = event.location().clone();
        if (PressingTubService.WALL_FURNITURE_ID.equals(furniture.id())) {
            pressingTubs.furnitureIngredientDrop(furniture)
                    .ifPresent(drop -> deferFurnitureBreak(event, () -> {
                        if (event.dropItems()) {
                            dropLocation.getWorld().dropItemNaturally(dropLocation, drop);
                        }
                    }));
            return;
        }
        switch (id) {
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

    /** Whether a station that consumes bucket fluids occupies this block.
     *  压榨桶是真 CE Block，交互成功后返回 SUCCESS_AND_CANCEL 会取消原版
     *  方块交互，不再需要这里的 Bukkit 方块扫描；该系统只为仍是 Furniture
     *  的 barrel 保留。 */
    private boolean fluidStationAt(Block block) {
        if (block == null) {
            return false;
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
        PortableShakerUse shakerUse = portableShakers.remove(
                event.getPlayer().getUniqueId());
        if (shakerUse != null) {
            shakerVisuals.endMix(event.getPlayer());
        }
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
        // The long consumable component exposes the native CE using_item /
        // use_cycle model clock. Always suppress vanilla consumption; only a
        // complete configured recipe (or the source-compatible three-item
        // fallback) starts explicitly below.
        if (items.shakerResult(shaker) != null) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        List<ItemStack> ingredients = items.shakerIngredients(shaker);
        if (!canMixShaker(ingredients)) {
            player.sendActionBar(net.kyori.adventure.text.Component.translatable(
                    "message.kaleidoscope_tavern.shaker.amount_too_low"));
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack current = handItem(player, hand);
            if (!player.isOnline() || !items.id(current).equals(SHAKER)
                    || items.shakerResult(current) != null
                    || !canMixShaker(items.shakerIngredients(current))) {
                return;
            }
            portableShakers.put(
                    player.getUniqueId(), new PortableShakerUse(player, hand, 0));
            ensurePortableShakerTask();
            shakerVisuals.beginMix(player);
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
        shakerVisuals.endMix(player);
        ItemStack shaker = handItem(player, hand);
        if (!items.id(shaker).equals(SHAKER) || items.shakerResult(shaker) != null) {
            return;
        }
        List<ItemStack> ingredients = items.shakerIngredients(shaker);
        if (!canMixShaker(ingredients)) {
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

    private boolean canMixShaker(List<ItemStack> ingredients) {
        return recipes.canMixShaker(ingredients.stream().map(items::id).toList());
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
                shakerVisuals.endMix(player);
                continue;
            }
            int ticks = use.ticks();
            shakerVisuals.updateMix(player, ticks);
            // The shaker's STAB swing_animation makes the vanilla arm thrust
            // each swing; every 4 ticks (duration 4) keeps a continuous
            // 5 Hz wave matching the source SHAKING frequency (2π/1.5 ticks).
            if (ticks % 4 == 0) {
                player.swingHand(use.hand());
            }
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
            setBarrelOpen(furniture, true, true, true);
            return true;
        }

        // With an open barrel, an empty-hand click on any non-centre top cell
        // closes the lid. The source's next 97-tick check decides whether a
        // recipe can start; the CE furniture ticker deliberately preserves that delay.
        if (hit == BarrelSemantics.Hit.TOP_RIM) {
            if (hand.isEmpty()) {
                setBarrelOpen(furniture, false, true, true);
                return true;
            }
            return false;
        }

        if (isBarrelBrewing(state)) {
            // This only repairs legacy/corrupt state where a brewing barrel
            // was persisted open; normal gameplay is caught by the branch
            // above and can never reach this combination.
            setBarrelOpen(furniture, false, false, true);
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
        // 绝大多数关闭桶直接返回，不访问 StateController / CompoundTag。
        if (!isBarrelOpen(furniture)) {
            return;
        }
        FurnitureState state = new FurnitureState(furniture);
        if (isBarrelBrewing(state)) {
            setBarrelOpen(furniture, false, false, false);
        }
    }

    private void setBarrelOpen(BukkitFurniture furniture, boolean open, boolean playSound,
                               boolean refreshTickSchedule) {
        if (furniture == null || !furniture.isValid() || furniture.bukkitEntity() == null) {
            return;
        }
        boolean variantChanged = furniture.setVariant(
                open ? "ground" : "ground_closed", true);
        if (refreshTickSchedule) {
            TickingFurnitureBehavior.refreshSchedule(
                    TickingFurnitureBehavior.Channel.BARREL, furniture);
        }
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
        return furniture.currentVariant().name().equals("ground");
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
        if (!recipes.canBeginBarrel(fluid, ingredientIds)) {
            return false;
        }
        Optional<BarrelRecipe> optional = recipes.barrel(fluid, ingredientIds);
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
            BarrelFallback fallback = recipes.fallback();
            result = fallback.result();
            recipeId = fallback.id();
            unitTicks = fallback.unitTicks();
            outputCount = fallback.output();
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
        List<String> currentIds = ingredients.stream().map(items::id).toList();
        if (!catalog.tag(NAMESPACE + "cocktail_ingredient").contains(handId)
                && !recipes.mayBeShakerIngredient(currentIds, handId)) {
            return false;
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
        boolean inheritIngredients = false;
        List<String> ids = ingredients.stream().map(items::id).toList();
        ShakerSpecialResults specialResults = recipes.specialResults();
        switch (ShakerSemantics.resultBand(ticks)) {
            case MYSTERY -> resultId = specialResults.mystery();
            case SIGNATURE -> {
                resultId = specialResults.signature();
                inheritIngredients = true;
            }
            case HAND_RECIPE -> {
                Optional<ContentCatalog.ShakerRecipe> recipe = recipes.shaker(ids);
                if (recipe.isPresent()) {
                    resultId = recipe.get().result();
                } else {
                    resultId = specialResults.signature();
                    inheritIngredients = true;
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
        if (inheritIngredients) {
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

    public BarrelSemantics.TapExtractStatus tapExtractStatus(BukkitFurniture barrel) {
        if (barrel == null || !barrel.isValid() || !barrel.id().equals(BARREL_KEY)) {
            return BarrelSemantics.TapExtractStatus.INVALID_CONTAINER;
        }
        FurnitureState state = new FurnitureState(barrel);
        String resultId = state.string("barrel_result");
        // A reload may remove or rename the source recipe while a barrel is
        // already aging. Its persisted result remains extractable; build()
        // below still rejects an output item that no longer exists.
        boolean carrierRecipeValid = resultId != null && !resultId.isBlank();
        return BarrelSemantics.tapExtractStatus(
                isBarrelBrewing(state), state.integer("barrel_output"), carrierRecipeValid);
    }

    public boolean isTapOutputHot(BukkitFurniture barrel) {
        return barrel != null && barrel.isValid() && barrel.id().equals(BARREL_KEY)
                && TapSemantics.isHotBarrelOutput(
                        new FurnitureState(barrel).string("barrel_result"));
    }

    /** Resolves an operator-configured tap color, then the generated item color tag. */
    public OptionalInt tapOutputColor(BukkitFurniture barrel) {
        if (barrel == null || !barrel.isValid() || !barrel.id().equals(BARREL_KEY)) {
            return OptionalInt.empty();
        }
        FurnitureState state = new FurnitureState(barrel);
        String recipeId = state.string("barrel_recipe");
        Optional<BarrelRecipe> recipe = recipes.barrelById(recipeId);
        if (recipe.isPresent() && recipe.get().tapColor().isPresent()) {
            return recipe.get().tapColor();
        }
        BarrelFallback fallback = recipes.fallback();
        if (fallback.id().equals(recipeId) && fallback.tapColor().isPresent()) {
            return fallback.tapColor();
        }
        return catalog.cocktailColor(state.string("barrel_result"));
    }

    public boolean transferTapOutput(BukkitFurniture barrel, Player context,
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

    private boolean tickBarrel(BukkitFurniture furniture) {
        if (isBarrelOpen(furniture)) {
            return false;
        }
        FurnitureState state = new FurnitureState(furniture);
        int level = state.integer("barrel_level");
        if (level >= 6) {
            return false;
        }
        if (level >= 1) {
            BarrelSemantics.BrewState next = BarrelSemantics.advance(
                    level, state.integer("barrel_time"), state.integer("barrel_unit"));
            state.integer("barrel_level", next.level());
            state.integer("barrel_time", next.remainingTicks());
            return next.level() < 6;
        }
        return beginBrewing(furniture, state);
    }

    private boolean shouldTickBarrel(BukkitFurniture furniture) {
        if (furniture == null || isBarrelOpen(furniture)) {
            return false;
        }
        FurnitureState state = new FurnitureState(furniture);
        int level = state.integer("barrel_level");
        int amount = state.integer("barrel_amount");
        boolean inputsReady = false;
        if (level <= 0 && amount >= BARREL_CAPACITY) {
            String fluid = state.string("barrel_fluid", "");
            List<String> ingredientIds = barrelIngredients(state).stream()
                    .map(items::id)
                    .toList();
            inputsReady = recipes.canBeginBarrel(fluid, ingredientIds);
        }
        return BarrelSemantics.needsTick(false,
                level, amount, BARREL_CAPACITY, inputsReady);
    }

    private void refreshStationVisuals(BukkitFurniture furniture) {
        if (furniture != null && furniture.isValid()) {
            StationVisualFurnitureBehavior.refresh(furniture);
        }
    }

    private List<DisplayVisual> stationVisuals(
            BukkitFurniture furniture, int limit) {
        if (furniture == null || !furniture.isValid()) {
            return List.of();
        }
        if (PressingTubService.WALL_FURNITURE_ID.equals(furniture.id())) {
            return pressingTubs.furnitureVisuals(furniture, limit);
        }
        return BARREL.equals(furniture.id().toString())
                ? barrelVisuals(furniture, limit) : List.of();
    }

    /** Mirrors BarrelBlockEntityRender's open-only fluid and ingredient layer. */
    private List<DisplayVisual> barrelVisuals(
            BukkitFurniture furniture, int limit) {
        if (!isBarrelOpen(furniture)) {
            return List.of();
        }
        FurnitureState state = new FurnitureState(furniture);
        int amount = Math.max(0, state.integer("barrel_amount"));
        String fluid = state.string("barrel_fluid");
        boolean hasFluid = amount > 0 && fluid != null;
        int itemLimit = Math.max(0, limit - (hasFluid ? 1 : 0));
        List<DisplayVisual> result = new ArrayList<>(
                Math.min(limit, MAX_STATION_ITEM_VISUALS + 1));
        long seed = blockPositionSeed(furniture.location());
        int globalIndex = 0;
        List<ItemStack> ingredients = barrelIngredients(state);
        for (int slot = 0; slot < ingredients.size() && globalIndex < itemLimit; slot++) {
            ItemStack ingredient = ingredients.get(slot);
            int visualCount = ingredient.isEmpty() ? 0 : ingredient.getAmount() / 2 + 1;
            if (visualCount == 0) {
                continue;
            }
            int perMaterialLimit = Math.min(
                    MAX_STATION_MATERIAL_VISUALS,
                    Math.min(visualCount, itemLimit - globalIndex));
            // Adapt each material once; every visual copy shares the same Item.
            ItemStack shown = ingredient.clone();
            shown.setAmount(1);
            Item displayItem = BukkitAdaptor.adapt(shown);
            for (int index = 0; index < perMaterialLimit; index++) {
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
                Location origin = furniture.location();
                result.add(DisplayVisual.of(
                        displayItem,
                        origin.getX() + x, origin.getY() + 2.7 + y, origin.getZ() + z,
                        0, 0, 0.5F, rotation,
                        DisplayVisual.ITEM_TRANSFORM_FIXED));
                globalIndex++;
            }
        }

        if (hasFluid) {
            items.buildVisual(NAMESPACE + "_render/barrel_fluid/" + path(fluid))
                    .ifPresent(renderItem -> {
                        renderItem.setAmount(1);
                        Location origin = furniture.location();
                        result.add(DisplayVisual.of(
                                BukkitAdaptor.adapt(renderItem),
                                origin.getX(),
                                origin.getY() + 2
                                        + Math.min(BARREL_CAPACITY, amount)
                                        / (float) BARREL_CAPACITY * 0.65F,
                                origin.getZ(),
                                0, 0, 1, new Quaternionf(),
                                DisplayVisual.ITEM_TRANSFORM_NONE));
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
