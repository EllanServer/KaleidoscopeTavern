package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

/**
 * Source-space storage transforms and hit selection copied from the archived
 * Forge renderers/blocks. Coordinates are expressed inside the original
 * occupied block (0..1), before the block's horizontal facing rotation.
 */
public final class StorageSemantics {
    private static final double FACE_EPSILON = 1.0E-3;

    private StorageSemantics() {
    }

    public enum Kind {
        BAR_CABINET,
        CELLAR_CABINET,
        TILTED_RACK,
        CIRCULAR_RACK,
        HOLDER,
        GLASSWARE_HOLDER
    }

    public record Visual(double centerX, double centerY, double centerZ,
                         float scale, float yRot, float xRot, boolean rotateWithFacing) {
    }

    public static Visual visual(Kind kind, int slot, boolean irregular, boolean facingAxisX) {
        return switch (kind) {
            case BAR_CABINET -> {
                double offset = irregular ? 0 : (slot == 0 ? 0.25 : -0.25);
                if (facingAxisX) {
                    offset = -offset;
                }
                yield renderStack(0.5 + offset, 0.0625, 0.5, 0.9F, 0, 0, true);
            }
            case CELLAR_CABINET -> {
                int row = slot / 3;
                int column = slot % 3;
                yield renderStack(0.825 - column * 0.325, 0.78 - row * 0.29,
                        0.875, 1, 0, -90, true);
            }
            case TILTED_RACK -> tiltedRack(slot);
            case CIRCULAR_RACK -> circularRack(slot);
            case HOLDER -> renderStack(0.5, 0.125, 0.75, 0.95F, 0, -45, true);
            case GLASSWARE_HOLDER -> glasswareHolder(slot);
        };
    }

    public static float correctedFacingYaw(Kind kind, float facingYaw, boolean facingAxisX) {
        // CE's ItemDisplay yaw mirrors the east/west block-model mapping for
        // these two asymmetric racks. Keep north/south byte-for-byte stable
        // and reflect only the quarter turns, matching their corrected bases.
        if (facingAxisX && (kind == Kind.TILTED_RACK || kind == Kind.HOLDER)) {
            return -facingYaw;
        }
        return facingYaw;
    }

    public static boolean changesRenderedArrangement(
            Kind kind, boolean facingChanged, boolean connectionChanged) {
        return facingChanged || (kind == Kind.CELLAR_CABINET && connectionChanged);
    }

    static int clickedSlot(Kind kind, double sourceX, double sourceY, double sourceZ,
                           boolean facingAxisX) {
        // GlasswareHolderBlock#getSlotFromHit only used the hit's block-local
        // X/Z quadrants. A ceiling furniture's interaction entity has a
        // different vertical origin from the archived block, so rejecting its
        // world hit by sourceY makes every otherwise valid click miss.
        boolean insideSource = inside(sourceX) && inside(sourceZ)
                && (kind == Kind.GLASSWARE_HOLDER || inside(sourceY));
        if (!insideSource) {
            return -1;
        }
        double x = clamp(sourceX);
        double y = clamp(sourceY);
        double z = clamp(sourceZ);
        return switch (kind) {
            case BAR_CABINET -> facingAxisX == (x < 0.5) ? 0 : 1;
            case CELLAR_CABINET -> cellarSlot(x, y, z);
            case TILTED_RACK -> thirds(1 - x);
            case CIRCULAR_RACK -> circularSlot(1 - x, z);
            case HOLDER -> 0;
            case GLASSWARE_HOLDER -> x > 0.5 ? (z > 0.5 ? 3 : 1) : (z > 0.5 ? 2 : 0);
        };
    }

    private static Visual renderStack(double x, double y, double z, float scale,
                                      double yRot, double xRot, boolean rotateWithFacing) {
        Point verticalCenter = rotate(0, scale * 0.5, 0, yRot, xRot);
        return new Visual(x + verticalCenter.x(), y + verticalCenter.y(), z + verticalCenter.z(),
                scale, (float) yRot, (float) xRot, rotateWithFacing);
    }

    private static Visual tiltedRack(int slot) {
        double x = 0.425 - 0.375 * slot;
        double y = 0.3125;
        double z = 0.02 + (slot - 1) * 0.005;
        float scale = 0.9F;
        Point rotatedCenter = rotate(0.5, 0.5, 0.5, 0, 22.5);
        // TiltedRackBlockEntityRender applies scale before both translation
        // and rotation, unlike StorageBlockEntityRender.renderStack().
        return new Visual(
                scale * (x + rotatedCenter.x()),
                scale * (y + rotatedCenter.y()),
                scale * (z + rotatedCenter.z()),
                scale, 0, 22.5F, true);
    }

    private static Visual circularRack(int slot) {
        double x;
        double z;
        double yRot;
        switch (slot) {
            case 0 -> {
                x = 0.5;
                z = 0.125;
                yRot = 0;
            }
            case 1 -> {
                x = 0.875;
                z = 0.3125;
                yRot = 22.5;
            }
            case 2 -> {
                x = 0.875;
                z = 0.6875;
                yRot = -22.5;
            }
            case 3 -> {
                x = 0.5;
                z = 0.875;
                yRot = 180;
            }
            case 4 -> {
                x = 0.125;
                z = 0.6875;
                yRot = 157.5;
            }
            case 5 -> {
                x = 0.125;
                z = 0.3125;
                yRot = -157.5;
            }
            default -> throw new IllegalArgumentException("Invalid circular-rack slot: " + slot);
        }
        return renderStack(x, 0.125, z, 0.82F, yRot, 0, true);
    }

    private static Visual glasswareHolder(int slot) {
        if (slot < 0 || slot > 3) {
            throw new IllegalArgumentException("Invalid glassware-holder slot: " + slot);
        }
        double x = -0.25 + 0.5 * (slot % 2);
        double z = 0.75 + 0.5 * (slot / 2);
        Point center = rotate(0.5, 0.5, 0.5, 0, -180);
        // The Forge renderer deliberately did not rotate these four contents
        // with FACING, so rotateWithFacing remains false.
        return new Visual(x + center.x(), 0.76 + center.y(), z + center.z(),
                1, 0, -180, false);
    }

    private static int cellarSlot(double sourceX, double sourceY, double sourceZ) {
        if (!isFrontFace(sourceX, sourceY, sourceZ)) {
            return -1;
        }
        int column = ((int) ((1 - sourceX) * 3)) % 3;
        int row = 2 - ((int) (sourceY * 3)) % 3;
        return column + row * 3;
    }

    private static boolean isFrontFace(double x, double y, double z) {
        double nearestOtherFace = Math.min(
                Math.min(Math.min(x, 1 - x), Math.min(y, 1 - y)),
                1 - z);
        return z <= nearestOtherFace + FACE_EPSILON;
    }

    private static int thirds(double coordinate) {
        if (coordinate < 1.0 / 3.0) {
            return 0;
        }
        return coordinate < 2.0 / 3.0 ? 1 : 2;
    }

    private static int circularSlot(double localX, double localZ) {
        double angle = Math.toDegrees(Math.atan2(localZ - 0.5, localX - 0.5));
        angle = (angle + 360) % 360;
        if (angle > 300) {
            return 5;
        }
        if (angle > 240) {
            return 0;
        }
        if (angle > 180) {
            return 1;
        }
        if (angle > 120) {
            return 2;
        }
        return angle > 60 ? 3 : 4;
    }

    private static Point rotate(double x, double y, double z, double yRot, double xRot) {
        double xRadians = Math.toRadians(xRot);
        double xCos = Math.cos(xRadians);
        double xSin = Math.sin(xRadians);
        double afterXy = xCos * y - xSin * z;
        double afterXz = xSin * y + xCos * z;

        double yRadians = Math.toRadians(yRot);
        double yCos = Math.cos(yRadians);
        double ySin = Math.sin(yRadians);
        return new Point(
                yCos * x + ySin * afterXz,
                afterXy,
                -ySin * x + yCos * afterXz);
    }

    private static boolean inside(double coordinate) {
        return coordinate >= -FACE_EPSILON && coordinate <= 1 + FACE_EPSILON;
    }

    private static double clamp(double coordinate) {
        return Math.max(0, Math.min(1, coordinate));
    }

    private record Point(double x, double y, double z) {
    }
}
