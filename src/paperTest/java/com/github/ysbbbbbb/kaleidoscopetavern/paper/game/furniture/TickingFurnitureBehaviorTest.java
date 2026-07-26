package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void firstFutureDelayNeverRunsOnTheLoadTickAndKeepsThePhase() {
        int[] intervals = {1, 2, 17, 97, 120};
        int[] hashes = {Integer.MIN_VALUE, -121, -1, 0, 1, 119, Integer.MAX_VALUE};
        long[] starts = {0, 1, 96, 119, 120, 1_000_000};

        for (int interval : intervals) {
            for (int hash : hashes) {
                for (long start : starts) {
                    int delay = TickingFurnitureBehavior.firstFutureDelay(
                            start, hash, interval);
                    assertTrue(delay >= 1 && delay <= interval);
                    assertEquals(0, Math.floorMod(
                            start + delay + Math.floorMod(hash, interval), interval));
                    for (int earlier = 1; earlier < delay; earlier++) {
                        assertFalse(Math.floorMod(
                                start + earlier + Math.floorMod(hash, interval), interval) == 0);
                    }
                }
            }
        }
    }

    @Test
    void geometricDelayUsesIndependentBernoulliBoundaries() {
        assertEquals(1, TickingFurnitureBehavior.geometricDelay(1, 0.999999));
        assertEquals(1, TickingFurnitureBehavior.geometricDelay(2, 0.0));
        assertEquals(1, TickingFurnitureBehavior.geometricDelay(2, Math.nextDown(0.5)));
        assertEquals(2, TickingFurnitureBehavior.geometricDelay(2, 0.5));
        assertEquals(2, TickingFurnitureBehavior.geometricDelay(2, Math.nextDown(0.75)));
        assertEquals(3, TickingFurnitureBehavior.geometricDelay(2, 0.75));

        assertThrows(IllegalArgumentException.class,
                () -> TickingFurnitureBehavior.geometricDelay(0, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> TickingFurnitureBehavior.geometricDelay(49, 1.0));
    }
}
