package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.integration.CustomCropsBridge;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.DirectionUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.block.property.IntegerProperty;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.proxy.minecraft.core.Vec3iProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.block.BlocksProxy;
import org.bukkit.World;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enforces the structural rule that grape clusters hang below a mature vine
 * trellis. Growth, stages, bone meal, interaction, drops and persistence are
 * owned entirely by the managed CustomCrops configuration.
 */
public final class HangingGrapeCropBehavior extends BukkitBlockBehavior {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "hanging_grape_crop");
    private static final Set<Key> VINES = Set.of(
            Key.of("kaleidoscope_tavern", "grapevine_trellis"),
            Key.of("kaleidoscope_tavern", "ice_grapevine_trellis"),
            Key.of("kaleidoscope_tavern", "gold_grapevine_trellis"));
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private HangingGrapeCropBehavior(BlockDefinition block) {
        super(block);
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            BlockBehaviors.register(TYPE, (block, section) -> new HangingGrapeCropBehavior(block));
        }
    }

    @Override
    public boolean canSurvive(Object thisBlock, Object[] args) {
        Object above = LocationUtils.above(args[2]);
        Object support = BlockGetterProxy.INSTANCE.getBlockState(args[1], above);
        return isMatureVine(support);
    }

    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        // Minecraft 26.2 HangingRootsBlock only revalidates its support when
        // the UP neighbour changes. CE passes that neighbour's NMS BlockState
        // directly, so this hot path needs neither a Bukkit block lookup nor
        // a second world read.
        if (DirectionUtils.fromNMSDirection(args[updateShape$direction]) != Direction.UP
                || isMatureVine(args[updateShape$neighborState])) {
            return args[0];
        }

        Object position = args[updateShape$blockPos];
        World world = LevelProxy.INSTANCE.getWorld(args[updateShape$level]);
        if (world != null) {
            int x = Vec3iProxy.INSTANCE.getX(position);
            int y = Vec3iProxy.INSTANCE.getY(position);
            int z = Vec3iProxy.INSTANCE.getZ(position);
            // The visible block self-destructs here, so the CustomCrops record
            // must go with it or explosions/pistons leave orphaned crop data.
            CustomCropsBridge.removeCrop(world.getBlockAt(x, y, z).getLocation());
        }
        return BlocksProxy.AIR$defaultState;
    }

    private static boolean isMatureVine(Object minecraftState) {
        ImmutableBlockState state = BlockStateUtils.getNullableCustomBlockState(minecraftState);
        if (state == null || !VINES.contains(state.owner().value().id())) {
            return false;
        }
        Property<?> rawAge = state.getProperty("age");
        return rawAge instanceof IntegerProperty age && state.get(age) >= age.max;
    }
}
