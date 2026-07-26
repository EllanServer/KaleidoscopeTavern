package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.Messages;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.BarrelRecipe;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.EffectSpec;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.PressingRecipe;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import io.papermc.paper.event.entity.EntityMoveEvent;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.Vec3d;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Implements the former Forge block-entity gameplay on CraftEngine furniture entities. */
public final class StationService implements Listener {
    private static final String NAMESPACE = "kaleidoscope_tavern:";
    private static final String KEY_NAMESPACE = "kaleidoscope_tavern";
    private static final String PRESSING_TUB = NAMESPACE + "pressing_tub";
    private static final String BARREL = NAMESPACE + "barrel";
    private static final String SHAKER = NAMESPACE + "shaker";
    private static final String EMPTY_GLASSWARE = NAMESPACE + "empty_glassware";
    private static final Key PRESSING_TUB_KEY = Key.of(PRESSING_TUB);
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
    private final NamespacedKey pressVisualOwnerKey;
    private final NamespacedKey pressVisualRoleKey;
    private final NamespacedKey pressVisualIndexKey;
    private final NamespacedKey barrelVisualOwnerKey;
    private final NamespacedKey barrelVisualRoleKey;
    private final NamespacedKey barrelVisualIndexKey;
    private final Map<UUID, Float> falling = new HashMap<>();
    private long barrelTickCounter;
    private final Map<UUID, Boolean> recentLandings = new HashMap<>();
    private final Map<UUID, PortableShakerUse> portableShakers = new HashMap<>();
    private final Set<UUID> loadedIncense = new HashSet<>();
    private final Set<UUID> loadedBarrels = new HashSet<>();
    private final Set<UUID> pendingVanillaBucketEmpty = new HashSet<>();
    private BukkitTask incenseTask;
    private BukkitTask incenseRedstoneTask;
    private BukkitTask portableShakerTask;
    private BukkitTask barrelTask;

    public StationService(JavaPlugin plugin, ContentCatalog catalog, ItemService items,
                          Messages messages, ShakerVisualService shakerVisuals) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.items = items;
        this.messages = messages;
        this.shakerVisuals = shakerVisuals;
        this.pressVisualOwnerKey = new NamespacedKey(plugin, "press_visual_owner");
        this.pressVisualRoleKey = new NamespacedKey(plugin, "press_visual_role");
        this.pressVisualIndexKey = new NamespacedKey(plugin, "press_visual_index");
        this.barrelVisualOwnerKey = new NamespacedKey(plugin, "barrel_visual_owner");
        this.barrelVisualRoleKey = new NamespacedKey(plugin, "barrel_visual_role");
        this.barrelVisualIndexKey = new NamespacedKey(plugin, "barrel_visual_index");
    }

    public void start() {
        incenseTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pulseIncense, 120L, 120L);
        incenseRedstoneTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pollIncenseRedstone, 1L, 1L);
        portableShakerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPortableShakers, 1L, 1L);
        barrelTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickBarrels, 1L, 1L);
        Bukkit.getScheduler().runTask(plugin, this::bootstrapIncense);
        Bukkit.getScheduler().runTask(plugin, this::bootstrapPressVisuals);
        Bukkit.getScheduler().runTask(plugin, this::bootstrapBarrels);
    }

    public void stop() {
        if (incenseTask != null) {
            incenseTask.cancel();
            incenseTask = null;
        }
        if (incenseRedstoneTask != null) {
            incenseRedstoneTask.cancel();
            incenseRedstoneTask = null;
        }
        if (portableShakerTask != null) {
            portableShakerTask.cancel();
            portableShakerTask = null;
        }
        if (barrelTask != null) {
            barrelTask.cancel();
            barrelTask = null;
        }
        falling.clear();
        recentLandings.clear();
        portableShakers.clear();
        loadedIncense.clear();
        loadedBarrels.clear();
        pendingVanillaBucketEmpty.clear();
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFurnitureInteract(FurnitureInteractEvent event) {
        String id = event.furniture().id().toString();
        if (!id.equals(PRESSING_TUB) && !id.equals(EMPTY_GLASSWARE)
                && event.hand() != InteractionHand.MAIN_HAND) {
            return;
        }
        boolean handled = switch (id) {
            case PRESSING_TUB -> interactPress(event.player(), event.furniture(), event.hand());
            case BARREL -> interactBarrel(event.player(), event.furniture(), event.interactionPoint());
            case SHAKER -> interactShaker(event.player(), event.furniture());
            case EMPTY_GLASSWARE -> pourPortableShaker(event.player(), event.furniture(), event.hand());
            // Incense toggling lives in the generated CE furniture events.
            default -> false;
        };
        if (handled) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurniturePlace(FurniturePlaceEvent event) {
        String id = event.furniture().id().toString();
        if (id.equals(PRESSING_TUB)) {
            Bukkit.getScheduler().runTask(plugin, () -> refreshPressVisuals(event.furniture()));
        } else if (id.equals(BARREL)) {
            FurnitureState state = new FurnitureState(plugin, event.furniture());
            state.bool("barrel_initialized", true);
            state.bool("barrel_open", true);
            loadedBarrels.add(event.furniture().bukkitEntity().getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> setBarrelOpen(event.furniture(), true, false));
        } else if (id.startsWith(NAMESPACE) && id.endsWith("_incense")) {
            loadedIncense.add(event.furniture().bukkitEntity().getUniqueId());
            initializeIncense(event.furniture());
        } else if (id.equals(SHAKER)) {
            loadPortableShaker(event.furniture());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent event) {
        BukkitFurniture furniture = event.furniture();
        String id = furniture.id().toString();
        Location dropLocation = event.location().clone();
        switch (id) {
            case PRESSING_TUB -> {
                FurnitureState state = new FurnitureState(plugin, furniture);
                List<ItemDisplay> visuals = currentPressVisuals(furniture, state);
                ItemStack storedIngredient = pressingItem(state);
                ItemStack ingredient = storedIngredient == null ? null : storedIngredient.clone();
                int ingredientCount = ingredient == null ? 0 : state.integer("press_count");
                deferFurnitureBreak(event, () -> {
                    visuals.forEach(Entity::remove);
                    if (event.dropItems() && ingredient != null) {
                        dropStored(dropLocation, ingredient, ingredientCount);
                    }
                });
                // PressingTubBlock#getDrops restores only the ingredient;
                // finished tank fluid is deliberately lost on break.
            }
            case BARREL -> {
                Entity entity = furniture.bukkitEntity();
                UUID furnitureId = entity == null ? null : entity.getUniqueId();
                FurnitureState state = new FurnitureState(plugin, furniture);
                List<ItemDisplay> visuals = currentBarrelVisuals(furniture, state);
                deferFurnitureBreak(event, () -> {
                    if (furnitureId != null) {
                        loadedBarrels.remove(furnitureId);
                    }
                    visuals.forEach(Entity::remove);
                });
                // Forge only drops the barrel itself. Its internal ingredients, fluid and
                // finished output are deliberately lost when the multiblock is destroyed.
            }
            case SHAKER -> {
                Optional<ItemStack> shaker = event.dropItems()
                        ? buildShakerItem(new FurnitureState(plugin, furniture), event.player())
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
                if (id.startsWith(NAMESPACE) && id.endsWith("_incense")) {
                    Entity entity = furniture.bukkitEntity();
                    if (entity != null) {
                        UUID furnitureId = entity.getUniqueId();
                        deferFurnitureBreak(event, () -> loadedIncense.remove(furnitureId));
                    }
                }
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

    private List<ItemDisplay> currentPressVisuals(BukkitFurniture furniture, FurnitureState state) {
        if (furniture.bukkitEntity() == null) {
            return List.of();
        }
        List<ItemDisplay> displays = new ArrayList<>();
        displays.addAll(pressVisuals(furniture, state, "item", "press_item_visuals"));
        displays.addAll(pressVisuals(furniture, state, "fluid", "press_fluid_visual"));
        return List.copyOf(displays);
    }

    private List<ItemDisplay> currentBarrelVisuals(BukkitFurniture furniture, FurnitureState state) {
        if (furniture.bukkitEntity() == null) {
            return List.of();
        }
        List<ItemDisplay> displays = new ArrayList<>();
        displays.addAll(barrelVisuals(furniture, state, "item", "barrel_item_visuals"));
        displays.addAll(barrelVisuals(furniture, state, "fluid", "barrel_fluid_visual"));
        return List.copyOf(displays);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || (event.getFrom().getX() == event.getTo().getX()
                && event.getFrom().getY() == event.getTo().getY()
                && event.getFrom().getZ() == event.getTo().getZ())) {
            return;
        }
        trackPressLanding(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityMove(EntityMoveEvent event) {
        if (event.getEntity() instanceof Player || !event.hasExplicitlyChangedPosition()) {
            return;
        }
        trackPressLanding(event.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL
                || !(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        float fallDistance = Math.max(living.getFallDistance(),
                falling.getOrDefault(living.getUniqueId(), 0F));
        falling.remove(living.getUniqueId());
        if (fallDistance >= 0.5F && handlePressLanding(living, fallDistance)) {
            event.setCancelled(true);
        }
    }

    /**
     * Stops a bucket from also emptying into the world when it feeds a station.
     *
     * <p>Filling a barrel or pressing tub is driven by FurnitureInteractEvent,
     * which only governs CraftEngine's own interaction flow. Vanilla still runs
     * its bucket placement for the same right-click, so the fluid was recorded on
     * the furniture *and* spilled as a real block beside it. Interaction hitboxes
     * are not blocks, so vanilla picks the air the player aimed through.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        // FurnitureInteractEvent already consumed this bucket into the station.
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
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        // The barrel's hitbox is a 3x3x3 around its origin, so scan far enough to
        // find a meta entity two blocks away and then test the real footprint.
        for (Entity entity : center.getWorld().getNearbyEntities(center, 3, 3, 3,
                candidate -> candidate instanceof ItemDisplay
                        && CraftEngineFurniture.isFurniture(candidate))) {
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture == null) {
                continue;
            }
            Block origin = furniture.location().getBlock();
            if (!origin.getWorld().equals(block.getWorld())) {
                continue;
            }
            int dx = block.getX() - origin.getX();
            int dy = block.getY() - origin.getY();
            int dz = block.getZ() - origin.getZ();
            boolean covered = switch (furniture.id().toString()) {
                case BARREL -> Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dy >= 0 && dy <= 2;
                case PRESSING_TUB -> dx == 0 && dz == 0 && dy == 0;
                default -> false;
            };
            if (covered) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        falling.remove(event.getPlayer().getUniqueId());
        recentLandings.remove(event.getPlayer().getUniqueId());
        portableShakers.remove(event.getPlayer().getUniqueId());
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUsePortableShaker(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR || event.getHand() == null
                || event.getItem() == null || !items.id(event.getItem()).equals(SHAKER)) {
            return;
        }
        // The migrated shaker uses a long consumable component solely to
        // expose the original brush-style use animation. Always suppress the
        // vanilla item use; only a valid three-ingredient shaker is started
        // explicitly below.
        event.setCancelled(true);
        ItemStack shaker = event.getItem();
        if (items.shakerResult(shaker) != null) {
            return;
        }
        int ingredientCount = items.shakerIngredients(shaker).size();
        if (ingredientCount != 3) {
            if (ingredientCount > 0) {
                event.getPlayer().sendActionBar(net.kyori.adventure.text.Component.translatable(
                        "message.kaleidoscope_tavern.shaker.amount_too_low"));
            }
            return;
        }

        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack current = handItem(player, hand);
            if (!player.isOnline() || !items.id(current).equals(SHAKER)
                    || items.shakerResult(current) != null
                    || items.shakerIngredients(current).size() != 3) {
                return;
            }
            portableShakers.put(player.getUniqueId(), new PortableShakerUse(hand, 0));
            player.startUsingItem(hand);
            player.setActiveItemRemainingTime(72_000);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStopPortableShaker(PlayerStopUsingItemEvent event) {
        Player player = event.getPlayer();
        PortableShakerUse use = portableShakers.remove(player.getUniqueId());
        if (use == null) {
            return;
        }
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
        if (portableShakers.isEmpty()) {
            return;
        }
        for (UUID playerId : new ArrayList<>(portableShakers.keySet())) {
            PortableShakerUse use = portableShakers.get(playerId);
            Player player = Bukkit.getPlayer(playerId);
            if (use == null || player == null || !player.isOnline()
                    || !player.isHandRaised() || !items.id(handItem(player, use.hand())).equals(SHAKER)) {
                portableShakers.remove(playerId);
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
                portableShakers.remove(playerId);
                player.clearActiveItem();
                finishPortableShaker(player, use.hand(), ticks);
            } else {
                portableShakers.put(playerId, new PortableShakerUse(use.hand(), ticks + 1));
            }
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (!(entity instanceof ItemDisplay) || !CraftEngineFurniture.isFurniture(entity)) {
                continue;
            }
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture != null && furniture.id().toString().equals(PRESSING_TUB)) {
                Bukkit.getScheduler().runTask(plugin, () -> refreshPressVisuals(furniture));
            } else if (furniture != null && furniture.id().toString().equals(BARREL)) {
                loadedBarrels.add(entity.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> syncBarrelState(furniture));
            } else if (isIncense(furniture)) {
                loadedIncense.add(entity.getUniqueId());
                initializeIncense(furniture);
            }
        }
    }

    private boolean interactPress(Player player, BukkitFurniture furniture, InteractionHand usedHand) {
        FurnitureState state = new FurnitureState(plugin, furniture);
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
                refreshPressVisuals(furniture);
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
                refreshPressVisuals(furniture);
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
                refreshPressVisuals(furniture);
                return true;
            }
        }
        return false;
    }

    private boolean pressOne(BukkitFurniture furniture) {
        if (!furniture.currentVariant().name().equals("ground")) {
            return false;
        }
        FurnitureState state = new FurnitureState(plugin, furniture);
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
        refreshPressVisuals(furniture);
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
        refreshPressVisuals(furniture);
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

    private void trackPressLanding(LivingEntity living) {
        UUID id = living.getUniqueId();
        if (living.getFallDistance() > 0) {
            falling.merge(id, living.getFallDistance(), Math::max);
            return;
        }
        float fallDistance = falling.getOrDefault(id, 0F);
        falling.remove(id);
        if (fallDistance >= 0.5F) {
            handlePressLanding(living, fallDistance);
        }
    }

    private boolean handlePressLanding(LivingEntity living, float fallDistance) {
        UUID id = living.getUniqueId();
        Boolean recent = recentLandings.get(id);
        if (recent != null) {
            return recent;
        }
        boolean pressed = pressingTubBelow(living.getLocation())
                .map(this::pressOne)
                .orElse(false);
        recentLandings.put(id, pressed);
        Bukkit.getScheduler().runTaskLater(plugin, () -> recentLandings.remove(id), 2L);
        return pressed;
    }

    private boolean interactBarrel(Player player, BukkitFurniture furniture, Location interactionPoint) {
        FurnitureState state = new FurnitureState(plugin, furniture);
        boolean open = initializeBarrelState(furniture, state);
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
        // recipe can start; tickBarrels deliberately preserves that delay.
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
                refreshBarrelVisuals(furniture);
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
                refreshBarrelVisuals(furniture);
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
                refreshBarrelVisuals(furniture);
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
        refreshBarrelVisuals(furniture);
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

    private boolean initializeBarrelState(BukkitFurniture furniture, FurnitureState state) {
        if (!state.bool("barrel_initialized")) {
            // BarrelBlockEntity.open defaults to true; an already-started
            // brewing state is closed when first observed.
            boolean open = !isBarrelBrewing(state);
            state.bool("barrel_initialized", true);
            state.bool("barrel_open", open);
            setBarrelOpen(furniture, open, false);
        }
        return state.bool("barrel_open");
    }

    private void syncBarrelState(BukkitFurniture furniture) {
        if (furniture == null || !furniture.isValid() || furniture.bukkitEntity() == null) {
            return;
        }
        FurnitureState state = new FurnitureState(plugin, furniture);
        boolean open = initializeBarrelState(furniture, state);
        if (isBarrelBrewing(state) && open) {
            open = false;
            state.bool("barrel_open", false);
        }
        setBarrelOpen(furniture, open, false);
    }

    private void setBarrelOpen(BukkitFurniture furniture, boolean open, boolean playSound) {
        if (furniture == null || !furniture.isValid() || furniture.bukkitEntity() == null) {
            return;
        }
        FurnitureState state = new FurnitureState(plugin, furniture);
        state.bool("barrel_initialized", true);
        state.bool("barrel_open", open);
        furniture.setVariant(open ? "ground_open" : "ground", true);
        furniture.setUnsaved();
        if (playSound) {
            // The Forge implementation intentionally uses BARREL_OPEN for
            // both transitions and plays it at the lid, two blocks above.
            furniture.location().getWorld().playSound(
                    furniture.location().clone().add(0, 2, 0),
                    "minecraft:block.barrel.open", SoundCategory.BLOCKS, 1.0F, 1.0F);
        }
        refreshBarrelVisuals(furniture);
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
        FurnitureState state = new FurnitureState(plugin, furniture);
        ItemStack hand = player.getInventory().getItemInMainHand();
        String handId = items.id(hand);
        // ShakerBlock only accepts ingredients while placed. Empty hand picks
        // up the entire shaker; mixing itself belongs exclusively to the item.
        if (hand.isEmpty()) {
            return takeShaker(player, furniture, state);
        }
        if (!catalog.tag(NAMESPACE + "cocktail_ingredient").contains(handId)) {
            return false;
        }
        List<ItemStack> ingredients = state.items("shaker_ingredients");
        if (state.item("shaker_result") != null || ingredients.size() >= 3) {
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
        state.items("shaker_ingredients", ingredients);
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

    private boolean takeShaker(Player player, BukkitFurniture furniture, FurnitureState state) {
        Optional<ItemStack> portable = buildShakerItem(state, player);
        if (portable.isEmpty()) {
            messages.send(player, "pack-missing");
            return true;
        }
        Location location = furniture.location().clone();
        items.give(player, portable.get());
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

    private Optional<ItemStack> buildShakerItem(FurnitureState state, Player context) {
        List<ItemStack> ingredients = state.items("shaker_ingredients");
        ItemStack result = state.item("shaker_result");
        return items.build(SHAKER, context)
                .map(stack -> items.withShakerState(stack, ingredients, result));
    }

    private void loadPortableShaker(BukkitFurniture furniture) {
        Item source = furniture.sourceItem();
        if (!(source instanceof BukkitItem bukkitItem) || source.isEmpty()) {
            return;
        }
        ItemStack stack = bukkitItem.getBukkitItem();
        List<ItemStack> ingredients = items.shakerIngredients(stack);
        ItemStack result = items.shakerResult(stack);
        if (ingredients.isEmpty() && result == null) {
            return;
        }
        FurnitureState state = new FurnitureState(plugin, furniture);
        state.items("shaker_ingredients", ingredients);
        state.item("shaker_result", result);
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
        new FurnitureState(plugin, placed).items("bottle_items", List.of(source));
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
        FurnitureState state = new FurnitureState(plugin, barrel);
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
                        new FurnitureState(plugin, barrel).string("barrel_result"));
    }

    boolean transferTapOutput(BukkitFurniture barrel, Player context,
                              java.util.function.Predicate<ItemStack> receiver) {
        if (!canTapExtract(barrel)) {
            return false;
        }
        FurnitureState state = new FurnitureState(plugin, barrel);
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
        String base = current.endsWith("_open") ? current.substring(0, current.length() - 5) : current;
        furniture.setVariant(active ? base + "_open" : base, true);
        furniture.setUnsaved();
        if (playSound) {
            furniture.location().getWorld().playSound(furniture.location(),
                    active ? "minecraft:block.stone_button.click_on"
                            : "minecraft:block.stone_button.click_off",
                    1.0F, 1.0F);
        }
    }

    private static boolean isIncenseLit(BukkitFurniture furniture) {
        return furniture.currentVariant().name().endsWith("_open");
    }

    private void pulseIncense() {
        List<UUID> invalid = null;
        for (UUID uuid : loadedIncense) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity == null || !entity.isValid()) {
                invalid = addInvalid(invalid, uuid);
                continue;
            }
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (!isIncense(furniture)) {
                invalid = addInvalid(invalid, uuid);
                continue;
            }
            if (!isIncenseLit(furniture)) {
                continue;
            }
            Location center = furniture.location();
            DamageSource source = DamageSource.builder(DamageType.MAGIC)
                    .withDamageLocation(center)
                    .build();
            for (Entity nearby : center.getWorld().getNearbyEntities(center, 32, 32, 32,
                    candidate -> candidate instanceof LivingEntity)) {
                LivingEntity living = (LivingEntity) nearby;
                if (living.isValid() && !living.isDead()
                        && Tag.ENTITY_TYPES_UNDEAD.isTagged(living.getType())) {
                    living.damage(1.0, source);
                    if (living instanceof ZombieVillager zombie && zombie.getHealth() <= 1.0) {
                        zombie.setConversionPlayer(null);
                        zombie.setConversionTime(60);
                    }
                }
            }
        }
        if (invalid != null) {
            loadedIncense.removeAll(invalid);
        }
    }

    private void pollIncenseRedstone() {
        List<UUID> invalid = null;
        for (UUID uuid : loadedIncense) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity == null || !entity.isValid()) {
                invalid = addInvalid(invalid, uuid);
                continue;
            }
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (!isIncense(furniture)) {
                invalid = addInvalid(invalid, uuid);
                continue;
            }
            initializeIncense(furniture);
            boolean powered = isIncensePowered(furniture);
            FurnitureState state = new FurnitureState(plugin, furniture);
            boolean wasPowered = state.bool("incense_powered");
            if (powered != wasPowered) {
                state.bool("incense_powered", powered);
                setIncenseActive(furniture, powered, true);
            }
        }
        if (invalid != null) {
            loadedIncense.removeAll(invalid);
        }
    }

    private void bootstrapIncense() {
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (!CraftEngineFurniture.isFurniture(display)) {
                    continue;
                }
                BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(display);
                if (isIncense(furniture)) {
                    loadedIncense.add(display.getUniqueId());
                    initializeIncense(furniture);
                }
            }
        }
    }

    /** IncenseBlock.getStateForPlacement copies redstone power without playing a toggle sound. */
    private void initializeIncense(BukkitFurniture furniture) {
        if (!isIncense(furniture)) {
            return;
        }
        FurnitureState state = new FurnitureState(plugin, furniture);
        if (state.bool("incense_initialized")) {
            return;
        }
        boolean powered = isIncensePowered(furniture);
        state.bool("incense_initialized", true);
        state.bool("incense_powered", powered);
        setIncenseActive(furniture, powered, false);
    }

    private static boolean isIncensePowered(BukkitFurniture furniture) {
        Block block = furniture.location().getBlock();
        return block.isBlockPowered() || block.isBlockIndirectlyPowered();
    }

    /** Restores the source default/open lid variant after plugin or CE reload. */
    private void bootstrapBarrels() {
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (!CraftEngineFurniture.isFurniture(display)) {
                    continue;
                }
                BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(display);
                if (furniture != null && furniture.id().equals(BARREL_KEY)) {
                    loadedBarrels.add(display.getUniqueId());
                    syncBarrelState(furniture);
                }
            }
        }
    }

    /** Runs the retained Forge barrel state machine only while its chunk/entity is loaded. */
    private void tickBarrels() {
        barrelTickCounter++;
        if (barrelTickCounter % 600 == 0) {
            // Entities despawned mid-air never fire a landing; sweep the
            // fall-tracking map so it cannot accumulate dead UUIDs.
            falling.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
        }
        List<UUID> invalid = null;
        for (UUID uuid : loadedBarrels) {
            // The 97-tick phase gate runs before any entity resolution so the
            // 96 quiet ticks per barrel cost a hash and a modulo only.
            long offset = uuid.hashCode() % BarrelSemantics.CHECK_INTERVAL
                    + BarrelSemantics.CHECK_INTERVAL;
            if ((barrelTickCounter + offset) % BarrelSemantics.CHECK_INTERVAL != 0) {
                continue;
            }
            Entity entity = Bukkit.getEntity(uuid);
            if (!(entity instanceof ItemDisplay) || !entity.isValid()
                    || !CraftEngineFurniture.isFurniture(entity)) {
                invalid = addInvalid(invalid, uuid);
                continue;
            }
            // BlockEntityTicker pauses in lazy chunks; brewing does the same.
            if (entity.getChunk().getLoadLevel() != Chunk.LoadLevel.ENTITY_TICKING) {
                continue;
            }
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture == null || !furniture.id().equals(BARREL_KEY)) {
                invalid = addInvalid(invalid, uuid);
                continue;
            }
            tickBarrel(furniture);
        }
        if (invalid != null) {
            loadedBarrels.removeAll(invalid);
        }
    }

    private void tickBarrel(BukkitFurniture furniture) {
        FurnitureState state = new FurnitureState(plugin, furniture);
        if (state.bool("barrel_open")) {
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

    /** Mirrors BarrelBlockEntityRender's open-only fluid and ingredient layer. */
    private void refreshBarrelVisuals(BukkitFurniture furniture) {
        if (furniture == null || !furniture.isValid() || furniture.bukkitEntity() == null) {
            return;
        }
        FurnitureState state = new FurnitureState(plugin, furniture);
        if (!state.bool("barrel_open")) {
            removeBarrelVisuals(furniture);
            return;
        }

        List<ItemStack> renderedItems = new ArrayList<>();
        List<Integer> renderedSlots = new ArrayList<>();
        List<ItemStack> ingredients = barrelIngredients(state);
        for (int slot = 0; slot < ingredients.size(); slot++) {
            ItemStack ingredient = ingredients.get(slot);
            int visualCount = ingredient.isEmpty() ? 0 : ingredient.getAmount() / 2 + 1;
            for (int index = 0; index < visualCount; index++) {
                renderedItems.add(ingredient);
                renderedSlots.add(slot);
            }
        }

        List<ItemDisplay> itemDisplays = barrelVisuals(
                furniture, state, "item", "barrel_item_visuals");
        while (itemDisplays.size() > renderedItems.size()) {
            itemDisplays.removeLast().remove();
        }
        while (itemDisplays.size() < renderedItems.size()) {
            itemDisplays.add(spawnBarrelVisual(furniture, "item", itemDisplays.size()));
        }
        long seed = blockPositionSeed(furniture.location());
        for (int index = 0; index < itemDisplays.size(); index++) {
            configureBarrelItem(furniture, itemDisplays.get(index), renderedItems.get(index),
                    seed, index, renderedSlots.get(index));
        }
        state.strings("barrel_item_visuals", itemDisplays.stream()
                .map(display -> display.getUniqueId().toString()).toList());

        int amount = Math.max(0, state.integer("barrel_amount"));
        String fluid = state.string("barrel_fluid");
        List<ItemDisplay> fluidDisplays = barrelVisuals(
                furniture, state, "fluid", "barrel_fluid_visual");
        ItemDisplay fluidDisplay = fluidDisplays.isEmpty() ? null : fluidDisplays.getFirst();
        fluidDisplays.stream().skip(1).forEach(Entity::remove);
        Optional<ItemStack> fluidItem = amount <= 0 || fluid == null
                ? Optional.empty()
                : items.build(NAMESPACE + "_render/barrel_fluid/" + path(fluid), null);
        if (fluidItem.isEmpty()) {
            if (fluidDisplay != null) {
                fluidDisplay.remove();
            }
            state.clear("barrel_fluid_visual");
        } else {
            if (fluidDisplay == null) {
                fluidDisplay = spawnBarrelVisual(furniture, "fluid", 0);
            }
            configureBarrelFluid(furniture, fluidDisplay, fluidItem.get(), amount);
            state.putString("barrel_fluid_visual", fluidDisplay.getUniqueId().toString());
        }
    }

    private List<ItemDisplay> barrelVisuals(BukkitFurniture furniture, FurnitureState state,
                                             String role, String stateKey) {
        String ownerId = furniture.bukkitEntity().getUniqueId().toString();
        Map<UUID, ItemDisplay> result = new LinkedHashMap<>();
        for (String stored : state.strings(stateKey)) {
            try {
                Entity entity = Bukkit.getEntity(UUID.fromString(stored));
                if (entity instanceof ItemDisplay display && display.isValid()
                        && isBarrelVisual(display, ownerId, role)) {
                    result.put(display.getUniqueId(), display);
                }
            } catch (IllegalArgumentException ignored) {
                // Recover stale UUID state via the owner scan below.
            }
        }
        for (Entity entity : furniture.location().getWorld().getNearbyEntities(
                furniture.location().clone().add(0, 1.5, 0), 4, 3, 4,
                nearby -> nearby instanceof ItemDisplay)) {
            ItemDisplay display = (ItemDisplay) entity;
            if (isBarrelVisual(display, ownerId, role)) {
                result.putIfAbsent(display.getUniqueId(), display);
            }
        }
        return new ArrayList<>(result.values().stream()
                .sorted(Comparator.comparingInt(display -> display.getPersistentDataContainer()
                        .getOrDefault(barrelVisualIndexKey, PersistentDataType.INTEGER, 0)))
                .toList());
    }

    private ItemDisplay spawnBarrelVisual(BukkitFurniture furniture, String role, int index) {
        String ownerId = furniture.bukkitEntity().getUniqueId().toString();
        Location location = furniture.location().clone();
        location.setYaw(0);
        location.setPitch(0);
        return location.getWorld().spawn(location, ItemDisplay.class, display -> {
            display.setPersistent(true);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setBillboard(Display.Billboard.FIXED);
            display.setShadowRadius(0F);
            display.setViewRange(2.5F);
            display.setDisplayWidth(1F);
            display.setDisplayHeight(1F);
            display.getPersistentDataContainer().set(
                    barrelVisualOwnerKey, PersistentDataType.STRING, ownerId);
            display.getPersistentDataContainer().set(
                    barrelVisualRoleKey, PersistentDataType.STRING, role);
            display.getPersistentDataContainer().set(
                    barrelVisualIndexKey, PersistentDataType.INTEGER, index);
        });
    }

    private void configureBarrelItem(BukkitFurniture furniture, ItemDisplay display, ItemStack ingredient,
                                     long seed, int globalIndex, int slot) {
        float x = stableRandom(seed, globalIndex, slot + 1) * 0.4F;
        float z = stableRandom(seed, globalIndex, slot + 2) * 0.4F;
        float y = globalIndex / 4 * 0.025F
                + stableRandom(seed, globalIndex, slot + 3) * 0.05F;
        float yRotation = stableRandom(seed, globalIndex, slot + 4) * 5F;
        float zRotation = stableRandom(seed, globalIndex, slot + 5) * 360F;

        ItemStack shown = ingredient.clone();
        shown.setAmount(1);
        display.setItemStack(shown);
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        Location target = display.getLocation();
        Location barrel = furniture.location();
        target.setX(barrel.getX() + x);
        target.setY(barrel.getY() + 2.7 + y);
        target.setZ(barrel.getZ() + z);
        target.setYaw(0);
        target.setPitch(0);
        display.teleport(target);
        Quaternionf rotation = new Quaternionf()
                .rotateX((float) Math.toRadians(-90))
                .rotateY((float) Math.toRadians(-yRotation))
                .rotateZ((float) Math.toRadians(-zRotation));
        display.setTransformation(new Transformation(
                new Vector3f(), rotation, new Vector3f(0.5F), new Quaternionf()));
    }

    private void configureBarrelFluid(BukkitFurniture furniture, ItemDisplay display,
                                      ItemStack renderItem, int amount) {
        renderItem.setAmount(1);
        display.setItemStack(renderItem);
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        Location location = furniture.location().clone();
        location.setY(location.getY() + 2
                + Math.min(BARREL_CAPACITY, amount) / (float) BARREL_CAPACITY * 0.65F);
        location.setYaw(0);
        location.setPitch(0);
        display.teleport(location);
        display.setTransformation(new Transformation(
                new Vector3f(), new Quaternionf(), new Vector3f(1), new Quaternionf()));
    }

    private boolean isBarrelVisual(ItemDisplay display, String ownerId, String role) {
        return ownerId.equals(display.getPersistentDataContainer().get(
                barrelVisualOwnerKey, PersistentDataType.STRING))
                && role.equals(display.getPersistentDataContainer().get(
                barrelVisualRoleKey, PersistentDataType.STRING));
    }

    private void removeBarrelVisuals(BukkitFurniture furniture) {
        if (furniture == null || furniture.bukkitEntity() == null) {
            return;
        }
        FurnitureState state = new FurnitureState(plugin, furniture);
        List<ItemDisplay> displays = new ArrayList<>();
        displays.addAll(barrelVisuals(furniture, state, "item", "barrel_item_visuals"));
        displays.addAll(barrelVisuals(furniture, state, "fluid", "barrel_fluid_visual"));
        displays.forEach(Entity::remove);
        state.clear("barrel_item_visuals", "barrel_fluid_visual");
    }

    /** Rebuilds the legacy pressing-tub block-entity visuals after a reload. */
    private void bootstrapPressVisuals() {
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (!CraftEngineFurniture.isFurniture(display)) {
                    continue;
                }
                BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(display);
                if (furniture != null && furniture.id().toString().equals(PRESSING_TUB)) {
                    refreshPressVisuals(furniture);
                }
            }
        }
    }

    private void refreshPressVisuals(BukkitFurniture furniture) {
        if (furniture == null || !furniture.isValid() || furniture.bukkitEntity() == null) {
            return;
        }
        FurnitureState state = new FurnitureState(plugin, furniture);
        ItemStack ingredient = pressingItem(state);
        int count = ingredient == null ? 0 : Math.max(0, state.integer("press_count"));
        List<ItemDisplay> itemDisplays = pressVisuals(furniture, state, "item", "press_item_visuals");
        while (itemDisplays.size() > count) {
            itemDisplays.removeLast().remove();
        }
        while (itemDisplays.size() < count) {
            itemDisplays.add(spawnPressVisual(furniture, "item", itemDisplays.size()));
        }
        if (ingredient != null) {
            long seed = blockPositionSeed(furniture.location());
            for (int index = 0; index < itemDisplays.size(); index++) {
                configurePressItem(furniture, itemDisplays.get(index), ingredient, seed, index, count);
            }
        }
        state.strings("press_item_visuals", itemDisplays.stream()
                .map(display -> display.getUniqueId().toString()).toList());

        int amount = Math.max(0, state.integer("press_amount"));
        String fluid = state.string("press_fluid");
        List<ItemDisplay> fluidDisplays = pressVisuals(
                furniture, state, "fluid", "press_fluid_visual");
        ItemDisplay fluidDisplay = fluidDisplays.isEmpty() ? null : fluidDisplays.getFirst();
        fluidDisplays.stream().skip(1).forEach(Entity::remove);
        Optional<ItemStack> fluidItem = amount <= 0 || fluid == null
                ? Optional.empty()
                : items.build(NAMESPACE + "_render/pressing_fluid/" + path(fluid), null);
        if (fluidItem.isEmpty()) {
            if (fluidDisplay != null) {
                fluidDisplay.remove();
            }
            state.clear("press_fluid_visual");
        } else {
            if (fluidDisplay == null) {
                fluidDisplay = spawnPressVisual(furniture, "fluid", 0);
            }
            configurePressFluid(furniture, fluidDisplay, fluidItem.get(), amount);
            state.putString("press_fluid_visual", fluidDisplay.getUniqueId().toString());
        }
    }

    private List<ItemDisplay> pressVisuals(BukkitFurniture furniture, FurnitureState state,
                                           String role, String stateKey) {
        String ownerId = furniture.bukkitEntity().getUniqueId().toString();
        Map<UUID, ItemDisplay> result = new LinkedHashMap<>();
        for (String stored : state.strings(stateKey)) {
            try {
                Entity entity = Bukkit.getEntity(UUID.fromString(stored));
                if (entity instanceof ItemDisplay display && display.isValid()
                        && isPressVisual(display, ownerId, role)) {
                    result.put(display.getUniqueId(), display);
                }
            } catch (IllegalArgumentException ignored) {
                // A stale UUID is recovered by the owner scan below.
            }
        }
        for (Entity entity : furniture.location().getWorld().getNearbyEntities(
                furniture.location(), 3, 3, 3, nearby -> nearby instanceof ItemDisplay)) {
            ItemDisplay display = (ItemDisplay) entity;
            if (isPressVisual(display, ownerId, role)) {
                result.putIfAbsent(display.getUniqueId(), display);
            }
        }
        return new ArrayList<>(result.values().stream()
                .sorted(Comparator.comparingInt(display -> display.getPersistentDataContainer()
                        .getOrDefault(pressVisualIndexKey, PersistentDataType.INTEGER, 0)))
                .toList());
    }

    private ItemDisplay spawnPressVisual(BukkitFurniture furniture, String role, int index) {
        String ownerId = furniture.bukkitEntity().getUniqueId().toString();
        Location location = furniture.location().clone();
        location.setYaw(0);
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
            display.setDisplayHeight(1F);
            display.getPersistentDataContainer().set(
                    pressVisualOwnerKey, PersistentDataType.STRING, ownerId);
            display.getPersistentDataContainer().set(
                    pressVisualRoleKey, PersistentDataType.STRING, role);
            display.getPersistentDataContainer().set(
                    pressVisualIndexKey, PersistentDataType.INTEGER, index);
        });
    }

    private void configurePressItem(BukkitFurniture furniture, ItemDisplay display, ItemStack ingredient,
                                    long seed, int index, int count) {
        float x = index % 4 % 2 == 0
                ? -0.15F : 0.15F + stableRandom(seed, index, 1) * 0.0625F;
        float z = index % 4 / 2 == 0
                ? -0.15F : 0.15F + stableRandom(seed, index, 2) * 0.0625F;
        float y = index / 4 * 0.03125F + stableRandom(seed, index, 3) * 0.05F;
        float yRotation = stableRandom(seed, index, 4) * count / 10F;
        float zRotation = stableRandom(seed, index, 5) * 360F;

        ItemStack shown = ingredient.clone();
        shown.setAmount(1);
        display.setItemStack(shown);
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        boolean tilted = furniture.currentVariant().name().equals("wall");
        Location origin = furniture.location();
        Location target;
        Quaternionf rotation;
        if (tilted) {
            PressingTubSemantics.Point point = PressingTubSemantics.tiltSouth(
                    0.5 + x, 0.2 + y, 0.5 + z);
            Vec3d worldPoint = furniture.getRelativePosition(new Vector3f(
                    (float) (point.x() - 0.5), 0, (float) -point.z()));
            target = new Location(origin.getWorld(), worldPoint.x,
                    origin.getY() - 0.5 + point.y(), worldPoint.z,
                    origin.getYaw() + 180F, 0);
            rotation = new Quaternionf()
                    .rotateX((float) Math.toRadians(PressingTubSemantics.TILT_X_DEGREES))
                    .rotateX((float) Math.toRadians(PressingTubSemantics.ITEM_X_DEGREES))
                    .rotateY((float) Math.toRadians(-yRotation))
                    .rotateZ((float) Math.toRadians(-zRotation));
        } else {
            target = new Location(origin.getWorld(), origin.getX() + x,
                    origin.getY() + 0.2 + y, origin.getZ() + z, 0, 0);
            rotation = new Quaternionf()
                    .rotateX((float) Math.toRadians(PressingTubSemantics.ITEM_X_DEGREES))
                    .rotateY((float) Math.toRadians(-yRotation))
                    .rotateZ((float) Math.toRadians(-zRotation));
        }
        target.setPitch(0);
        display.teleport(target);
        display.setTransformation(new Transformation(
                new Vector3f(), rotation, new Vector3f(0.5F), new Quaternionf()));
    }

    private void configurePressFluid(BukkitFurniture furniture, ItemDisplay display,
                                     ItemStack renderItem, int amount) {
        renderItem.setAmount(1);
        display.setItemStack(renderItem);
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        float y = 0.125F + Math.min(PRESS_CAPACITY, amount) / (float) PRESS_CAPACITY * 0.25F;
        Location origin = furniture.location();
        if (furniture.currentVariant().name().equals("wall")) {
            Vec3d center = furniture.getRelativePosition(new Vector3f(0, 0, -0.5F));
            display.teleport(new Location(origin.getWorld(), center.x,
                    origin.getY() - 0.5, center.z));
        } else {
            display.teleport(new Location(origin.getWorld(), origin.getX(),
                    origin.getY(), origin.getZ()));
        }
        display.setTransformation(new Transformation(
                new Vector3f(0, y, 0), new Quaternionf(), new Vector3f(1), new Quaternionf()));
    }

    private void removePressVisuals(BukkitFurniture furniture) {
        if (furniture.bukkitEntity() == null) {
            return;
        }
        FurnitureState state = new FurnitureState(plugin, furniture);
        List<ItemDisplay> displays = new ArrayList<>();
        displays.addAll(pressVisuals(furniture, state, "item", "press_item_visuals"));
        displays.addAll(pressVisuals(furniture, state, "fluid", "press_fluid_visual"));
        displays.forEach(Entity::remove);
        state.clear("press_item_visuals", "press_fluid_visual");
    }

    private boolean isPressVisual(ItemDisplay display, String ownerId, String role) {
        return ownerId.equals(display.getPersistentDataContainer().get(
                pressVisualOwnerKey, PersistentDataType.STRING))
                && role.equals(display.getPersistentDataContainer().get(
                pressVisualRoleKey, PersistentDataType.STRING));
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

    private static boolean isIncense(BukkitFurniture furniture) {
        return furniture != null && furniture.id().namespace().equals(KEY_NAMESPACE)
                && furniture.id().value().endsWith("_incense");
    }

    private static List<UUID> addInvalid(List<UUID> invalid, UUID uuid) {
        List<UUID> result = invalid == null ? new ArrayList<>() : invalid;
        result.add(uuid);
        return result;
    }

    private Optional<BukkitFurniture> pressingTubBelow(Location feet) {
        return feet.getWorld().getNearbyEntities(feet, 0.9, 1.25, 0.9).stream()
                .filter(CraftEngineFurniture::isFurniture)
                .map(CraftEngineFurniture::getLoadedFurnitureByMetaEntity)
                .filter(java.util.Objects::nonNull)
                .filter(furniture -> furniture.id().equals(PRESSING_TUB_KEY))
                .filter(furniture -> furniture.currentVariant().name().equals("ground"))
                .filter(furniture -> {
                    Location base = furniture.location();
                    double relativeY = feet.getY() - base.getY();
                    return Math.abs(feet.getX() - base.getX()) <= 0.5
                            && Math.abs(feet.getZ() - base.getZ()) <= 0.5
                            && relativeY >= 0.35 && relativeY <= 1.25;
                })
                .min(Comparator.comparingDouble(furniture ->
                        furniture.location().distanceSquared(feet)));
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

    private record PortableShakerUse(EquipmentSlot hand, int ticks) {
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
