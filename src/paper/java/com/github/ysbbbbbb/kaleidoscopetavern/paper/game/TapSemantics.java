package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

/** Source-level geometry and timing rules shared by the Paper tap adapter. */
final class TapSemantics {
    static final int TAKE_TICKS = 30;
    static final int TAKE_PARTICLE_TICKS = 5;
    static final int EMPTY_OPEN_TICKS = 6;

    private TapSemantics() {
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

    /** EMPTY_OPEN_STATE emits cloud particles on ticks 2 and 4 only. */
    static boolean emitsEmptyCloud(int tick) {
        return tick <= TAKE_PARTICLE_TICKS && tick % 2 == 0;
    }
}
