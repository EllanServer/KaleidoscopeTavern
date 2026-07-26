package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

/** Allocation-free column bounds and box checks shared by CE furniture indexes. */
public final class FurnitureSpatialSemantics {
    private FurnitureSpatialSemantics() {
    }

    public static int minimumColumn(double center, double radius) {
        return (int) Math.floor(center - Math.max(0, radius));
    }

    public static int maximumColumn(double center, double radius) {
        return (int) Math.floor(center + Math.max(0, radius));
    }

    public static boolean insideBox(double x, double y, double z,
                                    double centerX, double centerY, double centerZ,
                                    double horizontalRadius, double verticalRadius) {
        double horizontal = Math.max(0, horizontalRadius);
        double vertical = Math.max(0, verticalRadius);
        return Math.abs(x - centerX) <= horizontal
                && Math.abs(y - centerY) <= vertical
                && Math.abs(z - centerZ) <= horizontal;
    }
}
