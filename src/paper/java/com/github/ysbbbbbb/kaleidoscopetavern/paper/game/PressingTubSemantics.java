package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

/** Exact item-layer transform used by the source tilted pressing-tub renderer. */
final class PressingTubSemantics {
    static final float TILT_X_DEGREES = -45F;
    static final float ITEM_X_DEGREES = -90F;

    private PressingTubSemantics() {
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
