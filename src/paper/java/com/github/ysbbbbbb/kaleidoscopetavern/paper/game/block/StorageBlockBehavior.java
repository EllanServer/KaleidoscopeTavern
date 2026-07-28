package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.StorageSemantics;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.VirtualEntityIdentity;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.DirectionUtils;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.UpdateFlags;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.render.element.BlockEntityElement;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.chunk.CEChunk;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.libraries.antigrieflib.Flag;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.server.level.ServerLevelProxy;
import net.momirealms.craftengine.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypesProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelWriterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.SignalGetterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Source-compatible CE block implementation for the four bottle racks that
 * were real Forge blocks: cellar cabinet, tilted rack, circular rack and
 * holder.
 *
 * <p>Facing, connection position and the redstone edge latch live in the CE
 * block state. The CE block entity owns exact one-item slots, persistence and
 * packet-only ItemDisplay visuals, so no furniture/PDC scan is involved.</p>
 */
public final class StorageBlockBehavior extends BukkitBlockBehavior implements EntityBlock {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "storage");
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final int CIRCULAR_PARTICLE_CHANCE = 49 * 8;
    private static volatile Handler handler;

    private final StorageSemantics.Kind kind;
    private final int slots;
    private final String dataKey;
    private final Property<Direction> facingProperty;
    private final Property<Boolean> poweredProperty;
    private final Property<String> positionProperty;

    private StorageBlockBehavior(BlockDefinition block, ConfigSection section) {
        super(block);
        this.kind = StorageSemantics.Kind.valueOf(
                section.getNonEmptyString("kind").toUpperCase(Locale.ROOT));
        this.slots = section.getInt("slots", 0);
        if (slots < 1) {
            throw new IllegalArgumentException(
                    "Storage block slots must be positive at " + section.assemblePath("slots"));
        }
        this.dataKey = section.getNonEmptyString("data_key");
        this.facingProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "facing", Direction.class);
        this.poweredProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "powered", Boolean.class);
        this.positionProperty = BlockBehaviorFactory.getOptionalProperty(
                block, "position", String.class);
        if (kind == StorageSemantics.Kind.CELLAR_CABINET && positionProperty == null) {
            throw new IllegalArgumentException(
                    "Cellar cabinet storage requires a position property at " + section.path());
        }
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            VirtualEntityIdentity.prewarm();
            BlockBehaviors.register(TYPE, StorageBlockBehavior::new);
        }
    }

    public static void bind(Handler value) {
        handler = Objects.requireNonNull(value, "value");
    }

    public static void unbind(Handler value) {
        if (handler == value) {
            handler = null;
        }
    }

    @Override
    public ImmutableBlockState updateStateForPlacement(
            BlockPlaceContext context, ImmutableBlockState state) {
        BlockPos pos = context.getClickedPos();
        Object level = context.getLevel().minecraftWorld();
        ImmutableBlockState next = state.with(poweredProperty,
                SignalGetterProxy.INSTANCE.hasNeighborSignal(level, LocationUtils.toBlockPos(pos)));
        if (positionProperty == null) {
            return next;
        }

        Direction facing = next.get(facingProperty);
        boolean left = isMatchingCellar(
                context.getLevel().storageWorld().getBlockStateAtIfLoaded(
                        pos.relative(facing.clockWise())), facing);
        boolean right = isMatchingCellar(
                context.getLevel().storageWorld().getBlockStateAtIfLoaded(
                        pos.relative(facing.counterClockWise())), facing);
        return next.with(positionProperty, cellarPosition(left, right));
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getHand() != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        Player cePlayer = context.getPlayer();
        if (cePlayer == null) {
            return InteractionResult.PASS;
        }

        BlockPos pos = context.getClickedPos();
        World world = (World) context.getLevel().platformWorld();
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        if (!BukkitCraftEngine.instance().antiGriefProvider().test(
                (org.bukkit.entity.Player) cePlayer.platformPlayer(),
                Flag.OPEN_CONTAINER, location)) {
            return InteractionResult.FAIL;
        }

        Controller controller = controller(context.getLevel().storageWorld(), pos);
        if (controller == null) {
            return InteractionResult.PASS;
        }
        int slot = clickedSlot(context, state.get(facingProperty));
        if (slot < 0 || slot >= slots) {
            return InteractionResult.FAIL;
        }
        Handler current = handler;
        if (current == null) {
            return InteractionResult.FAIL;
        }
        return current.interact(controller, context, slot);
    }

    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        if (!ServerLevelProxy.CLASS.isInstance(args[1])) {
            return;
        }
        BlockStateUtils.getOptionalCustomBlockState(args[0])
                .ifPresent(state -> handlePower(args[1], args[2], state));
    }

    private void handlePower(Object level, Object minecraftPos, ImmutableBlockState state) {
        boolean powered = SignalGetterProxy.INSTANCE.hasNeighborSignal(level, minecraftPos);
        boolean wasPowered = state.get(poweredProperty);
        if (powered == wasPowered) {
            return;
        }

        if (powered) {
            World bukkitWorld = LevelProxy.INSTANCE.getWorld(level);
            if (bukkitWorld != null) {
                Controller controller = controller(
                        BukkitAdaptor.adapt(bukkitWorld).storageWorld(),
                        LocationUtils.fromBlockPos(minecraftPos));
                Handler current = handler;
                if (controller != null && current != null) {
                    current.launch(controller);
                }
            }
        }
        LevelWriterProxy.INSTANCE.setBlock(
                level, minecraftPos,
                state.with(poweredProperty, powered).customBlockState().minecraftState(),
                UpdateFlags.UPDATE_CLIENTS);
    }

    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        if (positionProperty == null) {
            return super.updateShape(thisBlock, args);
        }
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0])
                .orElse(null);
        if (state == null) {
            return args[0];
        }

        Direction facing = state.get(facingProperty);
        Direction direction = DirectionUtils.fromNMSDirection(args[updateShape$direction]);
        Direction left = facing.clockWise();
        Direction right = facing.counterClockWise();
        if (direction != left && direction != right) {
            return args[0];
        }

        ImmutableBlockState neighbor = BlockStateUtils.getOptionalCustomBlockState(
                args[updateShape$neighborState]).orElse(null);
        boolean connected = isMatchingCellar(neighbor, facing);
        String position = state.get(positionProperty);
        String next = position;
        if (direction == left) {
            if (connected) {
                next = switch (position) {
                    case "single" -> "right";
                    case "left" -> "middle";
                    default -> position;
                };
            } else {
                next = switch (position) {
                    case "right" -> "single";
                    case "middle" -> "left";
                    default -> position;
                };
            }
        } else if (connected) {
            next = switch (position) {
                case "single" -> "left";
                case "right" -> "middle";
                default -> position;
            };
        } else {
            next = switch (position) {
                case "left" -> "single";
                case "middle" -> "right";
                default -> position;
            };
        }
        return next.equals(position)
                ? args[0]
                : state.with(positionProperty, next).customBlockState().minecraftState();
    }

    @Override
    public Object playerWillDestroy(Object thisBlock, Object[] args) {
        org.bukkit.entity.Player player = ServerPlayerProxy.INSTANCE.getBukkitEntity(args[3]);
        if (player == null || player.getGameMode() != GameMode.CREATIVE) {
            return args[2];
        }
        World world = LevelProxy.INSTANCE.getWorld(args[0]);
        if (world != null) {
            Controller controller = controller(
                    BukkitAdaptor.adapt(world).storageWorld(),
                    LocationUtils.fromBlockPos(args[1]));
            if (controller != null) {
                controller.suppressContentDrops();
            }
        }
        return args[2];
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new Controller(blockEntity, this);
    }

    @Override
    public void initControllerId(int id) {
        // This behavior owns one controller and retrieves it by class.
    }

    private int clickedSlot(UseOnContext context, Direction facing) {
        if (kind == StorageSemantics.Kind.CELLAR_CABINET
                && context.getClickedFace() != facing) {
            return -1;
        }
        BlockPos pos = context.getClickedPos();
        Vec3d hit = context.getClickedLocation();
        double relativeX = hit.x - pos.x();
        double relativeY = hit.y - pos.y();
        double relativeZ = hit.z - pos.z();
        double localX = switch (facing) {
            case NORTH -> 1.0 - relativeX;
            case SOUTH -> relativeX;
            case EAST -> 1.0 - relativeZ;
            case WEST -> relativeZ;
            default -> 0.5;
        };
        double localZ = switch (facing) {
            case NORTH -> relativeZ;
            case SOUTH -> 1.0 - relativeZ;
            case EAST -> 1.0 - relativeX;
            case WEST -> relativeX;
            default -> 0.5;
        };
        return switch (kind) {
            case CELLAR_CABINET -> {
                int column = ((int) (localX * 3)) % 3;
                int row = 2 - ((int) (relativeY * 3)) % 3;
                yield column + row * 3;
            }
            case TILTED_RACK -> localX < 1.0 / 3.0 ? 0
                    : localX < 2.0 / 3.0 ? 1 : 2;
            case CIRCULAR_RACK -> circularSlot(localX, localZ);
            case HOLDER -> 0;
            default -> -1;
        };
    }

    private static int circularSlot(double localX, double localZ) {
        double angle = Math.toDegrees(Math.atan2(localZ - 0.5, localX - 0.5));
        angle = (angle + 360) % 360;
        if (angle > 300) {
            return 5;
        }
        if (angle > 240) {
            return 0;
        }
        if (angle > 180) {
            return 1;
        }
        if (angle > 120) {
            return 2;
        }
        return angle > 60 ? 3 : 4;
    }

    private boolean isMatchingCellar(ImmutableBlockState state, Direction facing) {
        return state != null
                && state.owner().value().id().equals(blockDefinition.id())
                && state.get(facingProperty) == facing;
    }

    private static String cellarPosition(boolean left, boolean right) {
        if (left && right) {
            return "middle";
        }
        if (left) {
            return "right";
        }
        return right ? "left" : "single";
    }

    private static Controller controller(CEWorld world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntityAtIfLoaded(pos);
        return blockEntity == null ? null : blockEntity.controller.get(Controller.class, 0);
    }

    public interface Handler {
        InteractionResult interact(Controller controller, UseOnContext context, int slot);

        void launch(Controller controller);

        Item visualItem(Controller controller, int slot);
    }

    public static final class Controller extends BlockEntityController {
        private final StorageBlockBehavior behavior;
        private final Item[] items;
        private final Item[] cachedVisuals;
        private final boolean[] visualsDirty;
        private final StorageVisualElement element;
        private ImmutableBlockState renderState;
        private int occupiedSlots;
        private boolean dropContents = true;

        private Controller(BlockEntity blockEntity, StorageBlockBehavior behavior) {
            super(blockEntity);
            this.behavior = behavior;
            this.items = new Item[behavior.slots];
            this.cachedVisuals = new Item[behavior.slots];
            this.visualsDirty = new boolean[behavior.slots];
            Arrays.fill(items, Item.empty());
            Arrays.fill(visualsDirty, true);
            this.element = new StorageVisualElement(this, behavior.slots);
        }

        public Key id() {
            return behavior.blockDefinition.id();
        }

        public StorageSemantics.Kind kind() {
            return behavior.kind;
        }

        public int slots() {
            return items.length;
        }

        public Item item(int slot) {
            return validSlot(slot) ? items[slot] : Item.empty();
        }

        public boolean put(int slot, Item item) {
            if (!validSlot(slot) || item == null || item.isEmpty() || !items[slot].isEmpty()) {
                return false;
            }
            items[slot] = item.copyWithCount(1);
            occupiedSlots++;
            changed(slot);
            return true;
        }

        public Item take(int slot) {
            if (!validSlot(slot) || items[slot].isEmpty()) {
                return Item.empty();
            }
            Item taken = items[slot];
            items[slot] = Item.empty();
            occupiedSlots--;
            changed(slot);
            return taken;
        }

        public boolean hasAny() {
            return occupiedSlots != 0;
        }

        public Direction facing() {
            return effectiveState().get(behavior.facingProperty);
        }

        public BlockPos pos() {
            return blockEntity.pos;
        }

        public World world() {
            return (World) blockEntity.world.world().platformWorld();
        }

        public Location location() {
            BlockPos pos = pos();
            return new Location(world(), pos.x() + 0.5, pos.y(), pos.z() + 0.5);
        }

        @Override
        public boolean hasElement() {
            return true;
        }

        @Override
        public void gatherElements(Consumer<BlockEntityElement> consumer) {
            consumer.accept(element);
        }

        @Override
        public void preBlockStateChange(ImmutableBlockState newState) {
            Direction oldFacing = blockEntity.blockState.get(behavior.facingProperty);
            Direction newFacing = newState.get(behavior.facingProperty);
            if (oldFacing == newFacing || blockEntity.world == null) {
                return;
            }
            renderState = newState;
            updateTrackedPlayers();
            renderState = null;
        }

        @Override
        public void loadCustomData(CompoundTag tag) {
            Arrays.fill(items, Item.empty());
            occupiedSlots = 0;
            CompoundTag data = tag.getCompound(behavior.dataKey);
            if (data == null) {
                invalidateVisuals();
                return;
            }
            int dataVersion = data.getInt(
                    "data_version", Config.itemDataFixerUpperFallbackVersion());
            for (int slot = 0; slot < items.length; slot++) {
                Tag itemTag = data.get("slot_" + slot);
                if (itemTag != null) {
                    items[slot] = ItemStackUtils.wrap(
                            ItemStackUtils.parseMinecraftItem(itemTag, dataVersion));
                    if (!items[slot].isEmpty()) {
                        occupiedSlots++;
                    }
                }
            }
            invalidateVisuals();
        }

        @Override
        public void saveCustomData(CompoundTag tag) {
            if (!hasAny()) {
                return;
            }
            CompoundTag data = new CompoundTag();
            data.putInt("data_version", VersionHelper.WORLD_VERSION);
            for (int slot = 0; slot < items.length; slot++) {
                Item item = items[slot];
                if (item != null && !item.isEmpty()) {
                    data.put("slot_" + slot,
                            ItemStackUtils.saveMinecraftItemStackAsTag(item.minecraftItem()));
                }
            }
            tag.put(behavior.dataKey, data);
        }

        @Override
        public void onRemove() {
            if (dropContents && blockEntity.world != null) {
                Vec3d center = Vec3d.atCenterOf(blockEntity.pos);
                for (Item item : items) {
                    if (item != null && !item.isEmpty()) {
                        blockEntity.world.world().dropItemNaturally(center, item);
                    }
                }
            }
            Arrays.fill(items, Item.empty());
            occupiedSlots = 0;
            invalidateVisuals();
            dropContents = true;
        }

        @Override
        public <C extends BlockEntityController> BlockEntityTicker<C> createBlockEntityTicker(
                CEWorld world, ImmutableBlockState blockState) {
            return behavior.kind == StorageSemantics.Kind.CIRCULAR_RACK
                    ? createTickerHelper(Controller::tickCircularRack) : null;
        }

        private static void tickCircularRack(
                CEWorld world, BlockPos pos, ImmutableBlockState state, Controller controller) {
            if (!controller.hasAny()
                    || ThreadLocalRandom.current().nextInt(CIRCULAR_PARTICLE_CHANCE) != 0) {
                return;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            World bukkitWorld = (World) world.world().platformWorld();
            double x = random.nextBoolean()
                    ? pos.x() + 0.125 + random.nextDouble(0, 0.25)
                    : pos.x() + 0.875 - random.nextDouble(0, 0.25);
            double z = random.nextBoolean()
                    ? pos.z() + 0.125 + random.nextDouble(0, 0.25)
                    : pos.z() + 0.875 - random.nextDouble(0, 0.25);
            Location point = new Location(
                    bukkitWorld, x, pos.y() + random.nextDouble(), z);
            bukkitWorld.spawnParticle(
                    Particle.END_ROD, point, 0, 0.01, 0.01, 0.01, 1);
        }

        private void suppressContentDrops() {
            dropContents = false;
        }

        private boolean validSlot(int slot) {
            return slot >= 0 && slot < items.length;
        }

        private void changed(int slot) {
            cachedVisuals[slot] = null;
            visualsDirty[slot] = true;
            if (blockEntity.world == null) {
                return;
            }
            blockEntity.world.blockEntityChanged(blockEntity.pos);
            updateTrackedPlayers();
        }

        private void invalidateVisuals() {
            Arrays.fill(cachedVisuals, null);
            Arrays.fill(visualsDirty, true);
        }

        private Item visualItem(int slot) {
            if (!validSlot(slot)) {
                return Item.empty();
            }
            if (visualsDirty[slot]) {
                Handler current = handler;
                Item visual = current == null ? null : current.visualItem(this, slot);
                cachedVisuals[slot] = visual == null ? Item.empty() : visual;
                visualsDirty[slot] = false;
            }
            return cachedVisuals[slot];
        }

        private ImmutableBlockState effectiveState() {
            return renderState == null ? blockEntity.blockState : renderState;
        }

        private void updateTrackedPlayers() {
            CEChunk chunk = blockEntity.world.getChunkAtIfLoaded(blockEntity.pos);
            if (chunk == null) {
                return;
            }
            for (Player tracked : chunk.getTrackedBy()) {
                element.update(tracked);
            }
        }
    }

    private static final class StorageVisualElement implements BlockEntityElement {
        private static final float VIEW_RANGE = 1.25F;

        private final Controller controller;
        private final int[] entityIds;
        private final UUID[] entityUuids;
        private final Object removePacket;

        private StorageVisualElement(Controller controller, int slots) {
            this.controller = controller;
            this.entityIds = new int[slots];
            this.entityUuids = new UUID[slots];
            for (int slot = 0; slot < slots; slot++) {
                entityIds[slot] = EntityUtils.ENTITY_COUNTER.incrementAndGet();
                entityUuids[slot] = VirtualEntityIdentity.fromEntityId(entityIds[slot]);
            }
            this.removePacket = ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance(
                    new IntArrayList(entityIds));
        }

        @Override
        public void show(Player player) {
            sendVisuals(player, false);
        }

        @Override
        public void hide(Player player) {
            player.sendPacket(removePacket, false);
        }

        @Override
        public void update(Player player) {
            sendVisuals(player, true);
        }

        private void sendVisuals(Player player, boolean replace) {
            List<Object> packets = new ArrayList<>(
                    entityIds.length * 2 + (replace ? 1 : 0));
            if (replace) {
                packets.add(removePacket);
            }
            boolean facingAxisX = controller.facing().axis() == Direction.Axis.X;
            for (int slot = 0; slot < entityIds.length; slot++) {
                Item item = controller.visualItem(slot);
                if (item == null || item.isEmpty()) {
                    continue;
                }
                StorageSemantics.Visual visual = StorageSemantics.visual(
                        controller.kind(), slot, false, facingAxisX);
                RenderPosition position = renderPosition(controller, visual);
                packets.add(ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
                        entityIds[slot], entityUuids[slot],
                        position.x(), position.y(), position.z(),
                        visual.xRot(), position.yRot(),
                        EntityTypesProxy.ITEM_DISPLAY, 0, Vec3Proxy.ZERO, 0));

                List<Object> metadata = new ArrayList<>(3);
                DisplayData.ItemDisplayData.ItemStack.addEntityData(
                        item.minecraftItem(), metadata);
                DisplayData.ItemDisplayData.Scale.addEntityDataIfNotDefaultValue(
                        new Vector3f(visual.scale()), metadata);
                DisplayData.ItemDisplayData.ViewRange.addEntityDataIfNotDefaultValue(
                        (float) (VIEW_RANGE * player.displayEntityViewDistance()), metadata);
                packets.add(ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(
                        entityIds[slot], metadata));
            }
            if (!packets.isEmpty()) {
                player.sendPackets(packets, false);
            }
        }
    }

    private static RenderPosition renderPosition(
            Controller controller, StorageSemantics.Visual visual) {
        BlockPos pos = controller.pos();
        Direction facing = controller.facing();
        float facingYaw = StorageSemantics.correctedFacingYaw(
                controller.kind(), facingAngle(facing), facing.axis() == Direction.Axis.X);
        double angle = Math.toRadians(facingYaw);
        double dx = visual.centerX() - 0.5;
        double dz = visual.centerZ() - 0.5;
        double rotatedX = Math.cos(angle) * dx + Math.sin(angle) * dz;
        double rotatedZ = -Math.sin(angle) * dx + Math.cos(angle) * dz;
        return new RenderPosition(
                pos.x() + 0.5 + rotatedX,
                pos.y() + visual.centerY(),
                pos.z() + 0.5 + rotatedZ,
                facingYaw + visual.yRot());
    }

    private static float facingAngle(Direction facing) {
        return switch (facing) {
            case NORTH -> 0;
            case EAST -> -90;
            case SOUTH -> 180;
            case WEST -> 90;
            default -> 0;
        };
    }

    private record RenderPosition(double x, double y, double z, float yRot) {
    }
}
