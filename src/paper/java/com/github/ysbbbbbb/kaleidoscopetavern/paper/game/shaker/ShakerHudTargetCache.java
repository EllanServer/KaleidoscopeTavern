package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.shaker;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Caches exact player views so an unchanged HUD target does not ray trace every poll. */
final class ShakerHudTargetCache {
    static final long MAX_REUSE_TICKS = 20L;

    private final Map<UUID, CachedTarget> entries = new HashMap<>();
    private long pollSequence;

    void beginPoll() {
        if (pollSequence == Long.MAX_VALUE) {
            entries.clear();
            pollSequence = 1;
        } else {
            pollSequence++;
        }
    }

    @Nullable
    CachedTarget reusable(UUID playerId, UUID worldId, long gameTick,
                          double x, double y, double z, float yaw, float pitch) {
        CachedTarget cached = entries.get(playerId);
        if (cached == null) {
            return null;
        }
        cached.seenPoll = pollSequence;
        return cached.matches(worldId, gameTick, x, y, z, yaw, pitch)
                ? cached : null;
    }

    void record(UUID playerId, UUID worldId, long gameTick,
                double x, double y, double z, float yaw, float pitch,
                @Nullable UUID targetId) {
        CachedTarget cached = entries.get(playerId);
        if (cached == null) {
            cached = new CachedTarget();
            entries.put(playerId, cached);
        }
        cached.worldId = Objects.requireNonNull(worldId, "worldId");
        cached.scanTick = gameTick;
        cached.x = x;
        cached.y = y;
        cached.z = z;
        cached.yaw = yaw;
        cached.pitch = pitch;
        cached.targetId = targetId;
        cached.seenPoll = pollSequence;
    }

    void remove(UUID playerId) {
        entries.remove(playerId);
    }

    void endPoll() {
        Iterator<CachedTarget> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().seenPoll != pollSequence) {
                iterator.remove();
            }
        }
    }

    void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }

    static final class CachedTarget {
        private UUID worldId;
        private long scanTick;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;
        private UUID targetId;
        private long seenPoll;

        @Nullable
        UUID targetId() {
            return targetId;
        }

        private boolean matches(UUID currentWorldId, long gameTick,
                                double currentX, double currentY, double currentZ,
                                float currentYaw, float currentPitch) {
            long age = gameTick - scanTick;
            return worldId.equals(currentWorldId)
                    && age >= 0 && age < MAX_REUSE_TICKS
                    && Double.doubleToLongBits(x) == Double.doubleToLongBits(currentX)
                    && Double.doubleToLongBits(y) == Double.doubleToLongBits(currentY)
                    && Double.doubleToLongBits(z) == Double.doubleToLongBits(currentZ)
                    && Float.floatToIntBits(yaw) == Float.floatToIntBits(currentYaw)
                    && Float.floatToIntBits(pitch) == Float.floatToIntBits(currentPitch);
        }
    }
}
