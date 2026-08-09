package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.integration.CustomCropsBridge;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.block.property.IntegerProperty;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.proxy.minecraft.core.Vec3iProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.block.BlocksProxy;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enforces the structural rule that grape clusters hang below a mature vine
 * trellis. Growth, stages, bone meal, interaction, drops and persistence are
 * owned entirely by the managed CustomCrops configuration.
 */
public final class HangingGrapeCropBehavior extends BukkitBlockBehavior {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "hanging_grape_crop");
    private static final Set<String> VINES = Set.of(
            "kaleidoscope_tavern:grapevine_trellis",
            "kaleidoscope_tavern:ice_grapevine_trellis",
            "kaleidoscope_tavern:gold_grapevine_trellis");
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
        World world = LevelProxy.INSTANCE.getWorld(args[1]);
        if (world == null) {
            return false;
        }
        Object position = args[2];
        return hasMatureVineAbove(world,
                Vec3iProxy.INSTANCE.getX(position),
                Vec3iProxy.INSTANCE.getY(position),
                Vec3iProxy.INSTANCE.getZ(position));
    }

    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        World world = LevelProxy.INSTANCE.getWorld(args[updateShape$level]);
        if (world == null) {
            return args[0];
        }
        Object position = args[updateShape$blockPos];
        int x = Vec3iProxy.INSTANCE.getX(position);
        int y = Vec3iProxy.INSTANCE.getY(position);
        int z = Vec3iProxy.INSTANCE.getZ(position);
        if (hasMatureVineAbove(world, x, y, z)) {
            return args[0];
        }
        // The visible block self-destructs here, so the CustomCrops record
        // must go with it or explosions/pistons leave orphaned crop data.
        CustomCropsBridge.removeCrop(world.getBlockAt(x, y, z).getLocation());
        return BlocksProxy.AIR$defaultState;
    }

    private static boolean hasMatureVineAbove(World world, int x, int y, int z) {
        Block above = world.getBlockAt(x, y, z).getRelative(BlockFace.UP);
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(above);
        if (state == null || !VINES.contains(state.owner().value().id().toString())) {
            return false;
        }
        Property<?> rawAge = state.getProperty("age");
        return rawAge instanceof IntegerProperty age && state.get(age) >= age.max;
    }
}
