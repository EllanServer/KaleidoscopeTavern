package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.BlockShape;
import net.momirealms.craftengine.core.block.DelegatingBlock;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.proxy.minecraft.world.phys.AABBProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.shapes.ShapesProxy;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Installs the exact source {@code SofaBlock} selection, collision and support
 * shapes on CE's generated block-state delegates.
 */
public final class SofaBlockShape implements BlockShape {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final List<String> COLORS = List.of(
            "white", "light_gray", "gray", "black",
            "brown", "red", "orange", "yellow",
            "lime", "green", "cyan", "light_blue",
            "blue", "purple", "magenta", "pink");
    private static final Map<ShapeKey, Object> EXACT_SHAPES =
            new ConcurrentHashMap<>();

    private final BlockShape original;
    private final Property<Direction> facingProperty;
    private final Property<String> connectionProperty;

    private SofaBlockShape(
            BlockShape original,
            Property<Direction> facingProperty,
            Property<String> connectionProperty) {
        this.original = original;
        this.facingProperty = facingProperty;
        this.connectionProperty = connectionProperty;
    }

    /**
     * Called after initial CE project loading and after every CE reload.
     *
     * @return number of CE sofa block delegates updated
     */
    public static int install() {
        Map<Object, Key> installed = new IdentityHashMap<>();
        for (String color : COLORS) {
            BlockDefinition definition = CraftEngineBlocks.byId(
                    Key.of(PREFIX + color + "_sofa"));
            if (definition == null) {
                continue;
            }
            Property<Direction> facing = property(
                    definition, "facing", Direction.class);
            Property<String> connection = property(
                    definition, "connection", String.class);
            Object minecraftState = definition.defaultState()
                    .customBlockState().minecraftState();
            Object owner = BlockStateUtils.getBlockOwner(minecraftState);
            if (!(owner instanceof DelegatingBlock delegatingBlock)) {
                continue;
            }
            Key previous = installed.putIfAbsent(owner, definition.id());
            if (previous != null) {
                throw new IllegalStateException(
                        "CraftEngine reused one sofa block delegate for "
                                + previous + " and " + definition.id());
            }
            BlockShape current = delegatingBlock.shapeDelegate().value();
            BlockShape original = current instanceof SofaBlockShape sofa
                    ? sofa.original : current;
            delegatingBlock.shapeDelegate().bindValue(
                    new SofaBlockShape(original, facing, connection));
        }
        return installed.size();
    }

    /**
     * The source has six visual/connection states, but only its two corner
     * states alter the VoxelShape. SINGLE, LEFT, RIGHT and MIDDLE deliberately
     * share one straight shape, so collapse them before the cached lookup.
     */
    static SofaConnectionSemantics.Connection collisionConnection(
            SofaConnectionSemantics.Connection connection) {
        return switch (connection) {
            case LEFT_CORNER, RIGHT_CORNER -> connection;
            default -> SofaConnectionSemantics.Connection.SINGLE;
        };
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> Property<T> property(
            BlockDefinition definition, String name, Class<T> valueClass) {
        Property<?> property = definition.getProperty(name);
        if (property == null || property.valueClass() != valueClass) {
            throw new IllegalStateException(
                    definition.id() + " requires " + valueClass.getSimpleName()
                            + " property " + name);
        }
        return (Property<T>) property;
    }

    @Override
    public Object getShape(Object thisBlock, Object[] args) {
        Object shape = exactShape(args[0]);
        return shape == null ? original.getShape(thisBlock, args) : shape;
    }

    @Override
    public Object getCollisionShape(Object thisBlock, Object[] args) {
        Object shape = exactShape(args[0]);
        return shape == null
                ? original.getCollisionShape(thisBlock, args) : shape;
    }

    @Override
    public Object getSupportShape(Object thisBlock, Object[] args) {
        Object shape = exactShape(args[0]);
        return shape == null
                ? original.getSupportShape(thisBlock, args) : shape;
    }

    private Object exactShape(Object minecraftState) {
        ImmutableBlockState state = BlockStateUtils
                .getOptionalCustomBlockState(minecraftState).orElse(null);
        if (state == null || !state.contains(facingProperty)
                || !state.contains(connectionProperty)) {
            return null;
        }
        SofaConnectionSemantics.Connection connection = collisionConnection(
                SofaConnectionSemantics.Connection.fromSerialized(
                        state.get(connectionProperty)));
        ShapeKey key = new ShapeKey(state.get(facingProperty), connection);
        return EXACT_SHAPES.computeIfAbsent(key, SofaBlockShape::shape);
    }

    static List<Box> boxes(
            Direction facing, SofaConnectionSemantics.Connection connection) {
        Box base = new Box(0, 0, 0, 16, 8, 16);
        Box back = switch (facing) {
            case NORTH -> new Box(0, 8, 11, 16, 18, 16);
            case SOUTH -> new Box(0, 8, 0, 16, 18, 5);
            case WEST -> new Box(11, 8, 0, 16, 18, 16);
            case EAST -> new Box(0, 8, 0, 5, 18, 16);
            default -> throw new IllegalArgumentException(
                    "Sofa facing must be horizontal: " + facing);
        };
        if (connection != SofaConnectionSemantics.Connection.LEFT_CORNER
                && connection != SofaConnectionSemantics.Connection.RIGHT_CORNER) {
            return List.of(base, back);
        }
        boolean left = connection == SofaConnectionSemantics.Connection.LEFT_CORNER;
        Box side = switch (facing) {
            case NORTH -> left
                    ? new Box(11, 8, 0, 16, 18, 16)
                    : new Box(0, 8, 0, 5, 18, 16);
            case SOUTH -> left
                    ? new Box(0, 8, 0, 5, 18, 16)
                    : new Box(11, 8, 0, 16, 18, 16);
            case WEST -> left
                    ? new Box(0, 8, 0, 16, 18, 5)
                    : new Box(0, 8, 11, 16, 18, 16);
            case EAST -> left
                    ? new Box(0, 8, 11, 16, 18, 16)
                    : new Box(0, 8, 0, 16, 18, 5);
            default -> throw new IllegalArgumentException(
                    "Sofa facing must be horizontal: " + facing);
        };
        return List.of(base, back, side);
    }

    private static Object shape(ShapeKey key) {
        List<Box> boxes = boxes(key.facing(), key.connection());
        Object result = shape(boxes.getFirst());
        for (int index = 1; index < boxes.size(); index++) {
            result = ShapesProxy.INSTANCE.or(result, shape(boxes.get(index)));
        }
        return result;
    }

    private static Object shape(Box box) {
        Object bounds = AABBProxy.INSTANCE.newInstance(
                box.minX() / 16.0, box.minY() / 16.0, box.minZ() / 16.0,
                box.maxX() / 16.0, box.maxY() / 16.0, box.maxZ() / 16.0);
        return ShapesProxy.INSTANCE.create(bounds);
    }

    private record ShapeKey(
            Direction facing, SofaConnectionSemantics.Connection connection) {
    }

    record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }
}
