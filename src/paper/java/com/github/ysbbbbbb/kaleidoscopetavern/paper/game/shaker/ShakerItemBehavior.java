package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.shaker;

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
        // The following furniture_item behavior is an intentional fallback:
        // Shift + right-click places the shaker, while ordinary right-click on
        // a non-interactable block must enter the same portable mixing path as
        // right-clicking air. Without this override, CompositeItemBehavior sees
        // PASS here and the furniture behavior consumes every block click.
        if (!shouldUsePortableOnBlock(context.isSecondaryUseActive())) {
            return InteractionResult.PASS;
        }
        return dispatch(context.getPlayer(), context.getHand());
    }

    @Override
    public InteractionResult use(World world, Player player, InteractionHand hand) {
        return dispatch(player, hand);
    }

    static boolean shouldUsePortableOnBlock(boolean secondaryUseActive) {
        return !secondaryUseActive;
    }

    private static InteractionResult dispatch(Player player, InteractionHand hand) {
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
