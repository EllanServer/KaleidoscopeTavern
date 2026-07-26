package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;

import java.util.List;
import java.util.Set;

/** Places bottle-shaped content as furniture without turning it into custom blocks. */
public final class BottlePlacementService implements Listener {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final String POTION_BOTTLE = PREFIX + "potion_bottle";
    private static final Set<String> DISPENSABLE_BOTTLES = Set.of(
            PREFIX + "empty_bottle", PREFIX + "molotov", PREFIX + "water_bottle",
            PREFIX + "honey_bottle", PREFIX + "dragon_breath_bottle", PREFIX + "xp_bottle",
            PREFIX + "watermelon_juice"
    );

    private final JavaPlugin plugin;
    private final ContentCatalog catalog;
    private final ItemService items;

    public BottlePlacementService(JavaPlugin plugin, ContentCatalog catalog, ItemService items) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.items = items;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlaceVanillaBottle(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() == null
                || !event.getPlayer().isSneaking() || event.getClickedBlock() == null
                || event.getItem() == null) {
            return;
        }
        Placement placement = placementFor(event.getItem());
        if (placement == null || placement.configPath() != null
                && !plugin.getConfig().getBoolean(placement.configPath(), true)) {
            return;
        }
        Block clicked = event.getClickedBlock();
        Block target = clicked.isReplaceable() ? clicked : clicked.getRelative(event.getBlockFace());
        if (!canPlaceAt(target)) {
            return;
        }
        // Forge's BottleBlockItem was still a normal BlockItem: the clicked
        // point selected the target block, but never offset the bottle inside
        // that block. Preserve the target-centred, cardinal placement.
        Location location = target.getLocation().add(0.5, 0, 0.5);
        location.setYaw(snapRotation(180F + event.getPlayer().getYaw()));
        BukkitFurniture furniture = CraftEngineFurniture.place(location, Key.of(placement.furniture()), "ground", true);
        if (furniture == null) {
            return;
        }
        ItemStack source = event.getItem().clone();
        source.setAmount(1);
        furniture.setSourceItem(BukkitAdaptor.adapt(source));
        furniture.refreshElements();
        furniture.setUnsaved();
        FurnitureState state = new FurnitureState(plugin, furniture);
        state.items("bottle_items", List.of(source));
        if (placement.storePotion()) {
            state.item("placed_potion", source);
        }
        consumeUnlessCreative(event.getPlayer(), event.getItem());
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        target.getWorld().playSound(location, Sound.BLOCK_GLASS_PLACE, 1F, 1F);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispenseBottle(BlockDispenseEvent event) {
        if (event.getBlock().getType() != Material.DISPENSER
                || !(event.getBlock().getBlockData() instanceof Directional directional)) {
            return;
        }
        String id = items.id(event.getItem());
        if (!isDispensableBottle(id) || CraftEngineFurniture.byId(Key.of(id)) == null) {
            return;
        }
        // BottleBlockDispenseBehavior is optional: if placement is blocked it
        // leaves the stack in the dispenser instead of falling back to the
        // base item's drop/projectile behavior.
        event.setCancelled(true);
        Block target = event.getBlock().getRelative(directional.getFacing());
        if (!canPlaceAt(target)) {
            return;
        }
        Location location = target.getLocation().add(0.5, 0, 0.5);
        location.setYaw(snapRotation(180F + facingYaw(directional.getFacing())));
        BukkitFurniture furniture = CraftEngineFurniture.place(location, Key.of(id), "ground", true);
        if (furniture == null) {
            return;
        }
        ItemStack source = event.getItem().clone();
        source.setAmount(1);
        furniture.setSourceItem(BukkitAdaptor.adapt(source));
        furniture.refreshElements();
        furniture.setUnsaved();
        new FurnitureState(plugin, furniture).items("bottle_items", List.of(source));
        if (!takeOneFromDispenser(event.getBlock().getState(), source)) {
            CraftEngineFurniture.remove(furniture, false, false);
            return;
        }
        target.getWorld().playSound(location, Sound.BLOCK_GLASS_PLACE, 1F, 1F);
    }

    private Placement placementFor(ItemStack stack) {
        String customId = items.id(stack);
        if (catalog.hasDrinkEffects(customId) || catalog.isCocktail(customId)) {
            // DrinkBlockItem/CocktailBlockItem placement is unconditional;
            // only the five vanilla bottle families had Forge config gates.
            return new Placement(customId, null, false);
        }
        return switch (stack.getType()) {
            case POTION -> {
                PotionType type = stack.getItemMeta() instanceof PotionMeta potion
                        ? potion.getBasePotionType() : null;
                boolean water = type == null || type == PotionType.WATER;
                yield new Placement(water ? PREFIX + "water_bottle" : POTION_BOTTLE,
                        water ? "bottle-placement.water" : "bottle-placement.potion", !water);
            }
            case HONEY_BOTTLE -> new Placement(PREFIX + "honey_bottle", "bottle-placement.honey", false);
            case DRAGON_BREATH -> new Placement(PREFIX + "dragon_breath_bottle", "bottle-placement.dragon-breath", false);
            case EXPERIENCE_BOTTLE -> new Placement(PREFIX + "xp_bottle", "bottle-placement.experience", false);
            default -> null;
        };
    }

    private boolean isDispensableBottle(String id) {
        return DISPENSABLE_BOTTLES.contains(id)
                || catalog.hasDrinkEffects(id) && !catalog.isCocktail(id);
    }

    private static boolean canPlaceAt(Block target) {
        if (!target.isReplaceable()) {
            return false;
        }
        Location center = target.getLocation().add(0.5, 0.4, 0.5);
        for (Entity entity : target.getWorld().getNearbyEntities(center, 0.32, 0.4, 0.32)) {
            if (CraftEngineFurniture.isFurniture(entity)) {
                return false;
            }
        }
        return true;
    }

    private static boolean takeOneFromDispenser(BlockState state, ItemStack expected) {
        if (!(state instanceof Container container)) {
            return false;
        }
        Inventory inventory = container.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current != null && current.isSimilar(expected)) {
                current.subtract(1);
                return true;
            }
        }
        return false;
    }

    private static float snapRotation(float yaw) {
        return Math.round(yaw / 90F) * 90F;
    }

    private static float facingYaw(org.bukkit.block.BlockFace face) {
        return switch (face) {
            case NORTH -> 180F;
            case WEST -> 90F;
            case EAST -> -90F;
            default -> 0F;
        };
    }

    private static void consumeUnlessCreative(Player player, ItemStack stack) {
        if (player.getGameMode() != GameMode.CREATIVE) {
            stack.subtract(1);
        }
    }

    private record Placement(String furniture, String configPath, boolean storePotion) {
    }
}
