package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Beehive;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Restores the tap's source/destination pipeline while keeping bottles and barrels as furniture. */
public final class TapService implements Listener {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final String TAP = PREFIX + "tap";
    private static final String BARREL = PREFIX + "barrel";
    private static final String EMPTY_BOTTLE = PREFIX + "empty_bottle";
    private static final int TAKE_TICKS = 30;
    private static final int DRIP_TICKS = 5;

    private final JavaPlugin plugin;
    private final StationService stations;
    private final ItemService items;
    private final Set<UUID> loadedTaps = new HashSet<>();
    private final Map<UUID, BukkitTask> running = new HashMap<>();
    private BukkitTask redstoneTask;

    public TapService(JavaPlugin plugin, StationService stations, ItemService items) {
        this.plugin = plugin;
        this.stations = stations;
        this.items = items;
    }

    public void start() {
        Bukkit.getScheduler().runTask(plugin, this::bootstrapTaps);
        redstoneTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pollRedstone, 4L, 4L);
    }

    public void stop() {
        if (redstoneTask != null) {
            redstoneTask.cancel();
            redstoneTask = null;
        }
        new ArrayList<>(running.values()).forEach(BukkitTask::cancel);
        running.clear();
        loadedTaps.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(FurnitureInteractEvent event) {
        if (event.hand() != InteractionHand.MAIN_HAND
                || !event.furniture().id().toString().equals(TAP)) {
            return;
        }
        event.setCancelled(true);
        UUID id = event.furniture().uuid();
        if (running.containsKey(id)) {
            closeTap(event.furniture(), true);
        } else {
            openTap(event.furniture(), event.player());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(FurniturePlaceEvent event) {
        if (event.furniture().id().toString().equals(TAP)) {
            loadedTaps.add(event.furniture().uuid());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(FurnitureBreakEvent event) {
        if (event.furniture().id().toString().equals(TAP)) {
            closeTap(event.furniture(), false);
            loadedTaps.remove(event.furniture().uuid());
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (!(entity instanceof ItemDisplay) || !CraftEngineFurniture.isFurniture(entity)) {
                continue;
            }
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture != null && furniture.id().toString().equals(TAP)) {
                loadedTaps.add(entity.getUniqueId());
            }
        }
    }

    private void openTap(BukkitFurniture tap, Player player) {
        if (!tap.isValid() || running.containsKey(tap.uuid())) {
            return;
        }
        TapPlan initial = resolve(tap);
        boolean extracting = initial != null;
        int duration = extracting ? TAKE_TICKS : DRIP_TICKS;
        setOpen(tap, true);
        tap.location().getWorld().playSound(tap.location(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1F, 0.8F);

        BukkitRunnable runnable = new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                if (!tap.isValid() || !running.containsKey(tap.uuid())) {
                    cancel();
                    return;
                }
                ticks++;
                if (ticks <= DRIP_TICKS) {
                    spawnDrip(tap, extracting, extracting && initial.hot());
                }
                if (ticks < duration) {
                    return;
                }
                running.remove(tap.uuid());
                cancel();
                if (extracting) {
                    TapPlan current = resolve(tap);
                    if (current != null) {
                        execute(current, player);
                    }
                }
                finishClose(tap);
            }
        };
        BukkitTask task = runnable.runTaskTimer(plugin, 1L, 1L);
        running.put(tap.uuid(), task);
    }

    private TapPlan resolve(BukkitFurniture tap) {
        TapGeometry geometry = geometry(tap);
        Block source = geometry.source();
        Block destination = geometry.destination();
        BukkitFurniture bottle = findFurniture(destination.getLocation().add(0.5, 0.3, 0.5), 0.9,
                EMPTY_BOTTLE).orElse(null);

        if (source.getType() == Material.WATER_CAULDRON
                || source.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
            if (canFillWaterCauldron(destination)) {
                return new TapPlan(Kind.FILL_WATER_CAULDRON, source, destination, null, null, false);
            }
            if (bottle != null) {
                return new TapPlan(Kind.BOTTLE_WATER, source, destination, null, bottle, false);
            }
        }
        if (source.getType() == Material.LAVA_CAULDRON) {
            if (plugin.getConfig().getBoolean("gameplay.infinite-lava-from-tap", true)
                    && destination.getType() == Material.CAULDRON) {
                return new TapPlan(Kind.FILL_LAVA_CAULDRON, source, destination, null, null, true);
            }
            if (bottle != null) {
                return new TapPlan(Kind.BOTTLE_MOLOTOV, source, destination, null, bottle, true);
            }
        }
        if ((source.getType() == Material.BEEHIVE || source.getType() == Material.BEE_NEST)
                && source.getBlockData() instanceof Beehive beehive && beehive.getHoneyLevel() > 0
                && bottle != null) {
            return new TapPlan(Kind.BOTTLE_HONEY, source, destination, null, bottle, true);
        }
        if ((source.getType() == Material.DRAGON_HEAD || source.getType() == Material.DRAGON_WALL_HEAD)
                && bottle != null) {
            return new TapPlan(Kind.BOTTLE_DRAGON_BREATH, source, destination, null, bottle, false);
        }
        if (source.getType() == Material.MELON && bottle != null) {
            return new TapPlan(Kind.BOTTLE_WATERMELON, source, destination, null, bottle, false);
        }
        if (bottle != null) {
            Optional<BukkitFurniture> barrel = findFurniture(tap.location(), 3.25, BARREL)
                    .filter(stations::canTapExtract);
            if (barrel.isPresent()) {
                return new TapPlan(Kind.BOTTLE_BARREL, source, destination, barrel.get(), bottle, false);
            }
        }
        return null;
    }

    private void execute(TapPlan plan, Player player) {
        boolean completed = switch (plan.kind()) {
            case FILL_WATER_CAULDRON -> fillWaterCauldron(plan.destination());
            case FILL_LAVA_CAULDRON -> fillLavaCauldron(plan.destination());
            case BOTTLE_WATER -> replaceBottle(plan.bottle(), PREFIX + "water_bottle", player);
            case BOTTLE_MOLOTOV -> replaceBottle(plan.bottle(), PREFIX + "molotov", player);
            case BOTTLE_DRAGON_BREATH -> replaceBottle(plan.bottle(), PREFIX + "dragon_breath_bottle", player);
            case BOTTLE_WATERMELON -> replaceBottle(plan.bottle(), PREFIX + "watermelon_juice", player);
            case BOTTLE_HONEY -> bottleHoney(plan, player);
            case BOTTLE_BARREL -> bottleBarrel(plan, player);
        };
        if (!completed) {
            return;
        }
        Location location = plan.destination().getLocation().add(0.5, 0.5, 0.5);
        location.getWorld().playSound(location, Sound.BLOCK_BREWING_STAND_BREW, 1F, 1F);
        location.getWorld().spawnParticle(Particle.WAX_OFF, location, 10, 0.25, 0.25, 0.25, 0.1);
    }

    private boolean bottleHoney(TapPlan plan, Player player) {
        if (!(plan.source().getBlockData() instanceof Beehive beehive) || beehive.getHoneyLevel() <= 0
                || !replaceBottle(plan.bottle(), PREFIX + "honey_bottle", player)) {
            return false;
        }
        beehive.setHoneyLevel(beehive.getHoneyLevel() - 1);
        plan.source().setBlockData(beehive, true);
        return true;
    }

    private boolean bottleBarrel(TapPlan plan, Player player) {
        if (plan.barrel() == null || plan.bottle() == null) {
            return false;
        }
        Optional<ItemStack> output = stations.takeTapOutput(plan.barrel(), player);
        if (output.isEmpty()) {
            return false;
        }
        if (replaceBottle(plan.bottle(), output.get())) {
            return true;
        }
        plan.barrel().location().getWorld().dropItemNaturally(plan.barrel().location(), output.get());
        return false;
    }

    private boolean replaceBottle(BukkitFurniture bottle, String resultId, Player player) {
        Optional<ItemStack> result = items.build(resultId, player);
        return result.isPresent() && replaceBottle(bottle, result.get());
    }

    private boolean replaceBottle(BukkitFurniture bottle, ItemStack result) {
        if (bottle == null || !bottle.isValid()) {
            return false;
        }
        String resultId = items.id(result);
        if (CraftEngineFurniture.byId(Key.of(resultId)) == null) {
            return false;
        }
        Location location = bottle.location().clone();
        CraftEngineFurniture.remove(bottle, false, false);
        BukkitFurniture replacement = CraftEngineFurniture.place(location, Key.of(resultId), "ground", false);
        if (replacement == null) {
            CraftEngineFurniture.place(location, Key.of(EMPTY_BOTTLE), "ground", false);
            return false;
        }
        ItemStack source = result.clone();
        source.setAmount(1);
        replacement.setSourceItem(BukkitAdaptor.adapt(source));
        replacement.refreshElements();
        replacement.setUnsaved();
        return true;
    }

    private static boolean canFillWaterCauldron(Block destination) {
        if (destination.getType() == Material.CAULDRON) {
            return true;
        }
        return destination.getType() == Material.WATER_CAULDRON
                && destination.getBlockData() instanceof Levelled levelled
                && levelled.getLevel() < levelled.getMaximumLevel();
    }

    private static boolean fillWaterCauldron(Block destination) {
        if (!canFillWaterCauldron(destination)) {
            return false;
        }
        Levelled state = (Levelled) Bukkit.createBlockData(Material.WATER_CAULDRON);
        state.setLevel(state.getMaximumLevel());
        destination.setBlockData(state, true);
        destination.getWorld().playSound(destination.getLocation(), Sound.ENTITY_AXOLOTL_SPLASH, 1F, 1F);
        return true;
    }

    private static boolean fillLavaCauldron(Block destination) {
        if (destination.getType() != Material.CAULDRON) {
            return false;
        }
        destination.setType(Material.LAVA_CAULDRON, true);
        destination.getWorld().playSound(destination.getLocation(), Sound.BLOCK_LAVA_POP, 1F, 1F);
        return true;
    }

    private void closeTap(BukkitFurniture tap, boolean sound) {
        BukkitTask task = running.remove(tap.uuid());
        if (task != null) {
            task.cancel();
        }
        setOpen(tap, false);
        if (sound && tap.isValid()) {
            tap.location().getWorld().playSound(tap.location(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1F, 0.8F);
        }
    }

    private void finishClose(BukkitFurniture tap) {
        if (!tap.isValid()) {
            return;
        }
        setOpen(tap, false);
        tap.location().getWorld().playSound(tap.location(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1F, 0.8F);
    }

    private static void setOpen(BukkitFurniture tap, boolean open) {
        if (!tap.isValid()) {
            return;
        }
        String current = tap.currentVariant().name();
        String base = current.endsWith("_open")
                ? current.substring(0, current.length() - "_open".length()) : current;
        tap.setVariant(open ? base + "_open" : base, true);
    }

    private static void spawnDrip(BukkitFurniture tap, boolean extracting, boolean hot) {
        Location location = tap.location().clone().add(0, -0.25, 0);
        if (extracting) {
            Particle particle = hot ? Particle.DRIPPING_LAVA : Particle.DRIPPING_WATER;
            location.getWorld().spawnParticle(particle, location, 1, 0, 0, 0, 0);
        } else {
            location.getWorld().spawnParticle(Particle.CLOUD, location, 1, 0.1, 0.1, 0.1, 0.01);
        }
    }

    private void pollRedstone() {
        List<UUID> invalid = new ArrayList<>();
        for (UUID id : loadedTaps) {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || !entity.isValid()) {
                invalid.add(id);
                continue;
            }
            BukkitFurniture tap = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (tap == null || !tap.id().toString().equals(TAP)) {
                invalid.add(id);
                continue;
            }
            Block block = geometry(tap).tapBlock();
            boolean powered = block.isBlockPowered() || block.getRelative(BlockFace.UP).isBlockPowered();
            FurnitureState state = new FurnitureState(plugin, tap);
            boolean triggered = state.bool("tap_triggered");
            if (powered && !triggered) {
                state.bool("tap_triggered", true);
                openTap(tap, null);
            } else if (!powered && triggered) {
                state.bool("tap_triggered", false);
            }
        }
        loadedTaps.removeAll(invalid);
    }

    private void bootstrapTaps() {
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (!CraftEngineFurniture.isFurniture(display)) {
                    continue;
                }
                BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(display);
                if (furniture != null && furniture.id().toString().equals(TAP)) {
                    loadedTaps.add(display.getUniqueId());
                }
            }
        }
    }

    private static TapGeometry geometry(BukkitFurniture tap) {
        Location origin = tap.location().clone();
        Vector outward = origin.getDirection().setY(0);
        if (outward.lengthSquared() < 0.001) {
            outward = new Vector(0, 0, 1);
        } else {
            outward.normalize();
        }
        Block source = origin.clone().subtract(outward.clone().multiply(0.05)).getBlock();
        Block tapBlock = origin.clone().add(outward.clone().multiply(0.05)).getBlock();
        return new TapGeometry(source, tapBlock, tapBlock.getRelative(BlockFace.DOWN));
    }

    private static Optional<BukkitFurniture> findFurniture(Location center, double radius, String id) {
        return center.getWorld().getNearbyEntities(center, radius, radius, radius).stream()
                .filter(CraftEngineFurniture::isFurniture)
                .map(CraftEngineFurniture::getLoadedFurnitureByMetaEntity)
                .filter(java.util.Objects::nonNull)
                .filter(furniture -> furniture.id().toString().equals(id))
                .min(Comparator.comparingDouble(furniture -> furniture.location().distanceSquared(center)));
    }

    private enum Kind {
        FILL_WATER_CAULDRON,
        FILL_LAVA_CAULDRON,
        BOTTLE_WATER,
        BOTTLE_MOLOTOV,
        BOTTLE_HONEY,
        BOTTLE_DRAGON_BREATH,
        BOTTLE_WATERMELON,
        BOTTLE_BARREL
    }

    private record TapGeometry(Block source, Block tapBlock, Block destination) {
    }

    private record TapPlan(Kind kind, Block source, Block destination,
                           BukkitFurniture barrel, BukkitFurniture bottle, boolean hot) {
    }
}
