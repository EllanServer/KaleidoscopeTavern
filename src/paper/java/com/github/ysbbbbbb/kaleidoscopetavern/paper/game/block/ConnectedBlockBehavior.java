package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
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

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal neighbour-state adapter for source connections CE 26.7.4 cannot
 * express declaratively. CE still owns block-item placement, facing/axis,
 * bucket behaviour, rendering, carriers, collision, loot and seats.
 */
public final class ConnectedBlockBehavior extends BukkitBlockBehavior {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "connected_block");
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private final Mode mode;
    private final Set<Key> connectableIds;
    private final Property<Direction> facingProperty;
    private final Property<String> connectionProperty;
    private final Property<Direction.Axis> axisProperty;
    private final Property<Integer> positionProperty;

    private ConnectedBlockBehavior(BlockDefinition block, ConfigSection section) {
        super(block);
        mode = Mode.valueOf(section.getNonEmptyString("mode").toUpperCase(Locale.ROOT));
        connectableIds = new HashSet<>();
        for (String id : section.getStringList("connects")) connectableIds.add(Key.of(id));
        if (connectableIds.isEmpty()) connectableIds.add(block.id());
        facingProperty = BlockBehaviorFactory.getOptionalProperty(block, "facing", Direction.class);
        connectionProperty = BlockBehaviorFactory.getOptionalProperty(block, "connection", String.class);
        axisProperty = BlockBehaviorFactory.getOptionalProperty(block, "axis", Direction.Axis.class);
        positionProperty = BlockBehaviorFactory.getOptionalProperty(block, "position", Integer.class);
        if (mode == Mode.CORNER
                && (facingProperty == null || connectionProperty == null)) {
            throw new IllegalArgumentException(mode + " requires facing/connection at " + section.path());
        }
        if (mode == Mode.TABLE && (axisProperty == null || positionProperty == null)) {
            throw new IllegalArgumentException("TABLE requires axis/position at " + section.path());
        }
    }
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) BlockBehaviors.register(TYPE, ConnectedBlockBehavior::new);
    }
    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        Object level = context.getLevel().minecraftWorld();
        BlockPos pos = context.getClickedPos();
        return switch (mode) {
            case CORNER -> updateCorner(level, pos, state);
            case TABLE -> placeTable(context, state);
        };
    }
    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null) return args[0];
        Direction changed = DirectionUtils.fromNMSDirection(args[updateShape$direction]);
        if (!changed.axis().isHorizontal()) return args[0];
        BlockPos pos = LocationUtils.fromBlockPos(args[updateShape$blockPos]);
        Object level = args[updateShape$level];
        ImmutableBlockState next = switch (mode) {
            case CORNER -> updateCornerAfterNeighbour(level, pos, state, changed);
            case TABLE -> updateTable(level, pos, state, changed.axis());
        };
        return next == state ? args[0] : next.customBlockState().minecraftState();
    }
    private ImmutableBlockState updateCornerAfterNeighbour(Object level, BlockPos pos,
                                                           ImmutableBlockState state, Direction changed) {
        Direction facing = state.get(facingProperty);
        Direction left = facing.clockWise();
        Direction right = facing.counterClockWise();
        if (changed.axis() != left.axis() && changed != facing) return state;
        String current = state.get(connectionProperty);
        if (("left_corner".equals(current) && changed == left)
                || ("right_corner".equals(current) && changed == right)) return state;
        return updateCorner(level, pos, state);
    }
    private ImmutableBlockState updateCorner(Object level, BlockPos pos, ImmutableBlockState state) {
        Direction facing = state.get(facingProperty);
        ImmutableBlockState left = customState(level, pos.relative(facing.clockWise()));
        ImmutableBlockState right = customState(level, pos.relative(facing.counterClockWise()));
        ImmutableBlockState front = customState(level, pos.relative(facing));
        String next = ConnectedBlockSemantics.cornerConnection(leftConnected(left, facing),
                rightConnected(right, facing), frontLeftConnected(front, facing),
                frontRightConnected(front, facing));
        return next.equals(state.get(connectionProperty)) ? state : state.with(connectionProperty, next);
    }
    private ImmutableBlockState placeTable(BlockPlaceContext context, ImmutableBlockState state) {
        ImmutableBlockState base = state.with(axisProperty, Direction.Axis.Z).with(positionProperty, 0);
        Direction.Axis playerAxis = context.getHorizontalDirection().axis();
        return updateTable(context.getLevel().minecraftWorld(), context.getClickedPos(), base,
                playerAxis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
    }
    private ImmutableBlockState updateTable(Object level, BlockPos pos, ImmutableBlockState state,
                                            Direction.Axis changedAxis) {
        ConnectedBlockSemantics.TableState current = tableState(state);
        ConnectedBlockSemantics.TableState next;
        if (changedAxis == Direction.Axis.X) {
            next = ConnectedBlockSemantics.eastWest(current,
                    tableConnects(level, pos.east(), Direction.Axis.Z),
                    tableConnects(level, pos.west(), Direction.Axis.Z));
        } else if (changedAxis == Direction.Axis.Z) {
            next = ConnectedBlockSemantics.northSouth(current,
                    tableConnects(level, pos.south(), Direction.Axis.X),
                    tableConnects(level, pos.north(), Direction.Axis.X));
        } else return state;
        Direction.Axis axis = next.axis() == ConnectedBlockSemantics.Axis.X ? Direction.Axis.X : Direction.Axis.Z;
        if (axis == state.get(axisProperty) && next.position() == state.get(positionProperty)) return state;
        return state.with(axisProperty, axis).with(positionProperty, next.position());
    }
    private ConnectedBlockSemantics.TableState tableState(ImmutableBlockState state) {
        return new ConnectedBlockSemantics.TableState(state.get(axisProperty) == Direction.Axis.X
                ? ConnectedBlockSemantics.Axis.X : ConnectedBlockSemantics.Axis.Z, state.get(positionProperty));
    }
    private boolean tableConnects(Object level, BlockPos pos, Direction.Axis correctionAxis) {
        ImmutableBlockState neighbor = customState(level, pos);
        if (!isConnectable(neighbor)) return false;
        Direction.Axis neighborAxis = property(neighbor, "axis", Direction.Axis.class);
        Integer neighborPosition = property(neighbor, "position", Integer.class);
        return neighborAxis != correctionAxis || Integer.valueOf(0).equals(neighborPosition);
    }
    private boolean leftConnected(ImmutableBlockState neighbor, Direction self) {
        if (!isConnectable(neighbor)) return false;
        Direction check = property(neighbor, "facing", Direction.class);
        if (check == self.counterClockWise()) {
            String connection = property(neighbor, "connection", String.class);
            return "single".equals(connection) || "right".equals(connection) || "right_corner".equals(connection);
        }
        return check == self;
    }
    private boolean rightConnected(ImmutableBlockState neighbor, Direction self) {
        if (!isConnectable(neighbor)) return false;
        Direction check = property(neighbor, "facing", Direction.class);
        if (check == self.clockWise()) {
            String connection = property(neighbor, "connection", String.class);
            return "single".equals(connection) || "left".equals(connection) || "left_corner".equals(connection);
        }
        return check == self;
    }
    private boolean frontLeftConnected(ImmutableBlockState neighbor, Direction self) {
        return isConnectable(neighbor) && property(neighbor, "facing", Direction.class) == self.clockWise()
                && !"left_corner".equals(property(neighbor, "connection", String.class));
    }
    private boolean frontRightConnected(ImmutableBlockState neighbor, Direction self) {
        return isConnectable(neighbor) && property(neighbor, "facing", Direction.class) == self.counterClockWise()
                && !"right_corner".equals(property(neighbor, "connection", String.class));
    }
    private boolean isConnectable(ImmutableBlockState state) {
        return state != null && connectableIds.contains(state.owner().value().id());
    }
    private static ImmutableBlockState customState(Object level, BlockPos pos) {
        return BlockStateUtils.getOptionalCustomBlockState(BlockGetterProxy.INSTANCE.getBlockState(
                level, LocationUtils.toBlockPos(pos))).orElse(null);
    }
    private static <T extends Comparable<T>> T property(ImmutableBlockState state, String name, Class<T> type) {
        if (state == null) return null;
        Property<T> property = state.getProperty(name);
        if (property == null || property.valueClass() != type) return null;
        return state.get(property);
    }
    private enum Mode { CORNER, TABLE }
}
