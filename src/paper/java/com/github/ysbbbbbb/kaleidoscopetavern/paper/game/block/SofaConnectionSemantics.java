package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import net.momirealms.craftengine.core.util.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Exact, side-effect-free port of the source mod's {@code IConnectionBlock}
 * rules used by every sofa colour.
 */
final class SofaConnectionSemantics {
    private SofaConnectionSemantics() {
    }

    static Connection connectionFor(
            Direction facing,
            @Nullable Neighbor left,
            @Nullable Neighbor right,
            @Nullable Neighbor front) {
        boolean leftConnected = leftConnected(left, facing);
        boolean rightConnected = rightConnected(right, facing);
        boolean frontLeftConnected = frontLeftConnected(front, facing);
        boolean frontRightConnected = frontRightConnected(front, facing);

        if (leftConnected && rightConnected) {
            return Connection.MIDDLE;
        }
        if (frontLeftConnected) {
            return rightConnected ? Connection.LEFT : Connection.RIGHT_CORNER;
        }
        if (frontRightConnected) {
            return leftConnected ? Connection.RIGHT : Connection.LEFT_CORNER;
        }
        if (leftConnected) {
            return Connection.RIGHT;
        }
        if (rightConnected) {
            return Connection.LEFT;
        }
        return Connection.SINGLE;
    }

    private static boolean leftConnected(@Nullable Neighbor neighbor, Direction facing) {
        if (neighbor == null) {
            return false;
        }
        Direction check = neighbor.facing();
        if (check == facing.counterClockWise()) {
            return switch (neighbor.connection()) {
                case SINGLE, RIGHT, RIGHT_CORNER -> true;
                default -> false;
            };
        }
        return check == facing;
    }

    private static boolean rightConnected(@Nullable Neighbor neighbor, Direction facing) {
        if (neighbor == null) {
            return false;
        }
        Direction check = neighbor.facing();
        if (check == facing.clockWise()) {
            return switch (neighbor.connection()) {
                case SINGLE, LEFT, LEFT_CORNER -> true;
                default -> false;
            };
        }
        return check == facing;
    }

    private static boolean frontLeftConnected(
            @Nullable Neighbor neighbor, Direction facing) {
        return neighbor != null
                && neighbor.facing() == facing.clockWise()
                && neighbor.connection() != Connection.LEFT_CORNER;
    }

    private static boolean frontRightConnected(
            @Nullable Neighbor neighbor, Direction facing) {
        return neighbor != null
                && neighbor.facing() == facing.counterClockWise()
                && neighbor.connection() != Connection.RIGHT_CORNER;
    }

    record Neighbor(Direction facing, Connection connection) {
    }

    enum Connection {
        SINGLE,
        LEFT,
        RIGHT,
        MIDDLE,
        LEFT_CORNER,
        RIGHT_CORNER;

        static Connection fromSerialized(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }

        String serialized() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
