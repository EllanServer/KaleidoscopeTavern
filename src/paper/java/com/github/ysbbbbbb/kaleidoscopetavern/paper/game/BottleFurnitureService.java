package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.item.Item;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Restores the non-block bottle, glassware and stacked-drink interactions. */
public final class BottleFurnitureService implements Listener {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final String POTION_BOTTLE = PREFIX + "potion_bottle";
    private static final String MOLOTOV = PREFIX + "molotov";
    private static final Set<String> SIMPLE_BOTTLES = Set.of(
            PREFIX + "empty_bottle", PREFIX + "water_bottle", PREFIX + "honey_bottle",
            PREFIX + "dragon_breath_bottle", PREFIX + "xp_bottle", POTION_BOTTLE, MOLOTOV);

    private final JavaPlugin plugin;
    private final ContentCatalog catalog;
    private final ItemService items;
    private final EffectService effects;

    public BottleFurnitureService(JavaPlugin plugin, ContentCatalog catalog,
                                  ItemService items, EffectService effects) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.items = items;
        this.effects = effects;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(FurniturePlaceEvent event) {
        BukkitFurniture furniture = event.furniture();
        if (!isBottleOrGlass(furniture.id().toString())) {
            return;
        }
        ItemStack source = sourceItem(furniture);
        if (source != null) {
            new FurnitureState(plugin, furniture).items("bottle_items", List.of(source));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(FurnitureInteractEvent event) {
        if (event.hand() != InteractionHand.MAIN_HAND) {
            return;
        }
        BukkitFurniture furniture = event.furniture();
        String furnitureId = furniture.id().toString();
        if (!isBottleOrGlass(furnitureId)) {
            return;
        }
        ItemStack hand = event.player().getInventory().getItemInMainHand();
        if (hand.isEmpty()) {
            takeBottle(event, furniture);
            return;
        }
        if (items.id(hand).equals(furnitureId) && maxBottleCount(furniture) > 1) {
            stackBottle(event, furniture, hand);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(FurnitureBreakEvent event) {
        if (!event.dropItems() || !isBottleOrGlass(event.furniture().id().toString())) {
            return;
        }
        List<ItemStack> stored = storedItems(event.furniture());
        if (stored.isEmpty()) {
            return;
        }
        event.setDropItems(false);
        stored.forEach(stack -> event.location().getWorld().dropItemNaturally(event.location(), stack));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        Location impact = projectile.getLocation();
        BukkitFurniture furniture = impact.getWorld().getNearbyEntities(impact, 0.85, 0.9, 0.85).stream()
                .filter(CraftEngineFurniture::isFurniture)
                .map(CraftEngineFurniture::getLoadedFurnitureByMetaEntity)
                .filter(candidate -> candidate != null && candidate.isValid())
                .filter(candidate -> isBottleOrGlass(candidate.id().toString()))
                .filter(candidate -> !candidate.id().toString().equals(MOLOTOV))
                .distinct()
                .min(Comparator.comparingDouble(candidate -> candidate.location().distanceSquared(impact)))
                .orElse(null);
        if (furniture == null) {
            return;
        }

        List<ItemStack> stored = storedItems(furniture);
        ItemStack strongest = stored.stream()
                .max(Comparator.comparingInt(stack -> catalog.isCocktail(items.id(stack))
                        ? 1 : items.brewLevel(stack)))
                .orElse(null);
        Location origin = furniture.location().clone().add(0, 0.35, 0);
        CraftEngineFurniture.remove(furniture, false, false);
        if (strongest != null) {
            effects.splash(strongest, origin, projectile.getShooter() instanceof Entity entity ? entity : null);
        }
        origin.getWorld().playSound(origin, Sound.BLOCK_GLASS_BREAK, 1.0F, 1.0F);
        origin.getWorld().spawnParticle(Particle.BLOCK, origin, 24,
                0.25, 0.25, 0.25, 0.08, Material.GLASS.createBlockData());
    }

    private void takeBottle(FurnitureInteractEvent event, BukkitFurniture furniture) {
        List<ItemStack> stored = storedItems(furniture);
        if (stored.isEmpty()) {
            return;
        }
        Location location = furniture.location().clone();
        ItemStack removed = stored.removeLast();
        items.give(event.player(), removed);
        if (stored.isEmpty()) {
            CraftEngineFurniture.remove(furniture, event.player(), false, true);
        } else {
            new FurnitureState(plugin, furniture).items("bottle_items", stored);
            setBottleCount(furniture, stored.size());
            furniture.setUnsaved();
        }
        event.setCancelled(true);
        location.getWorld().playSound(location, Sound.BLOCK_GLASS_PLACE, 0.8F, 1.15F);
    }

    private void stackBottle(FurnitureInteractEvent event, BukkitFurniture furniture, ItemStack hand) {
        List<ItemStack> stored = storedItems(furniture);
        int maximum = maxBottleCount(furniture);
        if (stored.isEmpty() || stored.size() >= maximum) {
            return;
        }
        ItemStack addition = hand.clone();
        addition.setAmount(1);
        if (!event.player().getGameMode().isInvulnerable()) {
            hand.subtract(1);
        }
        stored.add(addition);
        new FurnitureState(plugin, furniture).items("bottle_items", stored);
        setBottleCount(furniture, stored.size());
        furniture.setUnsaved();
        event.setCancelled(true);
        event.player().getWorld().playSound(furniture.location(), Sound.BLOCK_GLASS_PLACE, 0.8F, 0.9F);
    }

    private List<ItemStack> storedItems(BukkitFurniture furniture) {
        FurnitureState state = new FurnitureState(plugin, furniture);
        List<ItemStack> stored = new ArrayList<>(state.items("bottle_items"));
        if (!stored.isEmpty()) {
            return stored;
        }
        ItemStack fallback = state.item("placed_potion");
        if (fallback == null) {
            fallback = sourceItem(furniture);
        }
        if (fallback != null) {
            stored.add(fallback);
        }
        return stored;
    }

    private ItemStack sourceItem(BukkitFurniture furniture) {
        Item source = furniture.sourceItem();
        if (source instanceof BukkitItem bukkitItem && !source.isEmpty()) {
            ItemStack stack = bukkitItem.getBukkitItem().clone();
            stack.setAmount(1);
            return stack;
        }
        return items.build(furniture.id().toString(), null).orElse(null);
    }

    private boolean isBottleOrGlass(String id) {
        return SIMPLE_BOTTLES.contains(id) || id.equals(PREFIX + "empty_glassware")
                || catalog.hasDrinkEffects(id) || catalog.isCocktail(id);
    }

    private static int maxBottleCount(BukkitFurniture furniture) {
        int maximum = 1;
        while (furniture.config.variants().containsKey("ground_count_" + (maximum + 1))) {
            maximum++;
        }
        return maximum;
    }

    private static void setBottleCount(BukkitFurniture furniture, int count) {
        String variant = count <= 1 ? "ground" : "ground_count_" + count;
        furniture.setVariant(variant, true);
    }
}
