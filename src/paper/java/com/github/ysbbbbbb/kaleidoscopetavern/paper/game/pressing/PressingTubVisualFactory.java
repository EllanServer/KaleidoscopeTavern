package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.pressing;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.visual.DisplayVisual;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

/**
 * 压榨桶动态内容布局：把 {@link PressingTubState} 转成中立 {@link DisplayVisual}
 * 列表（原料堆 + 液面），完全镜像源 PressingTubBlockEntityRender 的 PoseStack
 * 姿态，但不再依赖任何家具层类型。
 *
 * <p>布局是纯函数式的：相同状态、朝向与原点必然产生相同视觉列表，因此
 * {@link com.github.ysbbbbbb.kaleidoscopetavern.paper.game.visual.DifferentialItemDisplayElement}
 * 可以稳定地做逐槽位差量比较。</p>
 */
public final class PressingTubVisualFactory {
    private static final String NAMESPACE = "kaleidoscope_tavern:";
    // 一个逻辑原料堆不是每件物品一个显示实体：有界视觉池保持高数量时
    // 刷新 packet 廉价，与 StationService 的 MAX_STATION_ITEM_VISUALS 一致。
    private static final int MAX_STATION_ITEM_VISUALS = 16;
    private static final int PRESS_CAPACITY = 1_000;
    // PressingTubBlockEntityRender's PoseStack tilt and item flattening.
    private static final double ITEM_X_DEGREES = -90;

    private final ItemService items;

    public PressingTubVisualFactory(ItemService items) {
        this.items = items;
    }

    /**
     * @param state  当前压榨桶状态快照
     * @param tilted 是否为墙面（tilt=true）压榨桶
     * @param facing 方块朝向
     * @param origin 桶单元中心（Controller.location()，已是 blockX+0.5 / blockZ+0.5）
     * @param limit  最多返回的视觉数量（含液体槽位）
     */
    public List<DisplayVisual> visuals(PressingTubState state, boolean tilted,
                                       Direction facing, Location origin, int limit) {
        int amount = Math.max(0, state.fluidAmount());
        Key fluid = state.fluid();
        boolean hasFluid = amount > 0 && fluid != null;
        int itemLimit = Math.max(0, limit - (hasFluid ? 1 : 0));
        List<DisplayVisual> result = new ArrayList<>(
                Math.min(limit, MAX_STATION_ITEM_VISUALS + 1));
        Item ingredient = state.ingredient();
        int count = ingredient == null ? 0
                : Math.min(64, Math.max(0, ingredient.count()));
        int visualCount = Math.min(itemLimit,
                Math.min(count, MAX_STATION_ITEM_VISUALS));
        if (ingredient != null && visualCount > 0) {
            long seed = blockPositionSeed(origin);
            // Share one Item instance (count=1) across every visual copy.
            Item displayItem = ingredient.copyWithCount(1);
            for (int index = 0; index < visualCount; index++) {
                float x = index % 4 % 2 == 0
                        ? -0.15F : 0.15F + stableRandom(seed, index, 1) * 0.0625F;
                float z = index % 4 / 2 == 0
                        ? -0.15F : 0.15F + stableRandom(seed, index, 2) * 0.0625F;
                float y = index / 4 * 0.03125F
                        + stableRandom(seed, index, 3) * 0.05F;
                // 与物品数量无关的稳定旋转：6.4F 是 count=64 时的最大范围，
                // 压榨成功只改变 count/press_amount，不再让所有已有物品旋转变化，
                // 否则每次压榨都会触发整组 metadata 差量重发。
                float yRotation = stableRandom(seed, index, 4) * 6.4F;
                float zRotation = stableRandom(seed, index, 5) * 360F;
                double displayX;
                double displayY;
                double displayZ;
                float displayYaw;
                Quaternionf rotation;
                if (tilted) {
                    // Keep the source outer YN facing rotation in the same
                    // quaternion as the tilt. Splitting it into entity yaw made
                    // CE wall-furniture yaw compose the facing a second time.
                    double[] display = tiltDisplay(facing, x, y, z);
                    displayX = origin.getX() + display[0];
                    displayY = origin.getY() + display[1];
                    displayZ = origin.getZ() + display[2];
                    displayYaw = 0;
                    rotation = tiltRotation(facing, yRotation, zRotation);
                } else {
                    displayX = origin.getX() + x;
                    displayY = origin.getY() + 0.2 + y;
                    displayZ = origin.getZ() + z;
                    displayYaw = 0;
                    rotation = new Quaternionf()
                            .rotateX((float) Math.toRadians(ITEM_X_DEGREES))
                            .rotateY((float) Math.toRadians(-yRotation))
                            .rotateZ((float) Math.toRadians(-zRotation));
                }
                result.add(DisplayVisual.of(
                        displayItem,
                        displayX, displayY, displayZ, displayYaw, 0, 0.5F, rotation,
                        DisplayVisual.ITEM_TRANSFORM_FIXED));
            }
        }

        if (hasFluid) {
            items.buildVisual(NAMESPACE + "_render/pressing_fluid/" + path(fluid.toString()))
                    .ifPresent(renderItem -> {
                        renderItem.setAmount(1);
                        float y = 0.125F + Math.min(PRESS_CAPACITY, amount)
                                / (float) PRESS_CAPACITY * 0.25F;
                        // The wall variant keeps the source fluid plane
                        // horizontal at the target cell centre.
                        result.add(DisplayVisual.of(
                                BukkitAdaptor.adapt(renderItem),
                                origin.getX(), origin.getY() + y, origin.getZ(),
                                0, 0, 1, new Quaternionf(),
                                DisplayVisual.ITEM_TRANSFORM_NONE));
                    });
        }
        return result;
    }

    /**
     * 源 PressingTubBlockEntityRender 的四方向 tilt 矩阵闭式解。
     *
     * <p>输入为物品在方块局部坐标中的偏移 (x, y, z)，物品中心局部点为
     * (0.5+x, 0.2+y, 0.5+z)。输出为相对单元中心（origin）的世界偏移，绕方块
     * 中心的 Y 旋转（θ=180-facingIndex*90）已包含在内，因此调用方无需再做
     * 额外旋转，也不能再加任何 +0.5。</p>
     */
    static double[] tiltDisplay(Direction facing, double x, double y, double z) {
        double c = Math.cos(Math.toRadians(45));
        double s = Math.sin(Math.toRadians(45));
        if (facing == Direction.EAST || facing == Direction.WEST) {
            // X 轴：Rx(+45°) 后 translate(0, +0.5, -0.5)。
            double tY = y + 0.7;
            double tZ = z;
            double p2y = c * tY - s * tZ;
            double p2z = s * tY + c * tZ;
            // Source get2DDataValue: WEST=1, EAST=3.
            return facing == Direction.WEST
                    ? new double[]{-p2z + 0.5, p2y, x}
                    : new double[]{p2z - 0.5, p2y, -x};
        }
        // Z 轴：Rx(-45°) 后 translate(0, -0.25, +0.25)。
        double tY = y - 0.05;
        double tZ = z + 0.75;
        double p2y = c * tY + s * tZ;
        double p2z = -s * tY + c * tZ;
        // Source get2DDataValue: SOUTH=0, NORTH=2.
        return facing == Direction.SOUTH
                ? new double[]{-x, p2y, -p2z + 0.5}
                : new double[]{x, p2y, p2z - 0.5};
    }

    static Quaternionf tiltRotation(Direction facing, float yRotation,
                                    float zRotation) {
        float outerY = switch (facing) {
            case NORTH -> 0;
            case EAST -> 90;
            case SOUTH -> -180;
            case WEST -> -90;
            default -> 0;
        };
        float tilt = (facing == Direction.EAST || facing == Direction.WEST)
                ? 45 : -45;
        return new Quaternionf()
                .rotateY((float) Math.toRadians(outerY))
                .rotateX((float) Math.toRadians(tilt))
                .rotateX((float) Math.toRadians(ITEM_X_DEGREES))
                .rotateY((float) Math.toRadians(-yRotation))
                .rotateZ((float) Math.toRadians(-zRotation));
    }

    private static String path(String resourceId) {
        int separator = resourceId.indexOf(':');
        return separator < 0 ? resourceId : resourceId.substring(separator + 1);
    }

    private static long blockPositionSeed(Location location) {
        return ((long) location.getBlockX() & 0x3FFFFFFL) << 38
                | ((long) location.getBlockZ() & 0x3FFFFFFL) << 12
                | (long) location.getBlockY() & 0xFFFL;
    }

    private static float stableRandom(long positionSeed, int index, int channel) {
        long hash = positionSeed ^ (long) index * 0x9e3779b97f4a7c15L
                ^ (long) channel * 0x6c62272e07bb0142L;
        hash = (hash ^ hash >>> 30) * 0xbf58476d1ce4e5b9L;
        hash = (hash ^ hash >>> 27) * 0x94d049bb133111ebL;
        hash ^= hash >>> 31;
        return (float) (int) hash / (float) Integer.MAX_VALUE;
    }
}
