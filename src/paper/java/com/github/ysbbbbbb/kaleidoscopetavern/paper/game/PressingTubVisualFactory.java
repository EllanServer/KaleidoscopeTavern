package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

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
    private static final double TILT_X_DEGREES = -45;
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
                    // The source renderer applied its Rx(-45) pose and the
                    // blockstate model carries the same tilt; rotate the local
                    // point around the cell centre by the model's effective yaw
                    // (CE's configured facing rotation plus the ItemDisplay
                    // renderer's intrinsic +180-degree turn) so the pile follows
                    // the wall-mounted tub like the old furniture transform.
                    double[] point = tiltNorth(0.5 + x, 0.2 + y, 0.5 + z);
                    double[] display = tiltDisplayPosition(
                            facing, point[0], point[1], point[2],
                            origin.getX(), origin.getY(), origin.getZ());
                    displayX = display[0];
                    displayY = display[1];
                    displayZ = display[2];
                    displayYaw = facingYaw(facing);
                    rotation = new Quaternionf()
                            .rotateX((float) Math.toRadians(TILT_X_DEGREES))
                            .rotateX((float) Math.toRadians(ITEM_X_DEGREES))
                            .rotateY((float) Math.toRadians(-yRotation))
                            .rotateZ((float) Math.toRadians(-zRotation));
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
            items.build(NAMESPACE + "_render/pressing_fluid/" + path(fluid.toString()), null)
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

    /** The source renderer's facing=NORTH tilt pose for one local point. */
    private static double[] tiltNorth(double x, double y, double z) {
        double translatedY = y - 0.25;
        double translatedZ = z + 0.25;
        double radians = Math.toRadians(TILT_X_DEGREES);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new double[]{
                x,
                cos * translatedY - sin * translatedZ,
                sin * translatedY + cos * translatedZ,
        };
    }

    /**
     * 把经过 {@link #tiltNorth} 旋转后的局部点投影到世界坐标。
     *
     * <p>origin 已是单元中心（blockX+0.5 / blockZ+0.5），因此这里绝不能再次
     * +0.5，否则墙面原料会整体偏移半个方块。</p>
     */
    static double[] tiltDisplayPosition(Direction facing,
                                        double pointX, double pointY, double pointZ,
                                        double originX, double originY, double originZ) {
        double radians = Math.toRadians(facingYaw(facing) + 180.0);
        double dx = pointX - 0.5;
        double dz = pointZ - 0.5;
        return new double[]{
                originX + Math.cos(radians) * dx + Math.sin(radians) * dz,
                originY + pointY,
                originZ - Math.sin(radians) * dx + Math.cos(radians) * dz,
        };
    }

    /** Matches the entity_renderer rotation the block config bakes per facing. */
    private static float facingYaw(Direction facing) {
        return switch (facing) {
            case NORTH -> 180;
            case EAST -> 90;
            case SOUTH -> 0;
            case WEST -> 270;
            default -> 0;
        };
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
