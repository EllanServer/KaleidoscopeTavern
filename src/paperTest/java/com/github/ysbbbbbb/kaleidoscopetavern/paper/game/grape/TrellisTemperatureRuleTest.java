package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TrellisTemperatureRuleTest {

    @Test
    void ordinaryGrapesUseTheirConfiguredChanceWithoutTemperatureAdjustment() {
        TrellisTemperatureSemantics.Rule rule =
                TrellisTemperatureSemantics.ruleForBlock(
                        "kaleidoscope_tavern:grapevine_trellis");

        assertSame(TrellisTemperatureSemantics.Rule.NONE, rule);
        assertEquals(0.25F, rule.adjust(0.25F, -10.0D));
        assertEquals(0.25F, rule.adjust(0.25F, 10.0D));
    }

    @Test
    void iceGrapesOnlyGainTheSourceBoostBelowPointFifteen() {
        TrellisTemperatureSemantics.Rule rule =
                TrellisTemperatureSemantics.ruleForBlock(
                        "kaleidoscope_tavern:ice_grapevine_trellis");

        assertSame(TrellisTemperatureSemantics.Rule.COLD, rule);
        assertEquals(0.8F, rule.adjust(0.25F, Math.nextDown(0.15F)));
        assertEquals(0.25F, rule.adjust(0.25F, 0.15F));
    }

    @Test
    void goldGrapesOnlyGainTheSourceBoostAboveOne() {
        TrellisTemperatureSemantics.Rule rule =
                TrellisTemperatureSemantics.ruleForBlock(
                        "kaleidoscope_tavern:gold_grapevine_trellis");

        assertSame(TrellisTemperatureSemantics.Rule.HOT, rule);
        assertEquals(0.25F, rule.adjust(0.25F, 1.0F));
        assertEquals(0.8F, rule.adjust(0.25F, Math.nextUp(1.0F)));
    }

    @Test
    void climateBoostNeverLowersAConfiguredChance() {
        assertEquals(0.9F,
                TrellisTemperatureSemantics.Rule.COLD.adjust(0.9F, 0.0D));
        assertEquals(0.9F,
                TrellisTemperatureSemantics.Rule.HOT.adjust(0.9F, 2.0D));
    }
}
