package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.VirtualEntityIdentity;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.ComponentUtils;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
import net.momirealms.craftengine.bukkit.util.LevelUtils;
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
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.block.property.type.DoubleBlockHalf;
import net.momirealms.craftengine.core.entity.display.TextDisplayAlignment;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.chunk.CEChunk;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.libraries.antigrieflib.Flag;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypesProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelWriterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.block.BlocksProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Source-compatible business behavior for the CraftEngine chalkboard block.
 *
 * <p>CE's native {@code double_high_block} behavior owns the vertical pair.
 * This behavior only supplies the source-specific three-board merge, resolves
 * every cell to the lower centre controller, and persists/renders editable
 * text. The client-facing collision and aim target come from released closed
 * iron-door states configured by the migration.</p>
 */
public final class ChalkboardBlockBehavior extends BukkitBlockBehavior
        implements EntityBlock {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "chalkboard");

    private static final String DATA_KEY = "kaleidoscope_tavern:chalkboard";
    private static final int MAX_LINES = 11;
    private static final float VIEW_RANGE = 0.75F;
    private static final int TRANSPARENT_BACKGROUND = 0;
    private static final byte GLOWING_ENTITY_FLAG = 0x40;
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    // CE invalidates controllers on chunk unload without calling onRemove().
    // Weak keys keep reload refreshes possible without retaining unloaded chunks.
    private static final Set<Controller> LOADED = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private static volatile Handler handler;

    private final Property<Direction> facingProperty;
    private final Property<DoubleBlockHalf> halfProperty;
    private final Property<String> positionProperty;
    private final Property<Boolean> waterloggedProperty;

    private ChalkboardBlockBehavior(BlockDefinition block, ConfigSection section) {
        super(block);
        this.facingProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "facing", Direction.class);
        this.halfProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "half", DoubleBlockHalf.class);
        this.positionProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "position", String.class);
        this.waterloggedProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "waterlogged", Boolean.class);
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            VirtualEntityIdentity.prewarm();
            BlockBehaviors.register(TYPE, ChalkboardBlockBehavior::new);
        }
    }

    public static void bind(Handler value) {
        handler = Objects.requireNonNull(value, "value");
        refreshLoaded();
    }

    public static void unbind(Handler value) {
        if (handler == value) {
            handler = null;
            refreshLoaded();
        }
    }

    private static void refreshLoaded() {
        List<Controller> snapshot;
        synchronized (LOADED) {
            snapshot = List.copyOf(LOADED);
        }
        snapshot.forEach(Controller::refresh);
    }

    public static Controller controller(org.bukkit.World world, BlockPos pos) {
        return controller(BukkitAdaptor.adapt(world).storageWorld(), pos);
    }

    @Override
    public ImmutableBlockState updateStateForPlacement(
            BlockPlaceContext context, ImmutableBlockState state) {
        // CE owns the double-high pair, the default `position=single` state and
        // waterlogging. Its generic `facing` placement follows player yaw,
        // whereas the source board follows a horizontal clicked face first.
        // Keep only that unconfigurable orientation difference here.
        Direction clickedFace = context.getClickedFace();
        Direction facing = clickedFace.axis().isHorizontal()
                ? clickedFace : context.getHorizontalDirection().opposite();
        return state.with(facingProperty, facing);
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        return interact(context, state);
    }

    @Override
    public InteractionResult useWithoutItem(UseOnContext context, ImmutableBlockState state) {
        return interact(context, state);
    }

    private InteractionResult interact(UseOnContext context, ImmutableBlockState state) {
        if (context.getHand() != InteractionHand.MAIN_HAND || context.getPlayer() == null) {
            return InteractionResult.PASS;
        }
        BlockPos clicked = context.getClickedPos();
        org.bukkit.World world = (org.bukkit.World) context.getLevel().platformWorld();
        Location location = new Location(world, clicked.x(), clicked.y(), clicked.z());
        org.bukkit.entity.Player player =
                (org.bukkit.entity.Player) context.getPlayer().platformPlayer();
        if (!BukkitCraftEngine.instance().antiGriefProvider().test(
                player, Flag.INTERACT, location)) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        BlockPos root = rootPos(clicked, state);
        Controller rootController = controller(context.getLevel().storageWorld(), root);
        Handler current = handler;
        if (rootController == null || current == null || !rootController.isRoot()) {
            return InteractionResult.PASS;
        }
        return current.interact(rootController, context);
    }

    @Override
    public void placeMultiState(Object thisBlock, Object[] args) {
        if (args[3] != null && ServerPlayerProxy.CLASS.isInstance(args[3])) {
            Object bukkit = ServerPlayerProxy.INSTANCE.getBukkitEntity(args[3]);
            if (bukkit instanceof org.bukkit.entity.Player player && player.isSneaking()) {
                return;
            }
        }
        ImmutableBlockState placed = BlockStateUtils
                .getOptionalCustomBlockState(args[2]).orElse(null);
        if (placed == null || placed.owner().value() != blockDefinition) {
            return;
        }
        tryMerge(args[0], LocationUtils.fromBlockPos(args[1]), placed);
    }

    private void tryMerge(Object level, BlockPos placedPos, ImmutableBlockState placed) {
        Direction facing = placed.get(facingProperty);
        CEWorld world = BukkitAdaptor.adapt(LevelProxy.INSTANCE.getWorld(level)).storageWorld();
        BlockPos clockwise1 = placedPos.relative(facing.clockWise());
        BlockPos clockwise2 = clockwise1.relative(facing.clockWise());
        ImmutableBlockState clockwise1State = stateAt(level, clockwise1);
        ImmutableBlockState clockwise2State = stateAt(level, clockwise2);

        if (isMergeCandidate(world, clockwise1, clockwise1State, facing)
                && isMergeCandidate(world, clockwise2, clockwise2State, facing)) {
            setPosition(level, placedPos, "right");
            setPosition(level, clockwise1, "middle");
            setPosition(level, clockwise2, "left");
            resetMergedData(world, placedPos, clockwise1, clockwise2);
            playMergeSound(level, clockwise1);
            return;
        }

        BlockPos counter1 = placedPos.relative(facing.counterClockWise());
        ImmutableBlockState counter1State = stateAt(level, counter1);
        if (isMergeCandidate(world, clockwise1, clockwise1State, facing)
                && isMergeCandidate(world, counter1, counter1State, facing)) {
            setPosition(level, placedPos, "middle");
            setPosition(level, clockwise1, "left");
            setPosition(level, counter1, "right");
            resetMergedData(world, placedPos, clockwise1, counter1);
            playMergeSound(level, placedPos);
            return;
        }

        BlockPos counter2 = counter1.relative(facing.counterClockWise());
        ImmutableBlockState counter2State = stateAt(level, counter2);
        if (isMergeCandidate(world, counter1, counter1State, facing)
                && isMergeCandidate(world, counter2, counter2State, facing)) {
            setPosition(level, placedPos, "left");
            setPosition(level, counter1, "middle");
            setPosition(level, counter2, "right");
            resetMergedData(world, placedPos, counter1, counter2);
            playMergeSound(level, counter1);
        }
    }

    private static void resetMergedData(CEWorld world, BlockPos... lowerParts) {
        for (BlockPos lowerPart : lowerParts) {
            Controller controller = controller(world, lowerPart);
            if (controller != null) {
                controller.resetForMerge();
            }
        }
    }

    private boolean isMergeCandidate(CEWorld world, BlockPos pos,
                                     ImmutableBlockState state, Direction facing) {
        if (state == null || state.owner().value() != blockDefinition
                || state.get(halfProperty) != DoubleBlockHalf.LOWER
                || !state.get(positionProperty).equals("single")
                || state.get(facingProperty) != facing) {
            return false;
        }
        Controller candidate = controller(world, pos);
        return candidate != null && candidate.string("board_text", "").isBlank();
    }

    private void setPosition(Object level, BlockPos lowerPos, String position) {
        setPartPosition(level, lowerPos, position, DoubleBlockHalf.LOWER);
        setPartPosition(level, lowerPos.above(), position, DoubleBlockHalf.UPPER);
    }

    private void setPartPosition(Object level, BlockPos pos, String position,
                                 DoubleBlockHalf half) {
        ImmutableBlockState current = stateAt(level, pos);
        if (current == null || current.owner().value() != blockDefinition
                || current.get(halfProperty) != half) {
            return;
        }
        LevelWriterProxy.INSTANCE.setBlock(
                level, LocationUtils.toBlockPos(pos),
                current.with(positionProperty, position)
                        .customBlockState().minecraftState(),
                UpdateFlags.UPDATE_ALL);
    }

    private void playMergeSound(Object level, BlockPos pos) {
        org.bukkit.World world = LevelProxy.INSTANCE.getWorld(level);
        world.playSound(new Location(world, pos.x() + 0.5, pos.y() + 0.5,
                pos.z() + 0.5), Sound.BLOCK_WOOD_PLACE, 1F, 0.9F);
    }

    @Override
    public Object playerWillDestroy(Object thisBlock, Object[] args) {
        ImmutableBlockState state = BlockStateUtils
                .getOptionalCustomBlockState(args[2]).orElse(null);
        if (state != null && state.owner().value() == blockDefinition) {
            removeOtherParts(args[0], LocationUtils.fromBlockPos(args[1]), state, args[3]);
        }
        return args[2];
    }

    @Override
    public void preExplosionHit(Object thisBlock, Object[] args) {
        ImmutableBlockState state = BlockStateUtils
                .getOptionalCustomBlockState(args[0]).orElse(null);
        if (state != null && state.owner().value() == blockDefinition) {
            removeOtherParts(args[1], LocationUtils.fromBlockPos(args[2]), state, null);
        }
    }

    private void removeOtherParts(Object level, BlockPos clicked,
                                  ImmutableBlockState clickedState, Object player) {
        BlockPos root = rootPos(clicked, clickedState);
        String position = clickedState.get(positionProperty);
        Direction facing = clickedState.get(facingProperty);
        List<ExpectedPart> parts = new ArrayList<>(6);
        if (position.equals("single")) {
            parts.add(new ExpectedPart(root, "single", DoubleBlockHalf.LOWER));
            parts.add(new ExpectedPart(root.above(), "single", DoubleBlockHalf.UPPER));
        } else {
            parts.addAll(columnParts(root.relative(facing.clockWise()), "left"));
            parts.addAll(columnParts(root, "middle"));
            parts.addAll(columnParts(root.relative(facing.counterClockWise()), "right"));
        }

        for (ExpectedPart part : parts) {
            // The clicked cell keeps its own CE loot roll and its vertical
            // lifecycle stays with CE's native double_high_block behavior.
            // Every other cell - the two sibling columns of a merged board and
            // the clicked column's other half - is removed with
            // UPDATE_SUPPRESS_DROPS so Paper's shape-update cascade cannot
            // roll the loot table a second time through Block.updateOrDestroy.
            // UPDATE_KNOWN_SHAPE stops the removal from propagating a shape
            // update back into the clicked cell, which would otherwise destroy
            // it through Level.destroyBlock(pos, true) before the player's
            // own break.
            if (part.pos().equals(clicked)) {
                continue;
            }
            ImmutableBlockState current = stateAt(level, part.pos());
            if (current == null || current.owner().value() != blockDefinition
                    || current.get(halfProperty) != part.half()
                    || !current.get(positionProperty).equals(part.position())
                    || current.get(facingProperty) != facing) {
                continue;
            }
            Object replacement = current.get(waterloggedProperty)
                    ? BlocksProxy.WATER$defaultState : BlocksProxy.AIR$defaultState;
            LevelWriterProxy.INSTANCE.setBlock(
                    level, LocationUtils.toBlockPos(part.pos()), replacement,
                    UpdateFlags.UPDATE_NEIGHBORS | UpdateFlags.UPDATE_CLIENTS
                            | UpdateFlags.UPDATE_SUPPRESS_DROPS
                            | UpdateFlags.UPDATE_KNOWN_SHAPE);
            LevelUtils.levelEvent(level, player,
                    net.momirealms.craftengine.core.world.WorldEvents.BLOCK_BREAK_EFFECT,
                    LocationUtils.toBlockPos(part.pos()),
                    current.customBlockState().registryId());
        }
    }

    private static List<ExpectedPart> columnParts(BlockPos lower, String position) {
        return List.of(
                new ExpectedPart(lower, position, DoubleBlockHalf.LOWER),
                new ExpectedPart(lower.above(), position, DoubleBlockHalf.UPPER));
    }

    private BlockPos rootPos(BlockPos pos, ImmutableBlockState state) {
        BlockPos lower = state.get(halfProperty) == DoubleBlockHalf.UPPER
                ? pos.below() : pos;
        return switch (state.get(positionProperty)) {
            case "left" -> lower.relative(state.get(facingProperty).counterClockWise());
            case "right" -> lower.relative(state.get(facingProperty).clockWise());
            default -> lower;
        };
    }

    private ImmutableBlockState stateAt(Object level, BlockPos pos) {
        Object state = BlockGetterProxy.INSTANCE.getBlockState(
                level, LocationUtils.toBlockPos(pos));
        return BlockStateUtils.getOptionalCustomBlockState(state).orElse(null);
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new Controller(blockEntity, this);
    }

    @Override
    public void initControllerId(int id) {
        // This behavior contributes one controller to CE's composite.
    }

    private static Controller controller(CEWorld world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntityAtIfLoaded(pos);
        return blockEntity == null ? null
                : blockEntity.controller.get(Controller.class, 0);
    }

    public interface Handler {
        InteractionResult interact(Controller controller, UseOnContext context);

        List<Visual> visuals(Controller controller);

        void unavailable(Controller controller);
    }

    public record Visual(Component text, double x, double y, double z,
                         float yRot, float xRot, float scale,
                         boolean glowing, int glowColor) {
        public Visual {
            Objects.requireNonNull(text, "text");
        }
    }

    private record PreparedVisual(Visual visual, Object minecraftText) {
    }

    private record ExpectedPart(BlockPos pos, String position, DoubleBlockHalf half) {
    }

    public static final class Controller extends BlockEntityController {
        private final ChalkboardBlockBehavior behavior;
        private final BlockTextElement element;
        private CompoundTag data = new CompoundTag();
        private ImmutableBlockState renderState;
        private List<PreparedVisual> cachedVisuals = List.of();
        private boolean visualsDirty = true;

        private Controller(BlockEntity blockEntity, ChalkboardBlockBehavior behavior) {
            super(blockEntity);
            this.behavior = behavior;
            ImmutableBlockState state = blockEntity.blockState;
            String position = state.get(behavior.positionProperty);
            this.element = state.get(behavior.halfProperty) == DoubleBlockHalf.LOWER
                    && (position.equals("single") || position.equals("middle"))
                    ? new BlockTextElement(this) : null;
            if (element != null) {
                LOADED.add(this);
            }
        }

        public BlockPos pos() {
            return blockEntity.pos;
        }

        public org.bukkit.World world() {
            return (org.bukkit.World) blockEntity.world.world().platformWorld();
        }

        public Location location() {
            BlockPos pos = pos();
            return new Location(world(), pos.x() + 0.5, pos.y(), pos.z() + 0.5);
        }

        public Direction facing() {
            return effectiveState().get(behavior.facingProperty);
        }

        public boolean isLarge() {
            return effectiveState().get(behavior.positionProperty).equals("middle");
        }

        public boolean isRoot() {
            ImmutableBlockState state = effectiveState();
            if (state.get(behavior.halfProperty) != DoubleBlockHalf.LOWER) {
                return false;
            }
            String position = state.get(behavior.positionProperty);
            return position.equals("single") || position.equals("middle");
        }

        public boolean isValid() {
            return blockEntity.isValid() && blockEntity.world != null && isRoot();
        }

        public String string(String name, String fallback) {
            return data.getString(name, fallback);
        }

        public void putString(String name, String value) {
            String normalized = value == null ? "" : value;
            if (normalized.isEmpty()) {
                if (data.containsKey(name)) {
                    data.remove(name);
                    changed();
                }
            } else if (!normalized.equals(data.getString(name, ""))) {
                data.putString(name, normalized);
                changed();
            }
        }

        public boolean bool(String name) {
            return data.getBoolean(name, false);
        }

        public void bool(String name, boolean value) {
            if (value) {
                if (!data.getBoolean(name, false)) {
                    data.putBoolean(name, true);
                    changed();
                }
            } else if (data.containsKey(name)) {
                data.remove(name);
                changed();
            }
        }

        public void refresh() {
            invalidateVisuals();
            updateTrackedPlayers();
        }

        @Override
        public boolean hasElement() {
            // Only source-equivalent lower roots allocate packet entity ids.
            // A live single already owns an element before it can merge into a
            // middle/side state; persisted side and upper cells need none.
            return element != null;
        }

        @Override
        public void gatherElements(Consumer<BlockEntityElement> consumer) {
            if (element != null) {
                consumer.accept(element);
            }
        }

        @Override
        public void preBlockStateChange(ImmutableBlockState newState) {
            if (blockEntity.world == null) {
                return;
            }
            renderState = newState;
            try {
                invalidateVisuals();
                updateTrackedPlayers();
            } finally {
                renderState = null;
            }
        }

        @Override
        public void loadCustomData(CompoundTag tag) {
            CompoundTag stored = tag.getCompound(DATA_KEY);
            data = stored == null ? new CompoundTag() : stored;
            invalidateVisuals();
        }

        @Override
        public void saveCustomData(CompoundTag tag) {
            if (!data.isEmpty()) {
                tag.put(DATA_KEY, data);
            }
        }

        @Override
        public void onRemove() {
            Handler current = handler;
            if (current != null && blockEntity.world != null) {
                current.unavailable(this);
            }
            LOADED.remove(this);
            invalidateVisuals();
        }

        private void changed() {
            invalidateVisuals();
            if (blockEntity.world != null) {
                blockEntity.world.blockEntityChanged(blockEntity.pos);
                updateTrackedPlayers();
            }
        }

        private void resetForMerge() {
            if (!data.isEmpty()) {
                data = new CompoundTag();
                changed();
            }
        }

        private ImmutableBlockState effectiveState() {
            return renderState == null ? blockEntity.blockState : renderState;
        }

        private void invalidateVisuals() {
            cachedVisuals = List.of();
            visualsDirty = true;
        }

        private List<PreparedVisual> visuals() {
            if (!visualsDirty) {
                return cachedVisuals;
            }
            Handler current = handler;
            List<Visual> visuals = current == null || !isRoot()
                    ? List.of() : current.visuals(this);
            List<PreparedVisual> prepared = new ArrayList<>(visuals.size());
            for (Visual visual : visuals) {
                prepared.add(new PreparedVisual(
                        visual, ComponentUtils.jsonToMinecraft(
                        GsonComponentSerializer.gson().serialize(visual.text()))));
            }
            cachedVisuals = List.copyOf(prepared);
            visualsDirty = false;
            return cachedVisuals;
        }

        private void updateTrackedPlayers() {
            if (element == null || blockEntity.world == null) {
                return;
            }
            CEChunk chunk = blockEntity.world.getChunkAtIfLoaded(blockEntity.pos);
            if (chunk == null) {
                return;
            }
            for (Player tracked : chunk.getTrackedBy()) {
                element.update(tracked);
            }
        }
    }

    private static final class BlockTextElement implements BlockEntityElement {
        private final Controller controller;
        private final int[] entityIds = new int[MAX_LINES];
        private final UUID[] entityUuids = new UUID[MAX_LINES];
        private final Object removePacket;

        private BlockTextElement(Controller controller) {
            this.controller = controller;
            for (int index = 0; index < MAX_LINES; index++) {
                entityIds[index] = EntityUtils.ENTITY_COUNTER.incrementAndGet();
                entityUuids[index] = VirtualEntityIdentity.fromEntityId(entityIds[index]);
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
            List<PreparedVisual> current = controller.visuals();
            int count = Math.min(MAX_LINES, current.size());
            if (count == 0) {
                if (replace) {
                    hide(player);
                }
                return;
            }

            List<Object> packets = new ArrayList<>(count * 2 + (replace ? 1 : 0));
            if (replace) {
                packets.add(removePacket);
            }
            for (int index = 0; index < count; index++) {
                PreparedVisual prepared = current.get(index);
                Visual visual = prepared.visual();
                packets.add(ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
                        entityIds[index], entityUuids[index],
                        visual.x(), visual.y(), visual.z(),
                        visual.xRot(), visual.yRot(),
                        EntityTypesProxy.TEXT_DISPLAY, 0, Vec3Proxy.ZERO, 0));

                List<Object> metadata = new ArrayList<>(8);
                if (visual.glowing()) {
                    DisplayData.TextDisplayData.SharedFlags.addEntityData(
                            GLOWING_ENTITY_FLAG, metadata);
                    DisplayData.TextDisplayData.GlowColorOverride.addEntityData(
                            visual.glowColor(), metadata);
                }
                DisplayData.TextDisplayData.Scale.addEntityDataIfNotDefaultValue(
                        new Vector3f(visual.scale()), metadata);
                DisplayData.TextDisplayData.Text.addEntityData(
                        prepared.minecraftText(), metadata);
                DisplayData.TextDisplayData.LineWidth.addEntityDataIfNotDefaultValue(
                        Integer.MAX_VALUE, metadata);
                DisplayData.TextDisplayData.BackgroundColor.addEntityDataIfNotDefaultValue(
                        TRANSPARENT_BACKGROUND, metadata);
                DisplayData.TextDisplayData.Flags.addEntityDataIfNotDefaultValue(
                        DisplayData.TextDisplayData.encodeFlags(
                                false, false, false, TextDisplayAlignment.CENTER), metadata);
                DisplayData.TextDisplayData.ViewRange.addEntityDataIfNotDefaultValue(
                        (float) (VIEW_RANGE * player.displayEntityViewDistance()), metadata);
                packets.add(ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(
                        entityIds[index], metadata));
            }
            player.sendPackets(packets, false);
        }
    }
}
