package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.drink;

import net.momirealms.craftengine.bukkit.item.behavior.FurnitureItemBehavior;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.context.UseOnContext;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the normal held-vessel action while delegating sneak-placement to
 * CraftEngine's native furniture item implementation.
 */
public final class SneakPlaceDrinkItemBehavior extends ItemBehavior {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "sneak_place_drink");
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private final FurnitureItemBehavior furnitureItem;

    private SneakPlaceDrinkItemBehavior(FurnitureItemBehavior furnitureItem) {
        this.furnitureItem = furnitureItem;
    }

    /** Must run from the plugin's onLoad, before CraftEngine parses projects. */
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            ItemBehaviors.register(TYPE, (pack, path, id, section) ->
                    new SneakPlaceDrinkItemBehavior(
                            FurnitureItemBehavior.FACTORY.create(pack, path, id, section)));
        }
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context) {
        if (!context.isSecondaryUseActive()) {
            // Let drinking, throwing or portable shaking continue through the
            // ordinary CE/vanilla item-use pipeline.
            return InteractionResult.PASS;
        }

        // CE owns the clicked surface height, anchor variant, alignment,
        // collision, protection checks, events, source item and consumption.
        // Passing the original context is what makes partial-height supports
        // such as bottom slabs work without Tavern-side block exceptions.
        return furnitureItem.useOnBlock(context);
    }
}
