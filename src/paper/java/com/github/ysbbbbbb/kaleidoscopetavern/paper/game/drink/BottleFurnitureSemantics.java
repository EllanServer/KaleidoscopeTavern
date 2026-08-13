package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.drink;

/** Pure bottle-stack state rules shared by the runtime and parity tests. */
final class BottleFurnitureSemantics {
    private static final String AXIS_X_SUFFIX = "_axis_x";

    private BottleFurnitureSemantics() {
    }

    /** A single bottle is already represented losslessly by CE's sourceItem. */
    static boolean needsExpandedItemState(int count) {
        return count > 1;
    }

    static String variantForCount(int count) {
        return count <= 1 ? "ground" : "ground_count_" + count;
    }

    /**
     * CE owns the four-way furniture yaw. East/west need a separate display
     * variant because item-display model yaw composes in the opposite direction.
     */
    static String variantForCount(int count, float furnitureYaw) {
        return withCardinalAxis(variantForCount(count), furnitureYaw);
    }

    static String withCardinalAxis(String variant, float furnitureYaw) {
        String base = variant.endsWith(AXIS_X_SUFFIX)
                ? variant.substring(0, variant.length() - AXIS_X_SUFFIX.length())
                : variant;
        return usesXAxis(furnitureYaw) ? base + AXIS_X_SUFFIX : base;
    }

    private static boolean usesXAxis(float furnitureYaw) {
        int quarterTurns = Math.round(furnitureYaw / 90.0F);
        return Math.floorMod(quarterTurns, 2) == 1;
    }
}
