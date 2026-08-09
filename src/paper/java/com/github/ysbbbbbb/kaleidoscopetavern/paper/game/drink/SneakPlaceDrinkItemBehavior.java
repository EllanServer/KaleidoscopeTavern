package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.drink;

import net.momirealms.craftengine.bukkit.item.behavior.FurnitureItemBehavior;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockHitResult;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.block.Block;
import org.bukkit.inventory.EquipmentSlot;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the normal held-vessel action while delegating sneak-placement to
 * CraftEngine's native furniture item implementation.
 */
public final class SneakPlaceDrinkItemBehavior extends ItemBehavior {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "sneak_place_drink");
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private final FurnitureItemBehavior furnitureItem;
    private final boolean syncActiveUse;

    private SneakPlaceDrinkItemBehavior(
            FurnitureItemBehavior furnitureItem,
            boolean syncActiveUse
    ) {
        this.furnitureItem = furnitureItem;
        this.syncActiveUse = syncActiveUse;
    }

    /** Must run from the plugin's onLoad, before CraftEngine parses projects. */
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            ItemBehaviors.register(TYPE, (pack, path, id, section) ->
                    new SneakPlaceDrinkItemBehavior(
                            FurnitureItemBehavior.FACTORY.create(pack, path, id, section),
                            section.getBoolean("sync_active_use", false)));
        }
    }

    @Override
    public InteractionResult use(World world, Player player, InteractionHand hand) {
        if (syncActiveUse && player != null
                && player.platformPlayer() instanceof org.bukkit.entity.Player bukkitPlayer) {
            // CraftEngine's custom item callback can otherwise leave the use
            // animation as client-only prediction. Start the native server
            // state so nearby clients receive the raised-hand pose as well.
            bukkitPlayer.startUsingItem(equipmentSlot(hand));
        }

        // Native item use still owns consumption and release handling.
        return InteractionResult.PASS;
    }

    static EquipmentSlot equipmentSlot(InteractionHand hand) {
        return hand == InteractionHand.OFF_HAND
                ? EquipmentSlot.OFF_HAND
                : EquipmentSlot.HAND;
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context) {
        if (!context.isSecondaryUseActive()) {
            // Let drinking, throwing or portable shaking continue through the
            // ordinary CE/vanilla item-use pipeline.
            return InteractionResult.PASS;
        }

        BlockPos clickedPos = context.getClickedPos();
        org.bukkit.World bukkitWorld = (org.bukkit.World) context.getLevel().platformWorld();
        Block clicked = bukkitWorld.getBlockAt(clickedPos.x(), clickedPos.y(), clickedPos.z());
        Block target = clicked.isReplaceable()
                ? clicked
                : bukkitWorld.getBlockAt(
                        clickedPos.x() + context.getClickedFace().stepX(),
                        clickedPos.y() + context.getClickedFace().stepY(),
                        clickedPos.z() + context.getClickedFace().stepZ());
        BlockPos targetPos = new BlockPos(target.getX(), target.getY(), target.getZ());

        // The original BottleBlockItem always selected its ground variant at
        // the target block centre, even when the support was clicked from a
        // side or below. Direction.UP forces the same CE anchor while keeping
        // the actual support block in the placement event/context.
        BlockHitResult hit = new BlockHitResult(
                Vec3d.atBottomCenterOf(targetPos),
                Direction.UP,
                clickedPos,
                context.isInside());
        UseOnContext placement = new UseOnContext(
                context.getLevel(),
                context.getPlayer(),
                context.getHand(),
                context.getItem(),
                hit);
        furnitureItem.useOnBlock(placement);

        // A recognized sneak placement owns the interaction even when CE
        // rejects it. Otherwise the held item action could run as a fallback.
        return InteractionResult.SUCCESS_AND_CANCEL;
    }
}
