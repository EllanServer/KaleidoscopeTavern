package com.github.ysbbbbbb.kaleidoscopetavern.paper.item;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.EffectSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Boundary between Bukkit inventory objects and CraftEngine item definitions. */
public final class ItemService {
    private final NamespacedKey brewLevelKey;
    private final NamespacedKey signatureEffectsKey;
    private final NamespacedKey signatureColorKey;
    private final NamespacedKey shakerIngredientsKey;
    private final NamespacedKey shakerResultKey;

    public ItemService(JavaPlugin plugin) {
        this.brewLevelKey = new NamespacedKey(plugin, "brew_level");
        this.signatureEffectsKey = new NamespacedKey(plugin, "signature_effects");
        this.signatureColorKey = new NamespacedKey(plugin, "signature_color");
        this.shakerIngredientsKey = new NamespacedKey(plugin, "shaker_ingredients");
        this.shakerResultKey = new NamespacedKey(plugin, "shaker_result");
    }

    public String id(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "minecraft:air";
        }
        Key custom = CraftEngineItems.getCustomItemId(stack);
        return custom == null ? stack.getType().getKey().asString() : custom.toString();
    }

    public Optional<ItemStack> build(String id, Player context) {
        BukkitItemDefinition custom = CraftEngineItems.byId(id);
        if (custom != null) {
            return Optional.of(context == null ? custom.buildBukkitItem() : custom.buildBukkitItem(context));
        }
        String materialName = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        Material material = Material.matchMaterial(materialName.toUpperCase(Locale.ROOT));
        return material == null || material.isAir() ? Optional.empty() : Optional.of(new ItemStack(material));
    }

    public boolean consumeOne(Player player, ItemStack expected) {
        if (expected == null || expected.isEmpty()) {
            return false;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!hand.isSimilar(expected)) {
            return false;
        }
        hand.subtract(1);
        return true;
    }

    public void give(Player player, ItemStack stack) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
        overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    public ItemStack withBrewLevel(ItemStack stack, int level) {
        ItemMeta meta = stack.getItemMeta();
        int clamped = Math.max(0, Math.min(6, level));
        meta.getPersistentDataContainer().set(brewLevelKey, PersistentDataType.INTEGER, clamped);
        if (clamped > 0) {
            Component quality = Component.translatable(
                    "message.kaleidoscope_tavern.barrel.brew_level." + clamped);
            Component line = Component.translatable(
                            "tooltip.kaleidoscope_tavern.bottle_block.brew_level", quality)
                    .color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false);
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(line);
            meta.lore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    public int brewLevel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        return stack.getPersistentDataContainer().getOrDefault(brewLevelKey, PersistentDataType.INTEGER, 0);
    }

    public ItemStack withSignature(ItemStack stack, List<EffectSpec> effects, int rgb) {
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(signatureEffectsKey, PersistentDataType.STRING, encodeEffects(effects));
        data.set(signatureColorKey, PersistentDataType.INTEGER, rgb & 0xFFFFFF);
        if (meta instanceof PotionMeta potionMeta) {
            potionMeta.setColor(Color.fromRGB(rgb & 0xFFFFFF));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    public List<EffectSpec> signatureEffects(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        String encoded = stack.getPersistentDataContainer().get(signatureEffectsKey, PersistentDataType.STRING);
        return encoded == null ? List.of() : decodeEffects(encoded);
    }

    public int signatureColor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0xA349A4;
        }
        return stack.getPersistentDataContainer()
                .getOrDefault(signatureColorKey, PersistentDataType.INTEGER, 0xA349A4);
    }

    public List<ItemStack> shakerIngredients(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        String encoded = stack.getPersistentDataContainer()
                .get(shakerIngredientsKey, PersistentDataType.STRING);
        return encoded == null ? List.of() : decodeItems(encoded);
    }

    public ItemStack shakerResult(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String encoded = stack.getPersistentDataContainer().get(shakerResultKey, PersistentDataType.STRING);
        List<ItemStack> decoded = encoded == null ? List.of() : decodeItems(encoded);
        return decoded.isEmpty() ? null : decoded.getFirst();
    }

    public ItemStack withShakerState(ItemStack stack, List<ItemStack> ingredients, ItemStack result) {
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        putEncodedItems(data, shakerIngredientsKey, ingredients);
        putEncodedItems(data, shakerResultKey, result == null ? List.of() : List.of(result));
        stack.setItemMeta(meta);
        return stack;
    }

    public List<EffectSpec> mergeEffects(List<List<EffectSpec>> ingredientEffects) {
        Map<String, AccumulatedEffect> merged = new LinkedHashMap<>();
        for (List<EffectSpec> source : ingredientEffects) {
            for (EffectSpec effect : source) {
                merged.computeIfAbsent(effect.effect(), ignored -> new AccumulatedEffect())
                        .add(effect);
            }
        }
        List<EffectSpec> result = new ArrayList<>();
        merged.forEach((effect, accumulated) -> result.add(new EffectSpec(
                effect,
                Math.max(1, (int) Math.round(accumulated.duration * 1.2)),
                accumulated.amplifier,
                accumulated.probability)));
        return List.copyOf(result);
    }

    public Optional<String> returnedContainer(String consumedId, boolean cocktail) {
        if (consumedId.equals("minecraft:potion") || consumedId.equals("minecraft:honey_bottle")) {
            return Optional.of("minecraft:glass_bottle");
        }
        if (consumedId.startsWith("kaleidoscope_tavern:")) {
            return Optional.of(cocktail
                    ? "kaleidoscope_tavern:empty_glassware"
                    : "kaleidoscope_tavern:empty_bottle");
        }
        return Optional.empty();
    }

    private static String encodeEffects(List<EffectSpec> effects) {
        return effects.stream()
                .map(effect -> effect.effect() + ',' + effect.durationTicks() + ',' + effect.amplifier()
                        + ',' + effect.probability())
                .reduce((left, right) -> left + ';' + right)
                .orElse("");
    }

    private static List<EffectSpec> decodeEffects(String encoded) {
        if (encoded.isBlank()) {
            return List.of();
        }
        List<EffectSpec> effects = new ArrayList<>();
        for (String entry : encoded.split(";")) {
            String[] fields = entry.split(",", -1);
            if (fields.length != 4) {
                continue;
            }
            try {
                effects.add(new EffectSpec(fields[0], Integer.parseInt(fields[1]),
                        Integer.parseInt(fields[2]), Double.parseDouble(fields[3])));
            } catch (NumberFormatException ignored) {
                // Ignore a single corrupt legacy entry rather than invalidating the entire drink.
            }
        }
        return List.copyOf(effects);
    }

    private static void putEncodedItems(PersistentDataContainer data, NamespacedKey key,
                                        List<ItemStack> stacks) {
        if (stacks.isEmpty()) {
            data.remove(key);
            return;
        }
        String encoded = stacks.stream()
                .filter(stack -> stack != null && !stack.isEmpty())
                .map(ItemStack::serializeAsBytes)
                .map(Base64.getUrlEncoder().withoutPadding()::encodeToString)
                .reduce((left, right) -> left + ';' + right)
                .orElse("");
        if (encoded.isEmpty()) {
            data.remove(key);
        } else {
            data.set(key, PersistentDataType.STRING, encoded);
        }
    }

    private static List<ItemStack> decodeItems(String encoded) {
        if (encoded.isBlank()) {
            return List.of();
        }
        List<ItemStack> result = new ArrayList<>();
        for (String entry : encoded.split(";")) {
            try {
                ItemStack stack = ItemStack.deserializeBytes(Base64.getUrlDecoder().decode(entry));
                if (!stack.isEmpty()) {
                    result.add(stack);
                }
            } catch (IllegalArgumentException ignored) {
                // Preserve the remaining shaker slots if one serialized item was corrupt.
            }
        }
        return List.copyOf(result);
    }

    private static final class AccumulatedEffect {
        private int duration;
        private int amplifier;
        private double probability;

        private void add(EffectSpec effect) {
            duration += effect.durationTicks();
            amplifier = Math.max(amplifier, effect.amplifier());
            probability = Math.max(probability, effect.probability());
        }
    }
}
