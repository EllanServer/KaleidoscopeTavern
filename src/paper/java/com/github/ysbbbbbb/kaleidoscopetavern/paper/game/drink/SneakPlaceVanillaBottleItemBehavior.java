package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.drink;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.integration.TheBrewingProjectCompat;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.bukkit.item.behavior.FurnitureItemBehavior;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Routes vanilla bottle stacks into CraftEngine's native furniture placement. */
public final class SneakPlaceVanillaBottleItemBehavior extends ItemBehavior {
    public static final Key TYPE = Key.of(
            "kaleidoscope_tavern", "sneak_place_vanilla_bottle");
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static volatile JavaPlugin plugin;

    private final Placement directPlacement;
    private final Placement waterPlacement;
    private final Placement potionPlacement;
    private final boolean anyPlacementEnabled;

    private SneakPlaceVanillaBottleItemBehavior(Map<Route, Placement> placements) {
        boolean potionRouting = placements.containsKey(Route.WATER)
                || placements.containsKey(Route.POTION);
        if (potionRouting) {
            if (placements.keySet().stream().anyMatch(route ->
                    route != Route.WATER && route != Route.POTION)) {
                throw new IllegalArgumentException(
                        "Potion routes cannot be mixed with fixed vanilla-bottle routes");
            }
            this.directPlacement = null;
            this.waterPlacement = placements.get(Route.WATER);
            this.potionPlacement = placements.get(Route.POTION);
        } else {
            if (placements.size() != 1) {
                throw new IllegalArgumentException(
                        "A non-potion vanilla bottle behavior requires exactly one route");
            }
            this.directPlacement = placements.values().iterator().next();
            this.waterPlacement = null;
            this.potionPlacement = null;
        }
        this.anyPlacementEnabled = placements.values().stream().anyMatch(placement ->
                placement != null);
    }

    /** Must run from the plugin's onLoad, before CraftEngine parses projects. */
    public static void register(JavaPlugin owner) {
        plugin = owner;
        if (REGISTERED.compareAndSet(false, true)) {
            ItemBehaviors.register(TYPE, SneakPlaceVanillaBottleItemBehavior::create);
        }
    }

    private static SneakPlaceVanillaBottleItemBehavior create(
            Pack pack, Path path, Key id, ConfigSection section) {
        ConfigSection configuredPlacements = section.getNonNullSection("placements");
        Map<Route, Placement> placements = new EnumMap<>(Route.class);
        JavaPlugin owner = plugin;
        if (owner == null) {
            throw new IllegalStateException(
                    "Vanilla bottle behavior was parsed before Tavern registration");
        }
        for (String routeName : configuredPlacements.keySet()) {
            Route route = Route.valueOf(routeName.toUpperCase(Locale.ROOT));
            ConfigSection placement = configuredPlacements.getNonNullSection(routeName);
            String configPath = placement.getNonEmptyString("config");
            placements.put(route, owner.getConfig().getBoolean(configPath, true)
                    ? new Placement(FurnitureItemBehavior.FACTORY.create(
                            pack, path, id, placement))
                    : null);
        }
        return new SneakPlaceVanillaBottleItemBehavior(placements);
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context) {
        // CraftEngine only calls this behavior for the four vanilla item ids
        // carrying it. Disabled routes can therefore bypass even the sneak
        // query; fixed-material routes need no Bukkit conversion or map lookup.
        if (!anyPlacementEnabled || !context.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }

        Placement placement = directPlacement;
        if (placement == null) {
            if (!(context.getItem() instanceof BukkitItem bukkitItem)) {
                return InteractionResult.PASS;
            }
            ItemStack stack = bukkitItem.getBukkitItem();
            if (stack.getType() != Material.POTION
                    || TheBrewingProjectCompat.isBrew(stack)) {
                return InteractionResult.PASS;
            }
            placement = potionPlacement(stack);
        }
        if (placement == null) {
            return InteractionResult.PASS;
        }

        // CE owns the clicked surface, collision/protection checks, placement
        // events, source item, custom data, consumption, hand swing and sound.
        // Cancel the underlying vanilla use for both successful and rejected
        // sneak-placement attempts, matching the former Paper listener.
        placement.furnitureItem().place(context);
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    private Placement potionPlacement(ItemStack stack) {
        PotionType type = stack.getItemMeta() instanceof PotionMeta potion
                ? potion.getBasePotionType() : null;
        return type == null || type == PotionType.WATER
                ? waterPlacement : potionPlacement;
    }

    private enum Route {
        WATER,
        POTION,
        HONEY,
        DRAGON_BREATH,
        EXPERIENCE
    }

    private record Placement(FurnitureItemBehavior furnitureItem) {
    }
}
