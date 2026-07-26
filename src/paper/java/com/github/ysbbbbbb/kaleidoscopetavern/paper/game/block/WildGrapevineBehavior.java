package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import net.momirealms.antigrieflib.Flag;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.block.behavior.VineCropBodyBlockBehavior;
import net.momirealms.craftengine.bukkit.block.behavior.VineCropHeadBlockBehavior;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.block.behavior.BonemealableBlock;
import net.momirealms.craftengine.core.block.behavior.RandomTickBlock;
import net.momirealms.craftengine.core.block.property.BooleanProperty;
import net.momirealms.craftengine.core.block.property.IntegerProperty;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemKeys;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.proxy.minecraft.core.Vec3iProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adds the legacy sheared growth lock on top of CraftEngine's native vine survival rules. */
public final class WildGrapevineBehavior extends BukkitBlockBehavior
        implements BonemealableBlock, RandomTickBlock {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "wild_grapevine");
    private static final String HEAD = "kaleidoscope_tavern:wild_grapevine";
    private static final String BODY = "kaleidoscope_tavern:wild_grapevine_plant";
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private final VineCropHeadBlockBehavior headDelegate;
    private final VineCropBodyBlockBehavior bodyDelegate;
    private final IntegerProperty ageProperty;
    private final BooleanProperty shearedProperty;
    private final float growSpeed;

    private WildGrapevineBehavior(BlockDefinition block, ConfigSection section) {
        super(block);
        String id = block.id().toString();
        if (HEAD.equals(id)) {
            this.headDelegate = VineCropHeadBlockBehavior.FACTORY.create(block, section);
            this.bodyDelegate = null;
            this.ageProperty = this.headDelegate.ageProperty;
            this.shearedProperty = (BooleanProperty) BlockBehaviorFactory.getProperty(
                    section.path(), block, "sheared", Boolean.class);
            this.growSpeed = section.getFloat("grow_speed", 0.15F);
        } else if (BODY.equals(id)) {
            this.headDelegate = null;
            this.bodyDelegate = VineCropBodyBlockBehavior.FACTORY.create(block, section);
            this.ageProperty = null;
            this.shearedProperty = null;
            this.growSpeed = 0F;
        } else {
            throw new IllegalArgumentException("Unsupported wild grapevine block: " + id);
        }
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            BlockBehaviors.register(TYPE, WildGrapevineBehavior::new);
        }
    }

    @Override
    public boolean canRandomlyTick(ImmutableBlockState state) {
        return headDelegate != null
                && !state.get(shearedProperty)
                && state.get(ageProperty) < ageProperty.max;
    }

    @Override
    public void randomTick(Object thisBlock, Object[] args) {
        if (headDelegate == null) {
            return;
        }
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
        if (state.get(ageProperty) >= ageProperty.max) {
            return;
        }
        extend(block, state);
    }

    @Override
    public void tick(Object thisBlock, Object[] args) {
        lifecycle().tick(thisBlock, args);
    }

    @Override
    public void onPlace(Object thisBlock, Object[] args) {
        lifecycle().onPlace(thisBlock, args);
    }

    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        return lifecycle().updateShape(thisBlock, args);
    }

    @Override
    public boolean canSurvive(Object thisBlock, Object[] args) {
        return lifecycle().canSurvive(thisBlock, args);
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        Item item = context.getItem();
        Player player = context.getPlayer();
        if (ItemUtils.isEmpty(item) || !item.vanillaId().equals(ItemKeys.BONE_MEAL)
                || player == null || player.isAdventureMode()) {
            return InteractionResult.PASS;
        }
        BlockPos pos = context.getClickedPos();
        Location location = new Location((World) context.getLevel().platformWorld(),
                pos.x(), pos.y(), pos.z());
        if (!BukkitCraftEngine.instance().antiGriefProvider().test(
                (org.bukkit.entity.Player) player.platformPlayer(), Flag.INTERACT, location)) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        Block clicked = location.getBlock();
        Block head = headDelegate != null ? clicked : findHead(clicked);
        ImmutableBlockState headState = head == null ? null : CraftEngineBlocks.getCustomBlockState(head);
        if (headState == null || !canExtend(head, headState)) {
            return InteractionResult.PASS;
        }
        player.swingHand(context.getHand());
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean isValidBonemealTarget(Object thisBlock, Object[] args) {
        if (bodyDelegate != null) {
            return bodyDelegate.isValidBonemealTarget(thisBlock, args);
        }
        Optional<ImmutableBlockState> optional = BlockStateUtils.getOptionalCustomBlockState(args[2]);
        Block head = blockAt(args[0], args[1]);
        return optional.isPresent() && head != null && canExtend(head, optional.get());
    }

    @Override
    public boolean isBonemealSuccess(Object thisBlock, Object[] args) {
        return bodyDelegate == null || bodyDelegate.isBonemealSuccess(thisBlock, args);
    }

    @Override
    public void performBonemeal(Object thisBlock, Object[] args) {
        if (bodyDelegate != null) {
            bodyDelegate.performBonemeal(thisBlock, args);
            return;
        }
        Optional<ImmutableBlockState> optional = BlockStateUtils.getOptionalCustomBlockState(args[3]);
        Block head = blockAt(args[0], args[2]);
        if (optional.isPresent() && head != null) {
            extend(head, optional.get());
        }
    }

    /** Extends a non-sheared head by one block; also used by bone meal interaction. */
    public static boolean extend(Block headBlock, ImmutableBlockState state) {
        if (!canExtend(headBlock, state)) {
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

    private static boolean canExtend(Block headBlock, ImmutableBlockState state) {
        return HEAD.equals(state.owner().value().id().toString())
                && !booleanProperty(state, "sheared")
                && headBlock.getY() > headBlock.getWorld().getMinHeight()
                && headBlock.getRelative(BlockFace.DOWN).isEmpty();
    }

    public static Block findHead(Block start) {
        Block cursor = start;
        int worldHeight = start.getWorld().getMaxHeight() - start.getWorld().getMinHeight();
        for (int index = 0; index < worldHeight && cursor.getY() >= start.getWorld().getMinHeight(); index++) {
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

    private static Block blockAt(Object level, Object position) {
        World world = LevelProxy.INSTANCE.getWorld(level);
        if (world == null) {
            return null;
        }
        return world.getBlockAt(
                Vec3iProxy.INSTANCE.getX(position),
                Vec3iProxy.INSTANCE.getY(position),
                Vec3iProxy.INSTANCE.getZ(position));
    }

    private BukkitBlockBehavior lifecycle() {
        return headDelegate != null ? headDelegate : bodyDelegate;
    }

    private static boolean booleanProperty(ImmutableBlockState state, String name) {
        return state.getProperty(name) instanceof BooleanProperty property && state.get(property);
    }
}
