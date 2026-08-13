package com.github.ysbbbbbb.kaleidoscopetavern.paper.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NativeDrinkEffectSemanticsTest {
    @Test
    void embedsOnlyGuaranteedMinecraftEffects() {
        assertTrue(NativeDrinkEffectSemantics.shouldEmbed("minecraft:speed", 1.0));
        assertFalse(NativeDrinkEffectSemantics.shouldEmbed("minecraft:speed", 0.999));
        assertFalse(NativeDrinkEffectSemantics.shouldEmbed(
                "kaleidoscope_tavern:slightly_tipsy", 1.0));
        assertFalse(NativeDrinkEffectSemantics.shouldEmbed(null, 1.0));
    }

    @Test
    void eventBridgeIsTheExactComplementOfNativePotionContents() {
        assertFalse(NativeDrinkEffectSemantics.shouldApplyThroughEvent(
                "minecraft:speed", 1.0));
        assertTrue(NativeDrinkEffectSemantics.shouldApplyThroughEvent(
                "minecraft:speed", 0.15));
        assertTrue(NativeDrinkEffectSemantics.shouldApplyThroughEvent(
                "kaleidoscope_tavern:vision", 1.0));
    }

    @Test
    void givesInstantEffectsAUsablePotionDuration() {
        assertEquals(1, NativeDrinkEffectSemantics.duration(true, 0));
        assertEquals(40, NativeDrinkEffectSemantics.duration(true, 40));
        assertEquals(0, NativeDrinkEffectSemantics.duration(false, 0));
        assertEquals(200, NativeDrinkEffectSemantics.duration(false, 200));
    }
}
