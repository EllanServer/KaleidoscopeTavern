package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.pressing;

import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link PressingTubState} 规范化的纯单元测试：损坏存档不能导致破坏时循环
 * 生成大量物品，因此加载/写入前的统一规范化必须把 ingredient 与液量收敛到
 * 合法区间。
 */
class PressingTubStateTest {

    private static final Key GRAPE_JUICE = Key.of("kaleidoscope_tavern:grape_juice");

    @Test
    void emptyStateNormalizesToNulls() {
        PressingTubState state = new PressingTubState(null, null, 0);
        assertNull(state.ingredient());
        assertNull(state.fluid());
        assertEquals(0, state.fluidAmount());
    }

    @Test
    void nullIngredientStaysNull() {
        PressingTubState state = new PressingTubState(null, GRAPE_JUICE, 500);
        assertNull(state.ingredient());
        assertEquals(GRAPE_JUICE, state.fluid());
        assertEquals(500, state.fluidAmount());
    }

    @Test
    void emptyItemBecomesNullIngredient() {
        PressingTubState state = new PressingTubState(item(1, 64, true), GRAPE_JUICE, 0);
        assertNull(state.ingredient());
    }

    @Test
    void countClampedToItemMaxStack() {
        PressingTubState state = new PressingTubState(item(100, 64, false), null, 0);
        assertEquals(64, state.ingredient().count());
    }

    @Test
    void countClampedToSixtyFourEvenWhenStackAllowsMore() {
        PressingTubState state = new PressingTubState(item(100, 127, false), null, 0);
        assertEquals(64, state.ingredient().count());
    }

    @Test
    void zeroCountBecomesNullIngredient() {
        PressingTubState state = new PressingTubState(item(0, 64, false), null, 0);
        assertNull(state.ingredient());
    }

    @Test
    void negativeCountBecomesNullIngredient() {
        PressingTubState state = new PressingTubState(item(-3, 64, false), null, 0);
        assertNull(state.ingredient());
    }

    @Test
    void fluidWithoutAmountIsCleared() {
        PressingTubState state = new PressingTubState(item(1, 64, false), GRAPE_JUICE, 0);
        assertNull(state.fluid());
        assertEquals(0, state.fluidAmount());
    }

    @Test
    void amountWithoutFluidIsCleared() {
        PressingTubState state = new PressingTubState(item(1, 64, false), null, 500);
        assertNull(state.fluid());
        assertEquals(0, state.fluidAmount());
    }

    @Test
    void amountClampedToCapacity() {
        PressingTubState state = new PressingTubState(item(1, 64, false), GRAPE_JUICE, 2_000);
        assertEquals(PressingTubState.MAX_FLUID_AMOUNT, state.fluidAmount());
    }

    @Test
    void amountClampedToZero() {
        PressingTubState state = new PressingTubState(item(1, 64, false), GRAPE_JUICE, -5);
        assertEquals(0, state.fluidAmount());
        assertNull(state.fluid());
    }

    @Test
    void comparatorSignalEmptyTankIsZero() {
        assertEquals(0, PressingTubState.comparatorSignal(0));
    }

    @Test
    void comparatorSignalTinyAmountStillOutputsAtLeastOne() {
        assertEquals(1, PressingTubState.comparatorSignal(1));
        assertEquals(1, PressingTubState.comparatorSignal(50));
    }

    @Test
    void comparatorSignalHalfTankIsEight() {
        assertEquals(8, PressingTubState.comparatorSignal(500));
    }

    @Test
    void comparatorSignalFullTankIsFifteen() {
        assertEquals(15, PressingTubState.comparatorSignal(1_000));
    }

    @Test
    void comparatorSignalClampsOutOfRangeInputs() {
        assertEquals(15, PressingTubState.comparatorSignal(2_000));
        assertEquals(0, PressingTubState.comparatorSignal(-10));
    }

    @Test
    void comparatorSignalIsMonotonic() {
        int previous = -1;
        for (int amount = 0; amount <= 1_000; amount += 50) {
            int signal = PressingTubState.comparatorSignal(amount);
            if (signal < previous) {
                throw new AssertionError(
                        "signal must never decrease, amount=" + amount);
            }
            previous = signal;
        }
    }

    /** Item mock：isEmpty / count / maxStackSize / copyWithCount 都按字段返回。 */
    private static Item item(int count, int maxStack, boolean empty) {
        return (Item) Proxy.newProxyInstance(
                PressingTubStateTest.class.getClassLoader(),
                new Class<?>[]{Item.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isEmpty" -> empty;
                    case "count" -> count;
                    case "maxStackSize" -> maxStack;
                    case "copyWithCount" -> item((Integer) args[0], maxStack, false);
                    case "isSimilar" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "ItemMock";
                    default -> null;
                });
    }
}
