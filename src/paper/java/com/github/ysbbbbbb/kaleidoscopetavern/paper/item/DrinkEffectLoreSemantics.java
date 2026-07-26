package com.github.ysbbbbbb.kaleidoscopetavern.paper.item;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.EffectSpec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Pure formatting semantics shared by runtime lore and dependency-free tests. */
final class DrinkEffectLoreSemantics {
    private DrinkEffectLoreSemantics() {
    }

    static Display describe(EffectSpec spec) {
        return new Display(
                effectKey(spec.effect()),
                spec.amplifier() > 0 ? "potion.potency." + spec.amplifier() : null,
                spec.durationTicks() > 0 ? formatDuration(spec.durationTicks()) : null,
                spec.probability() < 1.0 ? formatProbability(spec.probability()) : null);
    }

    static String effectKey(String effectId) {
        int separator = effectId.indexOf(':');
        String namespace = separator < 0 ? "minecraft" : effectId.substring(0, separator);
        String path = separator < 0 ? effectId : effectId.substring(separator + 1);
        return "effect." + namespace + "." + path;
    }

    static String formatDuration(int ticks) {
        long totalSeconds = Math.max(1L, ticks / 20L);
        long hours = totalSeconds / 3_600L;
        long minutes = totalSeconds % 3_600L / 60L;
        long seconds = totalSeconds % 60L;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    static String formatProbability(double probability) {
        double percentage = probability * 100.0;
        long rounded = Math.round(percentage);
        return Math.abs(percentage - rounded) < 0.000_001
                ? rounded + "%"
                : String.format(Locale.ROOT, "%.1f%%", percentage);
    }

    static AttributeDisplay attribute(String attributeKey, double amount, ModifierOperation operation) {
        double displayed = operation.percentage() ? amount * 100.0 : amount;
        return new AttributeDisplay(
                amount >= 0.0 ? "attribute.modifier.plus." + operation.translationId()
                        : "attribute.modifier.take." + operation.translationId(),
                formatAttributeAmount(Math.abs(displayed)),
                attributeKey);
    }

    static String formatAttributeAmount(double amount) {
        return BigDecimal.valueOf(amount)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    record Display(String effectKey, String potencyKey, String duration, String chance) {
    }

    enum ModifierOperation {
        ADD_NUMBER(0, false),
        ADD_SCALAR(1, true),
        MULTIPLY_SCALAR_1(2, true);

        private final int translationId;
        private final boolean percentage;

        ModifierOperation(int translationId, boolean percentage) {
            this.translationId = translationId;
            this.percentage = percentage;
        }

        int translationId() {
            return translationId;
        }

        boolean percentage() {
            return percentage;
        }
    }

    record AttributeDisplay(String modifierKey, String amount, String attributeKey) {
    }
}
