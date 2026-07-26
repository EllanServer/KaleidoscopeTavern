package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

/** Source-compatible pressing-tub geometry and fall thresholds. */
public final class PressingTubSemantics {
    public static final float MIN_FALL_DISTANCE = 0.5F;
    static final float TILT_X_DEGREES = -45F;
    static final float ITEM_X_DEGREES = -90F;

    private PressingTubSemantics() {
    }

    /**
     * Movement events only matter while this entity is falling or while its
     * own earlier fall still needs the landing edge observed.
     */
    static boolean needsMovementInspection(float fallDistance, boolean trackingEntity) {
        return fallDistance > 0 || trackingEntity;
    }

    /** Horizontal ownership plus the source ground-tub landing height. */
    public static boolean isLandingPosition(double feetX, double feetY, double feetZ,
                                            double baseX, double baseY, double baseZ) {
        double relativeY = feetY - baseY;
        return ownsColumn(feetX, feetZ, baseX, baseZ)
                && relativeY >= 0.35 && relativeY <= 1.25;
    }

    /** Whether a falling entity is currently over a possible ground-tub landing. */
    public static boolean isAboveColumn(double feetX, double feetY, double feetZ,
                                        double baseX, double baseY, double baseZ) {
        return ownsColumn(feetX, feetZ, baseX, baseZ) && feetY - baseY >= 0.35;
    }

    private static boolean ownsColumn(double feetX, double feetZ,
                                      double baseX, double baseZ) {
        return Math.abs(feetX - baseX) <= 0.5
                && Math.abs(feetZ - baseZ) <= 0.5;
    }

    /**
     * Applies the source renderer's facing=SOUTH tilted pose to one point in
     * block coordinates. CraftEngine's wall yaw subsequently rotates this
     * canonical south-facing result onto the wall selected by the player.
     */
    static Point tiltSouth(double x, double y, double z) {
        // PoseStack order:
        // T(.5,0,.5) * Ry(-180) * T(-.5,0,-.5)
        // * Rx(-45) * T(0,-.25,.25) * point
        double translatedY = y - 0.25;
        double translatedZ = z + 0.25;
        double radians = Math.toRadians(TILT_X_DEGREES);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double rotatedY = cos * translatedY - sin * translatedZ;
        double rotatedZ = sin * translatedY + cos * translatedZ;

        double centeredX = x - 0.5;
        double centeredZ = rotatedZ - 0.5;
        return new Point(0.5 - centeredX, rotatedY, 0.5 - centeredZ);
    }

    record Point(double x, double y, double z) {
    }
}
