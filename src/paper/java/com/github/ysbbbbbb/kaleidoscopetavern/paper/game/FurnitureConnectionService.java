package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Keeps connected sofas, counters, tables and cabinets in their legacy variants. */
public final class FurnitureConnectionService implements Listener {
    private static final String BAR_COUNTER = "kaleidoscope_tavern:bar_counter";
    private static final String TABLE = "kaleidoscope_tavern:table";
    private static final List<String> LINEAR_FURNITURE = List.of(
            TABLE,
            "kaleidoscope_tavern:bar_cabinet",
            "kaleidoscope_tavern:glass_bar_cabinet",
            "kaleidoscope_tavern:cellar_cabinet");
    private final JavaPlugin plugin;

    public FurnitureConnectionService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(FurniturePlaceEvent event) {
        if (isManaged(event.furniture())) {
            scheduleRefresh(event.location());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(FurnitureBreakEvent event) {
        if (isManaged(event.furniture())) {
            scheduleRefresh(event.location());
        }
    }

    private void scheduleRefresh(Location center) {
        Location captured = center.clone();
        Bukkit.getScheduler().runTask(plugin, () -> refresh(captured));
    }

    private static void refresh(Location center) {
        World world = center.getWorld();
        List<BukkitFurniture> furniture = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(center, 3.25, 1.25, 3.25)) {
            if (!CraftEngineFurniture.isFurniture(entity)) {
                continue;
            }
            BukkitFurniture found = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (found != null && found.isValid() && isManaged(found)) {
                furniture.add(found);
            }
        }

        Map<GridPosition, BukkitFurniture> byPosition = new HashMap<>();
        furniture.forEach(entry -> byPosition.put(GridPosition.of(entry.location()), entry));
        // Corner states depend on adjacent corner states, so converge over a few cheap passes.
        for (int pass = 0; pass < 4; pass++) {
            boolean changed = false;
            for (BukkitFurniture entry : furniture) {
                String variant;
                if (isLinear(entry)) {
                    variant = linearVariant(entry, byPosition);
                } else {
                    String connection = connectionFor(entry, byPosition);
                    variant = connection.equals("single")
                            ? "ground" : "ground_connection_" + connection;
                }
                if (!entry.currentVariant().name().equals(variant)) {
                    changed |= entry.setVariant(variant, true);
                }
            }
            if (!changed) {
                break;
            }
        }
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

    private static boolean isManaged(BukkitFurniture furniture) {
        String id = furniture.id().toString();
        return id.endsWith("_sofa") || id.equals(BAR_COUNTER) || LINEAR_FURNITURE.contains(id);
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

    private record GridPosition(int x, int y, int z) {
        private static GridPosition of(Location location) {
            return new GridPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        private GridPosition relative(BlockFace face) {
            return new GridPosition(x + face.getModX(), y + face.getModY(), z + face.getModZ());
        }
    }
}
