package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.storage;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.VirtualEntityIdentity;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
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
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.chunk.CEChunk;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.libraries.antigrieflib.Flag;
import net.momirealms.craftengine.libraries.nbt.ByteArrayTag;
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
import org.bukkit.SoundCategory;
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
 * Generic configuration-driven multi-slot storage block.
 *
 * <p>CraftEngine configuration owns the selector, slot transforms, item rules,
 * sounds, facing transforms, launch parameters and particles. Java only
 * supplies the reusable block-entity mechanism that CE 26.7.4's native
 * single-slot display behavior cannot express.</p>
 */
public final class StorageBlockBehavior extends BukkitBlockBehavior implements EntityBlock {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "storage");
    private static final int ITEM_DATA_VERSION = 2;
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static volatile Handler handler;

    private final StorageBlockConfig config;
    private final Property<Direction> facingProperty;
    private final Property<Boolean> poweredProperty;

    private StorageBlockBehavior(BlockDefinition block, ConfigSection section) {
        super(block);
        this.config = StorageBlockConfig.parse(section);
        this.facingProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "facing", Direction.class);
        this.poweredProperty = BlockBehaviorFactory.getOptionalProperty(
                block, "powered", Boolean.class);
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
        if (poweredProperty == null) {
            return state;
        }
        return state.with(poweredProperty,
                SignalGetterProxy.INSTANCE.hasNeighborSignal(
                        context.getLevel().minecraftWorld(),
                        LocationUtils.toBlockPos(context.getClickedPos())));
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getHand() != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }

        BlockPos pos = context.getClickedPos();
        World world = (World) context.getLevel().platformWorld();
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        if (!BukkitCraftEngine.instance().antiGriefProvider().test(
                (org.bukkit.entity.Player) player.platformPlayer(),
                Flag.OPEN_CONTAINER, location)) {
            return InteractionResult.FAIL;
        }

        Controller controller = controller(context.getLevel().storageWorld(), pos);
        if (controller == null) {
            return InteractionResult.PASS;
        }
        Direction facing;
        try {
            facing = state.get(facingProperty);
        } catch (IllegalArgumentException exception) {
            return InteractionResult.FAIL;
        }
        int selected = selectedSlot(context, facing);
        if (selected < 0 || selected >= config.slots().size()) {
            return InteractionResult.FAIL;
        }
        return interact(controller, context, player, selected);
    }

    private int selectedSlot(UseOnContext context, Direction facing) {
        if (config.selector().frontOnly() && context.getClickedFace() != facing) {
            return -1;
        }
        BlockPos pos = context.getClickedPos();
        Vec3d hit = context.getClickedLocation();
        double relativeX = hit.x - pos.x();
        double relativeY = hit.y - pos.y();
        double relativeZ = hit.z - pos.z();
        StorageBlockConfig.Orientation orientation = config.orientation(facing);
        return config.selector().select(
                orientation.sourceX(relativeX, relativeZ),
                relativeY,
                orientation.sourceZ(relativeX, relativeZ),
                orientation.reverseSlots());
    }

    private InteractionResult interact(
            Controller controller, UseOnContext context, Player player, int selected) {
        StorageBlockConfig.Interaction rules = config.interaction();
        Item hand = player.getItemInHand(context.getHand());
        boolean emptyHand = hand == null || hand.isEmpty();
        boolean containsExclusive = controller.containsExclusive();

        if (emptyHand) {
            if (containsExclusive) {
                selected = rules.exclusiveSlot();
            } else if (controller.item(selected).isEmpty() && rules.fallbackTake()) {
                selected = controller.firstOccupiedSlot();
            }
            if (selected < 0) {
                return InteractionResult.PASS;
            }
            Item taken = controller.take(selected);
            if (taken.isEmpty()) {
                return InteractionResult.PASS;
            }
            player.setItemInHand(context.getHand(), taken);
            player.swingHand(context.getHand());
            playSound(controller.location(), rules.takeSound());
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        Key itemId = hand.id();
        if (!config.isAllowed(itemId)) {
            return reject(player, rules.invalidMessage(), rules.invalidResult());
        }
        if (config.isBlocked(itemId)) {
            return reject(player, rules.blockedMessage(), rules.blockedResult());
        }
        if (containsExclusive) {
            return InteractionResult.PASS;
        }

        boolean insertingExclusive = config.isExclusive(itemId);
        if (insertingExclusive) {
            if (controller.hasAny()) {
                return InteractionResult.PASS;
            }
            selected = rules.exclusiveSlot();
        } else if (!controller.item(selected).isEmpty() && rules.fallbackPut()) {
            selected = controller.firstEmptySlot();
        }
        if (selected < 0 || !controller.item(selected).isEmpty()) {
            return InteractionResult.PASS;
        }

        if (!controller.put(selected, hand.copyWithCount(1))) {
            return InteractionResult.PASS;
        }
        if (rules.consumeInCreative() || !player.canInstabuild()) {
            hand.shrink(1);
        }
        player.swingHand(context.getHand());
        playSound(controller.location(),
                hand.isEmpty() && rules.putLastSound() != null
                        ? rules.putLastSound() : rules.putSound());
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    private static InteractionResult reject(
            Player player, String translationKey,
            StorageBlockConfig.InteractionFailure result) {
        if (translationKey != null && !translationKey.isBlank()) {
            ((org.bukkit.entity.Player) player.platformPlayer())
                    .sendActionBar(Component.translatable(translationKey));
        }
        return result == StorageBlockConfig.InteractionFailure.PASS
                ? InteractionResult.PASS : InteractionResult.FAIL;
    }

    private static void playSound(
            Location location, StorageBlockConfig.ConfiguredSound sound) {
        if (sound == null || location.getWorld() == null) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        location.getWorld().playSound(
                location, sound.id(), SoundCategory.BLOCKS,
                sound.sampleVolume(random), sound.samplePitch(random));
    }

    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        if (poweredProperty == null || !ServerLevelProxy.CLASS.isInstance(args[1])) {
            return;
        }
        BlockStateUtils.getOptionalCustomBlockState(args[0])
                .ifPresent(state -> handlePower(args[1], args[2], state));
    }

    private void handlePower(Object level, Object minecraftPos, ImmutableBlockState state) {
        boolean powered = SignalGetterProxy.INSTANCE.hasNeighborSignal(level, minecraftPos);
        boolean wasPowered;
        try {
            wasPowered = state.get(poweredProperty);
        } catch (IllegalArgumentException exception) {
            return;
        }
        if (powered == wasPowered) {
            return;
        }

        if (powered && config.launch() != null) {
            World bukkitWorld = LevelProxy.INSTANCE.getWorld(level);
            if (bukkitWorld != null) {
                Controller controller = controller(
                        BukkitAdaptor.adapt(bukkitWorld).storageWorld(),
                        LocationUtils.fromBlockPos(minecraftPos));
                if (controller != null) {
                    controller.launchRandom();
                }
            }
        }
        LevelWriterProxy.INSTANCE.setBlock(
                level, minecraftPos,
                state.with(poweredProperty, powered).customBlockState().minecraftState(),
                UpdateFlags.UPDATE_CLIENTS);
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

    private static Controller controller(CEWorld world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntityAtIfLoaded(pos);
        return blockEntity == null ? null : blockEntity.controller.get(Controller.class, 0);
    }

    public interface Handler {
        Item visualItem(Controller controller, int slot);

        void launch(Controller controller, Item item, StorageBlockConfig.Launch launch);
    }

    public static final class Controller extends BlockEntityController
            implements BlockEntityTicker<Controller> {
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
            int slots = behavior.config.slots().size();
            this.items = new Item[slots];
            this.cachedVisuals = new Item[slots];
            this.visualsDirty = new boolean[slots];
            Arrays.fill(items, Item.empty());
            Arrays.fill(visualsDirty, true);
            this.element = new StorageVisualElement(this, slots);
        }

        public Key id() {
            return behavior.blockDefinition.id();
        }

        public StorageBlockConfig config() {
            return behavior.config;
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

        public boolean containsExclusive() {
            for (Item item : items) {
                if (item != null && !item.isEmpty()
                        && behavior.config.isExclusive(item.id())) {
                    return true;
                }
            }
            return false;
        }

        public int firstOccupiedSlot() {
            for (int slot = 0; slot < items.length; slot++) {
                if (!items[slot].isEmpty()) {
                    return slot;
                }
            }
            return -1;
        }

        public int firstEmptySlot() {
            for (int slot = 0; slot < items.length; slot++) {
                if (items[slot].isEmpty()) {
                    return slot;
                }
            }
            return -1;
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
            ImmutableBlockState oldState = blockEntity.blockState;
            boolean changed = false;
            for (String propertyName : behavior.config.refreshProperties()) {
                Property<?> oldProperty = oldState.getProperty(propertyName);
                Property<?> newProperty = newState.getProperty(propertyName);
                if (oldProperty == null || newProperty == null
                        || !Objects.equals(value(oldState, oldProperty),
                        value(newState, newProperty))) {
                    changed = true;
                    break;
                }
            }
            if (!changed || blockEntity.world == null) {
                return;
            }
            renderState = newState;
            try {
                updateTrackedPlayers();
            } finally {
                renderState = null;
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static Object value(ImmutableBlockState state, Property property) {
            return state.get(property);
        }

        @Override
        public void loadCustomData(CompoundTag tag) {
            Arrays.fill(items, Item.empty());
            occupiedSlots = 0;
            CompoundTag data = tag.getCompound(behavior.config.dataKey());
            if (data == null) {
                invalidateVisuals();
                return;
            }
            int storageVersion = data.getInt("version", 1);
            int dataVersion = data.getInt(
                    "data_version", Config.itemDataFixerUpperFallbackVersion());
            for (int slot = 0; slot < items.length; slot++) {
                Tag itemTag = data.get("slot_" + slot);
                if (itemTag != null) {
                    items[slot] = storageVersion >= ITEM_DATA_VERSION
                            && itemTag instanceof ByteArrayTag encoded
                            ? decodeCached(encoded.getAsByteArray())
                            : decodeLegacy(itemTag, dataVersion);
                    if (!items[slot].isEmpty()) {
                        occupiedSlots++;
                    }
                }
            }
            invalidateVisuals();
        }

        private static Item decodeCached(byte[] encoded) {
            try {
                // CE 26.8 owns a bounded cache for this exact format and returns a copy.
                return Item.fromBytes(encoded);
            } catch (RuntimeException ignored) {
                return Item.empty();
            }
        }

        private static Item decodeLegacy(Tag itemTag, int dataVersion) {
            try {
                return net.momirealms.craftengine.bukkit.util.ItemStackUtils.wrap(
                        net.momirealms.craftengine.bukkit.util.ItemStackUtils
                                .parseMinecraftItem(itemTag, dataVersion));
            } catch (RuntimeException ignored) {
                return Item.empty();
            }
        }

        @Override
        public void saveCustomData(CompoundTag tag) {
            if (!hasAny()) {
                return;
            }
            CompoundTag data = new CompoundTag();
            data.putInt("version", ITEM_DATA_VERSION);
            for (int slot = 0; slot < items.length; slot++) {
                Item item = items[slot];
                if (item != null && !item.isEmpty()) {
                    data.put("slot_" + slot, new ByteArrayTag(item.toBytes()));
                }
            }
            tag.put(behavior.config.dataKey(), data);
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
            return behavior.config.particle() == null
                    ? null : createTickerHelper(this);
        }

        @Override
        public void tick(
                CEWorld world, BlockPos pos, ImmutableBlockState state, Controller controller) {
            controller.tickParticle(world, pos);
        }

        private void tickParticle(CEWorld world, BlockPos pos) {
            StorageBlockConfig.ParticleEffect effect = config().particle();
            if (!hasAny() || effect == null
                    || ThreadLocalRandom.current().nextInt(effect.chance()) != 0) {
                return;
            }
            Particle particle;
            try {
                particle = Particle.valueOf(effect.type().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            World bukkitWorld = (World) world.world().platformWorld();
            Location point = new Location(
                    bukkitWorld,
                    pos.x() + effect.sampleX(random),
                    pos.y() + effect.sampleY(random),
                    pos.z() + effect.sampleZ(random));
            bukkitWorld.spawnParticle(
                    particle, point, 0,
                    effect.offsetX(), effect.offsetY(), effect.offsetZ(), effect.speed());
        }

        private void launchRandom() {
            StorageBlockConfig.Launch launch = behavior.config.launch();
            Handler current = handler;
            if (launch == null || current == null) {
                return;
            }
            List<Integer> candidates = new ArrayList<>();
            for (int slot = 0; slot < items.length; slot++) {
                Item item = items[slot];
                if (item != null && !item.isEmpty()
                        && launch.candidateItems().contains(item.id())) {
                    candidates.add(slot);
                }
            }
            if (candidates.isEmpty()) {
                return;
            }
            int slot = candidates.get(
                    ThreadLocalRandom.current().nextInt(candidates.size()));
            Item item = items[slot];
            if (!launch.projectileItems().contains(item.id())) {
                return;
            }
            Item taken = take(slot);
            if (!taken.isEmpty()) {
                current.launch(this, taken, launch);
            }
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
            boolean axisX = controller.facing().axis() == Direction.Axis.X;
            boolean exclusive = controller.containsExclusive();
            int exclusiveSlot = controller.config().interaction().exclusiveSlot();
            for (int slot = 0; slot < entityIds.length; slot++) {
                if (exclusive && slot != exclusiveSlot) {
                    continue;
                }
                Item item = controller.visualItem(slot);
                if (item == null || item.isEmpty()) {
                    continue;
                }
                StorageBlockConfig.SlotVisual visual = controller.config().slots().get(slot);
                Vector3f sourcePosition = visual.position(axisX, exclusive);
                RenderPosition position = renderPosition(controller, sourcePosition, visual);
                packets.add(ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
                        entityIds[slot], entityUuids[slot],
                        position.x(), position.y(), position.z(),
                        visual.xRotation(), position.yRot(),
                        EntityTypesProxy.ITEM_DISPLAY, 0, Vec3Proxy.ZERO, 0));

                List<Object> metadata = new ArrayList<>(3);
                DisplayData.ItemDisplayData.ItemStack.addEntityData(
                        item.minecraftItem(), metadata);
                DisplayData.ItemDisplayData.Scale.addEntityDataIfNotDefaultValue(
                        new Vector3f(visual.scale()), metadata);
                DisplayData.ItemDisplayData.ViewRange.addEntityDataIfNotDefaultValue(
                        (float) (controller.config().viewRange()
                                * player.displayEntityViewDistance()), metadata);
                packets.add(ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(
                        entityIds[slot], metadata));
            }
            if (!packets.isEmpty()) {
                player.sendPackets(packets, false);
            }
        }
    }

    private static RenderPosition renderPosition(
            Controller controller, Vector3f source,
            StorageBlockConfig.SlotVisual visual) {
        BlockPos pos = controller.pos();
        StorageBlockConfig.Orientation orientation =
                controller.config().orientation(controller.facing());
        double angle = Math.toRadians(orientation.positionYaw());
        double dx = source.x - 0.5;
        double dz = source.z - 0.5;
        double rotatedX = Math.cos(angle) * dx + Math.sin(angle) * dz;
        double rotatedZ = -Math.sin(angle) * dx + Math.cos(angle) * dz;
        return new RenderPosition(
                pos.x() + 0.5 + rotatedX,
                pos.y() + source.y,
                pos.z() + 0.5 + rotatedZ,
                orientation.modelYaw() + visual.yRotation());
    }

    private record RenderPosition(double x, double y, double z, float yRot) {
    }
}
