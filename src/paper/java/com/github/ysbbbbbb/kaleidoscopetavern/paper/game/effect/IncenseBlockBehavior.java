package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.effect;

import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
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
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
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
    private static final int MEAN_PARTICLE_INTERVAL = 49;
    private static final int PARTICLE_DELAY_BOUND = MEAN_PARTICLE_INTERVAL * 2 - 1;
    private static final int DAMAGE_INTERVAL = 120;
    private static final int PARTICLE_PHASE_SALT = 0x4B1D_5EED;
    private static final int DAMAGE_PHASE_SALT = 0x51A7_0F1D;
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
            Controller.prewarm();
            BlockBehaviors.register(TYPE, IncenseBlockBehavior::new);
        }
    }

    /** Moves Bukkit's lazy entity-tag bridge off the first damage pulse. */
    public static void prewarmRuntime() {
        undeadTag();
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
            World world = net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy
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

    private void tick(CEWorld ceWorld, BlockPos pos, ImmutableBlockState state,
                      Controller controller) {
        World world = (World) ceWorld.world().platformWorld();
        if (world == null) {
            return;
        }

        boolean particleDue = controller.takeParticleDue();
        boolean damageDue = controller.takeDamageDue();
        if (!particleDue && !damageDue) {
            return;
        }

        boolean open = state.get(openProperty);
        if (particleDue) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            controller.scheduleNextParticle(random);
            spawnParticles(world, pos, open, random);
        }
        if (damageDue && open) {
            hurtNearbyUndead(world, pos);
        }
    }

    private void spawnParticles(World world, BlockPos pos, boolean open,
                                ThreadLocalRandom random) {
        double centerX = pos.x() + 0.5;
        double centerY = pos.y() + 0.5;
        double centerZ = pos.z() + 0.5;
        if (random.nextInt(3) == 0) {
            world.spawnParticle(smallParticle, centerX, centerY, centerZ, 0,
                    random.nextGaussian() * 0.01,
                    0.02 + random.nextDouble() * 0.01,
                    random.nextGaussian() * 0.01, 1);
        }
        if (!open) {
            return;
        }
        for (int index = 0; index < 5; index++) {
            world.spawnParticle(
                    largeParticle,
                    centerX + random.nextDouble(-16.0, 16.0),
                    centerY + largeParticleYOffset
                            + random.nextDouble() * largeParticleYRange,
                    centerZ + random.nextDouble(-16.0, 16.0),
                    1, 0, 0, 0, 0);
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

    private static final class Controller extends BlockEntityController
            implements BlockEntityTicker<Controller> {
        private final IncenseBlockBehavior behavior;
        private int particleDelay;
        private int damageDelay;

        private Controller(BlockEntity blockEntity, IncenseBlockBehavior behavior) {
            super(blockEntity);
            this.behavior = behavior;
            int positionHash = blockEntity.pos.hashCode();
            this.particleDelay = Math.floorMod(
                    positionHash ^ PARTICLE_PHASE_SALT, MEAN_PARTICLE_INTERVAL);
            this.damageDelay = Math.floorMod(
                    Integer.rotateLeft(positionHash, 13) ^ DAMAGE_PHASE_SALT,
                    DAMAGE_INTERVAL);
        }

        private static void prewarm() {
            // Loads the controller and ticker interface before the first live
            // incense block entity is constructed.
        }

        private boolean takeParticleDue() {
            if (particleDelay > 0) {
                particleDelay--;
                return false;
            }
            return true;
        }

        private void scheduleNextParticle(ThreadLocalRandom random) {
            // Uniform 1..97 tick gaps preserve the previous mean interval of
            // 49 ticks while reducing random draws by roughly 49x.
            particleDelay = random.nextInt(PARTICLE_DELAY_BOUND);
        }

        private boolean takeDamageDue() {
            if (damageDelay > 0) {
                damageDelay--;
                return false;
            }
            damageDelay = DAMAGE_INTERVAL - 1;
            return true;
        }

        @Override
        public <C extends BlockEntityController> BlockEntityTicker<C> createBlockEntityTicker(
                CEWorld world, ImmutableBlockState blockState) {
            return createTickerHelper(this);
        }

        @Override
        public void tick(CEWorld world, BlockPos pos, ImmutableBlockState state,
                         Controller controller) {
            controller.behavior.tick(world, pos, state, controller);
        }
    }
}
