package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.visual;

import net.momirealms.craftengine.core.item.Item;
import org.joml.Quaternionf;

import java.util.Objects;

/**
 * 中立显示描述：一个 ItemDisplay 的完整静态快照，同时供 CE 家具
 * （StationVisualFurnitureBehavior）与 CE 自定义方块（PressingTubBlockBehavior）
 * 使用，避免家具层反向依赖方块层或反之。
 *
 * <p>左侧旋转以 JOML 顺序的四个 float（x, y, z, w）存储，使快照可被所有
 * 追踪玩家共享同一个不可变 record，而无需在每个视觉、每次刷新时复制可变
 * {@link Quaternionf}。空物品被拒绝：动态视觉槽位必须始终携带真实显示物品，
 * 保证差量状态机（{@link DisplayVisualDiff}）无需处理空槽位迁移。</p>
 *
 * @param item         显示的 CE 物品（count 固定为 1 的展示副本）
 * @param x            世界坐标 X
 * @param y            世界坐标 Y
 * @param z            世界坐标 Z
 * @param yRot         实体 yaw（度）
 * @param xRot         实体 pitch（度）
 * @param scale        等比缩放
 * @param rotX/rotY/rotZ/rotW 左侧旋转四元数（JOML 顺序）
 * @param itemTransform ItemDisplay 的 transform 字段（NONE=0 / FIXED=8）
 */
public record DisplayVisual(Item item, double x, double y, double z,
                            float yRot, float xRot, float scale,
                            float rotX, float rotY, float rotZ, float rotW,
                            byte itemTransform) {

    public static final byte ITEM_TRANSFORM_NONE = 0;
    public static final byte ITEM_TRANSFORM_FIXED = 8;

    public DisplayVisual {
        Objects.requireNonNull(item, "item");
        if (item.isEmpty()) {
            throw new IllegalArgumentException("Visual item cannot be empty");
        }
    }

    public static DisplayVisual of(Item item, double x, double y, double z,
                                   float yRot, float xRot, float scale,
                                   Quaternionf leftRotation, byte itemTransform) {
        return new DisplayVisual(item, x, y, z, yRot, xRot, scale,
                leftRotation.x, leftRotation.y, leftRotation.z, leftRotation.w,
                itemTransform);
    }

    public Quaternionf leftRotation() {
        return new Quaternionf(rotX, rotY, rotZ, rotW);
    }
}
