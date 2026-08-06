package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import net.momirealms.craftengine.core.item.Item;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每代增量 diff 缓存的行为测试：同一 generation 返回同一列表实例（所有跟上
 * 版本观察者共享）、新 generation 重新计算、无 SPAWN 可整代共享 packet 列表、
 * 空 diff 仍正确推进缓存状态。
 */
class StationGenerationDiffTest {

    @Test
    void sameGenerationReturnsTheSameSharedOpsList() {
        StationGenerationDiff diff = new StationGenerationDiff();
        List<StationVisualFurnitureBehavior.Visual> previous = items(16);
        List<StationVisualFurnitureBehavior.Visual> current = items(15);

        List<StationVisualDiff.Op> first =
                diff.forGeneration(7, previous, current, 17, 15);
        List<StationVisualDiff.Op> second =
                diff.forGeneration(7, previous, current, 17, 15);

        // 两名跟上版本的观察者共享同一份 diff 实例。
        assertSame(first, second);
        // 收缩只产生 REMOVE，无 SPAWN，packet 列表可整代共享。
        assertTrue(diff.isSharedEligible());
    }

    @Test
    void newGenerationRecomputesASeparateList() {
        StationGenerationDiff diff = new StationGenerationDiff();
        List<StationVisualFurnitureBehavior.Visual> previous = items(16);
        List<StationVisualFurnitureBehavior.Visual> current = items(15);

        List<StationVisualDiff.Op> first =
                diff.forGeneration(7, previous, current, 17, 15);
        List<StationVisualDiff.Op> second =
                diff.forGeneration(8, previous, current, 17, 15);

        assertNotSame(first, second);
    }

    @Test
    void spawnPresentMarksPerPlayerBuild() {
        StationGenerationDiff diff = new StationGenerationDiff();
        // 15 → 16 个槽位：多出 SPAWN，需要按玩家附加 ViewRange。
        List<StationVisualDiff.Op> ops =
                diff.forGeneration(7, items(15), items(16), 17, 16);

        assertFalse(ops.isEmpty());
        assertFalse(diff.isSharedEligible());
    }

    @Test
    void emptyDiffRemainsSharedEligible() {
        StationGenerationDiff diff = new StationGenerationDiff();
        List<StationVisualFurnitureBehavior.Visual> same = items(2);

        List<StationVisualDiff.Op> ops =
                diff.forGeneration(7, same, same, 17, 2);

        assertTrue(ops.isEmpty());
        assertTrue(diff.isSharedEligible());
    }

    private static final Object CONTENT_A = new Object();
    private static final Map<Item, Object> CONTENT = new IdentityHashMap<>();

    private static List<StationVisualFurnitureBehavior.Visual> items(int count) {
        List<StationVisualFurnitureBehavior.Visual> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(StationVisualFurnitureBehavior.Visual.of(
                    item(), 0, 0, 0, 0, 0, 1, new Quaternionf(),
                    StationVisualFurnitureBehavior.ITEM_TRANSFORM_FIXED));
        }
        return result;
    }

    /** 与 StationVisualDiffTest 相同的 Item mock：内容相等而非同一实例。 */
    private static Item item() {
        Item proxy = (Item) Proxy.newProxyInstance(
                StationGenerationDiffTest.class.getClassLoader(),
                new Class<?>[]{Item.class},
                (handlerProxy, method, args) -> {
                    Object result = switch (method.getName()) {
                        case "isSimilar" ->
                                CONTENT.get(handlerProxy) == CONTENT.get(args[0]);
                        case "isEmpty" -> false;
                        case "count" -> 1;
                        case "hashCode" -> System.identityHashCode(handlerProxy);
                        case "equals" -> handlerProxy == args[0];
                        case "toString" -> "ItemMock";
                        default -> null;
                    };
                    if (result == null) {
                        Class<?> returnType = method.getReturnType();
                        if (returnType == boolean.class) {
                            return false;
                        }
                        if (returnType == int.class) {
                            return 0;
                        }
                        if (returnType == long.class) {
                            return 0L;
                        }
                        if (returnType == float.class) {
                            return 0F;
                        }
                        if (returnType == double.class) {
                            return 0D;
                        }
                    }
                    return result;
                });
        CONTENT.put(proxy, CONTENT_A);
        return proxy;
    }
}
