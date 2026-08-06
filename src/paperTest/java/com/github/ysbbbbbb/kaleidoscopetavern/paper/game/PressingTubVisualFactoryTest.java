package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import net.momirealms.craftengine.core.util.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PressingTubVisualFactory} 墙面坐标的纯单元测试。
 *
 * <p>Controller.location() 已经返回单元中心（blockX+0.5 / blockZ+0.5），因此
 * 墙面原料投影绝不能再次 +0.5；否则墙面原料会整体偏移半个方块。</p>
 */
class PressingTubVisualFactoryTest {

    @Test
    void cellCenterStaysAtOriginWithoutExtraHalfBlock() {
        // 局部点 (0.5, 0.5, 0.5) 是单元中心：投影后必须精确回到 origin，
        // 任何方向都不能出现 +0.5 偏移。
        for (Direction facing : new Direction[]{Direction.NORTH, Direction.EAST,
                Direction.SOUTH, Direction.WEST}) {
            double[] position = PressingTubVisualFactory.tiltDisplayPosition(
                    facing, 0.5, 0.5, 0.5, 10, 20, 30);
            assertEquals(10, position[0], 1e-6, "facing=" + facing);
            assertEquals(20.5, position[1], 1e-6, "facing=" + facing);
            assertEquals(30, position[2], 1e-6, "facing=" + facing);
        }
    }

    @Test
    void northMapsLocalOffsetDirectly() {
        // facingYaw(NORTH)=180 → 投影旋转 360°，局部偏移 (0.2, 0.1) 原样映射。
        double[] position = PressingTubVisualFactory.tiltDisplayPosition(
                Direction.NORTH, 0.7, 0.5, 0.6, 0, 0, 0);
        assertEquals(0.2, position[0], 1e-6);
        assertEquals(0.5, position[1], 1e-6);
        assertEquals(0.1, position[2], 1e-6);
    }

    @Test
    void eastRotatesLocalOffsetNinetyDegrees() {
        // facingYaw(EAST)=90 → 投影旋转 270°：dx 映射到 -dz，dz 映射到 +dx。
        double[] position = PressingTubVisualFactory.tiltDisplayPosition(
                Direction.EAST, 0.7, 0.5, 0.6, 0, 0, 0);
        assertEquals(-0.1, position[0], 1e-6);
        assertEquals(0.2, position[2], 1e-6);
    }

    @Test
    void southMirrorsLocalOffset() {
        double[] position = PressingTubVisualFactory.tiltDisplayPosition(
                Direction.SOUTH, 0.7, 0.5, 0.6, 0, 0, 0);
        assertEquals(-0.2, position[0], 1e-6);
        assertEquals(-0.1, position[2], 1e-6);
    }

    @Test
    void westRotatesLocalOffsetNinetyDegreesInverse() {
        // facingYaw(WEST)=270 → 投影旋转 90°：dx 映射到 +dz，dz 映射到 -dx。
        double[] position = PressingTubVisualFactory.tiltDisplayPosition(
                Direction.WEST, 0.7, 0.5, 0.6, 0, 0, 0);
        assertEquals(0.1, position[0], 1e-6);
        assertEquals(-0.2, position[2], 1e-6);
    }
}
