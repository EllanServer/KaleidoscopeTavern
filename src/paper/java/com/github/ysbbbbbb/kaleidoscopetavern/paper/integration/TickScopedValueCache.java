package com.github.ysbbbbbb.kaleidoscopetavern.paper.integration;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Cache for values that cannot change within one world tick. */
final class TickScopedValueCache<K, V> {
    private final Map<K, V> entries = new HashMap<>();
    private long activeTick = Long.MIN_VALUE;

    V get(K key, long tick, Supplier<? extends V> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        if (activeTick != tick) {
            entries.clear();
            activeTick = tick;
        }
        if (entries.containsKey(key)) {
            return entries.get(key);
        }
        V loaded = loader.get();
        entries.put(key, loaded);
        return loaded;
    }

    void clear() {
        entries.clear();
        activeTick = Long.MIN_VALUE;
    }

    int size() {
        return entries.size();
    }
}
