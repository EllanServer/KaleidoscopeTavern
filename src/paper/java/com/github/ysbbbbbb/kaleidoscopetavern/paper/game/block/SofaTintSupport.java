package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.block.entity.TintSourceBlockEntityController;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import org.bukkit.Location;

/** Native CE tint-source helpers used only by one-release sofa migrations. */
public final class SofaTintSupport {
    private SofaTintSupport() {
    }

    public static boolean placeShared(
            Location location, Key publicSofaItemId, Direction facing) {
        if (location.getWorld() == null
                || !SofaBlockIds.isLegacy(publicSofaItemId)) {
            return false;
        }
        BlockDefinition definition = CraftEngineBlocks.byId(SofaBlockIds.SHARED);
        if (definition == null) {
            return false;
        }
        ImmutableBlockState state = definition.defaultState();
        @SuppressWarnings("unchecked")
        Property<Direction> facingProperty =
                (Property<Direction>) definition.getProperty("facing");
        if (facingProperty == null) {
            return false;
        }
        state = state.with(facingProperty, facing);
        ConnectedBlockBehavior topology =
                state.behavior().getFirst(ConnectedBlockBehavior.class);
        if (topology == null) {
            return false;
        }

        var adapted = BukkitAdaptor.adapt(location.getWorld());
        BlockPos pos = new BlockPos(
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
        state = topology.resolveCornerState(adapted.minecraftWorld(), pos, state);
        if (state == null || state.isEmpty()
                || !CraftEngineBlocks.place(location, state, false)) {
            return false;
        }

        CEWorld world = adapted.storageWorld();
        if (setSourceItem(world, pos, publicSofaItemId)) {
            return true;
        }
        clearSourceItem(world, pos);
        CraftEngineBlocks.remove(location.getBlock(), false);
        return false;
    }

    public static boolean setSourceItem(
            CEWorld world, BlockPos pos, Key publicSofaItemId) {
        BukkitItemDefinition definition =
                CraftEngineItems.byId(publicSofaItemId.toString());
        if (definition == null) {
            return false;
        }
        Item source = definition.buildItem(ItemBuildContext.empty())
                .copyWithCount(1);
        BlockEntity blockEntity = world.getBlockEntityAtIfLoaded(pos, true);
        if (blockEntity == null || blockEntity.controller == null) {
            return false;
        }

        boolean[] found = {false};
        blockEntity.controller.let(
                TintSourceBlockEntityController.class,
                controller -> {
                    controller.setSourceItem(source);
                    Item stored = controller.tintSource();
                    found[0] = !stored.isEmpty()
                            && stored.id().equals(publicSofaItemId)
                            && stored.count() == 1;
                });
        if (found[0]) {
            world.blockEntityChanged(pos);
        }
        return found[0];
    }

    public static void clearSourceItem(CEWorld world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntityAtIfLoaded(pos, false);
        if (blockEntity == null || blockEntity.controller == null) {
            return;
        }
        blockEntity.controller.let(
                TintSourceBlockEntityController.class,
                controller -> controller.setSourceItem(Item.empty()));
        world.blockEntityChanged(pos);
    }

    public static boolean hasSourceItem(
            CEWorld world, BlockPos pos, Key publicSofaItemId) {
        return publicSofaItemId.equals(sourceItemId(world, pos));
    }

    public static Key sourceItemId(CEWorld world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntityAtIfLoaded(pos, false);
        if (blockEntity == null || blockEntity.controller == null) {
            return null;
        }
        Key[] result = {null};
        blockEntity.controller.let(
                TintSourceBlockEntityController.class,
                controller -> {
                    Item stored = controller.tintSource();
                    if (!stored.isEmpty()) {
                        result[0] = stored.id();
                    }
                });
        return result[0];
    }
}
