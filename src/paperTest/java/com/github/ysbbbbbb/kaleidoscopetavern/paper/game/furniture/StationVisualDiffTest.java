package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import net.momirealms.craftengine.core.item.Item;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavior tests for the pure {@link StationVisualDiff} state machine and the
 * {@link StationVisualFurnitureBehavior.Visual} value semantics.
 */
class StationVisualDiffTest {

    private static final byte ITEM_SLOT = StationVisualFurnitureBehavior.ITEM_TRANSFORM_FIXED;

    @Test
    void quaternionRoundTripPreservesComponentOrder() {
        // JOML constructor order is (x, y, z, w).
        Quaternionf rotation = new Quaternionf(0.1F, -0.2F, 0.3F, 0.9F).normalize();
        StationVisualFurnitureBehavior.Visual visual = visual(rotation, itemA());
        Quaternionf rebuilt = visual.leftRotation();
        assertEquals(rotation.x, rebuilt.x, 1e-6F);
        assertEquals(rotation.y, rebuilt.y, 1e-6F);
        assertEquals(rotation.z, rebuilt.z, 1e-6F);
        assertEquals(rotation.w, rebuilt.w, 1e-6F);
    }

    @Test
    void unitQuaternionIsNotTurnedIntoSidewaysRotation() {
        Quaternionf identity = new Quaternionf();
        Quaternionf rebuilt = visual(identity, itemA()).leftRotation();
        assertEquals(0F, rebuilt.x, 1e-6F);
        assertEquals(0F, rebuilt.y, 1e-6F);
        assertEquals(0F, rebuilt.z, 1e-6F);
        assertEquals(1F, rebuilt.w, 1e-6F);
    }

    @Test
    void emptyItemIsRejectedByVisual() {
        assertThrows(IllegalArgumentException.class,
                () -> visual(new Quaternionf(), emptyItem()));
    }

    @Test
    void similarItemsDoNotTriggerMetadataUpdate() {
        // Different wrapper instances, same content: identity comparison must
        // not make every refresh re-send the whole metadata.
        StationVisualFurnitureBehavior.Visual oldVisual = visual(itemA());
        StationVisualFurnitureBehavior.Visual newVisual = visual(itemA());
        assertFalse(StationVisualDiff.metadataChanged(oldVisual, newVisual));

        // Different content must still be detected.
        assertTrue(StationVisualDiff.metadataChanged(
                oldVisual, visual(itemB())));
    }

    @Test
    void crossGenerationResyncDropsEveryOldId() {
        List<StationVisualDiff.Op> ops = StationVisualDiff.fullResync(17, 0);
        assertEquals(List.of(new StationVisualDiff.Op(
                StationVisualDiff.OpType.REMOVE, 0, 17)), ops);
    }

    @Test
    void identicalContentSnapshotsProduceNoPackets() {
        // Distinct instances with identical content must yield an empty diff.
        List<StationVisualFurnitureBehavior.Visual> first = items(2);
        List<StationVisualFurnitureBehavior.Visual> second = items(2);
        List<StationVisualDiff.Op> ops =
                StationVisualDiff.compute(first, second, 2, 2);
        assertTrue(ops.isEmpty());
    }

    @Test
    void shrinkRemovesOnlyTheDroppedIds() {
        List<StationVisualFurnitureBehavior.Visual> before = items(17);
        List<StationVisualFurnitureBehavior.Visual> after = items(16);
        assertEquals(List.of(new StationVisualDiff.Op(
                        StationVisualDiff.OpType.REMOVE, 16, 17)),
                StationVisualDiff.compute(before, after, 17, 16));
    }

    @Test
    void growSpawnsOnlyTheNewIds() {
        List<StationVisualFurnitureBehavior.Visual> before = items(16);
        List<StationVisualFurnitureBehavior.Visual> after = items(17);
        assertEquals(List.of(new StationVisualDiff.Op(
                        StationVisualDiff.OpType.SPAWN, 16)),
                StationVisualDiff.compute(before, after, 16, 17));
    }

    @Test
    void fluidSlotShiftOnlyTouchesTheBoundarySlot() {
        // 16 items + fluid (17 slots) -> fluid disappears, only slot 16 goes.
        List<StationVisualFurnitureBehavior.Visual> withFluid = items(17);
        List<StationVisualFurnitureBehavior.Visual> withoutFluid = items(16);
        assertEquals(List.of(new StationVisualDiff.Op(
                        StationVisualDiff.OpType.REMOVE, 16, 17)),
                StationVisualDiff.compute(withFluid, withoutFluid, 17, 16));
        // Fluid appears, only slot 16 is spawned.
        assertEquals(List.of(new StationVisualDiff.Op(
                        StationVisualDiff.OpType.SPAWN, 16)),
                StationVisualDiff.compute(withoutFluid, withFluid, 16, 17));
    }

    @Test
    void positionChangeProducesPositionOp() {
        StationVisualFurnitureBehavior.Visual before = visual(itemA());
        StationVisualFurnitureBehavior.Visual after =
                StationVisualFurnitureBehavior.Visual.of(itemA(),
                        0, 0, 0.5, 0, 0, 1, new Quaternionf(), ITEM_SLOT);
        assertEquals(List.of(new StationVisualDiff.Op(
                        StationVisualDiff.OpType.POSITION, 0)),
                StationVisualDiff.compute(List.of(before), List.of(after), 1, 1));
    }

    @Test
    void fullResyncKeepsRemoveBeforeSpawns() {
        List<StationVisualDiff.Op> ops = StationVisualDiff.fullResync(17, 5);
        assertEquals(6, ops.size());
        assertEquals(new StationVisualDiff.Op(
                StationVisualDiff.OpType.REMOVE, 0, 17), ops.get(0));
        for (int index = 1; index < ops.size(); index++) {
            assertEquals(new StationVisualDiff.Op(
                    StationVisualDiff.OpType.SPAWN, index - 1), ops.get(index));
        }
    }

    private static final Object CONTENT_A = new Object();
    private static final Object CONTENT_B = new Object();
    private static final Map<Item, Object> CONTENT = new IdentityHashMap<>();

    private static List<StationVisualFurnitureBehavior.Visual> items(int count) {
        List<StationVisualFurnitureBehavior.Visual> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(visual(itemA()));
        }
        return result;
    }

    private static StationVisualFurnitureBehavior.Visual visual(Item item) {
        return visual(new Quaternionf(), item);
    }

    private static StationVisualFurnitureBehavior.Visual visual(
            Quaternionf rotation, Item item) {
        return StationVisualFurnitureBehavior.Visual.of(
                item, 0, 0, 0, 0, 0, 1, rotation, ITEM_SLOT);
    }

    private static Item itemA() {
        return item(CONTENT_A, false);
    }

    private static Item itemB() {
        return item(CONTENT_B, false);
    }

    private static Item emptyItem() {
        return item(CONTENT_A, true);
    }

    /**
     * Item mock. {@code isSimilar} compares the shared content key, so two
     * wrappers with the same key are content-equal even though their object
     * identity differs, mirroring CraftEngine's content comparison.
     */
    private static Item item(Object contentKey, boolean empty) {
        Item proxy = (Item) Proxy.newProxyInstance(
                StationVisualDiffTest.class.getClassLoader(),
                new Class<?>[]{Item.class},
                (handlerProxy, method, args) -> {
                    Object result = switch (method.getName()) {
                        case "isSimilar" ->
                                CONTENT.get(handlerProxy) == CONTENT.get(args[0]);
                        case "isEmpty" -> empty;
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
        CONTENT.put(proxy, contentKey);
        return proxy;
    }
}
