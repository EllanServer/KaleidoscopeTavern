package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.block.behavior.WaterloggedBlockBehavior;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.UpdateFlags;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.libraries.antigrieflib.Flag;
import net.momirealms.craftengine.proxy.bukkit.craftbukkit.block.CraftBlockProxy;
import net.momirealms.craftengine.proxy.minecraft.server.level.ServerLevelProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelAccessorProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelWriterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.SignalGetterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.material.FluidStateProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.material.FluidsProxy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CraftEngine block implementation of the legacy wall tap.
 *
 * <p>Facing, open, redstone edge-latch and waterlogged state live on the CE
 * block. The controller owns only the source's transient 6/30-tick extraction
 * cycle; source/destination business behavior remains in {@link Handler}.</p>
 */
public final class TapBlockBehavior extends WaterloggedBlockBehavior implements EntityBlock {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "tap");
    public static final int TAKE_TICKS = 30;
    public static final int TAKE_PARTICLE_TICKS = 5;
    public static final int EMPTY_OPEN_TICKS = 6;
    public static final int DRIP_LIFETIME_TICKS = 18;

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static volatile Handler handler;

    private final Property<Direction> facingProperty;
    private final Property<Boolean> openProperty;
    private final Property<Boolean> triggeredProperty;

    private TapBlockBehavior(BlockDefinition block, ConfigSection section) {
        super(block, BlockBehaviorFactory.getProperty(
                section.path(), block, "waterlogged", Boolean.class));
        this.facingProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "facing", Direction.class);
        this.openProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "open", Boolean.class);
        this.triggeredProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "triggered", Boolean.class);
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            BlockBehaviors.register(TYPE, TapBlockBehavior::new);
        }
    }

    public static void bind(Handler value) {
        handler = value;
    }

    public static void unbind(Handler value) {
        if (handler == value) {
            handler = null;
        }
    }

    @Override
    public ImmutableBlockState updateStateForPlacement(
            BlockPlaceContext context, ImmutableBlockState state) {
        Direction clickedFace = context.getClickedFace();
        Direction facing = clickedFace.axis().isHorizontal()
                ? clickedFace : context.getHorizontalDirection().opposite();
        Object pos = LocationUtils.toBlockPos(context.getClickedPos());
        Object fluid = BlockGetterProxy.INSTANCE.getFluidState(
                context.getLevel().minecraftWorld(), pos);
        boolean waterlogged = FluidStateProxy.INSTANCE.getType(fluid) == FluidsProxy.WATER;
        return state.with(facingProperty, facing)
                .with(openProperty, false)
                .with(triggeredProperty, false)
                .with(waterloggedProperty, waterlogged);
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getHand() != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        net.momirealms.craftengine.core.entity.player.Player cePlayer = context.getPlayer();
        if (cePlayer == null) {
            return InteractionResult.PASS;
        }
        BlockPos pos = context.getClickedPos();
        World world = (World) context.getLevel().platformWorld();
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        Player bukkitPlayer = (Player) cePlayer.platformPlayer();
        if (!BukkitCraftEngine.instance().antiGriefProvider().test(
                bukkitPlayer, Flag.INTERACT, location)) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        Controller controller = controller(context.getLevel().storageWorld(), pos);
        if (state.get(openProperty)) {
            if (controller != null) {
                controller.reset();
            }
            setOpen(context.getLevel().minecraftWorld(), pos, state, false,
                    UpdateFlags.UPDATE_ALL);
            playToggleSound(location, false);
        } else {
            if (controller != null) {
                controller.begin(world.getBlockAt(pos.x(), pos.y(), pos.z()),
                        state.get(facingProperty), bukkitPlayer);
            }
            setOpen(context.getLevel().minecraftWorld(), pos, state, true,
                    UpdateFlags.UPDATE_ALL);
            playToggleSound(location, true);
        }
        cePlayer.swingHand(context.getHand());
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    @Override
    public void onPlace(Object thisBlock, Object[] args) {
        if (!ServerLevelProxy.CLASS.isInstance(args[1])) {
            return;
        }
        BlockStateUtils.getOptionalCustomBlockState(args[0])
                .ifPresent(state -> handlePower(args[1], args[2], state));
    }

    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        if (!ServerLevelProxy.CLASS.isInstance(args[1])) {
            return;
        }
        BlockStateUtils.getOptionalCustomBlockState(args[0])
                .ifPresent(state -> handlePower(args[1], args[2], state));
    }

    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        BlockStateUtils.getOptionalCustomBlockState(args[0]).ifPresent(state -> {
            if (state.get(waterloggedProperty)) {
                LevelAccessorProxy.INSTANCE.scheduleTick$1(
                        args[updateShape$level], args[updateShape$blockPos],
                        FluidsProxy.WATER, 5);
            }
        });
        return args[0];
    }

    private void handlePower(Object level, Object minecraftPos, ImmutableBlockState state) {
        boolean powered = SignalGetterProxy.INSTANCE.hasNeighborSignal(level, minecraftPos)
                || SignalGetterProxy.INSTANCE.hasNeighborSignal(
                        level, LocationUtils.above(minecraftPos));
        boolean triggered = state.get(triggeredProperty);
        if (powered && !triggered) {
            ImmutableBlockState next = state;
            if (!state.get(openProperty)) {
                Block block = CraftBlockProxy.INSTANCE.at(level, minecraftPos);
                Controller controller = controller(
                        BukkitAdaptor.adapt(block.getWorld()).storageWorld(),
                        LocationUtils.fromBlockPos(minecraftPos));
                if (controller != null) {
                    controller.begin(block, state.get(facingProperty), null);
                }
                next = next.with(openProperty, true);
                playToggleSound(block.getLocation(), true);
            }
            LevelWriterProxy.INSTANCE.setBlock(
                    level, minecraftPos,
                    next.with(triggeredProperty, true).customBlockState().minecraftState(),
                    next == state ? UpdateFlags.UPDATE_NONE : UpdateFlags.UPDATE_CLIENTS);
        } else if (!powered && triggered) {
            LevelWriterProxy.INSTANCE.setBlock(
                    level, minecraftPos,
                    state.with(triggeredProperty, false).customBlockState().minecraftState(),
                    UpdateFlags.UPDATE_NONE);
        }
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new Controller(blockEntity, this);
    }

    @Override
    public void initControllerId(int id) {
        // This behavior has one controller and retrieves it by class.
    }

    private static Controller controller(CEWorld world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntityAtIfLoaded(pos);
        return blockEntity == null ? null : blockEntity.controller.get(Controller.class, 0);
    }

    private void setOpen(Object level, BlockPos pos, ImmutableBlockState state,
                         boolean open, int flags) {
        LevelWriterProxy.INSTANCE.setBlock(
                level, LocationUtils.toBlockPos(pos),
                state.with(openProperty, open).customBlockState().minecraftState(), flags);
    }

    private static void playToggleSound(Location location, boolean open) {
        location.getWorld().playSound(location,
                open ? Sound.BLOCK_IRON_TRAPDOOR_OPEN : Sound.BLOCK_IRON_TRAPDOOR_CLOSE,
                SoundCategory.BLOCKS, 1.0F, 0.8F);
    }

    private static BlockFace bukkitFace(Direction direction) {
        return switch (direction) {
            case NORTH -> BlockFace.NORTH;
            case SOUTH -> BlockFace.SOUTH;
            case WEST -> BlockFace.WEST;
            case EAST -> BlockFace.EAST;
            default -> throw new IllegalArgumentException("Tap facing must be horizontal: " + direction);
        };
    }

    public interface Handler {
        StartResult start(Block tapBlock, BlockFace facing, @Nullable Player player);

        void finish(Block tapBlock, BlockFace facing, @Nullable Player player);
    }

    public record StartResult(boolean extracting, boolean hot) {
        public static final StartResult EMPTY = new StartResult(false, false);
    }

    private enum Cycle {
        DEFAULT,
        TAKE,
        EMPTY
    }

    private static final class Controller extends BlockEntityController {
        private final TapBlockBehavior behavior;
        private Cycle cycle = Cycle.DEFAULT;
        private int ticks;
        private boolean hot;
        private UUID actorId;

        private Controller(BlockEntity blockEntity, TapBlockBehavior behavior) {
            super(blockEntity);
            this.behavior = behavior;
        }

        private void begin(Block tapBlock, Direction facing, @Nullable Player player) {
            Handler current = handler;
            StartResult result = current == null
                    ? StartResult.EMPTY : current.start(tapBlock, bukkitFace(facing), player);
            cycle = result.extracting() ? Cycle.TAKE : Cycle.EMPTY;
            ticks = 0;
            hot = result.hot();
            actorId = player == null ? null : player.getUniqueId();
        }

        private void reset() {
            cycle = Cycle.DEFAULT;
            ticks = 0;
            hot = false;
            actorId = null;
        }

        @Override
        public void onRemove() {
            reset();
        }

        @Override
        public <C extends BlockEntityController> BlockEntityTicker<C> createBlockEntityTicker(
                CEWorld world, ImmutableBlockState blockState) {
            return createTickerHelper(Controller::tick);
        }

        private static void tick(CEWorld world, BlockPos pos, ImmutableBlockState state,
                                 Controller controller) {
            controller.tickCycle(world, pos, state);
        }

        private void tickCycle(CEWorld world, BlockPos pos, ImmutableBlockState state) {
            if (!state.get(behavior.openProperty)) {
                if (cycle != Cycle.DEFAULT) {
                    reset();
                }
                return;
            }
            if (cycle == Cycle.DEFAULT) {
                behavior.setOpen(world.world().minecraftWorld(), pos, state, false,
                        UpdateFlags.UPDATE_CLIENTS);
                return;
            }

            ticks++;
            World bukkitWorld = (World) world.world().platformWorld();
            Location drip = new Location(bukkitWorld,
                    pos.x() + 0.5, pos.y() + 0.25, pos.z() + 0.5);
            if (cycle == Cycle.EMPTY) {
                if (ticks % 2 == 0) {
                    bukkitWorld.spawnParticle(
                            Particle.CLOUD, drip, 1, 0.1, 0.1, 0.1, 0.01);
                }
                if (ticks >= EMPTY_OPEN_TICKS) {
                    behavior.setOpen(world.world().minecraftWorld(), pos, state, false,
                            UpdateFlags.UPDATE_CLIENTS);
                    playToggleSound(drip, false);
                    reset();
                }
                return;
            }

            if (ticks <= TAKE_PARTICLE_TICKS) {
                bukkitWorld.spawnParticle(
                        hot ? Particle.DRIPPING_LAVA : Particle.DRIPPING_WATER,
                        drip, 1, 0, 0, 0, 0);
            }
            if (ticks <= TAKE_PARTICLE_TICKS + DRIP_LIFETIME_TICKS) {
                bukkitWorld.spawnParticle(
                        hot ? Particle.FALLING_DRIPSTONE_LAVA
                                : Particle.FALLING_DRIPSTONE_WATER,
                        drip, 1, 0, 0, 0, 0);
            }
            if (ticks < TAKE_TICKS) {
                return;
            }

            Direction facing = state.get(behavior.facingProperty);
            UUID finishingActor = actorId;
            reset();
            behavior.setOpen(world.world().minecraftWorld(), pos, state, false,
                    UpdateFlags.UPDATE_CLIENTS);
            playToggleSound(drip, false);
            Handler current = handler;
            if (current != null) {
                current.finish(
                        bukkitWorld.getBlockAt(pos.x(), pos.y(), pos.z()),
                        bukkitFace(facing),
                        finishingActor == null ? null : Bukkit.getPlayer(finishingActor));
            }
        }
    }
}
