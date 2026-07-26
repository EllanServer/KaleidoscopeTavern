package com.github.ysbbbbbb.kaleidoscopetavern.paper.item.behavior;

import net.momirealms.craftengine.bukkit.item.behavior.FurnitureItemBehavior;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockHitResult;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps normal drink use vanilla while delegating sneak-placement to
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
            // Let the potion base continue through CE to vanilla consumption.
            return InteractionResult.PASS;
        }

        BlockPos clickedPos = context.getClickedPos();
        World world = (World) context.getLevel().platformWorld();
        Block clicked = world.getBlockAt(clickedPos.x(), clickedPos.y(), clickedPos.z());
        Block target = clicked.isReplaceable()
                ? clicked
                : world.getBlockAt(
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
        // rejects it. Otherwise the potion can fall through and be drunk.
        return InteractionResult.SUCCESS_AND_CANCEL;
    }
}
