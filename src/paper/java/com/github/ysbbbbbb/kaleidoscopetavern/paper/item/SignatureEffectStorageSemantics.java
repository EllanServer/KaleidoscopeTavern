package com.github.ysbbbbbb.kaleidoscopetavern.paper.item;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.EffectSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Pure packing rules for the compact PDC representation of signature effects. */
final class SignatureEffectStorageSemantics {
    static final int MAX_EFFECTS = 256;

    private SignatureEffectStorageSemantics() {
    }

    static Encoded encode(List<EffectSpec> effects) {
        if (effects.size() > MAX_EFFECTS) {
            throw new IllegalArgumentException("A signature cocktail cannot contain more than "
                    + MAX_EFFECTS + " effects");
        }
        List<String> ids = new ArrayList<>(effects.size());
        long[] values = new long[effects.size() * 2];
        for (int index = 0; index < effects.size(); index++) {
            EffectSpec effect = effects.get(index);
            ids.add(effect.effect());
            values[index * 2] = packInts(effect.durationTicks(), effect.amplifier());
            values[index * 2 + 1] = Double.doubleToRawLongBits(effect.probability());
        }
        return new Encoded(ids, values);
    }

    static List<EffectSpec> decode(List<String> ids, long[] values, Predicate<String> validId) {
        if (ids == null || values == null || ids.size() > MAX_EFFECTS
                || values.length != ids.size() * 2) {
            return List.of();
        }
        List<EffectSpec> effects = new ArrayList<>(ids.size());
        for (int index = 0; index < ids.size(); index++) {
            String effectId = ids.get(index);
            long durationAndAmplifier = values[index * 2];
            int duration = unpackHigh(durationAndAmplifier);
            int amplifier = unpackLow(durationAndAmplifier);
            double probability = Double.longBitsToDouble(values[index * 2 + 1]);
            if (effectId == null || effectId.isEmpty() || effectId.length() > 256
                    || !validId.test(effectId) || duration < 0 || amplifier < 0
                    || !Double.isFinite(probability) || probability < 0.0 || probability > 1.0) {
                continue;
            }
            effects.add(new EffectSpec(effectId, duration, amplifier, probability));
        }
        return List.copyOf(effects);
    }

    private static long packInts(int high, int low) {
        return (long) high << 32 | low & 0xFFFF_FFFFL;
    }

    private static int unpackHigh(long packed) {
        return (int) (packed >>> 32);
    }

    private static int unpackLow(long packed) {
        return (int) packed;
    }

    record Encoded(List<String> ids, long[] values) {
    }
}
