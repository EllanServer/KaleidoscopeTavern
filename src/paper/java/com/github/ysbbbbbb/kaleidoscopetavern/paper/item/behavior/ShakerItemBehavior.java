package com.github.ysbbbbbb.kaleidoscopetavern.paper.item.behavior;

import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.context.UseOnContext;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Routes portable shaker use through CraftEngine's existing item-use pipeline. */
public final class ShakerItemBehavior extends ItemBehavior {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "shaker_item");
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static volatile Handler handler;

    private ShakerItemBehavior() {
    }

    /** Must run from the plugin's onLoad, before CraftEngine parses projects. */
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            ItemBehaviors.register(TYPE,
                    (pack, path, id, section) -> new ShakerItemBehavior());
        }
    }

    public static void bind(Handler newHandler) {
        handler = Objects.requireNonNull(newHandler, "newHandler");
    }

    public static void unbind(Handler oldHandler) {
        if (handler == oldHandler) {
            handler = null;
        }
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context) {
        // The following furniture_item behavior owns Shift + right-click
        // placement. Ordinary right-click on a non-interactable block must use
        // the portable shaker instead of being consumed by furniture placement.
        if (context.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        return usePortable(context.getPlayer(), context.getHand());
    }

    @Override
    public InteractionResult use(World world, Player player, InteractionHand hand) {
        return usePortable(player, hand);
    }

    private static InteractionResult usePortable(Player player, InteractionHand hand) {
        Handler current = handler;
        if (current == null || player == null) {
            return InteractionResult.PASS;
        }
        InteractionResult result = current.use(player, hand);
        if (result == InteractionResult.SUCCESS_AND_CANCEL) {
            // StationService previously cancelled the vanilla interaction and
            // attempted to recreate the long consumable use state one tick
            // later. On Paper/Leaf that state can be observed as not raised by
            // the portable-shaker ticker and the mix is discarded immediately.
            // Keep the handler's gameplay decision but leave the event
            // uncancelled so the item's real consumable component starts and
            // maintains the authoritative client/server use state.
            return InteractionResult.SUCCESS;
        }
        return result;
    }

    @FunctionalInterface
    public interface Handler {
        InteractionResult use(Player player, InteractionHand hand);
    }
}
