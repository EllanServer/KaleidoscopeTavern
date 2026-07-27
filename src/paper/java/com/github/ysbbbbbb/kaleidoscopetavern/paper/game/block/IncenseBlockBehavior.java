package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
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
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.libraries.antigrieflib.Flag;
import net.momirealms.craftengine.proxy.minecraft.server.level.ServerLevelProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelWriterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.SignalGetterProxy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.ZombieVillager;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CraftEngine block implementation of the legacy incense block.
 *
 * <p>The block state itself owns facing, manual/open state and the redstone
 * edge latch. A lightweight CE block-entity ticker preserves the source's
 * client animate-tick particles and six-second undead pulse without furniture
 * entities, PDC state or a global furniture polling bridge.</p>
 */
public final class IncenseBlockBehavior extends BukkitBlockBehavior implements EntityBlock {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "incense");
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final DamageSource MAGIC_DAMAGE =
            DamageSource.builder(DamageType.MAGIC).build();

    private static Tag<EntityType> undeadTag;

    private final Property<Boolean> openProperty;
    private final Property<Boolean> poweredProperty;
    private final Particle smallParticle;
    private final Particle largeParticle;
    private final double largeParticleYOffset;
    private final double largeParticleYRange;

    private IncenseBlockBehavior(BlockDefinition block, ConfigSection section) {
        super(block);
        this.openProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "open", Boolean.class);
        this.poweredProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "powered", Boolean.class);
        this.smallParticle = particle(section.getNonEmptyString("small_particle"));
        this.largeParticle = particle(section.getNonEmptyString("large_particle"));
        this.largeParticleYOffset = section.getDouble("large_particle_y_offset", -2.0);
        this.largeParticleYRange = section.getDouble("large_particle_y_range", 16.0);
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            BlockBehaviors.register(TYPE, IncenseBlockBehavior::new);
        }
    }

    @Override
    public ImmutableBlockState updateStateForPlacement(
            BlockPlaceContext context, ImmutableBlockState state) {
        Object level = context.getLevel().minecraftWorld();
        boolean powered = SignalGetterProxy.INSTANCE.hasNeighborSignal(
                level, LocationUtils.toBlockPos(context.getClickedPos()));
        return state.with(openProperty, powered).with(poweredProperty, powered);
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        Player player = context.getPlayer();
        BlockPos pos = context.getClickedPos();
        net.momirealms.craftengine.core.world.World world = context.getLevel();
        Location location = new Location((World) world.platformWorld(), pos.x(), pos.y(), pos.z());
        if (player != null && !BukkitCraftEngine.instance().antiGriefProvider().test(
                (org.bukkit.entity.Player) player.platformPlayer(), Flag.INTERACT, location)) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        boolean open = !state.get(openProperty);
        LevelWriterProxy.INSTANCE.setBlock(
                world.minecraftWorld(), LocationUtils.toBlockPos(pos),
                state.with(openProperty, open).customBlockState().minecraftState(),
                UpdateFlags.UPDATE_CLIENTS);
        playToggleSound(location, open);
        Optional.ofNullable(player).ifPresent(value -> value.swingHand(context.getHand()));
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        Object level = args[1];
        if (!ServerLevelProxy.CLASS.isInstance(level)) {
            return;
        }
        Optional<ImmutableBlockState> optional =
                BlockStateUtils.getOptionalCustomBlockState(args[0]);
        if (optional.isEmpty()) {
            return;
        }
        ImmutableBlockState state = optional.get();
        boolean powered = SignalGetterProxy.INSTANCE.hasNeighborSignal(level, args[2]);
        if (powered == state.get(poweredProperty)) {
            return;
        }

        boolean wasOpen = state.get(openProperty);
        ImmutableBlockState next = state.with(poweredProperty, powered);
        if (wasOpen != powered) {
            next = next.with(openProperty, powered);
        }
        LevelWriterProxy.INSTANCE.setBlock(
                level, args[2], next.customBlockState().minecraftState(),
                UpdateFlags.UPDATE_CLIENTS);
        if (wasOpen != powered) {
            World world = (World) net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy
                    .INSTANCE.getWorld(level);
            if (world != null) {
                playToggleSound(new Location(world,
                        net.momirealms.craftengine.proxy.minecraft.core.Vec3iProxy.INSTANCE.getX(args[2]),
                        net.momirealms.craftengine.proxy.minecraft.core.Vec3iProxy.INSTANCE.getY(args[2]),
                        net.momirealms.craftengine.proxy.minecraft.core.Vec3iProxy.INSTANCE.getZ(args[2])),
                        powered);
            }
        }
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new Controller(blockEntity, this);
    }

    @Override
    public void initControllerId(int id) {
        // This behavior owns exactly one controller and never looks it up by id.
    }

    private static void playToggleSound(Location location, boolean open) {
        location.getWorld().playSound(location,
                open ? "minecraft:block.stone_button.click_on"
                        : "minecraft:block.stone_button.click_off",
                SoundCategory.BLOCKS, 1.0F, 1.0F);
    }

    private void tick(CEWorld ceWorld, BlockPos pos, ImmutableBlockState state) {
        World world = (World) ceWorld.world().platformWorld();
        if (world == null) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextInt(49) == 0) {
            spawnParticles(world, pos, state.get(openProperty), random);
        }
        if (state.get(openProperty) && world.getGameTime() % 120L == 0L) {
            hurtNearbyUndead(world, pos);
        }
    }

    private void spawnParticles(World world, BlockPos pos, boolean open,
                                ThreadLocalRandom random) {
        Location center = new Location(world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5);
        if (random.nextInt(3) == 0) {
            world.spawnParticle(smallParticle, center, 0,
                    random.nextGaussian() * 0.01,
                    0.02 + random.nextDouble() * 0.01,
                    random.nextGaussian() * 0.01, 1);
        }
        if (!open) {
            return;
        }
        for (int index = 0; index < 5; index++) {
            Location point = center.clone().add(
                    random.nextDouble(-16.0, 16.0),
                    largeParticleYOffset + random.nextDouble() * largeParticleYRange,
                    random.nextDouble(-16.0, 16.0));
            world.spawnParticle(largeParticle, point, 1, 0, 0, 0, 0);
        }
    }

    private static void hurtNearbyUndead(World world, BlockPos pos) {
        Tag<EntityType> undead = undeadTag();
        if (undead == null) {
            return;
        }
        Location center = new Location(world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5);
        for (LivingEntity living : world.getNearbyLivingEntities(center, 32.5)) {
            if (living.isDead() || !undead.isTagged(living.getType())) {
                continue;
            }
            living.damage(1.0, MAGIC_DAMAGE);
            if (living instanceof ZombieVillager zombieVillager
                    && zombieVillager.getHealth() <= 1.0) {
                zombieVillager.setConversionPlayer(null);
                zombieVillager.setConversionTime(60);
            }
        }
    }

    private static Tag<EntityType> undeadTag() {
        if (undeadTag == null) {
            undeadTag = Bukkit.getTag(Tag.REGISTRY_ENTITY_TYPES,
                    NamespacedKey.minecraft("undead"), EntityType.class);
        }
        return undeadTag;
    }

    private static Particle particle(String name) {
        try {
            return Particle.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return Particle.END_ROD;
        }
    }

    private static final class Controller extends BlockEntityController {
        private final IncenseBlockBehavior behavior;

        private Controller(BlockEntity blockEntity, IncenseBlockBehavior behavior) {
            super(blockEntity);
            this.behavior = behavior;
        }

        @Override
        public <C extends BlockEntityController> BlockEntityTicker<C> createBlockEntityTicker(
                CEWorld world, ImmutableBlockState blockState) {
            return createTickerHelper(Controller::tick);
        }

        private static void tick(CEWorld world, BlockPos pos, ImmutableBlockState state,
                                 Controller controller) {
            controller.behavior.tick(world, pos, state);
        }
    }
}
