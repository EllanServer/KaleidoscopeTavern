package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.block.behavior.RandomTickBlock;
import net.momirealms.craftengine.core.block.property.BooleanProperty;
import net.momirealms.craftengine.core.block.property.IntegerProperty;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.proxy.minecraft.core.Vec3iProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adds the legacy sheared growth lock on top of CraftEngine's native vine survival rules. */
public final class WildGrapevineBehavior extends BukkitBlockBehavior implements RandomTickBlock {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "wild_grapevine");
    private static final String HEAD = "kaleidoscope_tavern:wild_grapevine";
    private static final String BODY = "kaleidoscope_tavern:wild_grapevine_plant";
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private final IntegerProperty ageProperty;
    private final BooleanProperty shearedProperty;
    private final float growSpeed;
    private final int maxHeight;

    private WildGrapevineBehavior(BlockDefinition block, ConfigSection section) {
        super(block);
        this.ageProperty = (IntegerProperty) BlockBehaviorFactory.getProperty(
                section.path(), block, "age", Integer.class);
        this.shearedProperty = (BooleanProperty) BlockBehaviorFactory.getProperty(
                section.path(), block, "sheared", Boolean.class);
        this.growSpeed = section.getFloat("grow_speed", 0.15F);
        this.maxHeight = section.getInt("max_height", 16);
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            BlockBehaviors.register(TYPE, WildGrapevineBehavior::new);
        }
    }

    @Override
    public boolean canRandomlyTick(ImmutableBlockState state) {
        return !state.get(shearedProperty);
    }

    @Override
    public void randomTick(Object thisBlock, Object[] args) {
        Optional<ImmutableBlockState> optional = BlockStateUtils.getOptionalCustomBlockState(args[0]);
        if (optional.isEmpty()) {
            return;
        }
        ImmutableBlockState state = optional.get();
        if (state.get(shearedProperty) || ThreadLocalRandom.current().nextFloat() >= growSpeed) {
            return;
        }
        World world = LevelProxy.INSTANCE.getWorld(args[1]);
        if (world == null) {
            return;
        }
        Object position = args[2];
        Block block = world.getBlockAt(
                Vec3iProxy.INSTANCE.getX(position),
                Vec3iProxy.INSTANCE.getY(position),
                Vec3iProxy.INSTANCE.getZ(position));
        if (state.get(ageProperty) < ageProperty.max) {
            CraftEngineBlocks.place(block.getLocation(), state.with(ageProperty, state.get(ageProperty) + 1), false);
            return;
        }
        extend(block, state, maxHeight);
    }

    /** Extends a non-sheared head by one block; also used by bone meal interaction. */
    public static boolean extend(Block headBlock, ImmutableBlockState state, int maxHeight) {
        if (booleanProperty(state, "sheared") || !headBlock.getRelative(BlockFace.DOWN).isEmpty()
                || connectedHeight(headBlock) >= maxHeight) {
            return false;
        }
        BlockDefinition headDefinition = CraftEngineBlocks.byId(Key.of(HEAD));
        BlockDefinition bodyDefinition = CraftEngineBlocks.byId(Key.of(BODY));
        if (headDefinition == null || bodyDefinition == null) {
            return false;
        }
        ImmutableBlockState nextHead = headDefinition.defaultState();
        if (nextHead.getProperty("age") instanceof IntegerProperty nextAge) {
            int age = state.getProperty("age") instanceof IntegerProperty currentAge
                    ? Math.min(nextAge.max, state.get(currentAge) + 1) : 0;
            nextHead = nextHead.with(nextAge, age);
        }
        if (!CraftEngineBlocks.place(headBlock.getRelative(BlockFace.DOWN).getLocation(), nextHead, false)) {
            return false;
        }
        CraftEngineBlocks.place(headBlock.getLocation(), bodyDefinition.defaultState(), false);
        return true;
    }

    public static Block findHead(Block start) {
        Block cursor = start;
        for (int index = 0; index < 16; index++) {
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(cursor);
            if (state == null) {
                return null;
            }
            String id = state.owner().value().id().toString();
            if (id.equals(HEAD)) {
                return cursor;
            }
            if (!id.equals(BODY)) {
                return null;
            }
            cursor = cursor.getRelative(BlockFace.DOWN);
        }
        return null;
    }

    private static int connectedHeight(Block head) {
        int height = 0;
        Block cursor = head;
        while (height < 64) {
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(cursor);
            if (state == null) {
                break;
            }
            String id = state.owner().value().id().toString();
            if (!id.equals(HEAD) && !id.equals(BODY)) {
                break;
            }
            height++;
            cursor = cursor.getRelative(BlockFace.UP);
        }
        return height;
    }

    private static boolean booleanProperty(ImmutableBlockState state, String name) {
        return state.getProperty(name) instanceof BooleanProperty property && state.get(property);
    }
}
