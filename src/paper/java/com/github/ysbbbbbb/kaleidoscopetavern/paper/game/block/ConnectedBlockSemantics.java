package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

/** Pure source-compatible state selection for connected custom blocks. */
public final class ConnectedBlockSemantics {
    private ConnectedBlockSemantics() {
    }

    public enum Axis {
        X,
        Z
    }

    /** A corner/end state depends on both side cells and the cell in front. */
    public static boolean cornerNeighbourAffectsState(boolean sideChange,
                                                      boolean frontChange) {
        return sideChange || frontChange;
    }

    public record TableState(Axis axis, int position) {
        public TableState {
            if (axis == null) {
                throw new IllegalArgumentException("axis");
            }
            if (position < 0 || position > 3) {
                throw new IllegalArgumentException("position=" + position);
            }
        }
    }

    public static String cornerConnection(
            boolean left, boolean right,
            boolean frontLeft, boolean frontRight) {
        if (left && right) {
            return "middle";
        }
        if (frontLeft) {
            return right ? "left" : "right_corner";
        }
        if (frontRight) {
            return left ? "right" : "left_corner";
        }
        if (left) {
            return "right";
        }
        if (right) {
            return "left";
        }
        return "single";
    }

    public static String linearPosition(boolean left, boolean right) {
        if (left && right) {
            return "middle";
        }
        if (left) {
            return "right";
        }
        return right ? "left" : "single";
    }

    public static TableState eastWest(
            TableState current, boolean east, boolean west) {
        if (current.axis() == Axis.Z && current.position() != 0) {
            return current;
        }
        if (east && west) {
            return new TableState(Axis.X, 2);
        }
        if (east) {
            return new TableState(Axis.X, 1);
        }
        if (west) {
            return new TableState(Axis.X, 3);
        }
        return new TableState(current.axis(), 0);
    }

    public static TableState northSouth(
            TableState current, boolean south, boolean north) {
        if (current.axis() == Axis.X && current.position() != 0) {
            return current;
        }
        if (south && north) {
            return new TableState(Axis.Z, 2);
        }
        if (south) {
            return new TableState(Axis.Z, 1);
        }
        if (north) {
            return new TableState(Axis.Z, 3);
        }
        return new TableState(current.axis(), 0);
    }
}
