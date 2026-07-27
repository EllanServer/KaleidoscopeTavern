package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.BlockShape;
import net.momirealms.craftengine.core.block.DelegatingBlock;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.proxy.minecraft.world.phys.AABBProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.shapes.ShapesProxy;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces the collision/selection delegate of CraftEngine's visual carrier
 * with the exact shapes authored by {@code ITrellis}.
 * The visual state remains non-occluding and is still rendered by an item
 * display; only the server-side shape delegate changes.
 */
public final class TrellisBlockShape implements BlockShape {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final List<String> BLOCK_IDS = List.of(
            "trellis", "grapevine_trellis", "ice_grapevine_trellis", "gold_grapevine_trellis");

    private static final Box COLLISION_VERTICAL = new Box(6, 0, 6, 10, 16, 10);
    private static final Box COLLISION_NORTH_SOUTH = new Box(6, 6, 0, 10, 10, 16);
    private static final Box COLLISION_EAST_WEST = new Box(0, 6, 6, 16, 10, 10);
    private static final Box SELECTION_VERTICAL = new Box(4, 0, 4, 12, 16, 12);
    private static final Box SELECTION_NORTH_SOUTH = new Box(4, 4, 0, 12, 12, 16);
    private static final Box SELECTION_EAST_WEST = new Box(0, 4, 4, 16, 12, 12);
    private static final Map<String, Object> SELECTION_SHAPES =
            new ConcurrentHashMap<>();
    private static final Map<String, Object> COLLISION_SHAPES =
            new ConcurrentHashMap<>();

    private final BlockShape original;
    private final Property<?> typeProperty;

    private TrellisBlockShape(BlockShape original, Property<?> typeProperty) {
        this.original = original;
        this.typeProperty = typeProperty;
    }

    /**
     * Installs one state-aware delegate per CraftEngine trellis block. All
     * property variants of a CE block share one NMS block owner, so the
     * delegate resolves the current custom state on every shape query. This is
     * called after initial project loading and after every CraftEngine reload.
     *
     * @return number of CE trellis block delegates updated
     */
    public static int install() {
        Map<Object, Key> installed = new IdentityHashMap<>();
        for (String blockId : BLOCK_IDS) {
            BlockDefinition definition = CraftEngineBlocks.byId(Key.of(PREFIX + blockId));
            if (definition == null) {
                continue;
            }
            Property<?> typeProperty = definition.getProperty("type");
            if (typeProperty == null) {
                continue;
            }
            Object minecraftState = definition.defaultState()
                    .customBlockState().minecraftState();
            Object owner = BlockStateUtils.getBlockOwner(minecraftState);
            if (!(owner instanceof DelegatingBlock delegatingBlock)) {
                continue;
            }
            Key previous = installed.putIfAbsent(owner, definition.id());
            if (previous != null) {
                throw new IllegalStateException(
                        "CraftEngine reused one trellis block delegate for "
                                + previous + " and " + definition.id());
            }
            BlockShape current = delegatingBlock.shapeDelegate().value();
            BlockShape original = current instanceof TrellisBlockShape trellis
                    ? trellis.original : current;
            delegatingBlock.shapeDelegate().bindValue(
                    new TrellisBlockShape(original, typeProperty));
        }
        return installed.size();
    }

    @Override
    public Object getShape(Object thisBlock, Object[] args) {
        String type = type(args[0]);
        return type == null ? original.getShape(thisBlock, args)
                : SELECTION_SHAPES.computeIfAbsent(
                        type, key -> combine(selectionBoxes(key)));
    }

    @Override
    public Object getCollisionShape(Object thisBlock, Object[] args) {
        String type = type(args[0]);
        return type == null ? original.getCollisionShape(thisBlock, args)
                : COLLISION_SHAPES.computeIfAbsent(
                        type, key -> combine(collisionBoxes(key)));
    }

    @Override
    public Object getSupportShape(Object thisBlock, Object[] args) {
        // Vanilla Block#getBlockSupportShape delegates to the collision shape,
        // which is also what the source TrellisBlock inherited.
        String type = type(args[0]);
        return type == null ? original.getSupportShape(thisBlock, args)
                : COLLISION_SHAPES.computeIfAbsent(
                        type, key -> combine(collisionBoxes(key)));
    }

    private String type(Object minecraftState) {
        ImmutableBlockState state = BlockStateUtils
                .getOptionalCustomBlockState(minecraftState).orElse(null);
        if (state == null || !state.propertyEntries().containsKey(typeProperty)) {
            return null;
        }
        return Property.formatValue(
                typeProperty, state.propertyEntries().get(typeProperty));
    }

    static List<Box> collisionBoxes(String type) {
        return boxes(type, COLLISION_VERTICAL, COLLISION_NORTH_SOUTH, COLLISION_EAST_WEST);
    }

    static List<Box> selectionBoxes(String type) {
        return boxes(type, SELECTION_VERTICAL, SELECTION_NORTH_SOUTH, SELECTION_EAST_WEST);
    }

    private static List<Box> boxes(String type, Box vertical, Box northSouth, Box eastWest) {
        return switch (type) {
            case "single" -> List.of(vertical);
            case "north_south" -> List.of(northSouth);
            case "east_west" -> List.of(eastWest);
            case "cross_north_south" -> List.of(vertical, northSouth);
            case "cross_east_west" -> List.of(vertical, eastWest);
            case "cross_up_down" -> List.of(northSouth, eastWest);
            case "six_direction" -> List.of(vertical, northSouth, eastWest);
            default -> throw new IllegalArgumentException("Unknown trellis type: " + type);
        };
    }

    private static Object combine(List<Box> boxes) {
        Object result = shape(boxes.getFirst());
        for (int index = 1; index < boxes.size(); index++) {
            result = ShapesProxy.INSTANCE.or(result, shape(boxes.get(index)));
        }
        return result;
    }

    private static Object shape(Box box) {
        Object bounds = AABBProxy.INSTANCE.newInstance(
                box.minX / 16.0, box.minY / 16.0, box.minZ / 16.0,
                box.maxX / 16.0, box.maxY / 16.0, box.maxZ / 16.0);
        return ShapesProxy.INSTANCE.create(bounds);
    }

    record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }
}
