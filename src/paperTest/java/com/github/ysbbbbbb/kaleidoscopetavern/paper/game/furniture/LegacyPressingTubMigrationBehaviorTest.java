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

/**
 * {@link LegacyPressingTubMigrationFurnitureBehavior#decideMigration} 纯决策测试。
 *
 * <p>迁移最危险的错误是把「未完成迁移」误判成「已完成」然后删除旧家具，导致
 * 物品被吞（isSimilar 忽略 count）或方向/墙面语义丢失（目标已有方向不同的
 * 空桶）。这里用 CE Item mock 直接验证决策函数，不需要服务器。</p>
 */
class LegacyPressingTubMigrationBehaviorTest {

    private static final Key GRAPE_JUICE = Key.of("kaleidoscope_tavern:grape_juice");

    @Test
    void identicalStateAndOrientationDeletesLegacy() {
        PressingTubState oldState = new PressingTubState(item(64), GRAPE_JUICE, 500);
        PressingTubState current = new PressingTubState(item(64), GRAPE_JUICE, 500);
        assertEquals(MigrationAction.DELETE_LEGACY,
                decideMigration(oldState, false, Direction.SOUTH,
                        current, false, Direction.SOUTH));
    }

    @Test
    void bothEmptyWithSameOrientationStillDeletesLegacy() {
        PressingTubState oldState = new PressingTubState(null, null, 0);
        PressingTubState current = new PressingTubState(null, null, 0);
        assertEquals(MigrationAction.DELETE_LEGACY,
                decideMigration(oldState, false, Direction.SOUTH,
                        current, false, Direction.SOUTH));
    }

    @Test
    void sameItemDifferentCountConflicts() {
        // isSimilar() 忽略 count：64 个葡萄与 1 个葡萄元数据相同，迁移不得
        // 把它们当作同一状态，否则删除旧家具会吞掉剩余 63 个。
        PressingTubState oldState = new PressingTubState(item(64), GRAPE_JUICE, 500);
        PressingTubState current = new PressingTubState(item(1), GRAPE_JUICE, 500);
        assertEquals(MigrationAction.CONFLICT,
                decideMigration(oldState, false, Direction.SOUTH,
                        current, false, Direction.SOUTH));
    }

    @Test
    void identicalContentDifferentFacingConflicts() {
        PressingTubState oldState = new PressingTubState(item(64), GRAPE_JUICE, 500);
        PressingTubState current = new PressingTubState(item(64), GRAPE_JUICE, 500);
        assertEquals(MigrationAction.CONFLICT,
                decideMigration(oldState, false, Direction.SOUTH,
                        current, false, Direction.NORTH));
    }

    @Test
    void identicalContentDifferentTiltConflicts() {
        PressingTubState oldState = new PressingTubState(item(64), GRAPE_JUICE, 500);
        PressingTubState current = new PressingTubState(item(64), GRAPE_JUICE, 500);
        assertEquals(MigrationAction.CONFLICT,
                decideMigration(oldState, false, Direction.SOUTH,
                        current, true, Direction.SOUTH));
    }

    @Test
    void differentFluidAmountConflicts() {
        PressingTubState oldState = new PressingTubState(item(64), GRAPE_JUICE, 500);
        PressingTubState current = new PressingTubState(item(64), GRAPE_JUICE, 600);
        assertEquals(MigrationAction.CONFLICT,
                decideMigration(oldState, false, Direction.SOUTH,
                        current, false, Direction.SOUTH));
    }

    @Test
    void emptyTargetTransfersStateThenDeletesLegacy() {
        PressingTubState oldState = new PressingTubState(item(16), GRAPE_JUICE, 250);
        PressingTubState current = new PressingTubState(null, null, 0);
        assertEquals(MigrationAction.TRANSFER_AND_DELETE,
                decideMigration(oldState, true, Direction.WEST,
                        current, true, Direction.WEST));
    }

    @Test
    void emptyTargetDifferentFacingConflicts() {
        PressingTubState oldState = new PressingTubState(item(16), null, 0);
        PressingTubState current = new PressingTubState(null, null, 0);
        // 目标已有一个方向不同的空桶：不能视为迁移完成而删除旧家具。
        assertEquals(MigrationAction.CONFLICT,
                decideMigration(oldState, false, Direction.SOUTH,
                        current, false, Direction.EAST));
    }

    @Test
    void emptyTargetDifferentTiltConflicts() {
        PressingTubState oldState = new PressingTubState(item(16), null, 0);
        PressingTubState current = new PressingTubState(null, null, 0);
        assertEquals(MigrationAction.CONFLICT,
                decideMigration(oldState, false, Direction.SOUTH,
                        current, true, Direction.SOUTH));
    }

    /** Item mock：isEmpty / count / maxStackSize / copyWithCount / isSimilar。 */
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
