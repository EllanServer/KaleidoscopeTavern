package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.Messages;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.BarrelRecipe;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.EffectSpec;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.PressingRecipe;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

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

/** Implements the former Forge block-entity gameplay on CraftEngine furniture entities. */
public final class StationService implements Listener {
    private static final String NAMESPACE = "kaleidoscope_tavern:";
    private static final String PRESSING_TUB = NAMESPACE + "pressing_tub";
    private static final String BARREL = NAMESPACE + "barrel";
    private static final String SHAKER = NAMESPACE + "shaker";
    private static final String EMPTY_GLASSWARE = NAMESPACE + "empty_glassware";
    private static final int PRESS_CAPACITY = 1_000;
    private static final int BARREL_CAPACITY = 4_000;
    private static final int MAX_BARREL_SLOTS = 4;
    private static final int MAX_BARREL_STACK = 16;

    private final JavaPlugin plugin;
    private final ContentCatalog catalog;
    private final ItemService items;
    private final Messages messages;
    private final Map<UUID, Float> falling = new HashMap<>();
    private final Map<UUID, EquipmentSlot> portableShakers = new HashMap<>();
    private final Set<UUID> loadedIncense = new HashSet<>();
    private final Set<UUID> activeIncense = new HashSet<>();
    private BukkitTask incenseTask;
    private BukkitTask incenseRedstoneTask;

    public StationService(JavaPlugin plugin, ContentCatalog catalog, ItemService items, Messages messages) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.items = items;
        this.messages = messages;
    }

    public void start() {
        int period = Math.max(20, plugin.getConfig().getInt("stations.incense-period-ticks", 120));
        incenseTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pulseIncense, period, period);
        incenseRedstoneTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pollIncenseRedstone, 4L, 4L);
        Bukkit.getScheduler().runTask(plugin, this::bootstrapIncense);
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
        falling.clear();
        portableShakers.clear();
        loadedIncense.clear();
        activeIncense.clear();
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFurnitureInteract(FurnitureInteractEvent event) {
        if (event.hand() != InteractionHand.MAIN_HAND) {
            return;
        }
        String id = event.furniture().id().toString();
        boolean handled = switch (id) {
            case PRESSING_TUB -> interactPress(event.player(), event.furniture());
            case BARREL -> interactBarrel(event.player(), event.furniture());
            case SHAKER -> interactShaker(event.player(), event.furniture());
            case EMPTY_GLASSWARE -> pourPortableShaker(event.player(), event.furniture());
            default -> id.startsWith(NAMESPACE) && id.endsWith("_incense")
                    && interactIncense(event.player(), event.furniture());
        };
        if (handled) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurniturePlace(FurniturePlaceEvent event) {
        String id = event.furniture().id().toString();
        if (id.startsWith(NAMESPACE) && id.endsWith("_incense")) {
            loadedIncense.add(event.furniture().bukkitEntity().getUniqueId());
            FurnitureState state = new FurnitureState(plugin, event.furniture());
            if (state.bool("incense_active")) {
                activeIncense.add(event.furniture().bukkitEntity().getUniqueId());
            }
        } else if (id.equals(SHAKER)) {
            loadPortableShaker(event.furniture());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent event) {
        String id = event.furniture().id().toString();
        switch (id) {
            case PRESSING_TUB -> {
                if (event.dropItems()) dropPressingContents(event.furniture(), event.location());
            }
            case BARREL -> {
                if (event.dropItems()) dropBarrelContents(event.furniture(), event.location());
            }
            case SHAKER -> {
                if (event.dropItems()) dropShaker(event);
            }
            default -> {
                if (id.startsWith(NAMESPACE) && id.endsWith("_incense")) {
                    Entity entity = event.furniture().bukkitEntity();
                    if (entity != null) {
                        loadedIncense.remove(entity.getUniqueId());
                        activeIncense.remove(entity.getUniqueId());
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || (event.getFrom().getX() == event.getTo().getX()
                && event.getFrom().getY() == event.getTo().getY()
                && event.getFrom().getZ() == event.getTo().getZ())) {
            return;
        }
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (player.getFallDistance() > 0) {
            falling.merge(playerId, player.getFallDistance(), Math::max);
            return;
        }
        float fallDistance = falling.getOrDefault(playerId, 0F);
        falling.remove(playerId);
        if (fallDistance < 0.5F) {
            return;
        }
        findNearbyFurniture(player.getLocation(), 1.35, PRESSING_TUB).ifPresent(this::pressOne);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        falling.remove(event.getPlayer().getUniqueId());
        portableShakers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUsePortableShaker(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR || event.getHand() == null
                || event.getItem() == null || !items.id(event.getItem()).equals(SHAKER)) {
            return;
        }
        ItemStack shaker = event.getItem();
        if (items.shakerResult(shaker) != null) {
            messages.send(event.getPlayer(), "station-busy");
            event.setCancelled(true);
            return;
        }
        if (items.shakerIngredients(shaker).size() != 3) {
            messages.send(event.getPlayer(), "shaker-needs-three");
            event.setCancelled(true);
            return;
        }

        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack current = handItem(player, hand);
            if (!player.isOnline() || !items.id(current).equals(SHAKER)
                    || items.shakerResult(current) != null
                    || items.shakerIngredients(current).size() != 3) {
                return;
            }
            portableShakers.put(player.getUniqueId(), hand);
            player.startUsingItem(hand);
            player.setActiveItemRemainingTime(72_000);
            player.getWorld().playSound(player.getLocation(),
                    "kaleidoscope_tavern:item.shaker.shaking", 0.9F, 1.0F);
            messages.send(player, "shaker-started");
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStopPortableShaker(PlayerStopUsingItemEvent event) {
        Player player = event.getPlayer();
        EquipmentSlot hand = portableShakers.remove(player.getUniqueId());
        if (hand == null) {
            return;
        }
        ItemStack shaker = handItem(player, hand);
        if (!items.id(shaker).equals(SHAKER) || items.shakerResult(shaker) != null) {
            return;
        }
        List<ItemStack> ingredients = items.shakerIngredients(shaker);
        if (ingredients.size() != 3) {
            return;
        }
        int ticks = Math.max(0, event.getTicksHeldFor());
        if (ticks < 19) {
            messages.send(player, "shaker-too-short");
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
                "kaleidoscope_tavern:item.shaker.end", 1.0F, 1.0F);
        messages.send(player, "shaker-ready");
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (!(entity instanceof ItemDisplay) || !CraftEngineFurniture.isFurniture(entity)) {
                continue;
            }
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (isIncense(furniture)) {
                loadedIncense.add(entity.getUniqueId());
                if (new FurnitureState(plugin, furniture).bool("incense_active")) {
                    activeIncense.add(entity.getUniqueId());
                }
            }
        }
    }

    private boolean interactPress(Player player, BukkitFurniture furniture) {
        FurnitureState state = new FurnitureState(plugin, furniture);
        ItemStack hand = player.getInventory().getItemInMainHand();
        String handId = items.id(hand);
        int storedCount = state.integer("press_count");
        if (hand.isEmpty() && storedCount > 0) {
            int removeCount = player.isSneaking() ? Math.min(64, storedCount) : 1;
            ItemStack stored = pressingItem(state, player);
            if (stored != null) {
                giveStored(player, stored, removeCount);
                int remaining = storedCount - removeCount;
                state.integer("press_count", remaining);
                if (remaining == 0) {
                    state.clear("press_ingredient", "press_item");
                }
                furniture.location().getWorld().playSound(furniture.location(),
                        "minecraft:entity.item_frame.remove_item", 0.8F, 1.0F);
                return true;
            }
        }

        if (handId.equals("minecraft:bucket") && state.integer("press_amount") >= PRESS_CAPACITY) {
            String fluid = state.string("press_fluid");
            Optional<PressingRecipe> recipe = catalog.pressingByFluid(fluid == null ? "" : fluid);
            if (recipe.isPresent() && items.consumeOne(player, hand)) {
                items.build(recipe.get().bucket(), player).ifPresent(result -> items.give(player, result));
                state.clear("press_amount", "press_fluid");
                furniture.location().getWorld().playSound(furniture.location(),
                        "minecraft:item.bucket.fill", 0.9F, 1.0F);
            }
            return true;
        }

        if (!hand.isEmpty()) {
            ItemStack current = state.item("press_item");
            int count = state.integer("press_count");
            int capacity = Math.min(64, hand.getMaxStackSize());
            if ((current == null || current.isSimilar(hand)) && count < capacity) {
                ItemStack template = hand.clone();
                template.setAmount(1);
                if (items.consumeOne(player, hand)) {
                    state.putString("press_ingredient", handId);
                    state.item("press_item", template);
                    state.integer("press_count", count + 1);
                    furniture.location().getWorld().playSound(furniture.location(),
                            "minecraft:entity.item_frame.add_item", 0.75F, 1.0F);
                }
            }
            return true;
        }

        if (hand.isEmpty()) {
            messages.send(player, "press-filled", Map.of("amount", state.integer("press_amount")));
            return true;
        }
        return false;
    }

    private void pressOne(BukkitFurniture furniture) {
        if (!furniture.currentVariant().name().equals("ground")) {
            return;
        }
        FurnitureState state = new FurnitureState(plugin, furniture);
        int count = state.integer("press_count");
        ItemStack ingredient = pressingItem(state, null);
        if (ingredient == null || count <= 0) {
            return;
        }
        Optional<PressingRecipe> optional = catalog.pressing(items.id(ingredient));
        if (optional.isEmpty()) {
            failPress(furniture, ingredient);
            ejectInvalidPressContents(furniture, state, ingredient, count);
            return;
        }
        PressingRecipe recipe = optional.get();
        String currentFluid = state.string("press_fluid");
        int amount = state.integer("press_amount");
        if (currentFluid != null && !currentFluid.equals(recipe.fluid())) {
            failPress(furniture, ingredient);
            ejectInvalidPressContents(furniture, state, ingredient, count);
            return;
        }
        if (amount >= PRESS_CAPACITY) {
            furniture.location().getWorld().playSound(furniture.location(),
                    "minecraft:block.honey_block.hit", 0.8F, 0.9F);
            furniture.location().getWorld().spawnParticle(Particle.RAIN,
                    furniture.location().clone().add(0, 0.75, 0), 10, 0.25, 0.2, 0.25, 0.05);
            return;
        }
        state.integer("press_count", count - 1);
        if (count == 1) {
            state.clear("press_ingredient", "press_item");
        }
        state.putString("press_fluid", recipe.fluid());
        state.integer("press_amount", Math.min(PRESS_CAPACITY, amount + recipe.amount()));
        Location location = furniture.location().clone().add(0, 0.75, 0);
        location.getWorld().spawnParticle(Particle.ITEM, location, 10, 0.25, 0.15, 0.25, 0.02, ingredient);
        location.getWorld().playSound(location, "minecraft:block.slime_block.fall", 0.8F, 1.0F);
    }

    private void failPress(BukkitFurniture furniture, ItemStack ingredient) {
        Location location = furniture.location().clone().add(0, 0.75, 0);
        location.getWorld().spawnParticle(Particle.ITEM, location, 10,
                0.25, 0.15, 0.25, 0.02, ingredient);
        location.getWorld().playSound(location, "minecraft:block.wood.fall", 0.8F, 0.8F);
    }

    private void ejectInvalidPressContents(BukkitFurniture furniture, FurnitureState state,
                                            ItemStack template, int totalCount) {
        if (!plugin.getConfig().getBoolean("stations.press-eject-invalid", true) || totalCount <= 0) {
            return;
        }
        state.clear("press_count", "press_ingredient", "press_item");
        int directionCount = Math.min(8, totalCount);
        double diagonal = 1.0 / Math.sqrt(2.0);
        double[][] directions = {
                {1, 0}, {diagonal, diagonal}, {0, 1}, {-diagonal, diagonal},
                {-1, 0}, {-diagonal, -diagonal}, {0, -1}, {diagonal, -diagonal},
        };
        int base = totalCount / directionCount;
        int remainder = totalCount % directionCount;
        Location origin = furniture.location().clone().add(0, 0.65, 0);
        for (int index = 0; index < directionCount; index++) {
            ItemStack dropped = template.clone();
            dropped.setAmount(base + (index < remainder ? 1 : 0));
            double[] direction = directions[index];
            origin.getWorld().dropItem(origin, dropped, entity -> entity.setVelocity(
                    new Vector(direction[0] * 0.35, 0.25, direction[1] * 0.35)));
        }
    }

    private boolean interactBarrel(Player player, BukkitFurniture furniture) {
        FurnitureState state = new FurnitureState(plugin, furniture);
        ItemStack hand = player.getInventory().getItemInMainHand();
        String handId = items.id(hand);
        if (state.longValue("barrel_started") > 0) {
            if (extractBarrel(player, furniture, state, hand, handId)) {
                return true;
            }
            if (hand.isEmpty()) {
                messages.send(player, "barrel-progress", Map.of("level", brewLevel(state)));
            } else {
                messages.send(player, "station-busy");
            }
            return true;
        }

        if (player.isSneaking() && hand.isEmpty()) {
            Map<String, Integer> stored = state.counts("barrel_ingredients");
            if (!stored.isEmpty()) {
                String last = stored.keySet().stream().reduce((left, right) -> right).orElseThrow();
                giveAmount(player, last, stored.remove(last));
                state.counts("barrel_ingredients", stored);
                return true;
            }
        }

        Optional<String> fluid = fluidFromBucket(handId);
        if (fluid.isPresent()) {
            Map<String, Integer> stored = state.counts("barrel_ingredients");
            String current = state.string("barrel_fluid");
            int amount = state.integer("barrel_amount");
            if (stored.isEmpty() && amount <= BARREL_CAPACITY - 1_000
                    && (current == null || current.equals(fluid.get())) && items.consumeOne(player, hand)) {
                state.putString("barrel_fluid", fluid.get());
                state.integer("barrel_amount", amount + 1_000);
                items.build("minecraft:bucket", player).ifPresent(bucket -> items.give(player, bucket));
                furniture.location().getWorld().playSound(furniture.location(),
                        "minecraft:item.bucket.empty", 0.9F, 1.0F);
            }
            return true;
        }

        if (handId.equals("minecraft:bucket") && state.integer("barrel_amount") >= 1_000
                && state.counts("barrel_ingredients").isEmpty()) {
            String storedFluid = state.string("barrel_fluid");
            Optional<String> bucketId = bucketForFluid(storedFluid);
            if (bucketId.isPresent() && items.consumeOne(player, hand)) {
                items.build(bucketId.get(), player).ifPresent(result -> items.give(player, result));
                int remaining = state.integer("barrel_amount") - 1_000;
                state.integer("barrel_amount", remaining);
                if (remaining == 0) {
                    state.clear("barrel_fluid");
                }
            }
            return true;
        }

        if (!hand.isEmpty()) {
            String storedFluid = state.string("barrel_fluid");
            Map<String, Integer> stored = state.counts("barrel_ingredients");
            List<String> types = new ArrayList<>(stored.keySet());
            if (state.integer("barrel_amount") == BARREL_CAPACITY
                    && (stored.containsKey(handId) ? stored.get(handId) < MAX_BARREL_STACK
                    : stored.size() < MAX_BARREL_SLOTS)
                    && catalog.mayBeBarrelIngredient(storedFluid, types, handId)
                    && items.consumeOne(player, hand)) {
                stored.merge(handId, 1, Integer::sum);
                state.counts("barrel_ingredients", stored);
                furniture.location().getWorld().playSound(furniture.location(),
                        "minecraft:entity.item_frame.add_item", 0.75F, 0.9F);
                return true;
            }
            return false;
        }

        beginBrewing(player, furniture, state);
        return true;
    }

    private void beginBrewing(Player player, BukkitFurniture furniture, FurnitureState state) {
        if (state.integer("barrel_amount") < BARREL_CAPACITY) {
            messages.send(player, "no-recipe");
            return;
        }
        String fluid = state.string("barrel_fluid", "");
        Map<String, Integer> stored = state.counts("barrel_ingredients");
        Optional<BarrelRecipe> optional = catalog.barrel(fluid, new ArrayList<>(stored.keySet()));
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
                    : Math.min(MAX_BARREL_STACK, stored.values().stream().mapToInt(Integer::intValue).min().orElse(1));
        } else {
            result = NAMESPACE + "vinegar";
            recipeId = NAMESPACE + "empty";
            unitTicks = plugin.getConfig().getInt("stations.barrel-level-ticks", 2400);
            outputCount = MAX_BARREL_STACK;
        }
        state.putString("barrel_recipe", recipeId);
        state.putString("barrel_result", result);
        state.integer("barrel_output", outputCount);
        state.integer("barrel_unit", Math.max(1, unitTicks));
        state.longValue("barrel_started", System.currentTimeMillis());
        state.clear("barrel_fluid", "barrel_amount", "barrel_ingredients");
        furniture.location().getWorld().playSound(furniture.location(),
                "minecraft:block.barrel.close", 1.0F, 0.9F);
        messages.send(player, "barrel-started");
    }

    private boolean extractBarrel(Player player, BukkitFurniture furniture, FurnitureState state,
                                  ItemStack carrier, String carrierId) {
        String recipeId = state.string("barrel_recipe", "");
        boolean validCarrier = catalog.barrelById(recipeId)
                .map(recipe -> catalog.selectorMatches(recipe.carrier(), carrierId))
                .orElse(carrierId.equals(NAMESPACE + "empty_bottle"));
        int remaining = state.integer("barrel_output");
        String resultId = state.string("barrel_result");
        if (!validCarrier || remaining <= 0 || resultId == null) {
            return false;
        }
        Optional<ItemStack> result = items.build(resultId, player)
                .map(stack -> items.withBrewLevel(stack, brewLevel(state)));
        if (result.isEmpty() || !items.consumeOne(player, carrier)) {
            return false;
        }
        items.give(player, result.get());
        state.integer("barrel_output", remaining - 1);
        if (remaining == 1) {
            state.clear("barrel_recipe", "barrel_result", "barrel_output", "barrel_unit", "barrel_started");
        }
        furniture.location().getWorld().playSound(furniture.location(),
                "minecraft:item.bottle.fill", 0.85F, 1.0F);
        return true;
    }

    private int brewLevel(FurnitureState state) {
        long started = state.longValue("barrel_started");
        if (started <= 0) {
            return 0;
        }
        long elapsedTicks = Math.max(0, (System.currentTimeMillis() - started) / 50L);
        int unit = Math.max(1, state.integer("barrel_unit"));
        int level = 1;
        long threshold = 0;
        for (int nextLevel = 2; nextLevel <= 6; nextLevel++) {
            threshold += (long) unit * (nextLevel - 1);
            if (elapsedTicks >= threshold) {
                level = nextLevel;
            } else {
                break;
            }
        }
        return level;
    }

    private boolean interactShaker(Player player, BukkitFurniture furniture) {
        FurnitureState state = new FurnitureState(plugin, furniture);
        ItemStack hand = player.getInventory().getItemInMainHand();
        String handId = items.id(hand);
        if (hand.isEmpty() && player.isSneaking()) {
            return takeShaker(player, furniture, state);
        }
        ItemStack result = state.item("shaker_result");
        if (result != null) {
            if (handId.equals(NAMESPACE + "empty_glassware") && items.consumeOne(player, hand)) {
                items.give(player, result);
                state.clear("shaker_result");
                furniture.location().getWorld().playSound(furniture.location(),
                        "minecraft:item.bottle.fill", 0.85F, 1.1F);
            }
            return true;
        }

        List<ItemStack> ingredients = state.items("shaker_ingredients");
        long started = state.longValue("shaker_started");
        if (hand.isEmpty()) {
            if (started == 0) {
                if (ingredients.size() != 3) {
                    messages.send(player, "shaker-needs-three");
                    return true;
                }
                state.longValue("shaker_started", System.currentTimeMillis());
                furniture.location().getWorld().playSound(furniture.location(),
                        "kaleidoscope_tavern:item.shaker.shaking", 1.0F, 1.0F);
                messages.send(player, "shaker-started");
            } else {
                finishShaking(player, furniture, state, ingredients, started);
            }
            return true;
        }

        if (started > 0 || ingredients.size() >= 3) {
            messages.send(player, "station-busy");
            return true;
        }
        List<String> currentIds = ingredients.stream().map(items::id).toList();
        if (!catalog.mayBeShakerIngredient(currentIds, handId)) {
            return false;
        }
        if (catalog.hasDrinkEffects(handId) && items.brewLevel(hand) < 4) {
            messages.send(player, "shaker-low-quality");
            return true;
        }
        ItemStack captured = hand.clone();
        captured.setAmount(1);
        if (!items.consumeOne(player, hand)) {
            return true;
        }
        ingredients.add(captured);
        state.items("shaker_ingredients", ingredients);
        if (catalog.hasDrinkEffects(handId)) {
            items.returnedContainer(handId, catalog.isCocktail(handId)).flatMap(id -> items.build(id, player))
                    .ifPresent(container -> items.give(player, container));
        } else if (captured.getItemMeta() instanceof PotionMeta) {
            items.give(player, new ItemStack(org.bukkit.Material.GLASS_BOTTLE));
        }
        furniture.location().getWorld().spawnParticle(Particle.BUBBLE_POP,
                furniture.location().clone().add(0, 0.75, 0), 8, 0.2, 0.25, 0.2, 0.01);
        return true;
    }

    private void finishShaking(Player player, BukkitFurniture furniture, FurnitureState state,
                               List<ItemStack> ingredients, long started) {
        int ticks = (int) Math.min(Integer.MAX_VALUE, Math.max(0, (System.currentTimeMillis() - started) / 50L));
        if (ticks < 19) {
            messages.send(player, "shaker-too-short");
            return;
        }
        Optional<ItemStack> built = buildShakerResult(player, ingredients, ticks);
        if (built.isEmpty()) {
            messages.send(player, "pack-missing");
            return;
        }
        state.item("shaker_result", built.get());
        state.clear("shaker_ingredients", "shaker_started");
        furniture.location().getWorld().playSound(furniture.location(),
                "kaleidoscope_tavern:item.shaker.end", 1.0F, 1.0F);
        messages.send(player, "shaker-ready");
    }

    private Optional<ItemStack> buildShakerResult(Player player, List<ItemStack> ingredients, int ticks) {
        String resultId;
        boolean signature = false;
        List<String> ids = ingredients.stream().map(items::id).toList();
        if (ticks < 69 || ticks >= 99) {
            resultId = NAMESPACE + "mystery_cocktail";
        } else if (ticks < 89) {
            resultId = NAMESPACE + "signature_cocktail";
            signature = true;
        } else {
            Optional<ContentCatalog.ShakerRecipe> recipe = catalog.shaker(ids);
            if (recipe.isPresent()) {
                resultId = recipe.get().result();
            } else {
                resultId = NAMESPACE + "signature_cocktail";
                signature = true;
            }
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
        CraftEngineFurniture.remove(furniture, player, false, true);
        location.getWorld().playSound(location, "minecraft:block.lantern.break", 0.8F, 1.1F);
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

    private boolean pourPortableShaker(Player player, BukkitFurniture glassware) {
        ItemStack shaker = player.getInventory().getItemInMainHand();
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
        player.getInventory().setItemInMainHand(shaker);
        location.getWorld().playSound(location, "minecraft:item.bottle.fill", 1.0F, 1.0F);
        return true;
    }

    private List<EffectSpec> effectsOfIngredient(ItemStack ingredient) {
        String id = items.id(ingredient);
        List<EffectSpec> result = new ArrayList<>(catalog.effects(id, items.brewLevel(ingredient)));
        if (ingredient.getItemMeta() instanceof PotionMeta potion) {
            for (PotionEffect effect : potion.getAllEffects()) {
                result.add(new EffectSpec(effect.getType().getKey().asString(), effect.getDuration(),
                        effect.getAmplifier(), 1.0));
            }
        }
        return result;
    }

    private int averageColor(List<ItemStack> ingredients) {
        int red = 0;
        int green = 0;
        int blue = 0;
        for (ItemStack ingredient : ingredients) {
            int rgb;
            if (ingredient.getItemMeta() instanceof PotionMeta potion && potion.hasColor()) {
                rgb = potion.getColor().asRGB();
            } else {
                rgb = catalog.cocktailColor(items.id(ingredient));
            }
            red += rgb >> 16 & 0xFF;
            green += rgb >> 8 & 0xFF;
            blue += rgb & 0xFF;
        }
        int count = Math.max(1, ingredients.size());
        return red / count << 16 | green / count << 8 | blue / count;
    }

    boolean canTapExtract(BukkitFurniture barrel) {
        if (!barrel.isValid() || !barrel.id().toString().equals(BARREL)) {
            return false;
        }
        FurnitureState state = new FurnitureState(plugin, barrel);
        return state.longValue("barrel_started") > 0
                && state.integer("barrel_output") > 0
                && state.string("barrel_result") != null;
    }

    Optional<ItemStack> takeTapOutput(BukkitFurniture barrel, Player context) {
        if (!canTapExtract(barrel)) {
            return Optional.empty();
        }
        FurnitureState state = new FurnitureState(plugin, barrel);
        String resultId = state.string("barrel_result");
        int remaining = state.integer("barrel_output");
        Optional<ItemStack> built = items.build(resultId, context)
                .map(result -> items.withBrewLevel(result, brewLevel(state)));
        if (built.isEmpty()) {
            return Optional.empty();
        }
        state.integer("barrel_output", remaining - 1);
        if (remaining == 1) {
            state.clear("barrel_recipe", "barrel_result", "barrel_output", "barrel_unit", "barrel_started");
        }
        return built;
    }

    private boolean interactIncense(Player player, BukkitFurniture furniture) {
        FurnitureState state = new FurnitureState(plugin, furniture);
        boolean active = !state.bool("incense_active");
        setIncenseActive(furniture, active, true);
        messages.send(player, active ? "incense-on" : "incense-off");
        return true;
    }

    private void setIncenseActive(BukkitFurniture furniture, boolean active, boolean playSound) {
        FurnitureState state = new FurnitureState(plugin, furniture);
        state.bool("incense_active", active);
        Entity entity = furniture.bukkitEntity();
        if (entity != null) {
            loadedIncense.add(entity.getUniqueId());
            if (active) {
                activeIncense.add(entity.getUniqueId());
            } else {
                activeIncense.remove(entity.getUniqueId());
            }
        }
        String current = furniture.currentVariant().name();
        String base = current.endsWith("_open") ? current.substring(0, current.length() - 5) : current;
        furniture.setVariant(active ? base + "_open" : base, true);
        if (playSound) {
            furniture.location().getWorld().playSound(furniture.location(),
                    active ? "minecraft:item.firecharge.use" : "minecraft:block.fire.extinguish", 0.65F, 1.2F);
        }
    }

    private void pulseIncense() {
        List<UUID> invalid = new ArrayList<>();
        for (UUID uuid : activeIncense) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity == null || !entity.isValid()) {
                invalid.add(uuid);
                continue;
            }
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture == null || !new FurnitureState(plugin, furniture).bool("incense_active")) {
                invalid.add(uuid);
                continue;
            }
            Location center = furniture.location();
            center.getWorld().spawnParticle(Particle.SMOKE, center.clone().add(0, 0.8, 0),
                    8, 0.15, 0.3, 0.15, 0.005);
            for (Entity nearby : center.getWorld().getNearbyEntities(center, 32, 32, 32,
                    candidate -> candidate instanceof LivingEntity)) {
                LivingEntity living = (LivingEntity) nearby;
                if (living.isValid() && !living.isDead()
                        && Tag.ENTITY_TYPES_UNDEAD.isTagged(living.getType())) {
                    living.damage(1.0);
                    living.playHurtAnimation(0F);
                    if (living instanceof ZombieVillager zombie && zombie.getHealth() <= 1.0) {
                        zombie.setConversionTime(60);
                    }
                }
            }
        }
        activeIncense.removeAll(invalid);
    }

    private void pollIncenseRedstone() {
        List<UUID> invalid = new ArrayList<>();
        for (UUID uuid : loadedIncense) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity == null || !entity.isValid()) {
                invalid.add(uuid);
                continue;
            }
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (!isIncense(furniture)) {
                invalid.add(uuid);
                continue;
            }
            Block block = furniture.location().getBlock();
            boolean powered = block.isBlockPowered() || block.isBlockIndirectlyPowered()
                    || block.getRelative(BlockFace.DOWN).isBlockPowered()
                    || block.getRelative(BlockFace.DOWN).isBlockIndirectlyPowered();
            FurnitureState state = new FurnitureState(plugin, furniture);
            boolean wasPowered = state.bool("incense_powered");
            if (powered != wasPowered) {
                state.bool("incense_powered", powered);
                setIncenseActive(furniture, powered, true);
            }
        }
        loadedIncense.removeAll(invalid);
        activeIncense.removeAll(invalid);
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
                    if (new FurnitureState(plugin, furniture).bool("incense_active")) {
                        activeIncense.add(display.getUniqueId());
                    }
                }
            }
        }
    }

    private static boolean isIncense(BukkitFurniture furniture) {
        return furniture != null && furniture.id().toString().startsWith(NAMESPACE)
                && furniture.id().toString().endsWith("_incense");
    }

    private Optional<BukkitFurniture> findNearbyFurniture(Location center, double radius, String id) {
        return center.getWorld().getNearbyEntities(center, radius, radius, radius).stream()
                .filter(CraftEngineFurniture::isFurniture)
                .map(CraftEngineFurniture::getLoadedFurnitureByMetaEntity)
                .filter(java.util.Objects::nonNull)
                .filter(furniture -> furniture.id().toString().equals(id))
                .min(Comparator.comparingDouble(furniture -> furniture.location().distanceSquared(center)));
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

    private void dropPressingContents(BukkitFurniture furniture, Location location) {
        FurnitureState state = new FurnitureState(plugin, furniture);
        ItemStack ingredient = pressingItem(state, null);
        if (ingredient != null) {
            dropStored(location, ingredient, state.integer("press_count"));
        }
        if (state.integer("press_amount") >= PRESS_CAPACITY) {
            catalog.pressingByFluid(state.string("press_fluid", ""))
                    .ifPresent(recipe -> dropAmount(location, recipe.bucket(), 1, 1, 1));
        }
    }

    private void dropBarrelContents(BukkitFurniture furniture, Location location) {
        FurnitureState state = new FurnitureState(plugin, furniture);
        state.counts("barrel_ingredients").forEach((id, count) -> dropAmount(location, id, count, 64, 1));
        int buckets = state.integer("barrel_amount") / 1_000;
        bucketForFluid(state.string("barrel_fluid")).ifPresent(id -> dropAmount(location, id, buckets, 16, 1));
        String result = state.string("barrel_result");
        if (result != null) {
            dropAmount(location, result, state.integer("barrel_output"), 16, Math.max(1, brewLevel(state)));
        }
    }

    private void dropShaker(FurnitureBreakEvent event) {
        FurnitureState state = new FurnitureState(plugin, event.furniture());
        Optional<ItemStack> shaker = buildShakerItem(state, event.player());
        if (shaker.isEmpty()) {
            return;
        }
        event.setDropItems(false);
        event.location().getWorld().dropItemNaturally(event.location(), shaker.get());
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

    private ItemStack pressingItem(FurnitureState state, Player context) {
        ItemStack stored = state.item("press_item");
        if (stored != null) {
            return stored;
        }
        String legacyId = state.string("press_ingredient");
        return legacyId == null ? null : items.build(legacyId, context).orElse(null);
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
