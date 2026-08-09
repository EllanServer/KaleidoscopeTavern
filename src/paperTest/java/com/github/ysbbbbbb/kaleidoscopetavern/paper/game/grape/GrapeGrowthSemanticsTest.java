package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape;

import org.junit.jupiter.api.Test;

import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape.GrapeGrowthSemantics.Variety.GOLD;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape.GrapeGrowthSemantics.Variety.ICE;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape.GrapeGrowthSemantics.Variety.NORMAL;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GrapeGrowthSemanticsTest {
    @Test
    void preservesSourceClimateThresholds() {
        assertEquals(0.25D, GrapeGrowthSemantics.overallChance(NORMAL, -1), 1.0E-12);
        assertEquals(0.8D, GrapeGrowthSemantics.overallChance(ICE, 0.149D), 1.0E-12);
        assertEquals(0.25D, GrapeGrowthSemantics.overallChance(ICE, 0.15D), 1.0E-12);
        assertEquals(0.25D, GrapeGrowthSemantics.overallChance(GOLD, 1.0D), 1.0E-12);
        assertEquals(0.8D, GrapeGrowthSemantics.overallChance(GOLD, 1.001D), 1.0E-12);
    }

}
