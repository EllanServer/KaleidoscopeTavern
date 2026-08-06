package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.LifecycleFurnitureBehavior;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.player.Player;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Keeps connected sofas, counters, tables and cabinets in their legacy variants. */
public final class FurnitureConnectionService {
    private static final String BAR_COUNTER = "kaleidoscope_tavern:bar_counter";
    private static final String TABLE = "kaleidoscope_tavern:table";
    private static final List<String> LINEAR_FURNITURE = List.of(
            TABLE,
            "kaleidoscope_tavern:bar_cabinet",
            "kaleidoscope_tavern:glass_bar_cabinet");
    private final JavaPlugin plugin;
    private final LifecycleFurnitureBehavior.Handler lifecycleHandler;

    public FurnitureConnectionService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.lifecycleHandler = new LifecycleFurnitureBehavior.Handler() {
            @Override
            public void onReady(BukkitFurniture furniture,
                                LifecycleFurnitureBehavior.ReadyReason reason) {
                scheduleRefresh(furniture.location());
            }

            @Override
            public void onReady(BukkitFurniture furniture,
                                LifecycleFurnitureBehavior.ReadyReason reason,
                                Player placingPlayer) {
                if (reason == LifecycleFurnitureBehavior.ReadyReason.PLACE) {
                    scheduleRefresh(furniture.location(), furniture, placingPlayer);
                } else {
                    scheduleRefresh(furniture.location());
                }
            }

            @Override
            public void onUnavailable(BukkitFurniture furniture,
                                      boolean removed, boolean stopping) {
                if (removed) {
                    scheduleRefresh(furniture.location());
                }
            }
        };
    }

    public void start() {
        LifecycleFurnitureBehavior.bind(
                LifecycleFurnitureBehavior.Channel.CONNECTION, lifecycleHandler);
    }

    public void stop() {
        LifecycleFurnitureBehavior.unbind(
                LifecycleFurnitureBehavior.Channel.CONNECTION, lifecycleHandler);
    }

    private void scheduleRefresh(Location center) {
        scheduleRefresh(center, null, null);
    }

    private void scheduleRefresh(Location center, BukkitFurniture placed,
                                 Player placingPlayer) {
        Location captured = center.clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            boolean placedVariantChanged = refresh(captured, placed);
            if (placedVariantChanged && placingPlayer != null) {
                // setVariant rebuilds CE hitboxes. During placement the player can still be
                // absent from CE's trackedBy snapshot, so send the final interaction ids once.
                placed.snapshotState().showHitboxes(placingPlayer);
            }
        });
    }

    private static boolean refresh(Location center, BukkitFurniture placed) {
        List<BukkitFurniture> furniture = LifecycleFurnitureBehavior.nearby(
                LifecycleFurnitureBehavior.Channel.CONNECTION, center, 3.25, 1.25);

        Map<GridPosition, BukkitFurniture> byPosition = new HashMap<>();
        furniture.forEach(entry -> byPosition.put(GridPosition.of(entry.location()), entry));
        boolean placedVariantChanged = false;
        // Corner states depend on adjacent corner states, so converge over a few cheap passes.
        for (int pass = 0; pass < 4; pass++) {
            boolean changed = false;
            for (BukkitFurniture entry : furniture) {
                String variant;
                if (entry.id().toString().equals(TABLE)) {
                    variant = tableVariant(entry, byPosition);
                } else if (isLinear(entry)) {
                    variant = linearVariant(entry, byPosition);
                } else {
                    String connection = connectionFor(entry, byPosition);
                    variant = connection.equals("single")
                            ? "ground" : "ground_connection_" + connection;
                }
                if (!entry.currentVariant().name().equals(variant)) {
                    boolean variantChanged = entry.setVariant(variant, true);
                    changed |= variantChanged;
                    placedVariantChanged |= variantChanged && entry == placed;
                }
            }
            if (!changed) {
                break;
            }
        }
        return placedVariantChanged;
    }

    private static String linearVariant(BukkitFurniture self, Map<GridPosition, BukkitFurniture> byPosition) {
        BlockFace facing = facing(self.location().getYaw());
        GridPosition position = GridPosition.of(self.location());
        boolean clockwise = isLinearNeighbor(self, byPosition.get(position.relative(clockwise(facing))), facing);
        boolean counterClockwise = isLinearNeighbor(
                self, byPosition.get(position.relative(counterClockwise(facing))), facing);
        String positionName;
        if (clockwise && counterClockwise) {
            positionName = self.id().toString().equals(TABLE) ? "2" : "middle";
        } else if (clockwise) {
            positionName = self.id().toString().equals(TABLE) ? "3" : "right";
        } else if (counterClockwise) {
            positionName = self.id().toString().equals(TABLE) ? "1" : "left";
        } else {
            return "ground";
        }
        return "ground_position_" + positionName;
    }

    private static String tableVariant(BukkitFurniture self, Map<GridPosition, BukkitFurniture> byPosition) {
        BlockFace facing = facing(self.location().getYaw());
        GridPosition position = GridPosition.of(self.location());

        // TableBlock stores AXIS in world coordinates. Furniture yaw is only a
        // placement detail and must not turn LEFT/RIGHT into a local-space
        // concept: two tables placed while looking at one another would then
        // select the same endpoint texture and render back-to-back.
        boolean east = isTableNeighbor(
                byPosition.get(position.relative(BlockFace.EAST)), HorizontalAxis.X);
        boolean west = isTableNeighbor(
                byPosition.get(position.relative(BlockFace.WEST)), HorizontalAxis.X);
        boolean south = isTableNeighbor(
                byPosition.get(position.relative(BlockFace.SOUTH)), HorizontalAxis.Z);
        boolean north = isTableNeighbor(
                byPosition.get(position.relative(BlockFace.NORTH)), HorizontalAxis.Z);

        HorizontalAxis current = tableWorldAxis(self);
        HorizontalAxis lateralAxis = worldAxis(clockwise(facing));
        HorizontalAxis longitudinalAxis = worldAxis(facing);
        HorizontalAxis selected;
        if (current == HorizontalAxis.X && (east || west)) {
            selected = HorizontalAxis.X;
        } else if (current == HorizontalAxis.Z && (south || north)) {
            selected = HorizontalAxis.Z;
        } else if (hasTableNeighbor(lateralAxis, east, west, south, north)) {
            // TableBlock#getStateForPlacement checks the axis lateral to the
            // player first, but stores the selected axis in world space.
            selected = lateralAxis;
        } else if (hasTableNeighbor(longitudinalAxis, east, west, south, north)) {
            selected = longitudinalAxis;
        } else {
            return tableVariantName(facing, null, 0);
        }

        int sourcePosition = selected == HorizontalAxis.X
                ? tableSourcePosition(east, west)
                : tableSourcePosition(south, north);
        return tableVariantName(facing, selected, sourcePosition);
    }

    private static boolean hasTableNeighbor(HorizontalAxis axis,
                                            boolean east, boolean west,
                                            boolean south, boolean north) {
        return axis == HorizontalAxis.X ? east || west : south || north;
    }

    /**
     * Source POSITION values are world-directional: positive X/Z is LEFT (1),
     * negative X/Z is RIGHT (3). They must not be derived from furniture yaw.
     */
    static int tableSourcePosition(boolean positiveNeighbor, boolean negativeNeighbor) {
        if (positiveNeighbor && negativeNeighbor) return 2;
        if (positiveNeighbor) return 1;
        if (negativeNeighbor) return 3;
        throw new IllegalArgumentException("A connected table requires at least one neighbour");
    }

    /**
     * SOUTH is the authored zero-yaw furniture orientation. Other cardinal
     * facings select CE variants whose element yaw cancels the furniture yaw,
     * keeping the archived world-aligned tabletop texture continuous.
     */
    static String tableVariantName(BlockFace facing, HorizontalAxis axis,
                                   int sourcePosition) {
        String base = axis == null
                ? "ground"
                : "ground_axis_" + axis.name().toLowerCase(Locale.ROOT)
                + "_position_" + sourcePosition;
        return facing == BlockFace.SOUTH
                ? base
                : base + "_facing_" + facing.name().toLowerCase(Locale.ROOT);
    }

    private static boolean isTableNeighbor(BukkitFurniture other, HorizontalAxis desiredAxis) {
        if (other == null || !other.id().toString().equals(TABLE)) {
            return false;
        }
        HorizontalAxis otherAxis = tableWorldAxis(other);
        return otherAxis == null || otherAxis == desiredAxis;
    }

    private static HorizontalAxis tableWorldAxis(BukkitFurniture furniture) {
        String variant = furniture.currentVariant().name();
        HorizontalAxis encoded;
        if (variant.contains("_axis_x_")) {
            encoded = HorizontalAxis.X;
        } else if (variant.contains("_axis_z_")) {
            encoded = HorizontalAxis.Z;
        } else {
            return null;
        }

        // New directional variants already encode the source world axis. The
        // unsuffixed variants also occur on old saves, where X/Z was local to
        // furniture yaw; convert those once so the next refresh migrates them.
        if (variant.contains("_facing_")) {
            return encoded;
        }
        BlockFace facing = facing(furniture.location().getYaw());
        return encoded == HorizontalAxis.X
                ? worldAxis(clockwise(facing)) : worldAxis(facing);
    }

    private static boolean isLinearNeighbor(BukkitFurniture self, BukkitFurniture other, BlockFace facing) {
        return other != null && self.id().equals(other.id()) && facing(other.location().getYaw()) == facing;
    }

    private static String connectionFor(BukkitFurniture self, Map<GridPosition, BukkitFurniture> byPosition) {
        BlockFace facing = facing(self.location().getYaw());
        BlockFace left = clockwise(facing);
        BlockFace right = counterClockwise(facing);
        GridPosition position = GridPosition.of(self.location());
        BukkitFurniture leftFurniture = byPosition.get(position.relative(left));
        BukkitFurniture rightFurniture = byPosition.get(position.relative(right));
        BukkitFurniture frontFurniture = byPosition.get(position.relative(facing));

        boolean leftConnected = leftConnected(self, leftFurniture, facing);
        boolean rightConnected = rightConnected(self, rightFurniture, facing);
        boolean frontLeft = frontLeftConnected(self, frontFurniture, facing);
        boolean frontRight = frontRightConnected(self, frontFurniture, facing);

        if (leftConnected && rightConnected) return "middle";
        if (frontLeft) return rightConnected ? "left" : "right_corner";
        if (frontRight) return leftConnected ? "right" : "left_corner";
        if (leftConnected) return "right";
        if (rightConnected) return "left";
        return "single";
    }

    private static boolean leftConnected(BukkitFurniture self, BukkitFurniture other, BlockFace facing) {
        if (!sameType(self, other)) return false;
        BlockFace check = facing(other.location().getYaw());
        if (check == counterClockwise(facing)) {
            String connection = connection(other);
            return connection.equals("single") || connection.equals("right")
                    || connection.equals("right_corner");
        }
        return check == facing;
    }

    private static boolean rightConnected(BukkitFurniture self, BukkitFurniture other, BlockFace facing) {
        if (!sameType(self, other)) return false;
        BlockFace check = facing(other.location().getYaw());
        if (check == clockwise(facing)) {
            String connection = connection(other);
            return connection.equals("single") || connection.equals("left")
                    || connection.equals("left_corner");
        }
        return check == facing;
    }

    private static boolean frontLeftConnected(BukkitFurniture self, BukkitFurniture other, BlockFace facing) {
        return sameType(self, other)
                && facing(other.location().getYaw()) == clockwise(facing)
                && !connection(other).equals("left_corner");
    }

    private static boolean frontRightConnected(BukkitFurniture self, BukkitFurniture other, BlockFace facing) {
        return sameType(self, other)
                && facing(other.location().getYaw()) == counterClockwise(facing)
                && !connection(other).equals("right_corner");
    }

    private static String connection(BukkitFurniture furniture) {
        String variant = furniture.currentVariant().name();
        String prefix = "ground_connection_";
        return variant.startsWith(prefix) ? variant.substring(prefix.length()) : "single";
    }

    private static boolean sameType(BukkitFurniture first, BukkitFurniture second) {
        if (second == null) return false;
        String firstId = first.id().toString();
        String secondId = second.id().toString();
        return firstId.endsWith("_sofa") && secondId.endsWith("_sofa")
                || firstId.equals(BAR_COUNTER) && secondId.equals(BAR_COUNTER);
    }

    private static boolean isLinear(BukkitFurniture furniture) {
        return LINEAR_FURNITURE.contains(furniture.id().toString());
    }

    private static BlockFace facing(float rawYaw) {
        float yaw = (rawYaw % 360F + 360F) % 360F;
        if (yaw < 45F || yaw >= 315F) return BlockFace.SOUTH;
        if (yaw < 135F) return BlockFace.WEST;
        if (yaw < 225F) return BlockFace.NORTH;
        return BlockFace.EAST;
    }

    private static BlockFace clockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> throw new IllegalArgumentException("Not horizontal: " + face);
        };
    }

    private static BlockFace counterClockwise(BlockFace face) {
        return clockwise(clockwise(clockwise(face)));
    }

    private static BlockFace opposite(BlockFace face) {
        return clockwise(clockwise(face));
    }

    private static HorizontalAxis worldAxis(BlockFace face) {
        return face == BlockFace.EAST || face == BlockFace.WEST
                ? HorizontalAxis.X : HorizontalAxis.Z;
    }

    enum HorizontalAxis {
        X,
        Z
    }

    private record GridPosition(int x, int y, int z) {
        private static GridPosition of(Location location) {
            return new GridPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        private GridPosition relative(BlockFace face) {
            return new GridPosition(x + face.getModX(), y + face.getModY(), z + face.getModZ());
        }
    }
}
