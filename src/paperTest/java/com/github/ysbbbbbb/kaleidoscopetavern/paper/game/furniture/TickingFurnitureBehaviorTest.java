package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TickingFurnitureBehaviorTest {
    @Test
    void countdownStartsOnTheSameGloballyPhasedTickAsTheReferenceModulo() {
        int[] intervals = {1, 2, 3, 17, 97};
        int[] hashes = {Integer.MIN_VALUE, -194, -98, -97, -1, 0, 1, 96, 97,
                98, 194, Integer.MAX_VALUE};
        long[] starts = {0, 1, 2, 96, 97, 98, 1_000_000, Long.MAX_VALUE - 200};

        for (int interval : intervals) {
            for (int hash : hashes) {
                for (long start : starts) {
                    int delay = TickingFurnitureBehavior.initialDelay(start, hash, interval);
                    assertEquals(0, Math.floorMod(
                            start + delay + Math.floorMod(hash, interval), interval));
                    for (int earlier = 0; earlier < delay; earlier++) {
                        long gameTime = start + earlier;
                        int remainder = (int) Math.floorMod(
                                gameTime + Math.floorMod(hash, interval), interval);
                        // No earlier tick in this countdown window may satisfy the source phase.
                        assertFalse(remainder == 0);
                    }
                }
            }
        }
    }
}
