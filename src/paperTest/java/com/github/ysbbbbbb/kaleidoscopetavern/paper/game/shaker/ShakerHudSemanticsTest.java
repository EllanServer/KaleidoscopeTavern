package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.shaker;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShakerHudSemanticsTest {
    @Test
    void pointerMatchesEveryForgeTimingBoundary() {
        assertEquals(27, ShakerHudSemantics.pointerOffsetPixels(18));
        assertEquals(29, ShakerHudSemantics.pointerOffsetPixels(19));
        assertEquals(102, ShakerHudSemantics.pointerOffsetPixels(68));
        assertEquals(104, ShakerHudSemantics.pointerOffsetPixels(69));
        assertEquals(132, ShakerHudSemantics.pointerOffsetPixels(88));
        assertEquals(134, ShakerHudSemantics.pointerOffsetPixels(89));
        assertEquals(147, ShakerHudSemantics.pointerOffsetPixels(98));
        assertEquals(149, ShakerHudSemantics.pointerOffsetPixels(99));
        assertEquals(165, ShakerHudSemantics.pointerOffsetPixels(110));
        assertEquals(165, ShakerHudSemantics.pointerOffsetPixels(111));
        assertEquals(0, ShakerHudSemantics.pointerOffsetPixels(-1));
    }

    @Test
    void layeredProgressLineKeepsAStableCenteredAdvance() {
        for (int ticks : List.of(0, 18, 19, 68, 69, 88, 89, 98, 99, 110)) {
            ShakerHudSemantics.ProgressLayout layout =
                    ShakerHudSemantics.progressLayout(ticks);
            assertEquals(ShakerHudSemantics.BAR_ADVANCE_PIXELS,
                    layout.totalAdvancePixels());
            assertEquals(1, layout.glyphs().chars()
                    .filter(value -> value == ShakerHudSemantics.BAR_GLYPH).count());
            assertEquals(1, layout.glyphs().chars()
                    .filter(value -> value == ShakerHudSemantics.POINTER_GLYPH).count());
        }
    }

    @Test
    void ingredientRhombiUseTheHudFontAndSourceColors() {
        Component component = ShakerHudSemantics.ingredientSubtitle(
                List.of(0xFF55FF, 0x5555FF, 0xFFFFFF));
        List<TextComponent> rhombi = new ArrayList<>();
        walk(component, child -> {
            if (child instanceof TextComponent text
                    && text.content().equals(String.valueOf(
                    ShakerHudSemantics.INGREDIENT_GLYPH))) {
                rhombi.add(text);
            }
        });

        assertEquals(3, rhombi.size());
        assertTrue(rhombi.stream().allMatch(text ->
                Key.key(ShakerHudSemantics.FONT_KEY).equals(text.font())));
        assertEquals(0xFF55FF, rhombi.get(0).color().value());
        assertEquals(0x5555FF, rhombi.get(1).color().value());
        assertEquals(0xFFFFFF, rhombi.get(2).color().value());
    }

    private static void walk(Component component,
                             java.util.function.Consumer<Component> consumer) {
        consumer.accept(component);
        component.children().forEach(child -> walk(child, consumer));
    }
}
