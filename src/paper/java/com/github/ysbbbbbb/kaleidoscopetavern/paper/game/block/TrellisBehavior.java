package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.integration.CustomCropsBridge;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.BlockStateWrapper;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.block.behavior.BonemealableBlock;
import net.momirealms.craftengine.core.block.behavior.RandomTickBlock;
import net.momirealms.craftengine.core.block.property.IntegerProperty;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemKeys;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.libraries.antigrieflib.Flag;
import net.momirealms.craftengine.proxy.minecraft.core.MutableBlockPosProxy;
import net.momirealms.craftengine.proxy.minecraft.core.Vec3iProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelAccessorProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.material.FluidStateProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.material.FluidsProxy;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adds the connection and mature-vine propagation rules that are specific to
 * Kaleidoscope Tavern's trellis. Fruit lifecycle is delegated to CustomCrops;
 * this behavior only models the supporting vine structure.
 */
public final class TrellisBehavior extends BukkitBlockBehavior
        implements BonemealableBlock, RandomTickBlock {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "trellis");
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final String PLAIN_TRELLIS = PREFIX + "trellis";
    private static final Set<String> TRELLISES = Set.of(
            PLAIN_TRELLIS,
            PREFIX + "grapevine_trellis",
            PREFIX + "ice_grapevine_trellis",
            PREFIX + "gold_grapevine_trellis");
    private static final List<BlockFace> GROW_DIRECTIONS = List.of(
            BlockFace.UP, BlockFace.EAST, BlockFace.WEST, BlockFace.SOUTH, BlockFace.NORTH);
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private final Property<Direction.Axis> axisProperty;
    private final Property<String> typeProperty;
    private final Property<Boolean> waterloggedProperty;
    private final IntegerProperty ageProperty;
    private final float spreadChance;

    private TrellisBehavior(BlockDefinition block, ConfigSection section) {
        super(block);
        this.axisProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "axis", Direction.Axis.class);
        this.typeProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "type", String.class);
        this.waterloggedProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "waterlogged", Boolean.class);
        Property<?> age = block.getProperty("age");
        this.ageProperty = age instanceof IntegerProperty integer ? integer : null;
        this.spreadChance = section.getFloat("spread_chance", 0.25F);
    }

    /** Must run from the plugin's onLoad, before CraftEngine parses projects. */
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            BlockBehaviors.register(TYPE, TrellisBehavior::new);
        }
    }

    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        BlockPos position = context.getClickedPos();
        boolean x = axisHasTrellis(context, position, Direction.Axis.X);
        boolean y = axisHasTrellis(context, position, Direction.Axis.Y);
        boolean z = axisHasTrellis(context, position, Direction.Axis.Z);
        // CE's hard-coded axis property behavior runs before configured block
        // behaviors and has already copied the clicked face axis into state.
        // Keep that native placement axis as the permanent base member while
        // Tavern adds only the neighbouring trellis connections.
        String type = typeFor(axisName(state.get(axisProperty)), x, y, z);
        Object fluid = BlockGetterProxy.INSTANCE.getFluidState(
                context.getLevel().minecraftWorld(), LocationUtils.toBlockPos(position));
        return state.with(typeProperty, type).with(
                waterloggedProperty,
                FluidStateProxy.INSTANCE.getType(fluid) == FluidsProxy.WATER);
    }

    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        Optional<ImmutableBlockState> optional = BlockStateUtils.getOptionalCustomBlockState(args[0]);
        if (optional.isEmpty()) {
            return args[0];
        }
        World world = LevelProxy.INSTANCE.getWorld(args[updateShape$level]);
        if (world == null) {
            return args[0];
        }
        Object position = args[updateShape$blockPos];
        int x = Vec3iProxy.INSTANCE.getX(position);
        int y = Vec3iProxy.INSTANCE.getY(position);
        int z = Vec3iProxy.INSTANCE.getZ(position);
        ImmutableBlockState state = optional.get();
        if (state.get(waterloggedProperty)) {
            LevelAccessorProxy.INSTANCE.scheduleTick$1(
                    args[updateShape$level], args[updateShape$blockPos],
                    FluidsProxy.WATER, 5);
        }
        boolean xConnected = axisHasTrellis(world, x, y, z, Direction.Axis.X);
        boolean yConnected = axisHasTrellis(world, x, y, z, Direction.Axis.Y);
        boolean zConnected = axisHasTrellis(world, x, y, z, Direction.Axis.Z);
        String baseAxis = axisName(state.get(axisProperty));
        String updated = typeFor(baseAxis, xConnected, yConnected, zConnected);
        return state.with(typeProperty, updated).customBlockState().minecraftState();
    }

    /**
     * Combines CE's native placement axis with every axis that has an adjacent
     * trellis. The base axis is never discarded merely because it has no
     * neighbour, so a vertical placement cannot collapse into a horizontal
     * shape during CE's immediate placement update.
     */
    static String typeFor(String baseAxis, boolean xConnected,
                          boolean yConnected, boolean zConnected) {
        if (!baseAxis.equals("x") && !baseAxis.equals("y") && !baseAxis.equals("z")) {
            throw new IllegalArgumentException("Unknown trellis axis: " + baseAxis);
        }
        boolean x = xConnected || baseAxis.equals("x");
        boolean y = yConnected || baseAxis.equals("y");
        boolean z = zConnected || baseAxis.equals("z");
        if (x && y && z) {
            return "six_direction";
        }
        if (x && y) {
            return "cross_east_west";
        }
        if (y && z) {
            return "cross_north_south";
        }
        if (x && z) {
            return "cross_up_down";
        }
        if (x) {
            return "east_west";
        }
        if (z) {
            return "north_south";
        }
        if (y) {
            return "single";
        }
        throw new IllegalStateException("A valid trellis axis must produce a shape");
    }

    @Override
    public boolean canRandomlyTick(ImmutableBlockState state) {
        // GrapevineTrellisBlock keeps random ticking at every age. Immature
        // single vines gain one age; immature horizontal vines jump to max.
        return ageProperty != null;
    }

    @Override
    public void randomTick(Object thisBlock, Object[] args) {
        Optional<ImmutableBlockState> optional = BlockStateUtils.getOptionalCustomBlockState(args[0]);
        if (optional.isEmpty() || ageProperty == null) {
            return;
        }
        ImmutableBlockState state = optional.get();
        Object level = args[1];
        World world = LevelProxy.INSTANCE.getWorld(level);
        if (world == null) {
            return;
        }
        Object position = args[2];
        int x = Vec3iProxy.INSTANCE.getX(position);
        int y = Vec3iProxy.INSTANCE.getY(position);
        int z = Vec3iProxy.INSTANCE.getZ(position);
        String id = state.owner().value().id().toString();
        float chance = adjustedChance(level, x, y, z, id);
        if (ThreadLocalRandom.current().nextFloat() >= chance) {
            return;
        }
        Location location = new Location(world, x, y, z);
        GrapeSeasonSemantics.Plant plant = GrapeSeasonSemantics.plantForTrellis(id);
        // SereneSeasons parity: only random ticks are season-gated; the bone
        // meal chain (performBonemeal -> grow) intentionally bypasses this.
        if (plant != null && !GrapeSeasonGate.permitsRandomGrowth(plant, location)) {
            return;
        }
        grow(location, state, level, position);
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
        if (!canGrow(location, state)) {
            return InteractionResult.PASS;
        }
        // The client only sees the trellis carrier block, so mirror CE's crop
        // behavior and acknowledge the successful use without cancelling the
        // vanilla BoneMealItem path that performs growth and consumption.
        player.swingHand(context.getHand());
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean isValidBonemealTarget(Object thisBlock, Object[] args) {
        Optional<ImmutableBlockState> optional = BlockStateUtils.getOptionalCustomBlockState(args[2]);
        Location location = location(args[0], args[1]);
        return optional.isPresent() && location != null && canGrow(location, optional.get());
    }

    @Override
    public boolean isBonemealSuccess(Object thisBlock, Object[] args) {
        return true;
    }

    @Override
    public void performBonemeal(Object thisBlock, Object[] args) {
        Optional<ImmutableBlockState> optional = BlockStateUtils.getOptionalCustomBlockState(args[3]);
        Location location = location(args[0], args[2]);
        if (optional.isPresent() && location != null) {
            grow(location, optional.get());
        }
    }

    /** Exact {@code GrapevineTrellisBlock.doGrow} step used by random ticks and bone meal. */
    public static boolean grow(Location location, ImmutableBlockState source) {
        return grow(location, source, null, null);
    }

    private static boolean grow(Location location, ImmutableBlockState source,
                                Object level, Object sourcePosition) {
        Property<?> rawAge = source.getProperty("age");
        if (!(rawAge instanceof IntegerProperty age)) {
            return false;
        }
        int currentAge = source.get(age);
        if (currentAge < age.max) {
            Property<?> rawType = source.getProperty("type");
            String type = rawType == null ? "" : formatted(source, rawType);
            int nextAge = type.equals("single") ? currentAge + 1 : age.max;
            return CraftEngineBlocks.place(location, source.with(age, nextAge), false);
        }

        // RandomTickBlock already receives the NMS level and position. Reuse
        // one mutable position for all neighbour probes instead of routing
        // every direction through CraftEngineBlocks' Bukkit conversion, which
        // allocates a BlockPos proxy for each lookup. Bone meal continues to
        // use the public Bukkit-compatible path.
        Object mutablePosition = level == null || sourcePosition == null
                ? null : MutableBlockPosProxy.INSTANCE.newInstance();
        for (BlockFace direction : GROW_DIRECTIONS) {
            Block target = location.getBlock().getRelative(direction);
            ImmutableBlockState targetState;
            if (mutablePosition == null) {
                targetState = CraftEngineBlocks.getCustomBlockState(target);
            } else {
                Object targetPosition = MutableBlockPosProxy.INSTANCE.setWithOffset(
                        mutablePosition, sourcePosition,
                        direction.getModX(), direction.getModY(), direction.getModZ());
                targetState = BlockStateUtils.getOptionalCustomBlockState(
                        BlockGetterProxy.INSTANCE.getBlockState(level, targetPosition))
                        .orElse(null);
            }
            if (targetState == null || !PLAIN_TRELLIS.equals(id(targetState)) || bool(targetState, "waxed")) {
                continue;
            }
            ImmutableBlockState grown = source.owner().value().defaultState();
            grown = copyNamed(targetState, grown, "axis");
            grown = copyNamed(targetState, grown, "type");
            grown = copyNamed(targetState, grown, "waterlogged");
            grown = withNamed(grown, "age", direction == BlockFace.UP ? "0" : Integer.toString(age.max));
            return CraftEngineBlocks.place(target.getLocation(), grown, false);
        }

        Block below = location.getBlock().getRelative(BlockFace.DOWN);
        if (!below.getType().isAir() || below.getY() < location.getWorld().getMinHeight()) {
            return false;
        }
        return CustomCropsBridge.placeHangingGrapes(below.getLocation(), id(source));
    }

    private static boolean canGrow(Location location, ImmutableBlockState source) {
        Property<?> rawAge = source.getProperty("age");
        if (!(rawAge instanceof IntegerProperty age)) {
            return false;
        }
        if (source.get(age) < age.max) {
            return true;
        }
        for (BlockFace direction : GROW_DIRECTIONS) {
            ImmutableBlockState targetState = CraftEngineBlocks.getCustomBlockState(
                    location.getBlock().getRelative(direction));
            if (targetState != null && PLAIN_TRELLIS.equals(id(targetState))
                    && !bool(targetState, "waxed")) {
                return true;
            }
        }
        Block below = location.getBlock().getRelative(BlockFace.DOWN);
        return below.getY() >= location.getWorld().getMinHeight() && below.getType().isAir();
    }

    /** Kept as an explicit parity marker for the source mature-growth branch. */
    public static boolean growMature(Location location, ImmutableBlockState source) {
        Property<?> rawAge = source.getProperty("age");
        return rawAge instanceof IntegerProperty age
                && source.get(age) >= age.max
                && grow(location, source);
    }

    private float adjustedChance(Object level, int x, int y, int z, String id) {
        double temperature = BiomeTemperature.at(level, x, y, z);
        if (id.equals(PREFIX + "ice_grapevine_trellis") && temperature < 0.15D) {
            return Math.max(spreadChance, 0.8F);
        }
        if (id.equals(PREFIX + "gold_grapevine_trellis") && temperature > 1.0D) {
            return Math.max(spreadChance, 0.8F);
        }
        return spreadChance;
    }

    private static Location location(Object level, Object position) {
        World world = LevelProxy.INSTANCE.getWorld(level);
        if (world == null) {
            return null;
        }
        return new Location(world,
                Vec3iProxy.INSTANCE.getX(position),
                Vec3iProxy.INSTANCE.getY(position),
                Vec3iProxy.INSTANCE.getZ(position));
    }

    private static boolean axisHasTrellis(BlockPlaceContext context, BlockPos position, Direction.Axis axis) {
        return isTrellis(context.getLevel().getBlockState(position.relative(axis.getPositive())))
                || isTrellis(context.getLevel().getBlockState(position.relative(axis.getNegative())));
    }

    private static boolean axisHasTrellis(World world, int x, int y, int z, Direction.Axis axis) {
        Direction positive = axis.getPositive();
        Direction negative = axis.getNegative();
        return isTrellis(world.getBlockAt(x + positive.stepX(), y + positive.stepY(), z + positive.stepZ()))
                || isTrellis(world.getBlockAt(x + negative.stepX(), y + negative.stepY(), z + negative.stepZ()));
    }

    private static boolean isTrellis(BlockStateWrapper state) {
        return state != null && TRELLISES.contains(state.ownerId().toString());
    }

    private static boolean isTrellis(Block block) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        return state != null && TRELLISES.contains(id(state));
    }

    private static String axisName(Direction.Axis axis) {
        return switch (axis) {
            case X -> "x";
            case Y -> "y";
            case Z -> "z";
        };
    }

    private static String id(ImmutableBlockState state) {
        return state.owner().value().id().toString();
    }

    private static boolean bool(ImmutableBlockState state, String propertyName) {
        Property<?> property = state.getProperty(propertyName);
        return property != null && Boolean.TRUE.equals(state.propertyEntries().get(property));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String formatted(ImmutableBlockState state, Property property) {
        Comparable value = state.propertyEntries().get(property);
        return Property.formatValue(property, value);
    }

    private static ImmutableBlockState copyNamed(ImmutableBlockState from, ImmutableBlockState to, String name) {
        Property<?> sourceProperty = from.getProperty(name);
        if (sourceProperty == null || to.getProperty(name) == null) {
            return to;
        }
        Comparable<?> value = from.propertyEntries().get(sourceProperty);
        return withNamed(to, name, Property.formatValue(sourceProperty, value));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ImmutableBlockState withNamed(ImmutableBlockState state, String name, String valueName) {
        Property property = state.getProperty(name);
        if (property == null) {
            return state;
        }
        Comparable value = property.valueByName(valueName);
        return value == null ? state : state.with(property, value);
    }
}
