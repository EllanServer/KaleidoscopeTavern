package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void miniMessageLineIsEmptyWithoutEffects() {
        assertEquals("", CustomEffectHudSemantics.miniMessageLine(List.of()));
    }

    @Test
    void miniMessageLineSurvivesAMiniMessageRoundTrip() {
        String line = CustomEffectHudSemantics.miniMessageLine(List.of(
                new CustomEffectHudSemantics.EffectEntry("kaleidoscope_tavern:vision", 1_199, 2),
                new CustomEffectHudSemantics.EffectEntry(
                        "kaleidoscope_tavern:slightly_tipsy", 72_000, 0)));
        assertTrue(line.startsWith("<!i>"));
        // Effect names carry the registered mod colours as MiniMessage hex tags.
        assertTrue(line.contains("<#408997>"), line);
        assertTrue(line.contains("<#FFD94A>"), line);

        Component parsed = MiniMessage.miniMessage().deserialize(line);
        Set<String> translationKeys = new HashSet<>();
        List<TextComponent> texts = new ArrayList<>();
        walk(parsed, component -> {
            if (component instanceof TranslatableComponent translatable) {
                translationKeys.add(translatable.key());
            } else if (component instanceof TextComponent text) {
                texts.add(text);
            }
        });

        // The vanilla nesting parsed despite the mixed argument quoting.
        assertTrue(translationKeys.containsAll(Set.of(
                "potion.withDuration", "potion.withAmplifier", "potion.potency.2",
                "effect.kaleidoscope_tavern.vision",
                "effect.kaleidoscope_tavern.slightly_tipsy")));
        // No tag survived as literal text.
        for (TextComponent text : texts) {
            assertFalse(text.content().contains("<"), text.content());
        }
        // Icons render through the resource-pack bitmap font.
        assertTrue(texts.stream().anyMatch(text -> "".equals(text.content())
                && Key.key(CustomEffectHudSemantics.FONT_KEY).equals(text.font())));
    }

    @Test
    void ambientSwirlColorsMatchTheForgeRegistrations() {
        assertEquals(0x408997, CustomEffectHudSemantics.color("kaleidoscope_tavern:vision"));
        assertEquals(0xFFD94A,
                CustomEffectHudSemantics.color("kaleidoscope_tavern:slightly_tipsy"));
        assertEquals(0x0D4C4A,
                CustomEffectHudSemantics.color("kaleidoscope_tavern:shriek_attack"));
        assertNull(CustomEffectHudSemantics.color("example:future_effect"));
    }

    @Test
    void cornerLineIsEmptyWithoutEffects() {
        assertEquals("", CustomEffectHudSemantics.cornerLine(List.of(), 240));
    }

    @Test
    void cornerLineRebuildsTheVanillaOverlayGeometryWithZeroTotalAdvance() {
        String line = CustomEffectHudSemantics.cornerLine(List.of(
                new CustomEffectHudSemantics.EffectEntry("kaleidoscope_tavern:vision", 1_199, 2),
                new CustomEffectHudSemantics.EffectEntry("kaleidoscope_tavern:high_heels", 200, 0),
                new CustomEffectHudSemantics.EffectEntry(
                        "kaleidoscope_tavern:slightly_tipsy", 200, 0)),
                240);

        Component parsed = MiniMessage.miniMessage().deserialize(line);
        StringBuilder hudGlyphs = new StringBuilder();
        walk(parsed, component -> {
            if (component instanceof TextComponent text) {
                assertFalse(text.content().contains("<"), text.content());
                if (Key.key(CustomEffectHudSemantics.HUD_FONT_KEY).equals(text.font())) {
                    hudGlyphs.append(text.content());
                }
            }
        });
        String glyphs = hudGlyphs.toString();

        // Centre-anchored boss bar text keeps its anchor only at zero width.
        assertEquals(0, advanceOf(glyphs));
        // Beneficial effects use row one, the neutral slightly_tipsy row two.
        assertEquals(2, glyphs.chars().filter(glyph -> glyph == 0xE320).count());
        assertEquals(1, glyphs.chars().filter(glyph -> glyph == 0xE321).count());
        // Icon glyphs reuse the shared 0xE100 index on the row pages.
        assertTrue(glyphs.contains("") && glyphs.contains("")
                && glyphs.contains(""));
        // The first frame starts at gui-half-width - 25, like the vanilla HUD.
        assertEquals(215, advanceOf(glyphs.substring(0, glyphs.indexOf(''))));
    }

    /** Mirrors the generated space/frame/icon advances to pin the layout. */
    private static int advanceOf(String glyphs) {
        int total = 0;
        for (char glyph : glyphs.toCharArray()) {
            if (glyph >= 0xE300 && glyph <= 0xE308) {
                total += 1 << (glyph - 0xE300);
            } else if (glyph >= 0xE310 && glyph <= 0xE318) {
                total -= 1 << (glyph - 0xE310);
            } else if (glyph == 0xE320 || glyph == 0xE321) {
                total += 25;
            } else if (glyph >= 0xE330 && glyph <= 0xE34B) {
                total += 19;
            } else {
                throw new AssertionError("unexpected glyph " + Integer.toHexString(glyph));
            }
        }
        return total;
    }

    private static void walk(Component component, Consumer<Component> visitor) {
        visitor.accept(component);
        if (component instanceof TranslatableComponent translatable) {
            for (TranslationArgument argument : translatable.arguments()) {
                walk(argument.asComponent(), visitor);
            }
        }
        for (Component child : component.children()) {
            walk(child, visitor);
        }
    }
}
