package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Namespaced persistent state stored directly on CraftEngine's furniture meta entity. */
final class FurnitureState {
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

    long longValue(String name) {
        return data.getOrDefault(key(name), PersistentDataType.LONG, 0L);
    }

    void longValue(String name, long value) {
        if (value == 0L) {
            data.remove(key(name));
        } else {
            data.set(key(name), PersistentDataType.LONG, value);
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
        String encoded = string(name, "");
        return encoded.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(encoded.split(";", -1)));
    }

    void strings(String name, List<String> values) {
        putString(name, String.join(";", values));
    }

    Map<String, Integer> counts(String name) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String entry : strings(name)) {
            int separator = entry.lastIndexOf('=');
            if (separator <= 0) {
                continue;
            }
            try {
                int count = Integer.parseInt(entry.substring(separator + 1));
                if (count > 0) {
                    result.put(entry.substring(0, separator), count);
                }
            } catch (NumberFormatException ignored) {
                // Skip a corrupt entry while preserving the rest of the furniture state.
            }
        }
        return result;
    }

    void counts(String name, Map<String, Integer> counts) {
        List<String> encoded = counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> entry.getKey() + '=' + entry.getValue())
                .toList();
        strings(name, encoded);
    }

    List<ItemStack> items(String name) {
        List<ItemStack> result = new ArrayList<>();
        for (String encoded : strings(name)) {
            try {
                result.add(ItemStack.deserializeBytes(Base64.getUrlDecoder().decode(encoded)));
            } catch (IllegalArgumentException ignored) {
                // A malformed item should not prevent the furniture itself from loading.
            }
        }
        return result;
    }

    void items(String name, List<ItemStack> items) {
        List<String> encoded = items.stream()
                .map(ItemStack::serializeAsBytes)
                .map(Base64.getUrlEncoder().withoutPadding()::encodeToString)
                .toList();
        strings(name, encoded);
    }

    ItemStack item(String name) {
        List<ItemStack> items = items(name);
        return items.isEmpty() ? null : items.getFirst();
    }

    void item(String name, ItemStack item) {
        items(name, item == null || item.isEmpty() ? List.of() : List.of(item));
    }

    void clear(String... names) {
        for (String name : names) {
            data.remove(key(name));
        }
    }

    private NamespacedKey key(String name) {
        return new NamespacedKey(plugin, name);
    }
}
