package com.github.ysbbbbbb.kaleidoscopetavern.paper.item.behavior;

import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.ListPersistentDataType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Routes portable shaker use through CraftEngine's existing item-use pipeline. */
public final class ShakerItemBehavior extends ItemBehavior {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "shaker_item");
    private static final ListPersistentDataType<byte[], byte[]> BYTE_ARRAY_LIST =
            PersistentDataType.LIST.byteArrays();
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
        // StationService returns SUCCESS_AND_CANCEL for both rejected uses and
        // a valid three-ingredient mix. Only the latter must leave the vanilla
        // consumable interaction uncancelled: its real item-use state is what
        // keeps isHandRaised() true until release. Inspect the already-persisted
        // shaker payload before dispatch so invalid/result-filled shakers still
        // receive the source-compatible action-bar rejection without entering a
        // one-hour dummy use state.
        boolean shouldStartUsing = hasThreeIngredientsWithoutResult(
                player.getItemInHand(hand));
        InteractionResult result = current.use(player, hand);
        return shouldStartUsing && result == InteractionResult.SUCCESS_AND_CANCEL
                ? InteractionResult.SUCCESS
                : result;
    }

    private static boolean hasThreeIngredientsWithoutResult(Item item) {
        if (!(item instanceof BukkitItem bukkitItem) || item.isEmpty()) {
            return false;
        }
        ItemStack stack = bukkitItem.getBukkitItem();
        PersistentDataContainer data = stack.getPersistentDataContainer();
        NamespacedKey ingredientKey = findKey(data, "shaker_ingredients");
        if (ingredientKey == null) {
            return false;
        }
        List<byte[]> encoded = data.get(ingredientKey, BYTE_ARRAY_LIST);
        if (encoded == null || encoded.size() != 3) {
            return false;
        }
        for (byte[] entry : encoded) {
            try {
                if (entry == null || ItemStack.deserializeBytes(entry).isEmpty()) {
                    return false;
                }
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        NamespacedKey resultKey = findKey(data, "shaker_result");
        return resultKey == null || !data.has(resultKey, PersistentDataType.BYTE_ARRAY);
    }

    private static NamespacedKey findKey(PersistentDataContainer data, String value) {
        for (NamespacedKey key : data.getKeys()) {
            if (key.getKey().equals(value)) {
                return key;
            }
        }
        return null;
    }

    @FunctionalInterface
    public interface Handler {
        InteractionResult use(Player player, InteractionHand hand);
    }
}
