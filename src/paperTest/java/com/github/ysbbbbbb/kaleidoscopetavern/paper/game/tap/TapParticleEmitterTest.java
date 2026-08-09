package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.tap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TapParticleEmitterTest {
    @Test
    void spacesColoredDropsAcrossTheExtractionWindow() {
        List<Integer> emittedTicks = IntStream.rangeClosed(0, 30)
                .filter(TapParticleEmitter::coloredDropDue)
                .boxed()
                .toList();

        assertEquals(List.of(1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23),
                emittedTicks);
    }
}
