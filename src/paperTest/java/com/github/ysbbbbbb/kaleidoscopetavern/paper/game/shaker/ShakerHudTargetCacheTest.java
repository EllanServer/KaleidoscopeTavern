package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.shaker;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShakerHudTargetCacheTest {
    private static final UUID PLAYER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID WORLD = UUID.fromString(
            "20000000-0000-0000-0000-000000000002");
    private static final UUID TARGET = UUID.fromString(
            "30000000-0000-0000-0000-000000000003");

    @Test
    void reusesAnExactViewForAtMostOneSecond() {
        ShakerHudTargetCache cache = new ShakerHudTargetCache();
        cache.beginPoll();
        cache.record(PLAYER, WORLD, 100, 1, 65, 2, 90, 10, TARGET);
        cache.endPoll();

        cache.beginPoll();
        ShakerHudTargetCache.CachedTarget reused = cache.reusable(
                PLAYER, WORLD, 119, 1, 65, 2, 90, 10);
        assertNotNull(reused);
        assertEquals(TARGET, reused.targetId());
        assertNull(cache.reusable(
                PLAYER, WORLD, 120, 1, 65, 2, 90, 10));
        cache.endPoll();
    }

    @Test
    void invalidatesImmediatelyWhenViewChanges() {
        ShakerHudTargetCache cache = new ShakerHudTargetCache();
        cache.beginPoll();
        cache.record(PLAYER, WORLD, 100, 1, 65, 2, 90, 10, TARGET);

        assertNull(cache.reusable(
                PLAYER, WORLD, 101, 1, 65, 2, 90.01F, 10));
        assertNull(cache.reusable(
                PLAYER, WORLD, 101, 1.001, 65, 2, 90, 10));
        cache.endPoll();
    }

    @Test
    void cachesMissesAndPrunesPlayersNotSeenInTheNextPoll() {
        ShakerHudTargetCache cache = new ShakerHudTargetCache();
        cache.beginPoll();
        cache.record(PLAYER, WORLD, 100, 1, 65, 2, 90, 10, null);
        ShakerHudTargetCache.CachedTarget miss = cache.reusable(
                PLAYER, WORLD, 101, 1, 65, 2, 90, 10);
        assertNotNull(miss);
        assertNull(miss.targetId());
        cache.endPoll();

        cache.beginPoll();
        cache.endPoll();
        assertEquals(0, cache.size());
    }
}
