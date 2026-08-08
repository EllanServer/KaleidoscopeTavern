package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;

/**
 * Immutable snapshot of a pressing-tub block entity.
 *
 * <p>The ingredient count lives on the CE {@link Item} itself
 * ({@code ingredient.count()}), so there is no separate count field that
 * could drift out of sync with the item stack.</p>
 *
 * @param ingredient the stacked ingredient pile, {@code null} when empty
 * @param fluid      the pressed fluid id, {@code null} when the tank is empty
 * @param fluidAmount pressed fluid amount, clamped to {@code 0..MAX_FLUID_AMOUNT}
 */
public record PressingTubState(Item ingredient, Key fluid, int fluidAmount) {

    public static final int MAX_FLUID_AMOUNT = 1_000;

    public PressingTubState {
        ingredient = normalizeIngredient(ingredient);
        fluidAmount = Math.max(0, Math.min(MAX_FLUID_AMOUNT, fluidAmount));
        if (fluid == null) {
            fluidAmount = 0;
        }
        if (fluidAmount == 0) {
            fluid = null;
        }
    }

    private static Item normalizeIngredient(Item ingredient) {
        if (ingredient == null || ingredient.isEmpty()) {
            return null;
        }
        int count = ingredient.count();
        if (count <= 0) {
            return null;
        }
        int capacity = Math.min(64, ingredient.maxStackSize());
        return count > capacity ? ingredient.copyWithCount(capacity) : ingredient;
    }

    /** 比较器信号：空桶 0，满桶 15，其余按液量线性插值，最终范围严格 0..15。 */
    public static int comparatorSignal(int fluidAmount) {
        int amount = Math.max(0, Math.min(MAX_FLUID_AMOUNT, fluidAmount));
        return amount == 0 ? 0 : 1 + amount * 14 / MAX_FLUID_AMOUNT;
    }
}
