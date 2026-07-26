package com.github.ysbbbbbb.kaleidoscopetavern.paper.item;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.EffectSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DrinkLoreTest {
    @Test
    void describesTimedVanillaEffect() {
        DrinkEffectLoreSemantics.Display display = DrinkEffectLoreSemantics.describe(
                new EffectSpec("minecraft:nausea", 600, 0, 1.0));

        assertEquals("effect.minecraft.nausea", display.effectKey());
        assertNull(display.potencyKey());
        assertEquals("0:30", display.duration());
        assertNull(display.chance());
    }

    @Test
    void exposesAmplifierAndProbability() {
        DrinkEffectLoreSemantics.Display display = DrinkEffectLoreSemantics.describe(
                new EffectSpec("minecraft:speed", 1_200, 2, 0.15));

        assertEquals("potion.potency.2", display.potencyKey());
        assertEquals("1:00", display.duration());
        assertEquals("15%", display.chance());
    }

    @Test
    void omitsDurationForInstantEffectAndFormatsLongDurations() {
        assertNull(DrinkEffectLoreSemantics.describe(
                new EffectSpec("minecraft:instant_health", 0, 3, 1.0)).duration());
        assertEquals("1:01:01", DrinkEffectLoreSemantics.formatDuration(73_220));
    }

    @Test
    void formatsPotionAttributeModifiersLikeVanillaTooltips() {
        DrinkEffectLoreSemantics.AttributeDisplay strength = DrinkEffectLoreSemantics.attribute(
                "attribute.name.generic.attack_damage", 6.0,
                DrinkEffectLoreSemantics.ModifierOperation.ADD_NUMBER);
        DrinkEffectLoreSemantics.AttributeDisplay speed = DrinkEffectLoreSemantics.attribute(
                "attribute.name.generic.movement_speed", 0.4,
                DrinkEffectLoreSemantics.ModifierOperation.ADD_SCALAR);

        assertEquals("attribute.modifier.plus.0", strength.modifierKey());
        assertEquals("6", strength.amount());
        assertEquals("attribute.modifier.plus.1", speed.modifierKey());
        assertEquals("40", speed.amount());
    }

}
