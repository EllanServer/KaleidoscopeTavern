package com.github.ysbbbbbb.kaleidoscopetavern.paper.item;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.EffectSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;

/** Builds the client-localised replacement for the vanilla potion tooltip. */
final class DrinkLore {
    static final String MANAGED_INSERTION = "kaleidoscope_tavern_managed_lore";

    private static final String CHANCE_KEY = "tooltip.kaleidoscope_tavern.drink_effect.chance";
    private static final String COLOR_PREFIX_KEY = "color.kaleidoscope_tavern.prefix";
    private static final String BREW_LEVEL_KEY = "tooltip.kaleidoscope_tavern.bottle_block.brew_level";

    private DrinkLore() {
    }

    static Component effectLine(EffectSpec spec, NamedTextColor color) {
        DrinkEffectLoreSemantics.Display display = DrinkEffectLoreSemantics.describe(spec);
        Component effect = Component.translatable(display.effectKey());
        if (display.potencyKey() != null) {
            effect = Component.translatable("potion.withAmplifier", effect,
                    Component.translatable(display.potencyKey()));
        }
        if (display.duration() != null) {
            effect = Component.translatable("potion.withDuration", effect,
                    Component.text(display.duration()));
        }
        if (display.chance() != null) {
            effect = effect.append(Component.space()).append(Component.translatable(
                    CHANCE_KEY, Component.text(display.chance())).color(NamedTextColor.DARK_GRAY));
        }
        return managed(effect.color(color).decoration(TextDecoration.ITALIC, false));
    }

    static Component colorLine(String colorName, int rgb) {
        return managed(Component.translatable(COLOR_PREFIX_KEY)
                .color(NamedTextColor.GRAY)
                .append(Component.translatable("color.kaleidoscope_tavern." + colorName)
                        .color(TextColor.color(rgb)))
                .decoration(TextDecoration.ITALIC, false));
    }

    static Component qualityLine(int level) {
        Component quality = Component.translatable(
                "message.kaleidoscope_tavern.barrel.brew_level." + level);
        return managed(Component.translatable(BREW_LEVEL_KEY, quality)
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
    }

    static Component managedBlank() {
        return managed(Component.empty());
    }

    static List<Component> attributeSection(
            List<DrinkEffectLoreSemantics.AttributeDisplay> attributes) {
        if (attributes.isEmpty()) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>(attributes.size() + 2);
        lines.add(managedBlank());
        lines.add(managed(Component.translatable("potion.whenDrank")
                .color(NamedTextColor.DARK_PURPLE)
                .decoration(TextDecoration.ITALIC, false)));
        for (DrinkEffectLoreSemantics.AttributeDisplay attribute : attributes) {
            lines.add(managed(Component.translatable(
                            attribute.modifierKey(),
                            Component.text(attribute.amount()),
                            Component.translatable(attribute.attributeKey()))
                    .color(attribute.modifierKey().contains(".take.")
                            ? NamedTextColor.RED : NamedTextColor.BLUE)
                    .decoration(TextDecoration.ITALIC, false)));
        }
        return List.copyOf(lines);
    }

    static Component managed(Component component) {
        return component.insertion(MANAGED_INSERTION);
    }

    static boolean isManagedDrinkLine(Component component) {
        return hasManagedMarker(component);
    }

    static boolean hasManagedMarker(Component component) {
        if (MANAGED_INSERTION.equals(component.insertion())) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasManagedMarker(child)) {
                return true;
            }
        }
        if (component instanceof TranslatableComponent translated) {
            for (TranslationArgument argument : translated.arguments()) {
                if (hasManagedMarker(argument.asComponent())) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isManagedShakerLine(Component component) {
        return hasManagedMarker(component);
    }
}
