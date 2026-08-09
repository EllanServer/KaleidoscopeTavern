package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.drink;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.integration.TheBrewingProjectCompat;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureAttemptPlaceEvent;
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.util.CollisionUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.FurnitureVariant;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBoxConfig;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.plugin.context.ContextHolder;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.collision.AABB;
import net.momirealms.craftengine.libraries.antigrieflib.Flag;
import net.momirealms.craftengine.proxy.bukkit.craftbukkit.CraftWorldProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.AABBProxy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Places bottle-shaped content as furniture without turning it into custom blocks. */
public final class BottlePlacementService implements Listener {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final String POTION_BOTTLE = PREFIX + "potion_bottle";
    private static final String WATERMELON_JUICE = PREFIX + "watermelon_juice";
    private static final Set<String> DISPENSABLE_BOTTLES = Set.of(
            PREFIX + "empty_bottle", PREFIX + "molotov", PREFIX + "water_bottle",
            PREFIX + "honey_bottle", PREFIX + "dragon_breath_bottle", PREFIX + "xp_bottle"
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
        // Never hijack TheBrewingProject brews: TBP's sealing mechanic
        // (sneak + right-click a crafting table with paper in the off hand)
        // runs at NORMAL priority and would be starved by this LOW handler
        // cancelling the event first.
        if (TheBrewingProjectCompat.isBrew(event.getItem())) {
            return;
        }
        // Custom drinks are handled later by CE's sneak-place vessel item
        // behavior. Returning here is essential: this LOW listener must not
        // cancel the event before CE's HIGHEST item dispatcher sees it.
        if (isPlaceableDrink(items.id(event.getItem()))) {
            return;
        }
        Placement placement = placementFor(event.getItem());
        if (placement == null || placement.configPath() != null
                && !plugin.getConfig().getBoolean(placement.configPath(), true)) {
            return;
        }

        // This listener owns recognized sneak-placement. Deny the underlying
        // Bukkit interaction before validation so a rejected CE placement can
        // neither consume the item nor fall through to another use behavior.
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        if (event.getPlayer().getGameMode() == GameMode.ADVENTURE) {
            return;
        }

        Block clicked = event.getClickedBlock();
        Block target = clicked.isReplaceable() ? clicked : clicked.getRelative(event.getBlockFace());
        // Forge's BottleBlockItem was still a normal BlockItem: the clicked
        // point selected the target block, but never offset the bottle inside
        // that block. Preserve the target-centred, cardinal placement.
        Location location = target.getLocation().add(0.5, 0, 0.5);
        location.setYaw(snapRotation(180F + event.getPlayer().getYaw()));

        FurnitureDefinition definition = CraftEngineFurniture.byId(Key.of(placement.furniture()));
        FurnitureVariant variant = definition == null ? null : definition.getVariant("ground");
        if (variant == null || !canPlaceAt(location, variant)
                || !BukkitCraftEngine.instance().antiGriefProvider()
                .test(event.getPlayer(), Flag.PLACE, location)) {
            return;
        }

        ContextHolder.Builder context = ContextHolder.builder();
        InteractionHand hand = event.getHand() == EquipmentSlot.OFF_HAND
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        FurnitureAttemptPlaceEvent attempt = new FurnitureAttemptPlaceEvent(
                event.getPlayer(), definition, variant, location, hand, clicked, context);
        Bukkit.getPluginManager().callEvent(attempt);
        if (attempt.isCancelled()) {
            return;
        }

        // CE's public placement API does not perform the checks above. It can,
        // however, play the configured placement sound; keep that disabled and
        // emit the original bottle's glass sound exactly once below.
        BukkitFurniture furniture = CraftEngineFurniture.place(location, definition, "ground", false);
        if (furniture == null) {
            return;
        }
        ItemStack source = event.getItem().clone();
        source.setAmount(1);
        try {
            furniture.setSourceItem(BukkitAdaptor.adapt(source));
            furniture.refreshElements();
            furniture.setUnsaved();

            FurniturePlaceEvent placed = new FurniturePlaceEvent(
                    event.getPlayer(), furniture, location, hand, context);
            Bukkit.getPluginManager().callEvent(placed);
            if (placed.isCancelled()) {
                CraftEngineFurniture.remove(furniture, false, false);
                return;
            }
        } catch (RuntimeException exception) {
            CraftEngineFurniture.remove(furniture, false, false);
            plugin.getLogger().warning("Failed to initialize placed bottle furniture: "
                    + exception.getMessage());
            return;
        }
        consumeUnlessCreative(event.getPlayer(), event.getItem());
        // BlockItem#place: (glass volume 1.0 + 1) / 2 and pitch * 0.8.
        target.getWorld().playSound(location, Sound.BLOCK_GLASS_PLACE,
                SoundCategory.BLOCKS, 1F, 0.8F);
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

    private Placement placementFor(ItemStack stack) {
        return switch (stack.getType()) {
            case POTION -> {
                PotionType type = stack.getItemMeta() instanceof PotionMeta potion
                        ? potion.getBasePotionType() : null;
                boolean water = type == null || type == PotionType.WATER;
                yield new Placement(water ? PREFIX + "water_bottle" : POTION_BOTTLE,
                        water ? "bottle-placement.water" : "bottle-placement.potion");
            }
            case HONEY_BOTTLE -> new Placement(PREFIX + "honey_bottle", "bottle-placement.honey");
            case DRAGON_BREATH -> new Placement(PREFIX + "dragon_breath_bottle", "bottle-placement.dragon-breath");
            case EXPERIENCE_BOTTLE -> new Placement(PREFIX + "xp_bottle", "bottle-placement.experience");
            default -> null;
        };
    }

    private boolean isDispensableBottle(String id) {
        return DISPENSABLE_BOTTLES.contains(id)
                || isBottleDrink(id);
    }

    private boolean isPlaceableDrink(String id) {
        return isBottleDrink(id) || catalog.isCocktail(id);
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

    private static void consumeUnlessCreative(Player player, ItemStack stack) {
        if (player.getGameMode() != GameMode.CREATIVE) {
            stack.subtract(1);
        }
    }

    private record Placement(String furniture, String configPath) {
    }
}
