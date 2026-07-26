package com.github.ysbbbbbb.kaleidoscopetavern.paper.item;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.EffectSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SignatureEffectStorageSemanticsTest {
    @Test
    void roundTripsOrderIntegerBoundsAndRawProbabilityBits() {
        List<EffectSpec> source = List.of(
                new EffectSpec("minecraft:speed", 0, Integer.MAX_VALUE, -0.0),
                new EffectSpec("kaleidoscope_tavern:vision", Integer.MAX_VALUE, 0, 1.0),
                new EffectSpec("minecraft:speed", 20, 2, 0.125));

        SignatureEffectStorageSemantics.Encoded encoded =
                SignatureEffectStorageSemantics.encode(source);
        List<EffectSpec> decoded = SignatureEffectStorageSemantics.decode(
                encoded.ids(), encoded.values(), id -> id.contains(":"));

        assertEquals(source, decoded);
        assertEquals(Double.doubleToRawLongBits(-0.0),
                Double.doubleToRawLongBits(decoded.getFirst().probability()));
    }

    @Test
    void rejectsMismatchedColumnsAndSkipsOnlyInvalidRows() {
        assertTrue(SignatureEffectStorageSemantics.decode(
                List.of("minecraft:speed"), new long[1], id -> true).isEmpty());

        SignatureEffectStorageSemantics.Encoded encoded = SignatureEffectStorageSemantics.encode(List.of(
                new EffectSpec("invalid", 20, 0, 1.0),
                new EffectSpec("minecraft:slowness", -1, 0, 1.0),
                new EffectSpec("minecraft:strength", 20, 0, Double.NaN),
                new EffectSpec("minecraft:speed", 40, 1, 0.5)));
        assertEquals(List.of(new EffectSpec("minecraft:speed", 40, 1, 0.5)),
                SignatureEffectStorageSemantics.decode(
                        encoded.ids(), encoded.values(), id -> id.contains(":")));
    }
}
