package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.RedstoneFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.StorageInteractionFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.StorageVisualFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.behavior.DisplayItemFurnitureBehaviorTemplate.DisplayItemFurnitureController;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** Adds legacy bottle restrictions and redstone launch behavior to CE display-slot furniture. */
public final class DisplayStorageService implements Listener {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final String IRREGULAR_TAG = PREFIX + "bar_cabinet_irregular";
    private static final String EMPTY_GLASSWARE = PREFIX + "empty_glassware";
    private static final String MOLOTOV = PREFIX + "molotov";
    private static final String WATERMELON_JUICE = PREFIX + "watermelon_juice";
    private static final Map<Key, StorageSpec> STORAGE = Map.ofEntries(
            Map.entry(Key.of(PREFIX + "bar_cabinet"), new StorageSpec(
                    2, null, false, StorageSemantics.Kind.BAR_CABINET)),
            Map.entry(Key.of(PREFIX + "glass_bar_cabinet"), new StorageSpec(
                    2, null, false, StorageSemantics.Kind.BAR_CABINET)),
            Map.entry(Key.of(PREFIX + "cellar_cabinet"), new StorageSpec(
                    9, PREFIX + "cellar_cabinet_blocklist", true, StorageSemantics.Kind.CELLAR_CABINET)),
            Map.entry(Key.of(PREFIX + "tilted_rack"), new StorageSpec(
                    3, PREFIX + "tilted_rack_blocklist", true, StorageSemantics.Kind.TILTED_RACK)),
            Map.entry(Key.of(PREFIX + "circular_rack"), new StorageSpec(
                    6, PREFIX + "circular_rack_blocklist", true, StorageSemantics.Kind.CIRCULAR_RACK)),
            Map.entry(Key.of(PREFIX + "holder"), new StorageSpec(
                    1, PREFIX + "holder_blocklist", true, StorageSemantics.Kind.HOLDER)),
            Map.entry(Key.of(PREFIX + "glassware_holder"), new StorageSpec(
                    4, null, false, StorageSemantics.Kind.GLASSWARE_HOLDER)));

    private final JavaPlugin plugin;
    private final ContentCatalog catalog;
    private final ItemService items;
    private final Field savedItemField;
    private final Method saveDisplayItemMethod;
    private boolean reflectionWarningLogged;
    private final StorageVisualFurnitureBehavior.Handler storageVisualHandler =
            this::storageVisual;
    private final StorageInteractionFurnitureBehavior.Handler storageInteractionHandler =
            this::interact;
    private final RedstoneFurnitureBehavior.Handler storageRedstoneHandler =
            (furniture, powered, initial) -> {
                if (initial || !powered) {
                    return;
                }
                StorageSpec spec = STORAGE.get(furniture.id());
                if (spec != null && spec.redstoneLauncher()) {
                    launchRandomBottle(furniture, spec);
                }
            };

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
        StorageInteractionFurnitureBehavior.bind(storageInteractionHandler);
        StorageVisualFurnitureBehavior.bind(storageVisualHandler);
        RedstoneFurnitureBehavior.bind(
                RedstoneFurnitureBehavior.Channel.STORAGE, storageRedstoneHandler);
    }

    public void stop() {
        StorageInteractionFurnitureBehavior.unbind(storageInteractionHandler);
        StorageVisualFurnitureBehavior.unbind(storageVisualHandler);
        RedstoneFurnitureBehavior.unbind(
                RedstoneFurnitureBehavior.Channel.STORAGE, storageRedstoneHandler);
    }

    private InteractionResult interact(BukkitFurniture furniture,
                                       InteractEntityContext context) {
        StorageSpec spec = STORAGE.get(furniture.id());
        if (spec == null) {
            return InteractionResult.PASS;
        }
        if (context.getHand() != InteractionHand.MAIN_HAND) {
            // Consume the off-hand use before CE's following native slot
            // controllers, preventing duplicate insertion/removal.
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        Player player = (Player) context.getPlayer().platformPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        Vec3d click = context.getClickLocation();
        Location interactionPoint = new Location(
                furniture.location().getWorld(), click.x, click.y, click.z);

        int selected = clickedSlot(furniture, spec, interactionPoint);
        if (selected < 0) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        if (spec.kind() == StorageSemantics.Kind.BAR_CABINET) {
            interactBarCabinet(player, furniture, spec, hand, selected);
        } else {
            interactStorage(player, furniture, spec, hand, selected);
        }
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(FurnitureBreakEvent event) {
        if (isStorage(event.furniture())) {
            dropAndClearStorage(event);
        }
    }

    private void interactBarCabinet(Player player, BukkitFurniture furniture,
                                    StorageSpec spec, ItemStack hand, int selected) {
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
        furniture.location().getWorld().playSound(furniture.location(),
                sound, SoundCategory.BLOCKS, 1.0F, 1.0F);
    }

    private static void playCabinetSound(BukkitFurniture furniture, boolean taking) {
        float volume = ThreadLocalRandom.current().nextFloat() * 0.2F + 0.8F;
        float pitch = ThreadLocalRandom.current().nextFloat() * 0.2F + (taking ? 0.8F : 0.2F);
        furniture.location().getWorld().playSound(furniture.location(),
                "minecraft:block.glass.place", SoundCategory.BLOCKS, volume, pitch);
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
        boolean drink = isBottleDrink(projectileId);
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
                    "kaleidoscope_tavern:block.holder.pop", SoundCategory.BLOCKS, 0.9F, 1.0F);
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

    boolean hasAnyStoredItem(BukkitFurniture furniture) {
        StorageSpec spec = STORAGE.get(furniture.id());
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
        DisplayItemFurnitureController controller = displayController(furniture, slot);
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
        DisplayItemFurnitureController controller = displayController(furniture, slot);
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

    private static DisplayItemFurnitureController displayController(
            BukkitFurniture furniture, int slot) {
        if (slot < 0) {
            return null;
        }
        int ordinal = 0;
        // FurnitureController#get uses the absolute index in the complete
        // behavior array. State/lifecycle controllers precede CE display
        // slots, so a storage slot number cannot be passed through directly.
        for (int index = 0; index < furniture.config.behaviors().size(); index++) {
            DisplayItemFurnitureController candidate = furniture.controller.get(
                    DisplayItemFurnitureController.class, index);
            if (candidate != null && ordinal++ == slot) {
                return candidate;
            }
        }
        return null;
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
                || isBottleDrink(id);
    }

    private boolean isBottleDrink(String id) {
        return id.equals(WATERMELON_JUICE)
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

    private StorageVisualFurnitureBehavior.Visual storageVisual(
            BukkitFurniture furniture, int slot) {
        StorageSpec spec = storageSpec(furniture);
        if (spec == null || slot < 0 || slot >= spec.slots()) {
            return null;
        }
        Item stored = controllerItem(furniture, slot);
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        boolean irregular = false;
        if (spec.kind() == StorageSemantics.Kind.BAR_CABINET) {
            Item first = controllerItem(furniture, 0);
            irregular = first != null && !first.isEmpty()
                    && catalog.tag(IRREGULAR_TAG).contains(items.id(bukkitItem(first)));
        }
        ItemStack storedStack = bukkitItem(stored);
        ItemStack shown = storageRenderItem(storedStack).orElseGet(storedStack::clone);
        shown.setAmount(1);
        StorageSemantics.Visual visual = StorageSemantics.visual(
                spec.kind(), slot, irregular, facingAxisX(furniture));
        return new StorageVisualFurnitureBehavior.Visual(
                BukkitAdaptor.adapt(shown),
                visual.centerX(), visual.centerY(), visual.centerZ(),
                visual.scale(), visual.yRot(), visual.xRot(),
                visual.rotateWithFacing());
    }

    private void dropAndClearStorage(FurnitureBreakEvent event) {
        BukkitFurniture furniture = event.furniture();
        StorageSpec spec = storageSpec(furniture);
        if (spec == null) {
            return;
        }
        List<ItemStack> drops = new ArrayList<>();
        for (int slot = 0; slot < spec.slots(); slot++) {
            Item stored = controllerItem(furniture, slot);
            if (stored != null && !stored.isEmpty()) {
                drops.add(bukkitItem(stored));
            }
        }
        if (drops.isEmpty()) {
            return;
        }
        // A later HIGHEST/MONITOR listener may still cancel the break; defer
        // the clear and the drops so cancellation cannot wipe or duplicate
        // the stored bottles (same pattern as BottleFurnitureService).
        Location dropLocation = event.location().clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled()) {
                return;
            }
            if (furniture.isValid()) {
                for (int slot = 0; slot < spec.slots(); slot++) {
                    setControllerItem(furniture, slot, null);
                }
            }
            if (event.dropItems()) {
                drops.forEach(stack ->
                        dropLocation.getWorld().dropItemNaturally(dropLocation, stack));
            }
        });
    }

    private static boolean isStorage(BukkitFurniture furniture) {
        return storageSpec(furniture) != null;
    }

    private static StorageSpec storageSpec(BukkitFurniture furniture) {
        return furniture == null ? null : STORAGE.get(furniture.id());
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
