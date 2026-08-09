package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.tap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TapFlowAppearanceTest {
    @Test
    void preservesConfiguredRgbWithoutChangingNativeStyles() {
        TapFlowAppearance wine = TapFlowAppearance.colored(0xFF55FF);

        assertEquals(TapFlowAppearance.Style.COLOR, wine.style());
        assertEquals(0xFF55FF, wine.rgb());
        assertEquals(0, TapFlowAppearance.WATER.rgb());
        assertEquals(0, TapFlowAppearance.LAVA.rgb());
        assertEquals(0, TapFlowAppearance.HONEY.rgb());
        assertEquals(0, TapFlowAppearance.OBSIDIAN_TEAR.rgb());
    }

    @Test
    void rejectsColorsOutsideRgbRange() {
        assertThrows(IllegalArgumentException.class,
                () -> TapFlowAppearance.colored(-1));
        assertThrows(IllegalArgumentException.class,
                () -> TapFlowAppearance.colored(0x1000000));
    }
}
