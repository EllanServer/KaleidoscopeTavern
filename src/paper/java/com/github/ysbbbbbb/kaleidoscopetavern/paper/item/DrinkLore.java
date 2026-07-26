package com.github.ysbbbbbb.kaleidoscopetavern.paper.item;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.EffectSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

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

    static boolean isManagedOrLegacyDrinkLine(Component component, Set<String> effectKeys) {
        if (hasManagedMarker(component)) {
            return true;
        }
        if (!(component instanceof TranslatableComponent translated)) {
            // The old generated MiniMessage preview used a styled empty root
            // with nested effect components. It is deliberately not matched:
            // preserving an externally composed lore line is more important
            // than avoiding a one-time duplicate on those short-lived stacks.
            return false;
        }
        String rootKey = translated.key();
        if (COLOR_PREFIX_KEY.equals(rootKey)
                || BREW_LEVEL_KEY.equals(rootKey)) {
            return true;
        }
        if (!hasLegacyEffectStyle(component)) {
            return false;
        }
        if (effectKeys.contains(rootKey)) {
            return true;
        }
        if (!"potion.withDuration".equals(rootKey) && !"potion.withAmplifier".equals(rootKey)) {
            return false;
        }
        for (TranslationArgument argument : translated.arguments()) {
            if (hasTranslationKey(argument.asComponent(), effectKeys::contains)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLegacyEffectStyle(Component component) {
        TextColor color = component.color();
        return component.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE
                && (NamedTextColor.BLUE.equals(color)
                || NamedTextColor.RED.equals(color)
                || NamedTextColor.GRAY.equals(color));
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

    static boolean isManagedOrLegacyShakerLine(Component component) {
        return hasManagedMarker(component)
                || component instanceof TextComponent text
                && ManagedLoreSemantics.isLegacyShakerLine(text.content())
                && NamedTextColor.GRAY.equals(component.color())
                && !component.children().isEmpty();
    }

    private static boolean hasTranslationKey(Component component, Predicate<String> predicate) {
        if (component instanceof TranslatableComponent translated) {
            if (predicate.test(translated.key())) {
                return true;
            }
            for (TranslationArgument argument : translated.arguments()) {
                if (hasTranslationKey(argument.asComponent(), predicate)) {
                    return true;
                }
            }
        }
        for (Component child : component.children()) {
            if (hasTranslationKey(child, predicate)) {
                return true;
            }
        }
        return false;
    }
}
