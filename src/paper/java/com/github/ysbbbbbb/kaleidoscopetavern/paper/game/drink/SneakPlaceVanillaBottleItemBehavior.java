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

    private final Map<Route, Placement> placements;

    private SneakPlaceVanillaBottleItemBehavior(Map<Route, Placement> placements) {
        this.placements = Map.copyOf(placements);
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
        for (String routeName : configuredPlacements.keySet()) {
            Route route = Route.valueOf(routeName.toUpperCase(Locale.ROOT));
            ConfigSection placement = configuredPlacements.getNonNullSection(routeName);
            placements.put(route, new Placement(
                    FurnitureItemBehavior.FACTORY.create(pack, path, id, placement),
                    placement.getNonEmptyString("config")));
        }
        return new SneakPlaceVanillaBottleItemBehavior(placements);
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context) {
        if (!context.isSecondaryUseActive()
                || !(context.getItem() instanceof BukkitItem bukkitItem)) {
            return InteractionResult.PASS;
        }

        ItemStack stack = bukkitItem.getBukkitItem();
        if (TheBrewingProjectCompat.isBrew(stack)) {
            return InteractionResult.PASS;
        }
        Placement placement = placements.get(routeFor(stack));
        JavaPlugin owner = plugin;
        if (placement == null || owner == null
                || !owner.getConfig().getBoolean(placement.configPath(), true)) {
            return InteractionResult.PASS;
        }

        // CE owns the clicked surface, collision/protection checks, placement
        // events, source item, custom data, consumption, hand swing and sound.
        // Cancel the underlying vanilla use for both successful and rejected
        // sneak-placement attempts, matching the former Paper listener.
        placement.furnitureItem().useOnBlock(context);
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    private static Route routeFor(ItemStack stack) {
        return switch (stack.getType()) {
            case POTION -> {
                PotionType type = stack.getItemMeta() instanceof PotionMeta potion
                        ? potion.getBasePotionType() : null;
                yield type == null || type == PotionType.WATER ? Route.WATER : Route.POTION;
            }
            case HONEY_BOTTLE -> Route.HONEY;
            case DRAGON_BREATH -> Route.DRAGON_BREATH;
            case EXPERIENCE_BOTTLE -> Route.EXPERIENCE;
            default -> null;
        };
    }

    private enum Route {
        WATER,
        POTION,
        HONEY,
        DRAGON_BREATH,
        EXPERIENCE
    }

    private record Placement(FurnitureItemBehavior furnitureItem, String configPath) {
    }
}
