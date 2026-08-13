package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.drink;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.util.CollisionUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.FurnitureVariant;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBoxConfig;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.collision.AABB;
import net.momirealms.craftengine.proxy.bukkit.craftbukkit.CraftWorldProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.AABBProxy;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Preserves the legacy dispenser path; player placement is owned by CE item behaviors. */
public final class BottlePlacementService implements Listener {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final String WATERMELON_JUICE = PREFIX + "watermelon_juice";
    private static final Set<String> DISPENSABLE_BOTTLES = Set.of(
            PREFIX + "empty_bottle", PREFIX + "molotov", PREFIX + "water_bottle",
            PREFIX + "honey_bottle", PREFIX + "dragon_breath_bottle", PREFIX + "xp_bottle"
    );

    private final ContentCatalog catalog;
    private final ItemService items;

    public BottlePlacementService(ContentCatalog catalog, ItemService items) {
        this.catalog = catalog;
        this.items = items;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispenseBottle(BlockDispenseEvent event) {
        if (event.getBlock().getType() != Material.DISPENSER
                || !(event.getBlock().getBlockData() instanceof Directional directional)) {
            return;
        }
        String id = items.id(event.getItem());
        if (!isDispensableBottle(id)) {
            return;
        }
        // BottleBlockDispenseBehavior is optional: if placement is blocked it
        // leaves the stack in the dispenser instead of falling back to the
        // base item's drop/projectile behavior.
        event.setCancelled(true);
        Block target = event.getBlock().getRelative(directional.getFacing());
        Location location = target.getLocation().add(0.5, 0, 0.5);
        location.setYaw(snapRotation(180F + facingYaw(directional.getFacing())));
        FurnitureDefinition definition = CraftEngineFurniture.byId(Key.of(id));
        FurnitureVariant variant = definition == null ? null : definition.getVariant("ground");
        if (variant == null || !canPlaceAt(location, variant)) {
            return;
        }
        BukkitFurniture furniture = CraftEngineFurniture.place(location, definition, "ground", false);
        if (furniture == null) {
            return;
        }
        ItemStack source = event.getItem().clone();
        source.setAmount(1);
        furniture.setSourceItem(BukkitAdaptor.adapt(source));
        furniture.refreshElements();
        furniture.setUnsaved();
        if (!takeOneFromDispenser(event.getBlock().getState(), source)) {
            CraftEngineFurniture.remove(furniture, false, false);
            return;
        }
        // BlockItem#place: (glass volume 1.0 + 1) / 2 and pitch * 0.8.
        target.getWorld().playSound(location, Sound.BLOCK_GLASS_PLACE,
                SoundCategory.BLOCKS, 1F, 0.8F);
    }

    private boolean isDispensableBottle(String id) {
        return DISPENSABLE_BOTTLES.contains(id)
                || isBottleDrink(id);
    }

    private boolean isBottleDrink(String id) {
        return id.equals(WATERMELON_JUICE)
                || catalog.hasDrinkEffects(id) && !catalog.isCocktail(id);
    }

    private static boolean canPlaceAt(Location placement, FurnitureVariant variant) {
        if (!placement.getBlock().isReplaceable()) {
            return false;
        }
        WorldPosition furniturePosition = LocationUtils.toWorldPosition(placement);
        List<AABB> boxes = new ArrayList<>();
        for (FurnitureHitBoxConfig<?> hitBox : variant.hitBoxConfigs()) {
            hitBox.prepareBoundingBox(furniturePosition, boxes::add, false);
        }
        if (boxes.isEmpty()) {
            return true;
        }
        List<Object> nativeBoxes = new ArrayList<>(boxes.size());
        for (AABB box : boxes) {
            nativeBoxes.add(AABBProxy.INSTANCE.newInstance(
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
        }
        Object nativeWorld = CraftWorldProxy.INSTANCE.getWorld(placement.getWorld());
        return CollisionUtils.test(nativeWorld, nativeBoxes, EntityProxy.INSTANCE::getBlocksBuilding);
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
            case SOUTH -> 0F;
            // Vertical dispensers hand BottleBlock a NORTH placement context,
            // which getStateForPlacement's opposite turns into FACING=SOUTH.
            default -> 180F;
        };
    }

}
