package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BarrelSemanticsTest {
    @Test
    void onlyTheCeilingLayerCanOperateTheLid() {
        assertEquals(BarrelSemantics.Hit.BODY, BarrelSemantics.classify(0, 1.998, 0));
        assertEquals(BarrelSemantics.Hit.TOP_RIM, BarrelSemantics.classify(1, 2.25, 0));
        assertEquals(BarrelSemantics.Hit.TOP_RIM, BarrelSemantics.classify(-1, 2.75, 1));
    }

    @Test
    void onlyIndexFourCanAccessContents() {
        assertEquals(BarrelSemantics.Hit.TOP_CENTER, BarrelSemantics.classify(0, 2.4, 0));
        assertEquals(BarrelSemantics.Hit.TOP_CENTER, BarrelSemantics.classify(0.5, 3, -0.5));
        assertEquals(BarrelSemantics.Hit.TOP_RIM, BarrelSemantics.classify(0.5011, 2.4, 0));
    }

    @Test
    void pointsOutsideTheMultiblockAreNotTopClicks() {
        assertEquals(BarrelSemantics.Hit.BODY, BarrelSemantics.classify(1.6, 2.5, 0));
        assertEquals(BarrelSemantics.Hit.BODY, BarrelSemantics.classify(0, 3.1, 0));
    }

    @Test
    void brewingUsesTheSourcesNinetySevenTickQuantization() {
        assertEquals(new BarrelSemantics.BrewState(1, 2303),
                BarrelSemantics.advance(1, 2400, 2400));
        assertEquals(new BarrelSemantics.BrewState(1, -47),
                BarrelSemantics.advance(1, 50, 2400));
        assertEquals(new BarrelSemantics.BrewState(2, 4800),
                BarrelSemantics.advance(1, -47, 2400));
        assertEquals(new BarrelSemantics.BrewState(6, -1),
                BarrelSemantics.advance(5, 0, 2400));
    }

    @Test
    void tapFailuresUseTheSourceCheckOrder() {
        assertEquals(BarrelSemantics.TapExtractStatus.NOT_BREWING,
                BarrelSemantics.tapExtractStatus(false, 0, false));
        assertEquals(BarrelSemantics.TapExtractStatus.EMPTY,
                BarrelSemantics.tapExtractStatus(true, 0, false));
        assertEquals(BarrelSemantics.TapExtractStatus.INVALID_CONTAINER,
                BarrelSemantics.tapExtractStatus(true, 1, false));
        assertEquals(BarrelSemantics.TapExtractStatus.READY,
                BarrelSemantics.tapExtractStatus(true, 1, true));
    }
}
