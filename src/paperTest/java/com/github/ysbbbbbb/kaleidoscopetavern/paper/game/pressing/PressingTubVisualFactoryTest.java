package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.pressing;

import net.momirealms.craftengine.core.util.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PressingTubVisualFactory} 墙面坐标/旋转的单元测试。
 *
 * <p>golden 值由源 Forge {@code PressingTubBlockEntityRender} 的 PoseStack 矩阵
 * 生成（translate(0.5,0,0.5) · YN(θ) · translate(-0.5,0,-0.5) · Rx(±45) ·
 * translate(0,a,b)，作用于局部物品点 (0.5+x, 0.2+y, 0.5+z)），以相对单元中心
 * 的偏移表示。测试基准是原模组矩阵，而不是 helper 自身的公式。</p>
 */
class PressingTubVisualFactoryTest {

    private static final Map<String, double[]> GOLDENS = Map.ofEntries(
            // key = "facing|x|y|z"（物品局部偏移），value = 相对 origin 的偏移
            Map.entry("SOUTH|0.0|0.0|0.0", new double[]{0.000000, 0.494975, -0.065685}),
            Map.entry("SOUTH|0.15|0.0|0.15", new double[]{-0.150000, 0.601041, -0.171751}),
            Map.entry("SOUTH|-0.15|0.0|-0.15", new double[]{0.150000, 0.388909, 0.040381}),
            Map.entry("SOUTH|0.15|0.0|-0.15", new double[]{-0.150000, 0.388909, 0.040381}),
            Map.entry("SOUTH|-0.15|0.0|0.15", new double[]{0.150000, 0.601041, -0.171751}),
            Map.entry("SOUTH|0.15|0.5|0.15", new double[]{-0.150000, 0.954594, 0.181802}),
            Map.entry("SOUTH|-0.15|0.5|-0.15", new double[]{0.150000, 0.742462, 0.393934}),
            Map.entry("SOUTH|0.15|0.5|-0.15", new double[]{-0.150000, 0.742462, 0.393934}),
            Map.entry("SOUTH|-0.15|0.5|0.15", new double[]{0.150000, 0.954594, 0.181802}),
            Map.entry("WEST|0.0|0.0|0.0", new double[]{0.005025, 0.494975, 0.000000}),
            Map.entry("WEST|0.15|0.0|0.15", new double[]{-0.101041, 0.388909, 0.150000}),
            Map.entry("WEST|-0.15|0.0|-0.15", new double[]{0.111091, 0.601041, -0.150000}),
            Map.entry("WEST|0.15|0.0|-0.15", new double[]{0.111091, 0.601041, 0.150000}),
            Map.entry("WEST|-0.15|0.0|0.15", new double[]{-0.101041, 0.388909, -0.150000}),
            Map.entry("WEST|0.15|0.5|0.15", new double[]{-0.454594, 0.742462, 0.150000}),
            Map.entry("WEST|-0.15|0.5|-0.15", new double[]{-0.242462, 0.954594, -0.150000}),
            Map.entry("WEST|0.15|0.5|-0.15", new double[]{-0.242462, 0.954594, 0.150000}),
            Map.entry("WEST|-0.15|0.5|0.15", new double[]{-0.454594, 0.742462, -0.150000}),
            Map.entry("NORTH|0.0|0.0|0.0", new double[]{0.000000, 0.494975, 0.065685}),
            Map.entry("NORTH|0.15|0.0|0.15", new double[]{0.150000, 0.601041, 0.171751}),
            Map.entry("NORTH|-0.15|0.0|-0.15", new double[]{-0.150000, 0.388909, -0.040381}),
            Map.entry("NORTH|0.15|0.0|-0.15", new double[]{0.150000, 0.388909, -0.040381}),
            Map.entry("NORTH|-0.15|0.0|0.15", new double[]{-0.150000, 0.601041, 0.171751}),
            Map.entry("NORTH|0.15|0.5|0.15", new double[]{0.150000, 0.954594, -0.181802}),
            Map.entry("NORTH|-0.15|0.5|-0.15", new double[]{-0.150000, 0.742462, -0.393934}),
            Map.entry("NORTH|0.15|0.5|-0.15", new double[]{0.150000, 0.742462, -0.393934}),
            Map.entry("NORTH|-0.15|0.5|0.15", new double[]{-0.150000, 0.954594, -0.181802}),
            Map.entry("EAST|0.0|0.0|0.0", new double[]{-0.005025, 0.494975, 0.000000}),
            Map.entry("EAST|0.15|0.0|0.15", new double[]{0.101041, 0.388909, -0.150000}),
            Map.entry("EAST|-0.15|0.0|-0.15", new double[]{-0.111091, 0.601041, 0.150000}),
            Map.entry("EAST|0.15|0.0|-0.15", new double[]{-0.111091, 0.601041, -0.150000}),
            Map.entry("EAST|-0.15|0.0|0.15", new double[]{0.101041, 0.388909, 0.150000}),
            Map.entry("EAST|0.15|0.5|0.15", new double[]{0.454594, 0.742462, -0.150000}),
            Map.entry("EAST|-0.15|0.5|-0.15", new double[]{0.242462, 0.954594, 0.150000}),
            Map.entry("EAST|0.15|0.5|-0.15", new double[]{0.242462, 0.954594, -0.150000}),
            Map.entry("EAST|-0.15|0.5|0.15", new double[]{0.454594, 0.742462, 0.150000})
    );

    @Test
    void tiltDisplayMatchesForgePoseStack() {
        for (Map.Entry<String, double[]> entry : GOLDENS.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            Direction facing = Direction.valueOf(parts[0]);
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            double[] expected = entry.getValue();
            double[] actual = PressingTubVisualFactory.tiltDisplay(facing, x, y, z);
            assertEquals(expected[0], actual[0], 1e-6,
                    "facing=" + facing + " x=" + x + " y=" + y + " z=" + z);
            assertEquals(expected[1], actual[1], 1e-6,
                    "facing=" + facing + " x=" + x + " y=" + y + " z=" + z);
            assertEquals(expected[2], actual[2], 1e-6,
                    "facing=" + facing + " x=" + x + " y=" + y + " z=" + z);
        }
    }

    @Test
    void itemQuaternionContainsTheCompleteSourceFacingAndTilt() {
        // The packet entity itself stays at yaw=0. The complete source
        // YN(facing) · Rx(tilt) · Rx(-90) transform lives in leftRotation,
        // preventing CE wall-furniture yaw from composing the facing twice.
        assertForward(Direction.NORTH, 0, 0.7071068F, -0.7071068F);
        assertForward(Direction.EAST, 0.7071068F, 0.7071068F, 0);
        assertForward(Direction.SOUTH, 0, 0.7071068F, 0.7071068F);
        assertForward(Direction.WEST, -0.7071068F, 0.7071068F, 0);
    }

    @Test
    void itemQuaternionPreservesTheSourceHorizontalBasis() {
        assertRight(Direction.NORTH, 1, 0, 0);
        assertRight(Direction.EAST, 0, 0, -1);
        assertRight(Direction.SOUTH, -1, 0, 0);
        assertRight(Direction.WEST, 0, 0, 1);
    }

    private static void assertForward(Direction facing, float x, float y, float z) {
        Vector3f transformed = PressingTubVisualFactory
                .tiltRotation(facing, 0, 0)
                .transform(new Vector3f(0, 0, 1));
        assertVector(facing, transformed, x, y, z);
    }

    private static void assertRight(Direction facing, float x, float y, float z) {
        Vector3f transformed = PressingTubVisualFactory
                .tiltRotation(facing, 0, 0)
                .transform(new Vector3f(1, 0, 0));
        assertVector(facing, transformed, x, y, z);
    }

    private static void assertVector(Direction facing, Vector3f actual,
                                     float x, float y, float z) {
        assertEquals(x, actual.x, 1e-5, "facing=" + facing + " x");
        assertEquals(y, actual.y, 1e-5, "facing=" + facing + " y");
        assertEquals(z, actual.z, 1e-5, "facing=" + facing + " z");
    }

}
