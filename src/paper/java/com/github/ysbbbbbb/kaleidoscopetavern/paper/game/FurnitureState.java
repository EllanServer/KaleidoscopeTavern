package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.StateFurnitureBehavior;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.libraries.nbt.ByteArrayTag;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.IntArrayTag;
import net.momirealms.craftengine.libraries.nbt.ListTag;
import net.momirealms.craftengine.libraries.nbt.NBT;
import net.momirealms.craftengine.libraries.nbt.StringTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Typed access to state persisted by CraftEngine's furniture controller. */
final class FurnitureState {
    private final StateFurnitureBehavior.StateController controller;
    private final CompoundTag data;

    FurnitureState(BukkitFurniture furniture) {
        this.controller = StateFurnitureBehavior.state(furniture);
        this.data = controller.data();
    }

    String string(String name) {
        return data.containsKey(name) ? data.getString(name) : null;
    }

    String string(String name, String fallback) {
        return data.getString(name, fallback);
    }

    void putString(String name, String value) {
        if (value == null || value.isEmpty()) {
            remove(name);
        } else {
            put(name, new StringTag(value));
        }
    }

    int integer(String name) {
        return data.getInt(name, 0);
    }

    void integer(String name, int value) {
        if (value == 0) {
            remove(name);
        } else {
            int previous = data.getInt(name, 0);
            if (!data.containsKey(name) || previous != value) {
                data.putInt(name, value);
                controller.markChanged();
            }
        }
    }

    boolean bool(String name) {
        return data.getBoolean(name, false);
    }

    void bool(String name, boolean value) {
        if (value) {
            if (!data.getBoolean(name, false)) {
                data.putBoolean(name, true);
                controller.markChanged();
            }
        } else {
            remove(name);
        }
    }

    UUID uuid(String name) {
        return data.getUUID(name);
    }

    void uuid(String name, UUID value) {
        if (value == null) {
            remove(name);
        } else {
            put(name, NBT.createUUID(value));
        }
    }

    List<UUID> uuids(String name) {
        ListTag stored = data.getList(name);
        if (stored == null) {
            return new ArrayList<>();
        }
        List<UUID> result = new ArrayList<>(stored.size());
        for (int index = 0; index < stored.size(); index++) {
            if (stored.get(index) instanceof IntArrayTag encoded) {
                try {
                    result.add(encoded.getAsUUID());
                } catch (IllegalArgumentException ignored) {
                    // A malformed helper UUID is recovered by the owning service.
                }
            }
        }
        return result;
    }

    void uuids(String name, List<UUID> values) {
        if (values.isEmpty()) {
            remove(name);
            return;
        }
        ListTag stored = new ListTag();
        values.forEach(value -> stored.addTag(stored.size(), NBT.createUUID(value)));
        put(name, stored);
    }

    List<ItemStack> items(String name) {
        List<ItemStack> result = new ArrayList<>();
        ListTag encodedItems = data.getList(name);
        if (encodedItems == null) {
            return result;
        }
        for (int index = 0; index < encodedItems.size(); index++) {
            Tag encodedTag = encodedItems.get(index);
            if (!(encodedTag instanceof ByteArrayTag encodedItem)) {
                continue;
            }
            try {
                ItemStack item = ItemStack.deserializeBytes(encodedItem.getAsByteArray());
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
            remove(name);
            return;
        }
        ListTag encoded = new ListTag();
        items.stream()
                .filter(item -> item != null && !item.isEmpty())
                .map(ItemStack::serializeAsBytes)
                .map(ByteArrayTag::new)
                .forEach(tag -> encoded.addTag(encoded.size(), tag));
        if (encoded.isEmpty()) {
            remove(name);
        } else {
            put(name, encoded);
        }
    }

    ItemStack item(String name) {
        byte[] encoded = data.getByteArray(name);
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
            remove(name);
        } else {
            put(name, new ByteArrayTag(item.serializeAsBytes()));
        }
    }

    void clear(String... names) {
        boolean changed = false;
        for (String name : names) {
            if (data.containsKey(name)) {
                data.remove(name);
                changed = true;
            }
        }
        if (changed) {
            controller.markChanged();
        }
    }

    private void put(String name, Tag value) {
        if (!value.equals(data.get(name))) {
            data.put(name, value);
            controller.markChanged();
        }
    }

    private void remove(String name) {
        if (data.containsKey(name)) {
            data.remove(name);
            controller.markChanged();
        }
    }
}
