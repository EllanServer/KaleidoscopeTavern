package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import net.momirealms.craftengine.bukkit.block.behavior.WaterloggedBlockBehavior;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.DirectionUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelAccessorProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.material.FluidStateProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.material.FluidsProxy;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CraftEngine block lifecycle for the source mod's six-state connected sofas.
 *
 * <p>CraftEngine's native {@code sofa_block} deliberately models only straight
 * and two inner-corner states. Tavern additionally needs separate single,
 * left, right and middle arm variants, so this behavior ports only that missing
 * state transition while CE still owns placement, persistence, seats, fluid
 * interaction, rendering and loot.</p>
 */
public final class SofaBlockBehavior extends WaterloggedBlockBehavior {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "connected_sofa");
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private final Property<Direction> facingProperty;
    private final Property<String> connectionProperty;

    private SofaBlockBehavior(BlockDefinition block, ConfigSection section) {
        super(block, BlockBehaviorFactory.getProperty(
                section.path(), block, "waterlogged", Boolean.class));
        this.facingProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "facing", Direction.class);
        this.connectionProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "connection", String.class);
    }

    /** Must run from the plugin's onLoad, before CraftEngine parses projects. */
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            BlockBehaviors.register(TYPE, SofaBlockBehavior::new);
        }
    }

    @Override
    public ImmutableBlockState updateStateForPlacement(
            BlockPlaceContext context, ImmutableBlockState state) {
        BlockPos pos = context.getClickedPos();
        Direction facing = context.getHorizontalDirection().opposite();
        Object level = context.getLevel().minecraftWorld();
        Object fluid = BlockGetterProxy.INSTANCE.getFluidState(
                level, LocationUtils.toBlockPos(pos));
        ImmutableBlockState placed = state.owner().value().defaultState()
                .with(facingProperty, facing)
                .with(connectionProperty,
                        SofaConnectionSemantics.Connection.SINGLE.serialized())
                .with(waterloggedProperty,
                        FluidStateProxy.INSTANCE.getType(fluid) == FluidsProxy.WATER);
        return placed.with(connectionProperty,
                connectionFor(level, pos, facing).serialized());
    }

    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        Optional<ImmutableBlockState> optional =
                BlockStateUtils.getOptionalCustomBlockState(args[0]);
        if (optional.isEmpty()) {
            return args[0];
        }
        ImmutableBlockState state = optional.get();
        if (state.get(waterloggedProperty)) {
            LevelAccessorProxy.INSTANCE.scheduleTick$1(
                    args[updateShape$level], args[updateShape$blockPos],
                    FluidsProxy.WATER, 5);
        }

        Direction facing = state.get(facingProperty);
        Direction update = DirectionUtils.fromNMSDirection(args[updateShape$direction]);
        boolean lateralChange = update.axis() == facing.clockWise().axis();
        if (!lateralChange && update != facing) {
            return args[0];
        }

        SofaConnectionSemantics.Connection current =
                SofaConnectionSemantics.Connection.fromSerialized(
                        state.get(connectionProperty));
        if ((current == SofaConnectionSemantics.Connection.LEFT_CORNER
                && update == facing.clockWise())
                || (current == SofaConnectionSemantics.Connection.RIGHT_CORNER
                && update == facing.counterClockWise())) {
            return args[0];
        }

        SofaConnectionSemantics.Connection updated = connectionFor(
                args[updateShape$level],
                LocationUtils.fromBlockPos(args[updateShape$blockPos]),
                facing);
        if (updated == current) {
            return args[0];
        }
        return state.with(connectionProperty, updated.serialized())
                .customBlockState().minecraftState();
    }

    private SofaConnectionSemantics.Connection connectionFor(
            Object level, BlockPos pos, Direction facing) {
        return SofaConnectionSemantics.connectionFor(
                facing,
                neighbor(level, pos.relative(facing.clockWise())),
                neighbor(level, pos.relative(facing.counterClockWise())),
                neighbor(level, pos.relative(facing)));
    }

    private static SofaConnectionSemantics.Neighbor neighbor(
            Object level, BlockPos pos) {
        Object minecraftState = BlockGetterProxy.INSTANCE.getBlockState(
                level, LocationUtils.toBlockPos(pos));
        Optional<ImmutableBlockState> optional =
                BlockStateUtils.getOptionalCustomBlockState(minecraftState);
        if (optional.isEmpty()) {
            return null;
        }
        ImmutableBlockState state = optional.get();
        SofaBlockBehavior behavior =
                state.behavior().getFirst(SofaBlockBehavior.class);
        if (behavior == null) {
            return null;
        }
        return new SofaConnectionSemantics.Neighbor(
                state.get(behavior.facingProperty),
                SofaConnectionSemantics.Connection.fromSerialized(
                        state.get(behavior.connectionProperty)));
    }
}
