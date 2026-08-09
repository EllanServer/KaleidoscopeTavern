package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.tap;

/** Source-level geometry and timing rules shared by the Paper tap adapter. */
public final class TapSemantics {
    private static final String TAP_ITEM = "kaleidoscope_tavern:tap";
    static final int TAKE_TICKS = 30;
    static final int TAKE_PARTICLE_TICKS = 5;
    static final int EMPTY_OPEN_TICKS = 6;
    static final int FULL_LAVA_CAULDRON_LEVEL = 3;

    private TapSemantics() {
    }

    /** Exact one-block AABB used by Forge when looking for a dropped carrier item. */
    record BlockBounds(double minX, double minY, double minZ,
                       double maxX, double maxY, double maxZ) {
    }

    static BlockBounds blockBounds(int x, int y, int z) {
        return new BlockBounds(x, y, z, x + 1.0, y + 1.0, z + 1.0);
    }

    /** BarrelTapBehavior uses lava drips only when its current output is Molotov. */
    public static boolean isHotBarrelOutput(String resultId) {
        return "kaleidoscope_tavern:molotov".equals(resultId);
    }

    /** A lava cauldron is consumed after a successful extraction unless infinite mode is explicit. */
    static boolean shouldConsumeLavaSource(boolean infiniteLavaFromTap) {
        return !infiniteLavaFromTap;
    }

    static int lavaLevelAfterExtraction(int currentLevel, int extractedLevels,
                                        boolean infiniteLavaFromTap) {
        int normalized = Math.max(0, Math.min(FULL_LAVA_CAULDRON_LEVEL, currentLevel));
        if (infiniteLavaFromTap) {
            return normalized;
        }
        return Math.max(0, normalized - Math.max(0, extractedLevels));
    }

    /**
     * Forge skips the barrel block's own use action while the player is
     * sneaking, allowing TapBlockItem placement to continue.  A CE barrel is
     * furniture, so its controller must explicitly yield the same interaction
     * to CE's native furniture-item behavior.
     */
    public static boolean shouldDelegateBarrelTapPlacement(
            boolean secondaryUse,
            String heldItemId
    ) {
        return secondaryUse && TAP_ITEM.equals(heldItemId);
    }

    /**
     * The Forge tap may only connect to the middle cell of the barrel's front
     * face (LAYER=wall and INDEX=1/3/5/7).  The barrel furniture origin is the
     * source multiblock's bottom-centre cell.
     */
    static boolean isBarrelConnection(
            int sourceX, int sourceY, int sourceZ,
            int tapFacingX, int tapFacingZ,
            int barrelX, int barrelY, int barrelZ,
            int barrelFacingX, int barrelFacingZ
    ) {
        return tapFacingX == barrelFacingX
                && tapFacingZ == barrelFacingZ
                && sourceX == barrelX + barrelFacingX
                && sourceY == barrelY + 1
                && sourceZ == barrelZ + barrelFacingZ;
    }

    /**
     * EMPTY_OPEN_STATE emits cloud particles on ticks 2, 4 and 6: the source
     * spawns the cloud before the same-tick close check.
     */
    static boolean emitsEmptyCloud(int tick) {
        return tick <= EMPTY_OPEN_TICKS && tick % 2 == 0;
    }
}
