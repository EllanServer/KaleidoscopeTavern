package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import net.momirealms.craftengine.core.util.Direction;

/** Pure legacy-variant decoding kept separate from Bukkit/CE runtime bootstrap. */
final class LegacyConnectedBlockMigrationSemantics {
    private LegacyConnectedBlockMigrationSemantics() {
    }

    static TableProperties tableProperties(String variant, Direction facing) {
        String axis = variant.contains("_axis_x_") ? "x" : "z";
        int position = 0;
        int marker = variant.indexOf("_position_");
        if (marker >= 0 && marker + 10 < variant.length()) {
            char value = variant.charAt(marker + 10);
            if (value >= '1' && value <= '3') {
                position = value - '0';
            }
        }
        if (position != 0 && !variant.contains("_facing_")) {
            if (axis.equals("x")) {
                axis = facing.clockWise().axis() == Direction.Axis.X
                        ? "x" : "z";
            } else {
                axis = facing.axis() == Direction.Axis.X ? "x" : "z";
            }
        }
        return new TableProperties(axis, position);
    }

    record TableProperties(String axis, int position) {
    }
}
