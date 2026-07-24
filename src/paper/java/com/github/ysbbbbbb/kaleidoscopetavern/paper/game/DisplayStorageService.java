package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.behavior.DisplayItemFurnitureBehaviorTemplate.DisplayItemFurnitureController;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.item.Item;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Adds legacy bottle restrictions and redstone launch behavior to CE display-slot furniture. */
public final class DisplayStorageService implements Listener {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final String IRREGULAR_TAG = PREFIX + "bar_cabinet_irregular";
    private static final String EMPTY_GLASSWARE = PREFIX + "empty_glassware";
    private static final String MOLOTOV = PREFIX + "molotov";
    private static final Map<String, StorageSpec> STORAGE = Map.ofEntries(
            Map.entry(PREFIX + "bar_cabinet", new StorageSpec(2, null, false, true, false)),
            Map.entry(PREFIX + "glass_bar_cabinet", new StorageSpec(2, null, false, true, false)),
            Map.entry(PREFIX + "cellar_cabinet", new StorageSpec(
                    9, PREFIX + "cellar_cabinet_blocklist", true, false, false)),
            Map.entry(PREFIX + "tilted_rack", new StorageSpec(
                    3, PREFIX + "tilted_rack_blocklist", true, false, false)),
            Map.entry(PREFIX + "circular_rack", new StorageSpec(
                    6, PREFIX + "circular_rack_blocklist", true, false, false)),
            Map.entry(PREFIX + "holder", new StorageSpec(
                    1, PREFIX + "holder_blocklist", true, false, false)),
            Map.entry(PREFIX + "glassware_holder", new StorageSpec(4, null, false, false, true)));

    private final JavaPlugin plugin;
    private final ContentCatalog catalog;
    private final ItemService items;
    private final Set<UUID> loadedLaunchers = new HashSet<>();
    private final Field savedItemField;
    private final Method saveDisplayItemMethod;
    private BukkitTask redstoneTask;
    private boolean reflectionWarningLogged;

    public DisplayStorageService(JavaPlugin plugin, ContentCatalog catalog, ItemService items) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.items = items;
        Field itemField = null;
        Method saveMethod = null;
        try {
            itemField = DisplayItemFurnitureController.class.getDeclaredField("savedItem");
            saveMethod = DisplayItemFurnitureController.class.getDeclaredMethod("saveDisplayItem", Item.class);
            if (!itemField.trySetAccessible() || !saveMethod.trySetAccessible()) {
                itemField = null;
                saveMethod = null;
            }
        } catch (ReflectiveOperationException ignored) {
            // A clear warning is logged lazily only if the pinned CE bridge is actually needed.
        }
        this.savedItemField = itemField;
        this.saveDisplayItemMethod = saveMethod;
    }

    public void start() {
        redstoneTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pollRedstone, 4L, 4L);
        Bukkit.getScheduler().runTask(plugin, this::bootstrap);
    }

    public void stop() {
        if (redstoneTask != null) {
            redstoneTask.cancel();
            redstoneTask = null;
        }
        loadedLaunchers.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(FurnitureInteractEvent event) {
        if (event.hand() != InteractionHand.MAIN_HAND) {
            return;
        }
        BukkitFurniture furniture = event.furniture();
        StorageSpec spec = STORAGE.get(furniture.id().toString());
        if (spec == null) {
            return;
        }
        Player player = event.player();
        ItemStack hand = player.getInventory().getItemInMainHand();

        if (hand.isEmpty()) {
            if (spec.barCabinet() && takeIrregularBottle(player, furniture, spec)) {
                event.setCancelled(true);
            }
            return;
        }

        String handId = items.id(hand);
        if (spec.glasswareOnly()) {
            if (!handId.equals(EMPTY_GLASSWARE)) {
                event.setCancelled(true);
            }
            return;
        }
        if (!isBottle(handId)) {
            event.setCancelled(true);
            player.sendActionBar(Component.translatable("message.kaleidoscope_tavern.rack.not_drink"));
            return;
        }
        if (spec.blocklistTag() != null && catalog.tag(spec.blocklistTag()).contains(handId)) {
            event.setCancelled(true);
            player.sendActionBar(Component.translatable("message.kaleidoscope_tavern.rack.irregular"));
            return;
        }
        if (spec.barCabinet() && handleBarCabinetInsertion(player, furniture, spec, hand, handId)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(FurniturePlaceEvent event) {
        track(event.furniture());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(FurnitureBreakEvent event) {
        Entity entity = event.furniture().bukkitEntity();
        if (entity != null) {
            loadedLaunchers.remove(entity.getUniqueId());
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (!(entity instanceof ItemDisplay) || !CraftEngineFurniture.isFurniture(entity)) {
                continue;
            }
            track(CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity));
        }
    }

    private boolean handleBarCabinetInsertion(Player player, BukkitFurniture furniture, StorageSpec spec,
                                              ItemStack hand, String handId) {
        boolean insertingIrregular = catalog.tag(IRREGULAR_TAG).contains(handId);
        List<Item> stored = controllerItems(furniture, spec.slots());
        boolean containsIrregular = stored.stream()
                .filter(item -> item != null && !item.isEmpty())
                .map(this::bukkitItem)
                .map(items::id)
                .anyMatch(catalog.tag(IRREGULAR_TAG)::contains);
        if (containsIrregular) {
            return true;
        }
        if (!insertingIrregular) {
            return false;
        }
        if (stored.stream().anyMatch(item -> item != null && !item.isEmpty())) {
            return true;
        }
        ItemStack one = hand.clone();
        one.setAmount(1);
        if (!setControllerItem(furniture, 0, BukkitAdaptor.adapt(one))) {
            return true;
        }
        if (!player.getGameMode().isInvulnerable()) {
            hand.subtract(1);
        }
        player.getWorld().playSound(furniture.location(), "minecraft:block.glass.place", 0.9F, 0.8F);
        return true;
    }

    private boolean takeIrregularBottle(Player player, BukkitFurniture furniture, StorageSpec spec) {
        for (int slot = 0; slot < spec.slots(); slot++) {
            Item stored = controllerItem(furniture, slot);
            if (stored == null || stored.isEmpty()) {
                continue;
            }
            ItemStack stack = bukkitItem(stored);
            if (!catalog.tag(IRREGULAR_TAG).contains(items.id(stack))) {
                return false;
            }
            if (!setControllerItem(furniture, slot, null)) {
                return false;
            }
            player.getInventory().setItemInMainHand(stack);
            player.getWorld().playSound(furniture.location(), "minecraft:entity.item_frame.remove_item", 0.9F, 1F);
            return true;
        }
        return false;
    }

    private void pollRedstone() {
        List<UUID> invalid = new ArrayList<>();
        for (UUID uuid : loadedLaunchers) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity == null || !entity.isValid()) {
                invalid.add(uuid);
                continue;
            }
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            StorageSpec spec = furniture == null ? null : STORAGE.get(furniture.id().toString());
            if (spec == null || !spec.redstoneLauncher()) {
                invalid.add(uuid);
                continue;
            }
            Block block = furniture.location().getBlock();
            Block below = block.getRelative(BlockFace.DOWN);
            boolean powered = block.isBlockPowered() || block.isBlockIndirectlyPowered()
                    || below.isBlockPowered() || below.isBlockIndirectlyPowered();
            FurnitureState state = new FurnitureState(plugin, furniture);
            boolean wasPowered = state.bool("storage_powered");
            if (powered != wasPowered) {
                state.bool("storage_powered", powered);
                if (powered) {
                    launchRandomBottle(furniture, spec);
                }
            }
        }
        loadedLaunchers.removeAll(invalid);
    }

    private void launchRandomBottle(BukkitFurniture furniture, StorageSpec spec) {
        List<Integer> launchable = new ArrayList<>();
        for (int slot = 0; slot < spec.slots(); slot++) {
            Item stored = controllerItem(furniture, slot);
            if (stored == null || stored.isEmpty()) {
                continue;
            }
            String id = items.id(bukkitItem(stored));
            if (id.equals(MOLOTOV) || catalog.hasDrinkEffects(id) && !catalog.isCocktail(id)) {
                launchable.add(slot);
            }
        }
        if (launchable.isEmpty()) {
            return;
        }
        int slot = launchable.get(ThreadLocalRandom.current().nextInt(launchable.size()));
        Item stored = controllerItem(furniture, slot);
        if (stored == null || !setControllerItem(furniture, slot, null)) {
            return;
        }
        ItemStack projectileItem = bukkitItem(stored);
        Location origin = launchOrigin(furniture);
        Vector velocity = launchVelocity(furniture);
        origin.getWorld().spawn(origin, ThrownPotion.class, potion -> {
            potion.setItem(projectileItem);
            potion.setVelocity(velocity);
        });
        origin.getWorld().playSound(origin, "kaleidoscope_tavern:block.holder.pop", 0.9F, 1.0F);
    }

    private Location launchOrigin(BukkitFurniture furniture) {
        Location location = furniture.location().clone();
        Vector forward = horizontalDirection(location);
        return switch (furniture.id().toString()) {
            case PREFIX + "holder" -> location.add(forward.clone().multiply(0.5)).add(0, 0.875, 0);
            case PREFIX + "tilted_rack" -> location.subtract(forward.clone().multiply(0.5)).add(0, 0.875, 0);
            case PREFIX + "cellar_cabinet" -> location.add(forward.clone().multiply(0.5)).add(0, 0.5, 0);
            default -> location.add(0, 0.5, 0);
        };
    }

    private Vector launchVelocity(BukkitFurniture furniture) {
        double factor = switch (furniture.id().toString()) {
            case PREFIX + "circular_rack", PREFIX + "cellar_cabinet" ->
                    ThreadLocalRandom.current().nextDouble(0.5, 2.5);
            default -> ThreadLocalRandom.current().nextDouble(0.5, 1.5);
        };
        Vector forward = horizontalDirection(furniture.location());
        return switch (furniture.id().toString()) {
            case PREFIX + "circular_rack" -> new Vector(0, factor, 0);
            case PREFIX + "tilted_rack" -> forward.multiply(-factor).setY(0.75 * factor);
            case PREFIX + "holder" -> forward.multiply(factor).setY(0.375 * factor);
            default -> forward.multiply(factor).setY(0.1 * factor);
        };
    }

    private static Vector horizontalDirection(Location location) {
        Vector direction = location.getDirection().setY(0);
        return direction.lengthSquared() < 1.0E-6 ? new Vector(0, 0, 1) : direction.normalize();
    }

    boolean hasAnyStoredItem(BukkitFurniture furniture) {
        StorageSpec spec = STORAGE.get(furniture.id().toString());
        if (spec == null) {
            return false;
        }
        for (int slot = 0; slot < spec.slots(); slot++) {
            Item stored = controllerItem(furniture, slot);
            if (stored != null && !stored.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private List<Item> controllerItems(BukkitFurniture furniture, int slots) {
        List<Item> result = new ArrayList<>(slots);
        for (int slot = 0; slot < slots; slot++) {
            result.add(controllerItem(furniture, slot));
        }
        return result;
    }

    private Item controllerItem(BukkitFurniture furniture, int slot) {
        DisplayItemFurnitureController controller = furniture.controller.get(DisplayItemFurnitureController.class, slot);
        if (controller == null || savedItemField == null) {
            warnReflectionBridge();
            return null;
        }
        try {
            return (Item) savedItemField.get(controller);
        } catch (IllegalAccessException exception) {
            warnReflectionBridge();
            return null;
        }
    }

    private boolean setControllerItem(BukkitFurniture furniture, int slot, Item item) {
        DisplayItemFurnitureController controller = furniture.controller.get(DisplayItemFurnitureController.class, slot);
        if (controller == null || saveDisplayItemMethod == null) {
            warnReflectionBridge();
            return false;
        }
        try {
            saveDisplayItemMethod.invoke(controller, item);
            furniture.refreshElements();
            furniture.setUnsaved();
            return true;
        } catch (IllegalAccessException | InvocationTargetException exception) {
            warnReflectionBridge();
            return false;
        }
    }

    private ItemStack bukkitItem(Item item) {
        if (item instanceof BukkitItem bukkitItem) {
            ItemStack result = bukkitItem.getBukkitItem().clone();
            result.setAmount(1);
            return result;
        }
        return new ItemStack(Material.AIR);
    }

    private boolean isBottle(String id) {
        return id.equals("minecraft:potion") || id.equals("minecraft:honey_bottle")
                || id.equals("minecraft:dragon_breath") || id.equals("minecraft:experience_bottle")
                || id.equals(PREFIX + "empty_bottle") || id.equals(PREFIX + "water_bottle")
                || id.equals(PREFIX + "honey_bottle") || id.equals(PREFIX + "dragon_breath_bottle")
                || id.equals(PREFIX + "xp_bottle") || id.equals(MOLOTOV)
                || catalog.hasDrinkEffects(id) && !catalog.isCocktail(id);
    }

    private void track(BukkitFurniture furniture) {
        if (furniture == null) {
            return;
        }
        StorageSpec spec = STORAGE.get(furniture.id().toString());
        Entity entity = furniture.bukkitEntity();
        if (spec != null && spec.redstoneLauncher() && entity != null) {
            loadedLaunchers.add(entity.getUniqueId());
        }
    }

    private void bootstrap() {
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (CraftEngineFurniture.isFurniture(display)) {
                    track(CraftEngineFurniture.getLoadedFurnitureByMetaEntity(display));
                }
            }
        }
    }

    private void warnReflectionBridge() {
        if (!reflectionWarningLogged) {
            reflectionWarningLogged = true;
            plugin.getLogger().severe("CraftEngine 26.7.4 display-slot bridge is unavailable; "
                    + "bar-cabinet irregular bottles and redstone rack launchers are disabled.");
        }
    }

    private record StorageSpec(int slots, String blocklistTag, boolean redstoneLauncher,
                               boolean barCabinet, boolean glasswareOnly) {
    }
}
