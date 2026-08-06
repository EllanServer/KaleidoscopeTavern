package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import net.momirealms.craftengine.core.util.Direction;
import org.joml.Quaternionf;
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
            Map.entry("NORTH|0.0|0.0|0.0", new double[]{0.000000, 0.494975, -0.065685}),
            Map.entry("NORTH|0.15|0.0|0.15", new double[]{-0.150000, 0.601041, -0.171751}),
            Map.entry("NORTH|-0.15|0.0|-0.15", new double[]{0.150000, 0.388909, 0.040381}),
            Map.entry("NORTH|0.15|0.0|-0.15", new double[]{-0.150000, 0.388909, 0.040381}),
            Map.entry("NORTH|-0.15|0.0|0.15", new double[]{0.150000, 0.601041, -0.171751}),
            Map.entry("NORTH|0.15|0.5|0.15", new double[]{-0.150000, 0.954594, 0.181802}),
            Map.entry("NORTH|-0.15|0.5|-0.15", new double[]{0.150000, 0.742462, 0.393934}),
            Map.entry("NORTH|0.15|0.5|-0.15", new double[]{-0.150000, 0.742462, 0.393934}),
            Map.entry("NORTH|-0.15|0.5|0.15", new double[]{0.150000, 0.954594, 0.181802}),
            Map.entry("EAST|0.0|0.0|0.0", new double[]{0.005025, 0.494975, 0.000000}),
            Map.entry("EAST|0.15|0.0|0.15", new double[]{-0.101041, 0.388909, 0.150000}),
            Map.entry("EAST|-0.15|0.0|-0.15", new double[]{0.111091, 0.601041, -0.150000}),
            Map.entry("EAST|0.15|0.0|-0.15", new double[]{0.111091, 0.601041, 0.150000}),
            Map.entry("EAST|-0.15|0.0|0.15", new double[]{-0.101041, 0.388909, -0.150000}),
            Map.entry("EAST|0.15|0.5|0.15", new double[]{-0.454594, 0.742462, 0.150000}),
            Map.entry("EAST|-0.15|0.5|-0.15", new double[]{-0.242462, 0.954594, -0.150000}),
            Map.entry("EAST|0.15|0.5|-0.15", new double[]{-0.242462, 0.954594, 0.150000}),
            Map.entry("EAST|-0.15|0.5|0.15", new double[]{-0.454594, 0.742462, -0.150000}),
            Map.entry("SOUTH|0.0|0.0|0.0", new double[]{0.000000, 0.494975, 0.065685}),
            Map.entry("SOUTH|0.15|0.0|0.15", new double[]{0.150000, 0.601041, 0.171751}),
            Map.entry("SOUTH|-0.15|0.0|-0.15", new double[]{-0.150000, 0.388909, -0.040381}),
            Map.entry("SOUTH|0.15|0.0|-0.15", new double[]{0.150000, 0.388909, -0.040381}),
            Map.entry("SOUTH|-0.15|0.0|0.15", new double[]{-0.150000, 0.601041, 0.171751}),
            Map.entry("SOUTH|0.15|0.5|0.15", new double[]{0.150000, 0.954594, -0.181802}),
            Map.entry("SOUTH|-0.15|0.5|-0.15", new double[]{-0.150000, 0.742462, -0.393934}),
            Map.entry("SOUTH|0.15|0.5|-0.15", new double[]{0.150000, 0.742462, -0.393934}),
            Map.entry("SOUTH|-0.15|0.5|0.15", new double[]{-0.150000, 0.954594, -0.181802}),
            Map.entry("WEST|0.0|0.0|0.0", new double[]{-0.005025, 0.494975, 0.000000}),
            Map.entry("WEST|0.15|0.0|0.15", new double[]{0.101041, 0.388909, -0.150000}),
            Map.entry("WEST|-0.15|0.0|-0.15", new double[]{-0.111091, 0.601041, 0.150000}),
            Map.entry("WEST|0.15|0.0|-0.15", new double[]{-0.111091, 0.601041, -0.150000}),
            Map.entry("WEST|-0.15|0.0|0.15", new double[]{0.101041, 0.388909, 0.150000}),
            Map.entry("WEST|0.15|0.5|0.15", new double[]{0.454594, 0.742462, -0.150000}),
            Map.entry("WEST|-0.15|0.5|-0.15", new double[]{0.242462, 0.954594, 0.150000}),
            Map.entry("WEST|0.15|0.5|-0.15", new double[]{0.242462, 0.954594, -0.150000}),
            Map.entry("WEST|-0.15|0.5|0.15", new double[]{0.454594, 0.742462, 0.150000})
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
    void itemQuaternionFollowsSourceTiltDirection() {
        // X 轴（EAST/WEST）Rx(+45°)、Z 轴（NORTH/SOUTH）Rx(-45°)，
        // 物品平放 Rx(-90°)；实体 yaw 承担源矩阵的 YN(θ) 部分。
        assertTiltDirection(Direction.EAST, 0.7071068f, 0.7071068f);
        assertTiltDirection(Direction.WEST, 0.7071068f, 0.7071068f);
        assertTiltDirection(Direction.NORTH, 0.7071068f, -0.7071068f);
        assertTiltDirection(Direction.SOUTH, 0.7071068f, -0.7071068f);
    }

    @Test
    void facingYawPointsContentAlongTheTubFacing() {
        // 实体 yaw 顺时针（0=南 90=西 180=北 270=东）：方向向量
        // (dirX, dirZ) = (-sin(yaw), cos(yaw)) 必须等于 facing 指向。
        assertFacingYaw(Direction.NORTH, 0, -1);
        assertFacingYaw(Direction.EAST, 1, 0);
        assertFacingYaw(Direction.SOUTH, 0, 1);
        assertFacingYaw(Direction.WEST, -1, 0);
    }

    private static void assertFacingYaw(Direction facing, double dx, double dz) {
        double radians = Math.toRadians(PressingTubVisualFactory.facingYaw(facing));
        assertEquals(dx, -Math.sin(radians), 1e-6, "facing=" + facing);
        assertEquals(dz, Math.cos(radians), 1e-6, "facing=" + facing);
    }

    private static void assertTiltDirection(Direction facing, float y, float z) {
        float tilt = (facing == Direction.EAST || facing == Direction.WEST) ? 45 : -45;
        Quaternionf rotation = new Quaternionf()
                .rotateX((float) Math.toRadians(tilt))
                .rotateX((float) Math.toRadians(-90))
                .rotateY(0)
                .rotateZ(0);
        Vector3f transformed = rotation.transform(new Vector3f(0, 0, 1));
        assertEquals(y, transformed.y, 1e-5, "facing=" + facing);
        assertEquals(z, transformed.z, 1e-5, "facing=" + facing);
    }
}
