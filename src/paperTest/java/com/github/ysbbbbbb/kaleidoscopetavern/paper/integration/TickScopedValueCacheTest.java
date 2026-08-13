package com.github.ysbbbbbb.kaleidoscopetavern.paper.integration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TickScopedValueCacheTest {
    @Test
    void loadsOnlyOnceForTheSameKeyAndTick() {
        TickScopedValueCache<String, Integer> cache = new TickScopedValueCache<>();
        AtomicInteger loads = new AtomicInteger();

        assertEquals(1, cache.get("world", 20, loads::incrementAndGet));
        assertEquals(1, cache.get("world", 20, loads::incrementAndGet));
        assertEquals(1, loads.get());
    }

    @Test
    void reloadsOnTheNextTickAndSeparatesWorlds() {
        TickScopedValueCache<String, Integer> cache = new TickScopedValueCache<>();
        AtomicInteger loads = new AtomicInteger();

        assertEquals(1, cache.get("overworld", 20, loads::incrementAndGet));
        assertEquals(2, cache.get("nether", 20, loads::incrementAndGet));
        assertEquals(3, cache.get("overworld", 21, loads::incrementAndGet));
        assertEquals(1, cache.size());
    }

    @Test
    void cachesNullAndClearForcesReload() {
        TickScopedValueCache<String, Object> cache = new TickScopedValueCache<>();
        AtomicInteger loads = new AtomicInteger();

        assertNull(cache.get("world", 20, () -> {
            loads.incrementAndGet();
            return null;
        }));
        assertNull(cache.get("world", 20, () -> {
            loads.incrementAndGet();
            return new Object();
        }));
        assertEquals(1, loads.get());

        cache.clear();
        assertEquals("reloaded", cache.get("world", 20, () -> {
            loads.incrementAndGet();
            return "reloaded";
        }));
        assertEquals(2, loads.get());
    }
}
