package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

/** Pure bottle-stack state rules shared by the runtime and parity tests. */
final class BottleFurnitureSemantics {
    private BottleFurnitureSemantics() {
    }

    /** A single bottle is already represented losslessly by CE's sourceItem. */
    static boolean needsExpandedItemState(int count) {
        return count > 1;
    }

    static String variantForCount(int count) {
        return count <= 1 ? "ground" : "ground_count_" + count;
    }
}
