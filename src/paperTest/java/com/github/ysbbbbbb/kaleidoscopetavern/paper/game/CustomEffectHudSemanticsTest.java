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
