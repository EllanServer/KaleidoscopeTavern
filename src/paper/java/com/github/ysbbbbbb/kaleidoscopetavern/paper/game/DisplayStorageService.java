package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.StorageBlockBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.StorageBlockConfig;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.StorageInteractionFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.StorageVisualFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.behavior.DisplayItemFurnitureBehaviorTemplate.DisplayItemFurnitureController;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
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

/** Preserves source bottle storage semantics across CE blocks and remaining display furniture. */
public final class DisplayStorageService {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final String EMPTY_GLASSWARE = PREFIX + "empty_glassware";
    private static final String MOLOTOV = PREFIX + "molotov";
    private static final String WATERMELON_JUICE = PREFIX + "watermelon_juice";
    // Glassware holder is the sole storage family that remains furniture.
    // Block-backed storage is described by each CE block's
    // `kaleidoscope_tavern:storage` configuration.
    private static final Map<Key, StorageSpec> FURNITURE_STORAGE = Map.ofEntries(
            Map.entry(Key.of(PREFIX + "glassware_holder"), new StorageSpec(
                    4, null, StorageSemantics.Kind.GLASSWARE_HOLDER)));

    private final JavaPlugin plugin;
    private final ContentCatalog catalog;
    private final ItemService items;
    private final Field savedItemField;
    private final Method saveDisplayItemMethod;
    private boolean reflectionWarningLogged;
    private final StorageVisualFurnitureBehavior.Handler storageVisualHandler =
            this::storageVisual;
    private final StorageInteractionFurnitureBehavior.Handler storageInteractionHandler =
            new StorageInteractionFurnitureBehavior.Handler() {
                @Override
                public InteractionResult interact(BukkitFurniture furniture,
                                                  InteractEntityContext context) {
                    return DisplayStorageService.this.interact(furniture, context);
                }

                @Override
                public void onRemove(BukkitFurniture furniture, boolean dropItems) {
                    dropAndClearStorage(furniture, dropItems);
                }
            };
    private final StorageBlockBehavior.Handler storageBlockHandler =
            new StorageBlockBehavior.Handler() {
                @Override
                public Item visualItem(StorageBlockBehavior.Controller controller, int slot) {
                    return storageBlockVisual(controller, slot);
                }

                @Override
                public void launch(StorageBlockBehavior.Controller controller, Item item,
                                   StorageBlockConfig.Launch launch) {
                    launchConfiguredItem(controller, item, launch);
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
        StorageBlockBehavior.bind(storageBlockHandler);
    }

    public void stop() {
        StorageBlockBehavior.unbind(storageBlockHandler);
        StorageInteractionFurnitureBehavior.unbind(storageInteractionHandler);
        StorageVisualFurnitureBehavior.unbind(storageVisualHandler);
    }

    private InteractionResult interact(BukkitFurniture furniture,
                                       InteractEntityContext context) {
        StorageSpec spec = FURNITURE_STORAGE.get(furniture.id());
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

        interactStorage(player, furniture, spec, hand, selected);
        return InteractionResult.SUCCESS_AND_CANCEL;
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
        playStorageSound(furniture.location(), spec, taking);
    }

    private static void playStorageSound(Location location, StorageSpec spec, boolean taking) {
        String sound;
        if (spec.kind() == StorageSemantics.Kind.GLASSWARE_HOLDER) {
            sound = "minecraft:block.amethyst_block.place";
        } else {
            sound = taking ? "minecraft:entity.item_frame.remove_item" : "minecraft:block.stone.place";
        }
        location.getWorld().playSound(location,
                sound, SoundCategory.BLOCKS, 1.0F, 1.0F);
    }

    private void launchConfiguredItem(
            StorageBlockBehavior.Controller controller,
            Item stored,
            StorageBlockConfig.Launch launch) {
        ItemStack projectileItem = bukkitItem(stored);
        if (projectileItem.isEmpty()) {
            return;
        }
        Location origin = controller.location();
        Vector facing = horizontalDirection(controller.facing());
        origin.add(facing.clone().multiply(launch.originForward()))
                .add(0, launch.originY(), 0);

        double factor = launch.factorMax() <= launch.factorMin()
                ? launch.factorMin()
                : ThreadLocalRandom.current().nextDouble(
                        launch.factorMin(), launch.factorMax());
        Vector velocity = switch (launch.direction()) {
            case FACING -> facing.multiply(factor).setY(launch.verticalFactor() * factor);
            case OPPOSITE -> facing.multiply(-factor).setY(launch.verticalFactor() * factor);
            case UP -> new Vector(0, factor, 0);
        };
        origin.getWorld().spawn(origin, ThrownPotion.class, potion -> {
            potion.setItem(projectileItem);
            potion.setVelocity(velocity);
        });
        if (launch.sound() != null && launch.soundItems().contains(stored.id())) {
            playConfiguredSound(origin, launch.sound());
        }
    }

    private static void playConfiguredSound(
            Location location, StorageBlockConfig.ConfiguredSound sound) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        location.getWorld().playSound(
                location, sound.id(), SoundCategory.BLOCKS,
                sound.sampleVolume(random), sound.samplePitch(random));
    }

    private static Vector horizontalDirection(Direction direction) {
        return new Vector(direction.stepX(), 0, direction.stepZ());
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
        return setControllerItem(furniture, slot, item, true);
    }

    private boolean setControllerItem(BukkitFurniture furniture, int slot, Item item,
                                      boolean refreshVisual) {
        DisplayItemFurnitureController controller = displayController(furniture, slot);
        if (controller == null || saveDisplayItemMethod == null) {
            warnReflectionBridge();
            return false;
        }
        try {
            saveDisplayItemMethod.invoke(controller, item);
            // CE's native controller owns the item and dirty flag. Tavern only
            // invalidates its transform cache before CE redistributes visuals.
            if (refreshVisual) {
                StorageVisualFurnitureBehavior.refresh(furniture);
            }
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

    private Optional<ItemStack> storageRenderHelper(String storedId, String prefix) {
        if (!storedId.startsWith(PREFIX)) {
            return Optional.empty();
        }
        return items.buildVisual(prefix + storedId.substring(PREFIX.length()));
    }

    private Optional<ItemStack> storageRenderItem(ItemStack stored, String prefix) {
        Optional<ItemStack> optionalHelper = storageRenderHelper(items.id(stored), prefix);
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
        ItemStack storedStack = bukkitItem(stored);
        ItemStack shown = storageRenderItem(
                storedStack, PREFIX + "_render/storage/").orElseGet(storedStack::clone);
        shown.setAmount(1);
        StorageSemantics.Visual visual = StorageSemantics.visual(
                spec.kind(), slot, false, facingAxisX(furniture));
        return new StorageVisualFurnitureBehavior.Visual(
                BukkitAdaptor.adapt(shown),
                visual.centerX(), visual.centerY(), visual.centerZ(),
                visual.scale(), visual.yRot(), visual.xRot(),
                visual.rotateWithFacing());
    }

    private Item storageBlockVisual(StorageBlockBehavior.Controller controller, int slot) {
        if (slot < 0 || slot >= controller.slots()) {
            return Item.empty();
        }
        Item stored = controller.item(slot);
        if (stored == null || stored.isEmpty()) {
            return Item.empty();
        }
        ItemStack storedStack = bukkitItem(stored);
        ItemStack shown = storageRenderItem(
                storedStack, controller.config().renderItemPrefix())
                .orElseGet(storedStack::clone);
        shown.setAmount(1);
        return BukkitAdaptor.adapt(shown);
    }

    private void dropAndClearStorage(BukkitFurniture furniture, boolean dropItems) {
        StorageSpec spec = storageSpec(furniture);
        if (spec == null) {
            return;
        }
        List<Item> drops = dropItems ? new ArrayList<>() : List.of();
        for (int slot = 0; slot < spec.slots(); slot++) {
            Item stored = controllerItem(furniture, slot);
            if (stored != null && !stored.isEmpty()) {
                if (dropItems) {
                    drops.add(stored);
                }
                // This controller precedes all native display-slot
                // controllers. Clear their state before CE invokes their
                // preRemove hooks, preventing duplicate drops without a
                // pointless visual refresh on a furniture being destroyed.
                setControllerItem(furniture, slot, null, false);
            }
        }
        drops.forEach(item ->
                furniture.world().dropItemNaturally(furniture.position(), item));
    }

    private static StorageSpec storageSpec(BukkitFurniture furniture) {
        return furniture == null ? null : FURNITURE_STORAGE.get(furniture.id());
    }

    private void warnReflectionBridge() {
        if (!reflectionWarningLogged) {
            reflectionWarningLogged = true;
            plugin.getLogger().severe("CraftEngine 26.7.4 display-slot bridge is unavailable; "
                    + "source-compatible storage interactions, visuals and launchers are disabled.");
        }
    }

    private record StorageSpec(int slots, String blocklistTag, StorageSemantics.Kind kind) {
    }

    private record SourcePoint(double x, double y, double z) {
    }
}
