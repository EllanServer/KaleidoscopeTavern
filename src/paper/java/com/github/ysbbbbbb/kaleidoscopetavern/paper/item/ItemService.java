package com.github.ysbbbbbb.kaleidoscopetavern.paper.item;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.EffectSpec;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.ShakerSemantics;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.ListPersistentDataType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Objects;
import java.util.Optional;

/** Boundary between Bukkit inventory objects and CraftEngine item definitions. */
public final class ItemService {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final ListPersistentDataType<byte[], byte[]> BYTE_ARRAY_LIST =
            PersistentDataType.LIST.byteArrays();
    private static final ListPersistentDataType<String, String> STRING_LIST =
            PersistentDataType.LIST.strings();
    private static final java.util.Set<String> NEUTRAL_CUSTOM_EFFECTS = java.util.Set.of(
            PREFIX + "slightly_tipsy", PREFIX + "upside_down");
    private static final Map<Integer, String> COLOR_NAMES_BY_RGB = Map.ofEntries(
            Map.entry(0x000000, "black"), Map.entry(0x0000AA, "dark_blue"),
            Map.entry(0x00AA00, "dark_green"), Map.entry(0x00AAAA, "dark_aqua"),
            Map.entry(0xAA0000, "dark_red"), Map.entry(0xAA00AA, "dark_purple"),
            Map.entry(0xFFAA00, "gold"), Map.entry(0xAAAAAA, "gray"),
            Map.entry(0x555555, "dark_gray"), Map.entry(0x5555FF, "blue"),
            Map.entry(0x55FF55, "green"), Map.entry(0x55FFFF, "aqua"),
            Map.entry(0xFF5555, "red"), Map.entry(0xFF55FF, "light_purple"),
            Map.entry(0xFFFF55, "yellow"), Map.entry(0xFFFFFF, "white"));

    private final ContentCatalog catalog;
    private final NamespacedKey brewLevelKey;
    private final NamespacedKey signatureEffectsKey;
    private final NamespacedKey signatureColorKey;
    private final NamespacedKey signatureEffectIdsKey;
    private final NamespacedKey signatureEffectValuesKey;
    private final NamespacedKey shakerIngredientsKey;
    private final NamespacedKey shakerResultKey;

    public ItemService(JavaPlugin plugin, ContentCatalog catalog) {
        this.catalog = catalog;
        this.brewLevelKey = new NamespacedKey(plugin, "brew_level");
        this.signatureEffectsKey = new NamespacedKey(plugin, "signature_effects");
        this.signatureColorKey = new NamespacedKey(plugin, "signature_color");
        this.signatureEffectIdsKey = new NamespacedKey(plugin, "signature_effect_ids");
        this.signatureEffectValuesKey = new NamespacedKey(plugin, "signature_effect_values");
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
            ItemStack stack = context == null ? custom.buildBukkitItem() : custom.buildBukkitItem(context);
            return Optional.of(refreshLore(stack));
        }
        String materialName = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        Material material = Material.matchMaterial(materialName.toUpperCase(Locale.ROOT));
        return material == null || material.isAir() ? Optional.empty() : Optional.of(new ItemStack(material));
    }

    /**
     * Loads Minecraft's item Codec path before a player's first furniture placement.
     * CraftEngine persists the exact source stack for lossless furniture drops;
     * the first encode otherwise performs lazy class/JAR-manifest loading on the
     * interaction tick. Serializing each cocktail once also seeds component
     * encoder caches without retaining duplicate item stacks in this plugin.
     */
    public int warmCocktailFurnitureSerialization() {
        int warmed = 0;
        for (String itemId : catalog.cocktailItems()) {
            Optional<ItemStack> built = build(itemId, null);
            if (built.isEmpty()) {
                continue;
            }
            BukkitAdaptor.adapt(built.get()).copyWithCount(1).toBytes();
            warmed++;
        }
        return warmed;
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
        refreshLore(stack);
        // ItemUtils#giveItemToPlayer: an empty main hand receives the item
        // directly, the rest goes to the inventory with overflow dropped, and
        // the vanilla pickup blip always plays.
        if (player.getInventory().getItemInMainHand().isEmpty()) {
            player.getInventory().setItemInMainHand(stack);
        } else {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
            overflow.values().forEach(item ->
                    player.getWorld().dropItemNaturally(player.getLocation(), item));
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        player.getWorld().playSound(player.getLocation(), "minecraft:entity.item.pickup",
                SoundCategory.PLAYERS, 0.2F,
                ((random.nextFloat() - random.nextFloat()) * 0.7F + 1.0F) * 2.0F);
    }

    public ItemStack withBrewLevel(ItemStack stack, int level) {
        ItemMeta meta = stack.getItemMeta();
        int clamped = Math.max(0, Math.min(6, level));
        meta.getPersistentDataContainer().set(brewLevelKey, PersistentDataType.INTEGER, clamped);
        stack.setItemMeta(meta);
        return refreshDrinkLore(stack);
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
        putEffects(data, effects);
        data.set(signatureColorKey, PersistentDataType.INTEGER, rgb & 0xFFFFFF);
        if (meta instanceof PotionMeta potionMeta) {
            potionMeta.setColor(Color.fromRGB(rgb & 0xFFFFFF));
        }
        stack.setItemMeta(meta);
        return refreshDrinkLore(stack);
    }

    /** Rebuilds lore from the exact effects this stack will apply when consumed. */
    public ItemStack refreshDrinkLore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        return refreshDrinkLore(stack, id(stack));
    }

    private ItemStack refreshDrinkLore(ItemStack stack, String itemId) {
        boolean cocktail = catalog.isCocktail(itemId);
        boolean configuredDrink = catalog.hasDrinkEffects(itemId);
        boolean hasSignature = stack.getPersistentDataContainer()
                .has(signatureEffectsKey, PersistentDataType.TAG_CONTAINER);
        if (!cocktail && !configuredDrink && !hasSignature) {
            return stack;
        }
        List<EffectSpec> specs = hasSignature ? signatureEffects(stack) : List.of();
        if (specs.isEmpty() && !cocktail && !configuredDrink) {
            return stack;
        }

        int level = cocktail ? 1 : brewLevel(stack);
        if (specs.isEmpty() && level > 0) {
            specs = catalog.effects(itemId, level);
        }

        syncNativePotionEffects(stack, specs);
        ItemMeta meta = stack.getItemMeta();
        List<Component> managedLore = new ArrayList<>();
        if (!cocktail) {
            var color = catalog.cocktailColor(itemId);
            if (color.isPresent()) {
                int rgb = color.getAsInt();
                String colorName = COLOR_NAMES_BY_RGB.get(rgb);
                if (colorName != null) {
                    managedLore.add(DrinkLore.colorLine(colorName, rgb));
                }
            }
        }
        if (!cocktail && level > 0) {
            managedLore.add(DrinkLore.qualityLine(level));
        }
        if (!specs.isEmpty()) {
            if (!managedLore.isEmpty()) {
                managedLore.add(DrinkLore.managedBlank());
            }
            for (EffectSpec spec : specs) {
                managedLore.add(DrinkLore.effectLine(spec, effectColor(spec.effect())));
            }
            managedLore.addAll(attributeLore(specs));
        }

        List<Component> mergedLore = ManagedLoreSemantics.replace(
                meta.lore(),
                DrinkLore::isManagedDrinkLine,
                Component.empty()::equals,
                managedLore);
        List<Component> updatedLore = mergedLore.isEmpty() ? null : mergedLore;
        if (!Objects.equals(meta.lore(), updatedLore)) {
            meta.lore(updatedLore);
            stack.setItemMeta(meta);
        }
        hidePotionTooltip(stack);
        return stack;
    }

    /**
     * Lets Minecraft apply guaranteed vanilla effects from the potion-contents
     * component. Probabilistic and Tavern-specific effects remain in the
     * consume event bridge because potion contents cannot express them with
     * the migrated semantics.
     */
    private static void syncNativePotionEffects(ItemStack stack, List<EffectSpec> specs) {
        if (!(stack.getItemMeta() instanceof PotionMeta potionMeta)) {
            return;
        }
        List<PotionEffect> expected = new ArrayList<>();
        for (EffectSpec spec : specs) {
            if (!NativeDrinkEffectSemantics.shouldEmbed(spec.effect(), spec.probability())) {
                continue;
            }
            NamespacedKey key = NamespacedKey.fromString(spec.effect());
            PotionEffectType type = key == null ? null : Registry.EFFECT.get(key);
            if (type == null) {
                continue;
            }
            expected.add(new PotionEffect(
                    type,
                    NativeDrinkEffectSemantics.duration(type.isInstant(), spec.durationTicks()),
                    spec.amplifier(),
                    false,
                    true,
                    true));
        }
        if (potionMeta.getCustomEffects().equals(expected)) {
            return;
        }
        potionMeta.clearCustomEffects();
        for (PotionEffect effect : expected) {
            potionMeta.addCustomEffect(effect, true);
        }
        stack.setItemMeta(potionMeta);
    }

    private static void hidePotionTooltip(ItemStack stack) {
        TooltipDisplay current = stack.getData(DataComponentTypes.TOOLTIP_DISPLAY);
        if (current != null && current.hiddenComponents().contains(DataComponentTypes.POTION_CONTENTS)) {
            return;
        }
        TooltipDisplay.Builder builder = TooltipDisplay.tooltipDisplay();
        if (current != null) {
            builder.hideTooltip(current.hideTooltip()).hiddenComponents(current.hiddenComponents());
        }
        builder.addHiddenComponents(DataComponentTypes.POTION_CONTENTS);
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, builder);
    }

    private ItemStack refreshLore(ItemStack stack) {
        return refreshLore(stack, id(stack));
    }

    private ItemStack refreshLore(ItemStack stack, String itemId) {
        refreshDrinkLore(stack, itemId);
        if (PREFIX.concat("shaker").equals(itemId)) {
            List<ItemStack> ingredients = new ArrayList<>(shakerIngredients(stack));
            ingredients.replaceAll(this::refreshDrinkLore);
            ItemStack result = shakerResult(stack);
            if (result != null) {
                refreshDrinkLore(result);
            }
            // Keep nested dynamic drink lore and the visible shaker tooltip in
            // sync whenever portable shaker state is rebuilt.
            withShakerState(stack, ingredients, result);
        }
        return stack;
    }

    private static NamedTextColor effectColor(String effectId) {
        NamespacedKey key = NamespacedKey.fromString(effectId);
        PotionEffectType vanilla = key == null ? null : Registry.EFFECT.get(key);
        if (vanilla != null) {
            PotionEffectTypeCategory category = vanilla.getCategory();
            return switch (category) {
                case BENEFICIAL -> NamedTextColor.BLUE;
                case HARMFUL -> NamedTextColor.RED;
                case NEUTRAL -> NamedTextColor.GRAY;
            };
        }
        return NEUTRAL_CUSTOM_EFFECTS.contains(effectId) ? NamedTextColor.GRAY : NamedTextColor.BLUE;
    }

    private static List<Component> attributeLore(List<EffectSpec> specs) {
        List<DrinkEffectLoreSemantics.AttributeDisplay> attributes = new ArrayList<>();
        for (EffectSpec spec : specs) {
            if (spec.probability() < 1.0) {
                continue;
            }
            NamespacedKey key = NamespacedKey.fromString(spec.effect());
            PotionEffectType vanilla = key == null ? null : Registry.EFFECT.get(key);
            if (vanilla != null) {
                for (Map.Entry<Attribute, AttributeModifier> entry
                        : vanilla.getEffectAttributes().entrySet()) {
                    attributes.add(DrinkEffectLoreSemantics.attribute(
                            entry.getKey().translationKey(),
                            vanilla.getAttributeModifierAmount(entry.getKey(), spec.amplifier()),
                            modifierOperation(entry.getValue().getOperation())));
                }
                continue;
            }
            double level = spec.amplifier() + 1.0;
            if ((PREFIX + "high_heels").equals(spec.effect())) {
                attributes.add(DrinkEffectLoreSemantics.attribute(
                        Attribute.STEP_HEIGHT.translationKey(), 0.5 * level,
                        DrinkEffectLoreSemantics.ModifierOperation.ADD_NUMBER));
            } else if ((PREFIX + "long_reach").equals(spec.effect())) {
                attributes.add(DrinkEffectLoreSemantics.attribute(
                        Attribute.BLOCK_INTERACTION_RANGE.translationKey(), 3.0 * level,
                        DrinkEffectLoreSemantics.ModifierOperation.ADD_NUMBER));
                attributes.add(DrinkEffectLoreSemantics.attribute(
                        Attribute.ENTITY_INTERACTION_RANGE.translationKey(), 3.0 * level,
                        DrinkEffectLoreSemantics.ModifierOperation.ADD_NUMBER));
            }
        }
        return DrinkLore.attributeSection(attributes);
    }

    private static DrinkEffectLoreSemantics.ModifierOperation modifierOperation(
            AttributeModifier.Operation operation) {
        return switch (operation) {
            case ADD_NUMBER -> DrinkEffectLoreSemantics.ModifierOperation.ADD_NUMBER;
            case ADD_SCALAR -> DrinkEffectLoreSemantics.ModifierOperation.ADD_SCALAR;
            case MULTIPLY_SCALAR_1 -> DrinkEffectLoreSemantics.ModifierOperation.MULTIPLY_SCALAR_1;
        };
    }

    public List<EffectSpec> signatureEffects(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        var data = stack.getPersistentDataContainer();
        if (!data.has(signatureEffectsKey, PersistentDataType.TAG_CONTAINER)) {
            return List.of();
        }
        PersistentDataContainer encoded = data.get(signatureEffectsKey, PersistentDataType.TAG_CONTAINER);
        if (encoded == null
                || !encoded.has(signatureEffectIdsKey, STRING_LIST)
                || !encoded.has(signatureEffectValuesKey, PersistentDataType.LONG_ARRAY)) {
            return List.of();
        }
        List<String> ids = encoded.get(signatureEffectIdsKey, STRING_LIST);
        long[] values = encoded.get(signatureEffectValuesKey, PersistentDataType.LONG_ARRAY);
        return SignatureEffectStorageSemantics.decode(
                ids, values, effectId -> NamespacedKey.fromString(effectId) != null);
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
        var data = stack.getPersistentDataContainer();
        if (!data.has(shakerIngredientsKey, BYTE_ARRAY_LIST)) {
            return List.of();
        }
        List<byte[]> encoded = data.get(shakerIngredientsKey, BYTE_ARRAY_LIST);
        return encoded == null ? List.of() : decodeItems(encoded);
    }

    public ItemStack shakerResult(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        var data = stack.getPersistentDataContainer();
        if (!data.has(shakerResultKey, PersistentDataType.BYTE_ARRAY)) {
            return null;
        }
        byte[] encoded = data.get(shakerResultKey, PersistentDataType.BYTE_ARRAY);
        if (encoded == null) {
            return null;
        }
        try {
            ItemStack result = ItemStack.deserializeBytes(encoded);
            return result.isEmpty() ? null : result;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public ItemStack withShakerState(ItemStack stack, List<ItemStack> ingredients, ItemStack result) {
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        putEncodedItems(data, shakerIngredientsKey, ingredients);
        putEncodedItem(data, shakerResultKey, result);
        stack.setItemMeta(meta);
        return refreshShakerLore(stack, ingredients, result);
    }

    /** Mirrors ShakerItem's result/ingredient tooltip for portable state. */
    private ItemStack refreshShakerLore(ItemStack stack, List<ItemStack> ingredients, ItemStack result) {
        List<ItemStack> displayed = result == null ? ingredients : List.of(result);
        List<Component> managedLore = new ArrayList<>();
        for (ItemStack entry : displayed) {
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            TextColor color = NamedTextColor.GRAY;
            if (result == null) {
                var ingredientColor = catalog.cocktailColor(id(entry));
                if (ingredientColor.isPresent()) {
                    color = TextColor.color(ingredientColor.getAsInt());
                }
            }
            managedLore.add(DrinkLore.managed(Component.text("\u25B6 ", NamedTextColor.GRAY)
                    .append(entry.effectiveName().color(color))
                    .decoration(TextDecoration.ITALIC, false)));
        }
        ItemMeta meta = stack.getItemMeta();
        List<Component> mergedLore = ManagedLoreSemantics.replace(
                meta.lore(),
                DrinkLore::isManagedShakerLine,
                Component.empty()::equals,
                managedLore);
        List<Component> updatedLore = mergedLore.isEmpty() ? null : mergedLore;
        if (!Objects.equals(meta.lore(), updatedLore)) {
            meta.lore(updatedLore);
            stack.setItemMeta(meta);
        }
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
                ShakerSemantics.mergedEffectDurationTicks(accumulated.durationTicks),
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

    private void putEffects(PersistentDataContainer data, List<EffectSpec> effects) {
        SignatureEffectStorageSemantics.Encoded packed =
                SignatureEffectStorageSemantics.encode(effects);
        PersistentDataContainer encoded = data.getAdapterContext().newPersistentDataContainer();
        encoded.set(signatureEffectIdsKey, STRING_LIST, packed.ids());
        encoded.set(signatureEffectValuesKey, PersistentDataType.LONG_ARRAY, packed.values());
        data.set(signatureEffectsKey, PersistentDataType.TAG_CONTAINER, encoded);
    }

    private static void putEncodedItems(PersistentDataContainer data, NamespacedKey key,
                                        List<ItemStack> stacks) {
        if (stacks.isEmpty()) {
            data.remove(key);
            return;
        }
        List<byte[]> encoded = stacks.stream()
                .filter(stack -> stack != null && !stack.isEmpty())
                .map(ItemStack::serializeAsBytes)
                .toList();
        if (encoded.isEmpty()) {
            data.remove(key);
        } else {
            data.set(key, BYTE_ARRAY_LIST, encoded);
        }
    }

    private static void putEncodedItem(PersistentDataContainer data, NamespacedKey key, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            data.remove(key);
        } else {
            data.set(key, PersistentDataType.BYTE_ARRAY, stack.serializeAsBytes());
        }
    }

    private static List<ItemStack> decodeItems(List<byte[]> encoded) {
        if (encoded.isEmpty()) {
            return List.of();
        }
        List<ItemStack> result = new ArrayList<>();
        for (byte[] entry : encoded) {
            try {
                ItemStack stack = ItemStack.deserializeBytes(entry);
                if (!stack.isEmpty()) {
                    result.add(stack);
                }
            } catch (RuntimeException ignored) {
                // Preserve the remaining shaker slots if one serialized item was corrupt.
            }
        }
        return List.copyOf(result);
    }

    private static final class AccumulatedEffect {
        private int durationTicks;
        private int amplifier;
        private double probability;

        private void add(EffectSpec effect) {
            durationTicks += Math.max(0, effect.durationTicks());
            amplifier = Math.max(amplifier, effect.amplifier());
            probability = Math.max(probability, effect.probability());
        }
    }
}
