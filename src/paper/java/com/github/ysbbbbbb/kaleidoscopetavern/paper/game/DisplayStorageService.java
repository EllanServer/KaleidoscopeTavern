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
import net.momirealms.craftengine.core.world.Vec3d;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
            Map.entry(PREFIX + "bar_cabinet", new StorageSpec(
                    2, null, false, StorageSemantics.Kind.BAR_CABINET)),
            Map.entry(PREFIX + "glass_bar_cabinet", new StorageSpec(
                    2, null, false, StorageSemantics.Kind.BAR_CABINET)),
            Map.entry(PREFIX + "cellar_cabinet", new StorageSpec(
                    9, PREFIX + "cellar_cabinet_blocklist", true, StorageSemantics.Kind.CELLAR_CABINET)),
            Map.entry(PREFIX + "tilted_rack", new StorageSpec(
                    3, PREFIX + "tilted_rack_blocklist", true, StorageSemantics.Kind.TILTED_RACK)),
            Map.entry(PREFIX + "circular_rack", new StorageSpec(
                    6, PREFIX + "circular_rack_blocklist", true, StorageSemantics.Kind.CIRCULAR_RACK)),
            Map.entry(PREFIX + "holder", new StorageSpec(
                    1, PREFIX + "holder_blocklist", true, StorageSemantics.Kind.HOLDER)),
            Map.entry(PREFIX + "glassware_holder", new StorageSpec(
                    4, null, false, StorageSemantics.Kind.GLASSWARE_HOLDER)));

    private final JavaPlugin plugin;
    private final ContentCatalog catalog;
    private final ItemService items;
    private final NamespacedKey cabinetVisualOwnerKey;
    private final NamespacedKey cabinetVisualSlotKey;
    private final Set<UUID> loadedLaunchers = new HashSet<>();
    private final Field savedItemField;
    private final Method saveDisplayItemMethod;
    private BukkitTask redstoneTask;
    private boolean reflectionWarningLogged;

    public DisplayStorageService(JavaPlugin plugin, ContentCatalog catalog, ItemService items) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.items = items;
        this.cabinetVisualOwnerKey = new NamespacedKey(plugin, "cabinet_visual_owner");
        this.cabinetVisualSlotKey = new NamespacedKey(plugin, "cabinet_visual_slot");
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
        redstoneTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pollRedstone, 1L, 1L);
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

        int selected = clickedSlot(furniture, spec, event.interactionPoint());
        event.setCancelled(true);
        if (selected < 0) {
            return;
        }

        if (spec.kind() == StorageSemantics.Kind.BAR_CABINET) {
            interactBarCabinet(event, spec, hand, selected);
            return;
        }
        interactStorage(player, furniture, spec, hand, selected);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(FurniturePlaceEvent event) {
        track(event.furniture());
        if (isStorage(event.furniture())) {
            Bukkit.getScheduler().runTask(plugin, () -> refreshStorageVisuals(event.furniture()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(FurnitureBreakEvent event) {
        if (isStorage(event.furniture())) {
            dropAndClearStorage(event);
            removeStorageVisuals(event.furniture());
        }
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
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            track(furniture);
            if (isStorage(furniture)) {
                Bukkit.getScheduler().runTask(plugin, () -> refreshStorageVisuals(furniture));
            }
        }
    }

    private void interactBarCabinet(FurnitureInteractEvent event, StorageSpec spec,
                                    ItemStack hand, int selected) {
        Player player = event.player();
        BukkitFurniture furniture = event.furniture();
        List<Item> stored = controllerItems(furniture, spec.slots());
        if (hand.isEmpty()) {
            if (stored.get(selected) == null || stored.get(selected).isEmpty()) {
                int other = 1 - selected;
                if (stored.get(other) != null && !stored.get(other).isEmpty()) {
                    selected = other;
                }
            }
            Item selectedItem = stored.get(selected);
            if (selectedItem == null || selectedItem.isEmpty()) {
                return;
            }
            ItemStack taken = bukkitItem(selectedItem);
            if (!setControllerItem(furniture, selected, null)) {
                return;
            }
            player.getInventory().setItemInMainHand(taken);
            playCabinetSound(furniture, true);
            return;
        }

        String handId = items.id(hand);
        if (!isBottle(handId)) {
            return;
        }
        boolean insertingIrregular = catalog.tag(IRREGULAR_TAG).contains(handId);
        boolean containsIrregular = stored.stream()
                .filter(item -> item != null && !item.isEmpty())
                .map(this::bukkitItem)
                .map(items::id)
                .anyMatch(catalog.tag(IRREGULAR_TAG)::contains);
        if (containsIrregular) {
            return;
        }
        if (insertingIrregular) {
            if (stored.stream().anyMatch(item -> item != null && !item.isEmpty())) {
                return;
            }
            selected = 0;
        } else if (stored.get(selected) != null && !stored.get(selected).isEmpty()) {
            int other = 1 - selected;
            if (stored.get(other) != null && !stored.get(other).isEmpty()) {
                return;
            }
            selected = other;
        }

        ItemStack one = hand.clone();
        one.setAmount(1);
        if (!setControllerItem(furniture, selected, BukkitAdaptor.adapt(one))) {
            return;
        }
        // BarCabinetBlock uses ItemStack#split directly and therefore also
        // consumes one bottle from creative players.
        hand.subtract(1);
        // The source selects pitch from the post-split stack. Inserting the
        // last carried bottle consequently uses its "empty hand" pitch.
        playCabinetSound(furniture, hand.isEmpty());
    }

    private void interactStorage(Player player, BukkitFurniture furniture, StorageSpec spec,
                                 ItemStack hand, int selected) {
        Item stored = controllerItem(furniture, selected);
        if (hand.isEmpty()) {
            if (stored == null || stored.isEmpty()) {
                return;
            }
            if (setControllerItem(furniture, selected, null)) {
                player.getInventory().setItemInMainHand(bukkitItem(stored));
                playStorageSound(furniture, spec, true);
            }
            return;
        }

        String handId = items.id(hand);
        if (spec.kind() == StorageSemantics.Kind.GLASSWARE_HOLDER) {
            if (!handId.equals(EMPTY_GLASSWARE)) {
                return;
            }
        } else {
            if (!isBottle(handId)) {
                player.sendActionBar(Component.translatable("message.kaleidoscope_tavern.rack.not_drink"));
                return;
            }
            if (spec.blocklistTag() != null && catalog.tag(spec.blocklistTag()).contains(handId)) {
                player.sendActionBar(Component.translatable("message.kaleidoscope_tavern.rack.irregular"));
                return;
            }
        }
        if (stored != null && !stored.isEmpty()) {
            return;
        }

        ItemStack one = hand.clone();
        one.setAmount(1);
        if (!setControllerItem(furniture, selected, BukkitAdaptor.adapt(one))) {
            return;
        }
        if (spec.kind() != StorageSemantics.Kind.GLASSWARE_HOLDER
                || player.getGameMode() != GameMode.CREATIVE) {
            hand.subtract(1);
        }
        playStorageSound(furniture, spec, false);
    }

    private int clickedSlot(BukkitFurniture furniture, StorageSpec spec, Location point) {
        if (point == null) {
            return -1;
        }
        SourcePoint source = sourcePoint(furniture, point,
                spec.kind() == StorageSemantics.Kind.GLASSWARE_HOLDER);
        return StorageSemantics.clickedSlot(spec.kind(), source.x(), source.y(), source.z(),
                facingAxisX(furniture));
    }

    private static SourcePoint sourcePoint(BukkitFurniture furniture, Location point,
                                           boolean worldAligned) {
        Location origin = furniture.location();
        double baseY = worldAligned ? origin.getY() - 1 : origin.getY();
        if (worldAligned) {
            return new SourcePoint(
                    point.getX() - origin.getX() + 0.5,
                    point.getY() - baseY,
                    point.getZ() - origin.getZ() + 0.5);
        }

        Vec3d xEnd = furniture.getRelativePosition(new Vector3f(1, 0, 0));
        Vec3d zEnd = furniture.getRelativePosition(new Vector3f(0, 0, -1));
        double deltaX = point.getX() - origin.getX();
        double deltaZ = point.getZ() - origin.getZ();
        double xBasisX = xEnd.x - origin.getX();
        double xBasisZ = xEnd.z - origin.getZ();
        double zBasisX = zEnd.x - origin.getX();
        double zBasisZ = zEnd.z - origin.getZ();
        return new SourcePoint(
                deltaX * xBasisX + deltaZ * xBasisZ + 0.5,
                point.getY() - baseY,
                deltaX * zBasisX + deltaZ * zBasisZ + 0.5);
    }

    private static boolean facingAxisX(BukkitFurniture furniture) {
        int quarterTurns = Math.floorMod(Math.round(furniture.location().getYaw() / 90F), 4);
        return (quarterTurns & 1) == 1;
    }

    private static void playStorageSound(BukkitFurniture furniture, StorageSpec spec, boolean taking) {
        String sound;
        if (spec.kind() == StorageSemantics.Kind.GLASSWARE_HOLDER) {
            sound = "minecraft:block.amethyst_block.place";
        } else {
            sound = taking ? "minecraft:entity.item_frame.remove_item" : "minecraft:block.stone.place";
        }
        furniture.location().getWorld().playSound(furniture.location(), sound, 1.0F, 1.0F);
    }

    private static void playCabinetSound(BukkitFurniture furniture, boolean taking) {
        float volume = ThreadLocalRandom.current().nextFloat() * 0.2F + 0.8F;
        float pitch = ThreadLocalRandom.current().nextFloat() * 0.2F + (taking ? 0.8F : 0.2F);
        furniture.location().getWorld().playSound(
                furniture.location(), "minecraft:block.glass.place", volume, pitch);
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
            boolean powered = isPowered(furniture);
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
            // AbstractStorageBlock first chose among every BottleBlockItem.
            // A selected plain bottle intentionally consumed the redstone
            // edge without launching, so preserve that source behavior.
            if (isBottle(id)) {
                launchable.add(slot);
            }
        }
        if (launchable.isEmpty()) {
            return;
        }
        int slot = launchable.get(ThreadLocalRandom.current().nextInt(launchable.size()));
        Item stored = controllerItem(furniture, slot);
        if (stored == null) {
            return;
        }
        ItemStack projectileItem = bukkitItem(stored);
        String projectileId = items.id(projectileItem);
        boolean molotov = projectileId.equals(MOLOTOV);
        boolean drink = catalog.hasDrinkEffects(projectileId) && !catalog.isCocktail(projectileId);
        if (!molotov && !drink) {
            return;
        }
        if (!setControllerItem(furniture, slot, null)) {
            return;
        }
        Location origin = launchOrigin(furniture);
        Vector velocity = launchVelocity(furniture);
        origin.getWorld().spawn(origin, ThrownPotion.class, potion -> {
            potion.setItem(projectileItem);
            potion.setVelocity(velocity);
        });
        if (drink) {
            origin.getWorld().playSound(origin,
                    "kaleidoscope_tavern:block.holder.pop", 0.9F, 1.0F);
        }
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

    private static boolean isPowered(BukkitFurniture furniture) {
        Block block = furniture.location().getBlock();
        Block below = block.getRelative(BlockFace.DOWN);
        return block.isBlockPowered() || block.isBlockIndirectlyPowered()
                || below.isBlockPowered() || below.isBlockIndirectlyPowered();
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
            if (isStorage(furniture)) {
                refreshStorageVisuals(furniture);
            }
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
        // AbstractStorageBlock accepted BottleBlockItem only. Vanilla bottle
        // items and cocktail GlasswareBlockItems must not enter these racks.
        return id.equals(PREFIX + "empty_bottle") || id.equals(PREFIX + "water_bottle")
                || id.equals(PREFIX + "honey_bottle") || id.equals(PREFIX + "dragon_breath_bottle")
                || id.equals(PREFIX + "xp_bottle") || id.equals(MOLOTOV)
                || catalog.hasDrinkEffects(id) && !catalog.isCocktail(id);
    }

    private Optional<ItemStack> storageRenderHelper(String storedId) {
        if (!storedId.startsWith(PREFIX)) {
            return Optional.empty();
        }
        return items.build(PREFIX + "_render/storage/" + storedId.substring(PREFIX.length()), null);
    }

    private Optional<ItemStack> storageRenderItem(ItemStack stored) {
        Optional<ItemStack> optionalHelper = storageRenderHelper(items.id(stored));
        if (optionalHelper.isEmpty()) {
            return Optional.empty();
        }
        ItemStack shown = stored.clone();
        ItemMeta shownMeta = shown.getItemMeta();
        ItemMeta helperMeta = optionalHelper.get().getItemMeta();
        if (!helperMeta.hasItemModel()) {
            return optionalHelper;
        }
        shownMeta.setItemModel(helperMeta.getItemModel());
        shown.setItemMeta(shownMeta);
        shown.setAmount(1);
        return Optional.of(shown);
    }

    private void refreshStorageVisuals(BukkitFurniture furniture) {
        StorageSpec spec = storageSpec(furniture);
        if (spec == null || !furniture.isValid() || furniture.bukkitEntity() == null) {
            return;
        }
        FurnitureState state = new FurnitureState(plugin, furniture);
        Map<Integer, ItemDisplay> displays = storageVisuals(furniture, state, spec);
        List<String> active = new ArrayList<>();
        boolean irregular = false;
        Item first = spec.kind() == StorageSemantics.Kind.BAR_CABINET
                ? controllerItem(furniture, 0) : null;
        if (first != null && !first.isEmpty()) {
            irregular = catalog.tag(IRREGULAR_TAG).contains(items.id(bukkitItem(first)));
        }
        for (int slot = 0; slot < spec.slots(); slot++) {
            Item stored = controllerItem(furniture, slot);
            if (stored == null || stored.isEmpty()) {
                ItemDisplay stale = displays.remove(slot);
                if (stale != null) {
                    stale.remove();
                }
                continue;
            }
            ItemDisplay display = displays.remove(slot);
            if (display == null) {
                display = spawnStorageVisual(furniture, slot);
            }
            configureStorageVisual(furniture, spec, display, bukkitItem(stored), slot, irregular);
            active.add(display.getUniqueId().toString());
        }
        displays.values().forEach(Entity::remove);
        state.strings("cabinet_visuals", active);
    }

    private Map<Integer, ItemDisplay> storageVisuals(BukkitFurniture furniture, FurnitureState state,
                                                     StorageSpec spec) {
        String ownerId = furniture.bukkitEntity().getUniqueId().toString();
        Map<Integer, ItemDisplay> result = new LinkedHashMap<>();
        for (String stored : state.strings("cabinet_visuals")) {
            try {
                Entity entity = Bukkit.getEntity(UUID.fromString(stored));
                if (entity instanceof ItemDisplay display && display.isValid()
                        && ownerId.equals(display.getPersistentDataContainer().get(
                        cabinetVisualOwnerKey, PersistentDataType.STRING))) {
                    putStorageVisual(result, display, spec);
                }
            } catch (IllegalArgumentException ignored) {
                // Recover stale state from the owner scan below.
            }
        }
        for (Entity entity : furniture.location().getWorld().getNearbyEntities(
                furniture.location(), 3, 3, 3, nearby -> nearby instanceof ItemDisplay)) {
            ItemDisplay display = (ItemDisplay) entity;
            if (ownerId.equals(display.getPersistentDataContainer().get(
                    cabinetVisualOwnerKey, PersistentDataType.STRING))) {
                putStorageVisual(result, display, spec);
            }
        }
        return result;
    }

    private void putStorageVisual(Map<Integer, ItemDisplay> displays, ItemDisplay candidate,
                                  StorageSpec spec) {
        int slot = candidate.getPersistentDataContainer().getOrDefault(
                cabinetVisualSlotKey, PersistentDataType.INTEGER, -1);
        if (slot < 0 || slot >= spec.slots()) {
            candidate.remove();
            return;
        }
        ItemDisplay duplicate = displays.putIfAbsent(slot, candidate);
        if (duplicate != null && duplicate != candidate) {
            candidate.remove();
        }
    }

    private ItemDisplay spawnStorageVisual(BukkitFurniture furniture, int slot) {
        String ownerId = furniture.bukkitEntity().getUniqueId().toString();
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
            display.setDisplayHeight(1F);
            display.getPersistentDataContainer().set(
                    cabinetVisualOwnerKey, PersistentDataType.STRING, ownerId);
            display.getPersistentDataContainer().set(
                    cabinetVisualSlotKey, PersistentDataType.INTEGER, slot);
        });
    }

    private void configureStorageVisual(BukkitFurniture furniture, StorageSpec spec,
                                        ItemDisplay display, ItemStack stored,
                                        int slot, boolean irregular) {
        ItemStack shown = storageRenderItem(stored).orElseGet(stored::clone);
        shown.setAmount(1);
        display.setItemStack(shown);
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        StorageSemantics.Visual visual = StorageSemantics.visual(
                spec.kind(), slot, irregular, facingAxisX(furniture));
        Location origin = furniture.location();
        Location location = origin.clone();
        if (visual.rotateWithFacing()) {
            Vec3d center = furniture.getRelativePosition(new Vector3f(
                    (float) (visual.centerX() - 0.5), 0,
                    (float) (0.5 - visual.centerZ())));
            location.setX(center.x);
            location.setY(origin.getY() + visual.centerY());
            location.setZ(center.z);
            location.setYaw(origin.getYaw() + visual.yRot());
        } else {
            location.setX(origin.getX() + visual.centerX() - 0.5);
            location.setY(origin.getY() - 1 + visual.centerY());
            location.setZ(origin.getZ() + visual.centerZ() - 0.5);
            location.setYaw(180 + visual.yRot());
        }
        location.setPitch(visual.xRot());
        display.teleport(location);
        display.setTransformation(new Transformation(
                new Vector3f(), new Quaternionf(),
                new Vector3f(visual.scale()), new Quaternionf()));
    }

    private void dropAndClearStorage(FurnitureBreakEvent event) {
        BukkitFurniture furniture = event.furniture();
        StorageSpec spec = storageSpec(furniture);
        if (spec == null) {
            return;
        }
        for (int slot = 0; slot < spec.slots(); slot++) {
            Item stored = controllerItem(furniture, slot);
            if (stored == null || stored.isEmpty()) {
                continue;
            }
            ItemStack dropped = bukkitItem(stored);
            if (setControllerItem(furniture, slot, null) && event.dropItems()) {
                event.location().getWorld().dropItemNaturally(event.location(), dropped);
            }
        }
    }

    private void removeStorageVisuals(BukkitFurniture furniture) {
        if (furniture.bukkitEntity() == null) {
            return;
        }
        StorageSpec spec = storageSpec(furniture);
        if (spec == null) {
            return;
        }
        FurnitureState state = new FurnitureState(plugin, furniture);
        storageVisuals(furniture, state, spec).values().forEach(Entity::remove);
        state.clear("cabinet_visuals");
    }

    private static boolean isStorage(BukkitFurniture furniture) {
        return storageSpec(furniture) != null;
    }

    private static StorageSpec storageSpec(BukkitFurniture furniture) {
        return furniture == null ? null : STORAGE.get(furniture.id().toString());
    }

    private void track(BukkitFurniture furniture) {
        if (furniture == null) {
            return;
        }
        StorageSpec spec = STORAGE.get(furniture.id().toString());
        Entity entity = furniture.bukkitEntity();
        if (spec != null && spec.redstoneLauncher() && entity != null) {
            loadedLaunchers.add(entity.getUniqueId());
            FurnitureState state = new FurnitureState(plugin, furniture);
            if (!state.bool("storage_power_initialized")) {
                // getStateForPlacement copied the current signal into POWERED;
                // it did not fire popBottle merely because placement happened
                // in an already-powered position.
                state.bool("storage_power_initialized", true);
                state.bool("storage_powered", isPowered(furniture));
            }
        }
    }

    private void bootstrap() {
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (CraftEngineFurniture.isFurniture(display)) {
                    BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(display);
                    track(furniture);
                    if (isStorage(furniture)) {
                        refreshStorageVisuals(furniture);
                    }
                }
            }
        }
    }

    private void warnReflectionBridge() {
        if (!reflectionWarningLogged) {
            reflectionWarningLogged = true;
            plugin.getLogger().severe("CraftEngine 26.7.4 display-slot bridge is unavailable; "
                    + "source-compatible storage interactions, visuals and launchers are disabled.");
        }
    }

    private record StorageSpec(int slots, String blocklistTag, boolean redstoneLauncher,
                               StorageSemantics.Kind kind) {
    }

    private record SourcePoint(double x, double y, double z) {
    }
}
