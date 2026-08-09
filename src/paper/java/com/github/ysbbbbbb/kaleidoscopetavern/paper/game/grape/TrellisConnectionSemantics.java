package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape;

/** Pure connection-state reducer for the three possible trellis axes. */
final class TrellisConnectionSemantics {
    private TrellisConnectionSemantics() {
    }

    /**
     * Combines CE's native placement axis with every axis that has an adjacent
     * trellis. The base axis is never discarded merely because it has no
     * neighbour, so a vertical placement cannot collapse into a horizontal
     * shape during CE's immediate placement update.
     */
    static String typeFor(String baseAxis, boolean xConnected,
                          boolean yConnected, boolean zConnected) {
        if (!baseAxis.equals("x") && !baseAxis.equals("y") && !baseAxis.equals("z")) {
            throw new IllegalArgumentException("Unknown trellis axis: " + baseAxis);
        }
        boolean x = xConnected || baseAxis.equals("x");
        boolean y = yConnected || baseAxis.equals("y");
        boolean z = zConnected || baseAxis.equals("z");
        if (x && y && z) {
            return "six_direction";
        }
        if (x && y) {
            return "cross_east_west";
        }
        if (y && z) {
            return "cross_north_south";
        }
        if (x && z) {
            return "cross_up_down";
        }
        if (x) {
            return "east_west";
        }
        if (z) {
            return "north_south";
        }
        if (y) {
            return "single";
        }
        throw new IllegalStateException("A valid trellis axis must produce a shape");
    }

    static boolean containsAxis(String type, String axis) {
        return switch (axis) {
            case "x" -> type.equals("east_west")
                    || type.equals("cross_east_west")
                    || type.equals("cross_up_down")
                    || type.equals("six_direction");
            case "y" -> type.equals("single")
                    || type.equals("cross_east_west")
                    || type.equals("cross_north_south")
                    || type.equals("six_direction");
            case "z" -> type.equals("north_south")
                    || type.equals("cross_north_south")
                    || type.equals("cross_up_down")
                    || type.equals("six_direction");
            default -> false;
        };
    }
}
