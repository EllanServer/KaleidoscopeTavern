package com.github.ysbbbbbb.kaleidoscopetavern.paper.item.behavior;

import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.World;

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
    public InteractionResult use(World world, Player player, InteractionHand hand) {
        Handler current = handler;
        return current == null || player == null
                ? InteractionResult.PASS
                : current.use(player, hand);
    }

    @FunctionalInterface
    public interface Handler {
        InteractionResult use(Player player, InteractionHand hand);
    }
}
