package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.PressingTubState;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.LegacyPressingTubMigrationFurnitureBehavior.MigrationAction;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.LegacyPressingTubMigrationFurnitureBehavior.decideMigration;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ground-only legacy migration decision tests. */
class LegacyPressingTubMigrationBehaviorTest {

    private static final Key GRAPE_JUICE = Key.of("kaleidoscope_tavern:grape_juice");

    @Test
    void identicalStateAndFacingDeletesLegacy() {
        PressingTubState oldState = new PressingTubState(item(64), GRAPE_JUICE, 500);
        PressingTubState current = new PressingTubState(item(64), GRAPE_JUICE, 500);
        assertEquals(MigrationAction.DELETE_LEGACY,
                decideMigration(oldState, Direction.SOUTH, current, Direction.SOUTH));
    }

    @Test
    void bothEmptyWithSameFacingStillDeletesLegacy() {
        PressingTubState empty = new PressingTubState(null, null, 0);
        assertEquals(MigrationAction.DELETE_LEGACY,
                decideMigration(empty, Direction.SOUTH, empty, Direction.SOUTH));
    }

    @Test
    void sameItemDifferentCountConflicts() {
        PressingTubState oldState = new PressingTubState(item(64), GRAPE_JUICE, 500);
        PressingTubState current = new PressingTubState(item(1), GRAPE_JUICE, 500);
        assertEquals(MigrationAction.CONFLICT,
                decideMigration(oldState, Direction.SOUTH, current, Direction.SOUTH));
    }

    @Test
    void identicalContentDifferentFacingConflicts() {
        PressingTubState state = new PressingTubState(item(64), GRAPE_JUICE, 500);
        assertEquals(MigrationAction.CONFLICT,
                decideMigration(state, Direction.SOUTH, state, Direction.NORTH));
    }

    @Test
    void differentFluidAmountConflicts() {
        PressingTubState oldState = new PressingTubState(item(64), GRAPE_JUICE, 500);
        PressingTubState current = new PressingTubState(item(64), GRAPE_JUICE, 600);
        assertEquals(MigrationAction.CONFLICT,
                decideMigration(oldState, Direction.SOUTH, current, Direction.SOUTH));
    }

    @Test
    void emptyTargetTransfersStateThenDeletesLegacy() {
        PressingTubState oldState = new PressingTubState(item(16), GRAPE_JUICE, 250);
        PressingTubState current = new PressingTubState(null, null, 0);
        assertEquals(MigrationAction.TRANSFER_AND_DELETE,
                decideMigration(oldState, Direction.WEST, current, Direction.WEST));
    }

    @Test
    void emptyTargetDifferentFacingConflicts() {
        PressingTubState oldState = new PressingTubState(item(16), null, 0);
        PressingTubState current = new PressingTubState(null, null, 0);
        assertEquals(MigrationAction.CONFLICT,
                decideMigration(oldState, Direction.SOUTH, current, Direction.EAST));
    }

    private static Item item(int count) {
        return (Item) Proxy.newProxyInstance(
                LegacyPressingTubMigrationBehaviorTest.class.getClassLoader(),
                new Class<?>[]{Item.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isEmpty" -> count <= 0;
                    case "count" -> count;
                    case "maxStackSize" -> 64;
                    case "copyWithCount" -> item((Integer) args[0]);
                    case "isSimilar" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "ItemMock(" + count + ")";
                    default -> null;
                });
    }
}
