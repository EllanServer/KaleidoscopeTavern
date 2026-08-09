package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.drink;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.effect.EffectService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.FurnitureState;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.context.ContextHolder;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import net.momirealms.craftengine.libraries.antigrieflib.Flag;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
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
    // A DrinkBlockItem with no drink-effects entry: still a stackable drink.
    private static final String WATERMELON_JUICE = PREFIX + "watermelon_juice";
    private static final Set<String> SIMPLE_BOTTLES = Set.of(
            PREFIX + "empty_bottle", PREFIX + "water_bottle", PREFIX + "honey_bottle",
            PREFIX + "dragon_breath_bottle", PREFIX + "xp_bottle", POTION_BOTTLE, MOLOTOV);

    private final JavaPlugin plugin;
    private final ContentCatalog catalog;
    private final ItemService items;
    private final EffectService effects;
    private final BottleFurnitureBehavior.Handler interactionHandler = this::interact;

    public BottleFurnitureService(JavaPlugin plugin, ContentCatalog catalog,
                                  ItemService items, EffectService effects) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.items = items;
        this.effects = effects;
    }

    public void start() {
        BottleFurnitureBehavior.bind(interactionHandler);
    }

    public void stop() {
        BottleFurnitureBehavior.unbind(interactionHandler);
    }

    private InteractionResult interact(BukkitFurniture furniture,
                                       InteractEntityContext context) {
        Player player = (Player) context.getPlayer().platformPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        ItemStack hand = context.getHand() == InteractionHand.MAIN_HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (hand.isEmpty()) {
            return takeBottle(player, furniture)
                    ? InteractionResult.SUCCESS_AND_CANCEL
                    : InteractionResult.PASS;
        }
        if (items.id(hand).equals(furniture.id().toString())
                && maxBottleCount(furniture) > 1
                && stackBottle(player, furniture, hand)) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        return InteractionResult.PASS;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(FurnitureBreakEvent event) {
        if (!event.dropItems() || !isBottleOrGlass(event.furniture().id().toString())) {
            return;
        }
        // CE's furniture_item loot already returns the exact sourceItem for a
        // single bottle. Only a genuinely expanded stack needs a custom drop
        // list and suppression of CE's one-item loot table.
        List<ItemStack> stored = new FurnitureState(event.furniture())
                .items("bottle_items");
        if (stored.isEmpty()) {
            return;
        }
        event.setDropItems(false);
        Location dropLocation = event.location().clone();
        List<ItemStack> drops = stored.stream().map(ItemStack::clone).toList();
        // A later HIGHEST/MONITOR listener may still cancel the break. Defer
        // custom drops until event dispatch has completed so a cancelled
        // FurnitureBreakEvent cannot duplicate the stored bottles.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled() || event.dropItems()) {
                return;
            }
            drops.forEach(stack -> dropLocation.getWorld()
                    .dropItemNaturally(dropLocation, stack));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        BukkitFurniture furniture = directlyHitFurniture(event);
        if (furniture == null || !isBottleOrGlass(furniture.id().toString())
                || furniture.id().toString().equals(MOLOTOV)) {
            return;
        }

        List<ItemStack> stored = storedItems(furniture);
        // Only DrinkBlock overrides onProjectileHit to create a splash. The
        // cocktail hierarchy extends GlasswareBlock and therefore merely
        // shatters, even though drinking those items has effects.
        ItemStack strongest = isDrinkBlock(furniture.id().toString())
                ? stored.stream()
                    .max(Comparator.comparingInt(items::brewLevel))
                    .filter(stack -> items.brewLevel(stack) > 0)
                    .orElse(null)
                : null;
        // Forge spawns the ThrownPotion at the removed block's base position
        // with no initial movement. The entity then falls and produces the
        // normal delayed PotionSplashEvent at its real impact location.
        Location origin = furniture.location().getBlock().getLocation();
        if (strongest != null) {
            effects.launchSplash(strongest, origin,
                    projectile.getShooter() instanceof Entity entity ? entity : null);
        }
        // DrinkBlock launches its falling splash before BottleBlock checks
        // Projectile.mayInteract. Protection therefore keeps the bottle but
        // does not suppress the splash caused by the hit.
        if (!mayBreak(projectile, furniture)) {
            return;
        }
        CraftEngineFurniture.remove(furniture, false, false);
        origin.getWorld().playSound(origin, Sound.BLOCK_GLASS_BREAK,
                SoundCategory.BLOCKS, 1.0F, 1.0F);
        origin.getWorld().spawnParticle(Particle.BLOCK, origin, 24,
                0.25, 0.25, 0.25, 0.08, Material.GLASS.createBlockData());
    }

    /**
     * Furniture is represented by an item-display meta entity plus collision
     * entities. ProjectileHitEvent identifies the collider that was actually
     * hit; proximity lookup is deliberately forbidden because it can select a
     * neighbouring bottle when the projectile hit a block or another entity.
     */
    public static BukkitFurniture directlyHitFurniture(ProjectileHitEvent event) {
        if (event.getHitBlock() != null || event.getHitEntity() == null) {
            return null;
        }
        Entity hit = event.getHitEntity();
        BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByCollider(hit);
        if (furniture == null && CraftEngineFurniture.isFurniture(hit)) {
            furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(hit);
        }
        return furniture != null && furniture.isValid() ? furniture : null;
    }

    /** Mirrors Projectile.mayInteract while retaining CraftEngine protection hooks. */
    public static boolean mayBreak(Projectile projectile, BukkitFurniture furniture) {
        if (projectile.getShooter() instanceof Player player) {
            if (player.getGameMode() == GameMode.SPECTATOR
                    || player.getGameMode() == GameMode.ADVENTURE
                    && !furniture.config.settings().allowBreakingInAdventureMode()) {
                return false;
            }
            if (!BukkitCraftEngine.instance().antiGriefProvider()
                    .test(player, Flag.BREAK, furniture.location())) {
                return false;
            }

            ContextHolder.Builder context = ContextHolder.builder()
                    .withParameter(DirectContextParameters.FURNITURE, furniture)
                    .withParameter(DirectContextParameters.POSITION, furniture.position());
            FurnitureBreakEvent breakEvent = new FurnitureBreakEvent(player, furniture, context);
            // Projectile shattering never drops the stored bottles in Forge.
            // Set this before dispatch so this service's normal break-drop
            // listener and other integrations observe the correct semantics.
            breakEvent.setDropItems(false);
            Bukkit.getPluginManager().callEvent(breakEvent);
            return !breakEvent.isCancelled() && furniture.isValid();
        }

        if (projectile.getShooter() instanceof Entity) {
            Boolean mobGriefing = projectile.getWorld().getGameRuleValue(GameRules.MOB_GRIEFING);
            return !Boolean.FALSE.equals(mobGriefing);
        }
        // Dispensers and ownerless projectiles have no Forge owner, and
        // Projectile.mayInteract permits them.
        return true;
    }

    private boolean takeBottle(Player player, BukkitFurniture furniture) {
        List<ItemStack> stored = storedItems(furniture);
        if (stored.isEmpty()) {
            return false;
        }
        Location location = furniture.location().clone();
        ItemStack removed = stored.removeLast();
        items.give(player, removed);
        if (stored.isEmpty()) {
            // BottleBlock#use removes via silent setBlock; the pickup
            // sound below is the only audible cue, so CE's furniture
            // break sound (glass shatter) must stay muted here.
            CraftEngineFurniture.remove(furniture, player, false, false);
        } else {
            storeExpandedItems(furniture, stored);
            setBottleCount(furniture, stored.size());
            furniture.setUnsaved();
        }
        // DrinkBlock uses GLASS_PLACE on the BLOCKS channel; BottleBlock and
        // GlasswareBlock retain the source's slightly surprising STONE
        // placement sound played through the interacting player's channel.
        if (isDrinkBlock(furniture.id().toString())) {
            location.getWorld().playSound(location, Sound.BLOCK_GLASS_PLACE,
                    SoundCategory.BLOCKS, 1.0F, 1.0F);
        } else {
            location.getWorld().playSound(location, Sound.BLOCK_STONE_PLACE,
                    SoundCategory.PLAYERS, 1.0F, 1.0F);
        }
        return true;
    }

    private boolean stackBottle(Player player, BukkitFurniture furniture, ItemStack hand) {
        List<ItemStack> stored = storedItems(furniture);
        int maximum = maxBottleCount(furniture);
        if (stored.isEmpty() || stored.size() >= maximum) {
            return false;
        }
        ItemStack addition = hand.clone();
        addition.setAmount(1);
        if (player.getGameMode() != GameMode.CREATIVE) {
            hand.subtract(1);
        }
        stored.add(addition);
        storeExpandedItems(furniture, stored);
        setBottleCount(furniture, stored.size());
        furniture.setUnsaved();
        // BlockItem#place volume (glass 1.0 + 1) / 2 and pitch * 0.8.
        player.getWorld().playSound(furniture.location(), Sound.BLOCK_GLASS_PLACE,
                SoundCategory.BLOCKS, 1.0F, 0.8F);
        return true;
    }

    private List<ItemStack> storedItems(BukkitFurniture furniture) {
        List<ItemStack> stored = new ArrayList<>();
        if (maxBottleCount(furniture) > 1) {
            stored.addAll(new FurnitureState(furniture).items("bottle_items"));
            if (!stored.isEmpty()) {
                return stored;
            }
        }
        ItemStack fallback = sourceItem(furniture);
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

    private static void storeExpandedItems(BukkitFurniture furniture, List<ItemStack> stored) {
        // CE already persists the first bottle as sourceItem. Store an
        // explicit list only while extra bottles with potentially different
        // brew levels/effects are stacked on the same furniture.
        new FurnitureState(furniture).items("bottle_items",
                BottleFurnitureSemantics.needsExpandedItemState(stored.size())
                        ? stored : List.of());
    }

    private boolean isBottleOrGlass(String id) {
        return SIMPLE_BOTTLES.contains(id) || id.equals(PREFIX + "empty_glassware")
                || id.equals(WATERMELON_JUICE)
                || catalog.hasDrinkEffects(id) || catalog.isCocktail(id);
    }

    private boolean isDrinkBlock(String id) {
        return id.equals(WATERMELON_JUICE)
                || catalog.hasDrinkEffects(id) && !catalog.isCocktail(id);
    }

    private static int maxBottleCount(BukkitFurniture furniture) {
        int maximum = 1;
        while (furniture.config.variants().containsKey("ground_count_" + (maximum + 1))) {
            maximum++;
        }
        return maximum;
    }

    private static void setBottleCount(BukkitFurniture furniture, int count) {
        furniture.setVariant(BottleFurnitureSemantics.variantForCount(count), true);
    }
}
