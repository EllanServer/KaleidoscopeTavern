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
 * Generic fixed-neighbour topology adapter.
 *
 * <p>Every family-specific property name, output value and compatible neighbour
 * state lives in CraftEngine configuration. Java only performs the O(1)
 * neighbour reads that CE 26.7.4 cannot declare in YAML.</p>
 */
public final class ConnectedBlockBehavior extends BukkitBlockBehavior {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "connected_block");
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private final Mode mode;
    private final Set<Key> connectableIds;
    private final Property<Direction> facingProperty;
    private final Property<String> stringStateProperty;
    private final Property<Direction.Axis> axisProperty;
    private final Property<Integer> integerStateProperty;
    private final CornerConfig corner;
    private final LinearConfig linear;
    private final TableConfig table;

    private ConnectedBlockBehavior(BlockDefinition block, ConfigSection section) {
        super(block);
        mode = section.getNonNullEnum("mode", Mode.class);
        connectableIds = new HashSet<>();
        for (String id : section.getStringList("connects")) {
            connectableIds.add(Key.of(id));
        }
        if (connectableIds.isEmpty()) {
            connectableIds.add(block.id());
        }

        facingProperty = BlockBehaviorFactory.getOptionalProperty(
                block, section.getString("facing_property", "facing"), Direction.class);
        String stateProperty = section.getString("state_property",
                mode == Mode.TABLE ? "position" : mode == Mode.CORNER ? "connection" : "position");
        stringStateProperty = mode == Mode.TABLE ? null
                : BlockBehaviorFactory.getProperty(
                        section.path(), block, stateProperty, String.class);
        integerStateProperty = mode == Mode.TABLE
                ? BlockBehaviorFactory.getProperty(
                        section.path(), block, stateProperty, Integer.class)
                : null;
        axisProperty = mode == Mode.TABLE
                ? BlockBehaviorFactory.getProperty(
                        section.path(), block,
                        section.getString("axis_property", "table_axis"),
                        Direction.Axis.class)
                : null;

        corner = mode == Mode.CORNER
                ? CornerConfig.parse(section.getNonNullSection("topology")) : null;
        linear = mode == Mode.LINEAR
                ? LinearConfig.parse(section.getNonNullSection("topology")) : null;
        table = mode == Mode.TABLE
                ? TableConfig.parse(section.getNonNullSection("topology")) : null;
        if (mode != Mode.TABLE && facingProperty == null) {
            throw new IllegalArgumentException(
                    mode + " requires a horizontal facing property at " + section.path());
        }
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            BlockBehaviors.register(TYPE, ConnectedBlockBehavior::new);
        }
    }

    @Override
    public ImmutableBlockState updateStateForPlacement(
            BlockPlaceContext context, ImmutableBlockState state) {
        Object level = context.getLevel().minecraftWorld();
        BlockPos pos = context.getClickedPos();
        return switch (mode) {
            case CORNER -> updateCorner(level, pos, state);
            case LINEAR -> updateLinear(level, pos, state);
            case TABLE -> placeTable(context, state);
        };
    }

    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        ImmutableBlockState state = BlockStateUtils
                .getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null) {
            return args[0];
        }
        Direction changed = DirectionUtils.fromNMSDirection(
                args[updateShape$direction]);
        if (!changed.axis().isHorizontal()) {
            return args[0];
        }
        BlockPos pos = LocationUtils.fromBlockPos(args[updateShape$blockPos]);
        Object level = args[updateShape$level];
        ImmutableBlockState next = switch (mode) {
            case CORNER -> updateCornerAfterNeighbour(level, pos, state, changed);
            case LINEAR -> updateLinearAfterNeighbour(level, pos, state, changed);
            case TABLE -> updateTable(level, pos, state, changed.axis());
        };
        return next == state
                ? args[0]
                : next.customBlockState().minecraftState();
    }

    private ImmutableBlockState updateCornerAfterNeighbour(
            Object level, BlockPos pos, ImmutableBlockState state,
            Direction changed) {
        Direction facing = state.get(facingProperty);
        Direction side = facing.clockWise();
        if (changed.axis() != side.axis() && changed != facing) {
            return state;
        }
        return updateCorner(level, pos, state);
    }

    ImmutableBlockState resolveCornerState(
            Object level, BlockPos pos, ImmutableBlockState state) {
        if (mode != Mode.CORNER) {
            throw new IllegalStateException("Not a corner-connection behavior");
        }
        return updateCorner(level, pos, state);
    }

    private ImmutableBlockState updateCorner(
            Object level, BlockPos pos, ImmutableBlockState state) {
        Direction facing = state.get(facingProperty);
        ImmutableBlockState left = customState(
                level, pos.relative(facing.clockWise()));
        ImmutableBlockState right = customState(
                level, pos.relative(facing.counterClockWise()));
        ImmutableBlockState front = customState(
                level, pos.relative(facing));
        String next = corner.output(
                leftConnected(left, facing),
                rightConnected(right, facing),
                frontLeftConnected(front, facing),
                frontRightConnected(front, facing));
        return next.equals(state.get(stringStateProperty))
                ? state : state.with(stringStateProperty, next);
    }

    private ImmutableBlockState updateLinearAfterNeighbour(
            Object level, BlockPos pos, ImmutableBlockState state,
            Direction changed) {
        Direction facing = state.get(facingProperty);
        if (changed != facing.clockWise()
                && changed != facing.counterClockWise()) {
            return state;
        }
        return updateLinear(level, pos, state);
    }

    private ImmutableBlockState updateLinear(
            Object level, BlockPos pos, ImmutableBlockState state) {
        Direction facing = state.get(facingProperty);
        boolean left = sameFacing(
                customState(level, pos.relative(facing.clockWise())), facing);
        boolean right = sameFacing(
                customState(level, pos.relative(facing.counterClockWise())), facing);
        String next = linear.output(left, right);
        return next.equals(state.get(stringStateProperty))
                ? state : state.with(stringStateProperty, next);
    }

    private ImmutableBlockState placeTable(
            BlockPlaceContext context, ImmutableBlockState state) {
        Direction.Axis defaultAxis = table.defaultAxis;
        ImmutableBlockState base = state.with(axisProperty, defaultAxis)
                .with(integerStateProperty, table.none);
        Direction.Axis playerAxis = context.getHorizontalDirection().axis();
        Direction.Axis initialUpdate = table.perpendicularToPlayer
                ? perpendicular(playerAxis) : playerAxis;
        return updateTable(
                context.getLevel().minecraftWorld(), context.getClickedPos(),
                base, initialUpdate);
    }

    private ImmutableBlockState updateTable(
            Object level, BlockPos pos, ImmutableBlockState state,
            Direction.Axis changedAxis) {
        Direction.Axis currentAxis = state.get(axisProperty);
        int currentPosition = state.get(integerStateProperty);
        if (currentPosition != table.none && changedAxis != currentAxis) {
            return state;
        }

        boolean positive;
        boolean negative;
        if (changedAxis == Direction.Axis.X) {
            positive = tableConnects(level, pos.east(), Direction.Axis.X);
            negative = tableConnects(level, pos.west(), Direction.Axis.X);
        } else if (changedAxis == Direction.Axis.Z) {
            positive = tableConnects(level, pos.south(), Direction.Axis.Z);
            negative = tableConnects(level, pos.north(), Direction.Axis.Z);
        } else {
            return state;
        }
        int nextPosition = table.output(positive, negative);
        Direction.Axis nextAxis = nextPosition == table.none ? currentAxis : changedAxis;
        if (nextAxis == currentAxis && nextPosition == currentPosition) {
            return state;
        }
        return state.with(axisProperty, nextAxis)
                .with(integerStateProperty, nextPosition);
    }

    private boolean tableConnects(
            Object level, BlockPos pos, Direction.Axis connectionAxis) {
        ImmutableBlockState neighbor = customState(level, pos);
        if (!isConnectable(neighbor)) {
            return false;
        }
        Direction.Axis neighborAxis = property(
                neighbor, axisProperty.name(), Direction.Axis.class);
        Integer neighborPosition = property(
                neighbor, integerStateProperty.name(), Integer.class);
        return (table.allowCrossAxisSingles
                && Integer.valueOf(table.none).equals(neighborPosition))
                || neighborAxis == connectionAxis;
    }

    private boolean leftConnected(
            ImmutableBlockState neighbor, Direction self) {
        if (!isConnectable(neighbor)) {
            return false;
        }
        Direction check = property(neighbor, facingProperty.name(), Direction.class);
        if (check == self.counterClockWise()) {
            return corner.leftPerpendicularStates.contains(connection(neighbor));
        }
        return check == self;
    }

    private boolean rightConnected(
            ImmutableBlockState neighbor, Direction self) {
        if (!isConnectable(neighbor)) {
            return false;
        }
        Direction check = property(neighbor, facingProperty.name(), Direction.class);
        if (check == self.clockWise()) {
            return corner.rightPerpendicularStates.contains(connection(neighbor));
        }
        return check == self;
    }

    private boolean frontLeftConnected(
            ImmutableBlockState neighbor, Direction self) {
        return isConnectable(neighbor)
                && property(neighbor, facingProperty.name(), Direction.class)
                == self.clockWise()
                && !corner.frontLeftExcluded.equals(connection(neighbor));
    }

    private boolean frontRightConnected(
            ImmutableBlockState neighbor, Direction self) {
        return isConnectable(neighbor)
                && property(neighbor, facingProperty.name(), Direction.class)
                == self.counterClockWise()
                && !corner.frontRightExcluded.equals(connection(neighbor));
    }

    private String connection(ImmutableBlockState state) {
        String connection = property(
                state, stringStateProperty.name(), String.class);
        if (connection == null && state != null
                && SofaBlockIds.isLegacy(state.owner().value().id())) {
            return corner.none;
        }
        return connection;
    }

    private boolean sameFacing(
            ImmutableBlockState neighbor, Direction facing) {
        return isConnectable(neighbor)
                && property(neighbor, facingProperty.name(), Direction.class) == facing;
    }

    private boolean isConnectable(ImmutableBlockState state) {
        return state != null
                && connectableIds.contains(state.owner().value().id());
    }

    private static ImmutableBlockState customState(Object level, BlockPos pos) {
        return BlockStateUtils.getOptionalCustomBlockState(
                BlockGetterProxy.INSTANCE.getBlockState(
                        level, LocationUtils.toBlockPos(pos)))
                .orElse(null);
    }

    private static Direction.Axis perpendicular(Direction.Axis axis) {
        return axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
    }

    private static <T extends Comparable<T>> T property(
            ImmutableBlockState state, String name, Class<T> type) {
        if (state == null) {
            return null;
        }
        Property<T> property = state.getProperty(name);
        if (property == null || property.valueClass() != type) {
            return null;
        }
        return state.get(property);
    }

    private enum Mode {
        CORNER,
        LINEAR,
        TABLE
    }

    private record CornerConfig(
            String none,
            String left,
            String right,
            String both,
            String frontLeft,
            String frontLeftWithRight,
            String frontRight,
            String frontRightWithLeft,
            Set<String> leftPerpendicularStates,
            Set<String> rightPerpendicularStates,
            String frontLeftExcluded,
            String frontRightExcluded
    ) {
        static CornerConfig parse(ConfigSection section) {
            ConfigSection outputs = section.getNonNullSection("outputs");
            ConfigSection compatibility = section.getNonNullSection("compatibility");
            return new CornerConfig(
                    outputs.getNonEmptyString("none"),
                    outputs.getNonEmptyString("left"),
                    outputs.getNonEmptyString("right"),
                    outputs.getNonEmptyString("both"),
                    outputs.getNonEmptyString("front_left"),
                    outputs.getNonEmptyString("front_left_with_right"),
                    outputs.getNonEmptyString("front_right"),
                    outputs.getNonEmptyString("front_right_with_left"),
                    Set.copyOf(compatibility.getStringList("left_perpendicular")),
                    Set.copyOf(compatibility.getStringList("right_perpendicular")),
                    compatibility.getNonEmptyString("front_left_excluded"),
                    compatibility.getNonEmptyString("front_right_excluded"));
        }

        String output(boolean leftConnected, boolean rightConnected,
                      boolean frontLeftConnected, boolean frontRightConnected) {
            if (leftConnected && rightConnected) {
                return both;
            }
            if (frontLeftConnected) {
                return rightConnected ? frontLeftWithRight : frontLeft;
            }
            if (frontRightConnected) {
                return leftConnected ? frontRightWithLeft : frontRight;
            }
            if (leftConnected) {
                return left;
            }
            if (rightConnected) {
                return right;
            }
            return none;
        }
    }

    private record LinearConfig(String none, String left, String right, String both) {
        static LinearConfig parse(ConfigSection section) {
            ConfigSection outputs = section.getNonNullSection("outputs");
            return new LinearConfig(
                    outputs.getNonEmptyString("none"),
                    outputs.getNonEmptyString("left"),
                    outputs.getNonEmptyString("right"),
                    outputs.getNonEmptyString("both"));
        }

        String output(boolean leftConnected, boolean rightConnected) {
            if (leftConnected && rightConnected) {
                return both;
            }
            if (leftConnected) {
                return left;
            }
            return rightConnected ? right : none;
        }
    }

    private record TableConfig(
            Direction.Axis defaultAxis,
            boolean perpendicularToPlayer,
            boolean allowCrossAxisSingles,
            int none,
            int positive,
            int negative,
            int both
    ) {
        static TableConfig parse(ConfigSection section) {
            ConfigSection outputs = section.getNonNullSection("outputs");
            return new TableConfig(
                    section.getEnum("default_axis", Direction.Axis.class,
                            Direction.Axis.Z),
                    section.getBoolean("perpendicular_to_player", true),
                    section.getBoolean("allow_cross_axis_singles", true),
                    outputs.getInt("none", 0),
                    outputs.getInt("positive", 1),
                    outputs.getInt("negative", 3),
                    outputs.getInt("both", 2));
        }

        int output(boolean positiveConnected, boolean negativeConnected) {
            if (positiveConnected && negativeConnected) {
                return both;
            }
            if (positiveConnected) {
                return positive;
            }
            return negativeConnected ? negative : none;
        }
    }
}
