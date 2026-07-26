package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CustomEffectHudSemanticsTest {
    @Test
    void describesTheVisibleLayerUsingTheOriginalEffectIcon() {
        CustomEffectHudSemantics.Display display = CustomEffectHudSemantics.describe(
                "kaleidoscope_tavern:high_heels", 72_000, 0);

        assertEquals("effect.kaleidoscope_tavern.high_heels", display.effectKey());
        assertEquals("\uE101", display.icon());
        assertNull(display.potencyKey());
        assertEquals("1:00:00", display.duration());
    }

    @Test
    void exposesAmplifierAndRoundsDownLikeTheVanillaPotionTooltip() {
        CustomEffectHudSemantics.Display display = CustomEffectHudSemantics.describe(
                "kaleidoscope_tavern:vision", 1_199, 2);

        assertEquals("potion.potency.2", display.potencyKey());
        assertEquals("0:59", display.duration());
        assertEquals("0:01", CustomEffectHudSemantics.formatDuration(1));
        assertEquals("1:00", CustomEffectHudSemantics.formatDuration(1_200));
    }

    @Test
    void unknownPersistedEffectStillHasALocalisedTextFallback() {
        CustomEffectHudSemantics.Display display = CustomEffectHudSemantics.describe(
                "example:future_effect", 20, 0);

        assertEquals("effect.example.future_effect", display.effectKey());
        assertNull(display.icon());
    }
}
