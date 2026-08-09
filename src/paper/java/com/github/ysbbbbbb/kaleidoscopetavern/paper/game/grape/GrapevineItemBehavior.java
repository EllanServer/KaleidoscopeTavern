package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape;

import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.context.UseOnContext;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Routes grapevine planting through the grapevine item's CE interaction. */
public final class GrapevineItemBehavior extends ItemBehavior {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "grapevine_item");
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static volatile Handler handler;

    private GrapevineItemBehavior() {
    }

    /** Must run from the plugin's onLoad, before CraftEngine parses projects. */
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            ItemBehaviors.register(TYPE,
                    (pack, path, id, section) -> new GrapevineItemBehavior());
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
        Handler current = handler;
        return current == null ? InteractionResult.PASS : current.useOnBlock(context);
    }

    @FunctionalInterface
    public interface Handler {
        InteractionResult useOnBlock(UseOnContext context);
    }
}
