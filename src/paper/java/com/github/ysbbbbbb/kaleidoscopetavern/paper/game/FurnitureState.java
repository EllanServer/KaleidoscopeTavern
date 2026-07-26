package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.ListPersistentDataType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Namespaced persistent state stored directly on CraftEngine's furniture meta entity. */
final class FurnitureState {
    private static final Map<String, NamespacedKey> KEY_CACHE = new ConcurrentHashMap<>();
    private static final ListPersistentDataType<String, String> STRING_LIST = PersistentDataType.LIST.strings();
    private static final ListPersistentDataType<byte[], byte[]> BYTE_ARRAY_LIST =
            PersistentDataType.LIST.byteArrays();

    private final JavaPlugin plugin;
    private final PersistentDataContainer data;

    FurnitureState(JavaPlugin plugin, BukkitFurniture furniture) {
        this.plugin = plugin;
        Entity entity = furniture.bukkitEntity();
        if (entity == null) {
            throw new IllegalStateException("Furniture meta entity is unavailable");
        }
        this.data = entity.getPersistentDataContainer();
    }

    String string(String name) {
        return data.get(key(name), PersistentDataType.STRING);
    }

    String string(String name, String fallback) {
        return data.getOrDefault(key(name), PersistentDataType.STRING, fallback);
    }

    void putString(String name, String value) {
        if (value == null || value.isEmpty()) {
            data.remove(key(name));
        } else {
            data.set(key(name), PersistentDataType.STRING, value);
        }
    }

    int integer(String name) {
        return data.getOrDefault(key(name), PersistentDataType.INTEGER, 0);
    }

    void integer(String name, int value) {
        if (value == 0) {
            data.remove(key(name));
        } else {
            data.set(key(name), PersistentDataType.INTEGER, value);
        }
    }

    boolean bool(String name) {
        return data.getOrDefault(key(name), PersistentDataType.BOOLEAN, false);
    }

    void bool(String name, boolean value) {
        if (value) {
            data.set(key(name), PersistentDataType.BOOLEAN, true);
        } else {
            data.remove(key(name));
        }
    }

    List<String> strings(String name) {
        NamespacedKey stateKey = key(name);
        if (!data.has(stateKey, STRING_LIST)) {
            return new ArrayList<>();
        }
        List<String> stored = data.get(stateKey, STRING_LIST);
        return stored == null ? new ArrayList<>() : new ArrayList<>(stored);
    }

    void strings(String name, List<String> values) {
        if (values.isEmpty()) {
            data.remove(key(name));
        } else {
            data.set(key(name), STRING_LIST, values);
        }
    }

    List<ItemStack> items(String name) {
        List<ItemStack> result = new ArrayList<>();
        NamespacedKey stateKey = key(name);
        if (!data.has(stateKey, BYTE_ARRAY_LIST)) {
            return result;
        }
        List<byte[]> encodedItems = data.get(stateKey, BYTE_ARRAY_LIST);
        if (encodedItems == null) {
            return result;
        }
        for (byte[] encoded : encodedItems) {
            try {
                ItemStack item = ItemStack.deserializeBytes(encoded);
                if (!item.isEmpty()) {
                    result.add(item);
                }
            } catch (RuntimeException ignored) {
                // A malformed item should not prevent the furniture itself from loading.
            }
        }
        return result;
    }

    void items(String name, List<ItemStack> items) {
        if (items.isEmpty()) {
            data.remove(key(name));
            return;
        }
        List<byte[]> encoded = items.stream()
                .filter(item -> item != null && !item.isEmpty())
                .map(ItemStack::serializeAsBytes)
                .toList();
        if (encoded.isEmpty()) {
            data.remove(key(name));
        } else {
            data.set(key(name), BYTE_ARRAY_LIST, encoded);
        }
    }

    ItemStack item(String name) {
        NamespacedKey stateKey = key(name);
        if (!data.has(stateKey, PersistentDataType.BYTE_ARRAY)) {
            return null;
        }
        byte[] encoded = data.get(stateKey, PersistentDataType.BYTE_ARRAY);
        if (encoded == null) {
            return null;
        }
        try {
            ItemStack item = ItemStack.deserializeBytes(encoded);
            return item.isEmpty() ? null : item;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    void item(String name, ItemStack item) {
        if (item == null || item.isEmpty()) {
            data.remove(key(name));
        } else {
            data.set(key(name), PersistentDataType.BYTE_ARRAY, item.serializeAsBytes());
        }
    }

    void clear(String... names) {
        for (String name : names) {
            data.remove(key(name));
        }
    }

    private NamespacedKey key(String name) {
        NamespacedKey cached = KEY_CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        NamespacedKey created = new NamespacedKey(plugin, name);
        NamespacedKey existing = KEY_CACHE.putIfAbsent(name, created);
        return existing == null ? created : existing;
    }
}
