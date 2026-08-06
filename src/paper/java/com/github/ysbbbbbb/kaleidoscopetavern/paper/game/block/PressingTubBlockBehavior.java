package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.StationVisualFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.VirtualEntityIdentity;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.behavior.PrioritizedFallOnHandler;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.render.element.BlockEntityElement;
import net.momirealms.craftengine.core.block.property.Property;
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
import net.momirealms.craftengine.libraries.nbt.ByteArrayTag;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypesProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.LivingEntityProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * CE server-side custom block for the pressing tub.
 *
 * <p>The tub is no longer a furniture: the released {@code petrified_oak_slab}
 * bottom-half state carries the client collision, the {@code transparent} flag
 * hides it, and an ItemDisplay renderer draws the authored tub model. Landings
 * arrive through {@link PrioritizedFallOnHandler#fallOn} from CraftEngine's
 * NMS interceptor, so the previous global move-event bridge and its
 * reverse spatial index are gone entirely.</p>
 *
 * <p>State (ingredient pile, pressed fluid) lives in the CE block entity and
 * drives a packet-only item-pile/fluid-plane visual, mirroring the former
 * station visuals without any Bukkit furniture/PDC lookup.</p>
 */
public final class PressingTubBlockBehavior extends BukkitBlockBehavior
        implements EntityBlock, PrioritizedFallOnHandler {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "pressing_tub_block");
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static volatile Handler handler;

    private final Property<Direction> facingProperty;
    private final Property<Boolean> tiltProperty;
    private final Property<Boolean> waterloggedProperty;

    private PressingTubBlockBehavior(BlockDefinition block, ConfigSection section) {
        super(block);
        this.facingProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "facing", Direction.class);
        this.tiltProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "tilt", Boolean.class);
        this.waterloggedProperty = BlockBehaviorFactory.getOptionalProperty(
                block, "waterlogged", Boolean.class);
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            VirtualEntityIdentity.prewarm();
            BlockBehaviors.register(TYPE, PressingTubBlockBehavior::new);
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
        // Mirrors PressingTubBlock#getStateForPlacement: a horizontal clicked
        // face hangs the tub tilted on that face, otherwise it stands upright
        // facing away from the player.
        Direction clickedFace = context.getClickedFace();
        boolean tilt = clickedFace.axis().isHorizontal();
        ImmutableBlockState next = state.with(tiltProperty, tilt)
                .with(facingProperty, tilt ? clickedFace
                        : context.getHorizontalDirection().opposite());
        if (waterloggedProperty != null && context.isWaterSource()) {
            next = next.with(waterloggedProperty, true);
        }
        return next;
    }

    @Override
    public void fallOn(Object thisBlock, Object[] args) {
        // args: Level, BlockState, BlockPos, Entity, double fallDistance
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[1])
                .orElse(null);
        if (state == null) {
            super.fallOn(thisBlock, args);
            return;
        }
        // Tilted tubs cannot be pressed; the source PressingTubBlock delegates
        // the fall straight to normal fall damage.
        if (tiltProperty != null && state.get(tiltProperty)) {
            super.fallOn(thisBlock, args);
            return;
        }
        Controller controller = controller(args[0], args[2]);
        Handler current = handler;
        if (controller != null
                && LivingEntityProxy.CLASS.isInstance(args[3])
                && current != null
                && current.press(controller,
                ((Number) args[4]).doubleValue())) {
            // The press consumed the landing: skip the default fall damage.
            return;
        }
        super.fallOn(thisBlock, args);
    }

    @Override
    public void updateEntityMovementAfterFallOn(Object thisBlock, Object[] args) {
        // Keep the vanilla vertical-momentum reset that follows a landing,
        // pressed or not.
        super.updateEntityMovementAfterFallOn(thisBlock, args);
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
        Controller controller = controller(context.getLevel().storageWorld(),
                context.getClickedPos());
        if (controller == null) {
            return InteractionResult.PASS;
        }
        Handler current = handler;
        if (current == null) {
            return InteractionResult.FAIL;
        }
        return current.interact(controller, cePlayer, context.getHand());
    }

    @Override
    public Object playerWillDestroy(Object thisBlock, Object[] args) {
        // Mirrors the furniture-break contract: creative players must not
        // receive the pressed ingredient pile when they delete the block.
        org.bukkit.entity.Player player = ServerPlayerProxy.INSTANCE.getBukkitEntity(args[3]);
        if (player == null || player.getGameMode() != GameMode.CREATIVE) {
            return args[2];
        }
        World bukkitWorld = LevelProxy.INSTANCE.getWorld(args[0]);
        if (bukkitWorld != null) {
            Controller controller = controller(
                    BukkitAdaptor.adapt(bukkitWorld).storageWorld(),
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

    private static Controller controller(Object nmsLevel, Object nmsPos) {
        World bukkitWorld = LevelProxy.INSTANCE.getWorld(nmsLevel);
        if (bukkitWorld == null) {
            return null;
        }
        return controller(BukkitAdaptor.adapt(bukkitWorld).storageWorld(),
                LocationUtils.fromBlockPos(nmsPos));
    }

    private static Controller controller(CEWorld world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntityAtIfLoaded(pos);
        return blockEntity == null ? null : blockEntity.controller.get(Controller.class, 0);
    }

    /** Gameplay bridge implemented by StationService. */
    public interface Handler {
        InteractionResult interact(Controller controller, Player cePlayer, InteractionHand hand);

        /** 返回是否成功压榨（成功则跳过默认摔落伤害）。 */
        boolean press(Controller controller, double fallDistance);

        List<StationVisualFurnitureBehavior.Visual> visuals(Controller controller, int limit);
    }

    public static final class Controller extends BlockEntityController {
        private static final String DATA_KEY = "kaleidoscope_tavern:press";

        private final PressingTubBlockBehavior behavior;
        private final PressingTubVisualElement element;
        private ItemStack pressItem;
        private int pressCount;
        private String pressFluid;
        private int pressAmount;
        private boolean dropContents = true;

        private Controller(BlockEntity blockEntity, PressingTubBlockBehavior behavior) {
            super(blockEntity);
            this.behavior = behavior;
            this.element = new PressingTubVisualElement(this);
        }

        public Key id() {
            return behavior.blockDefinition.id();
        }

        public ItemStack pressItem() {
            return pressItem;
        }

        public int pressCount() {
            return pressCount;
        }

        public String pressFluid() {
            return pressFluid;
        }

        public int pressAmount() {
            return pressAmount;
        }

        /** 墙面（tilt=true）压榨桶不能压榨、交互取放。 */
        public boolean isTilted() {
            try {
                return behavior.tiltProperty != null
                        && blockEntity.blockState.get(behavior.tiltProperty);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        public Direction facing() {
            try {
                return blockEntity.blockState.get(behavior.facingProperty);
            } catch (IllegalArgumentException e) {
                return Direction.NORTH;
            }
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

        public void setPressState(ItemStack item, int count, String fluid, int amount) {
            boolean changed = false;
            if (!Objects.equals(pressItem, item)) {
                pressItem = item;
                changed = true;
            }
            if (pressCount != count) {
                pressCount = count;
                changed = true;
            }
            if (!Objects.equals(pressFluid, fluid)) {
                pressFluid = fluid;
                changed = true;
            }
            if (pressAmount != amount) {
                pressAmount = amount;
                changed = true;
            }
            if (changed) {
                markChanged();
                updateTrackedPlayers();
            }
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
        public void loadCustomData(CompoundTag tag) {
            pressItem = null;
            pressCount = 0;
            pressFluid = null;
            pressAmount = 0;
            CompoundTag data = tag.getCompound(DATA_KEY);
            if (data == null) {
                element.invalidate();
                return;
            }
            byte[] encodedItem = data.getByteArray("press_item");
            if (encodedItem != null) {
                try {
                    pressItem = ItemStack.deserializeBytes(encodedItem);
                    if (pressItem.isEmpty()) {
                        pressItem = null;
                    }
                } catch (RuntimeException ignored) {
                    pressItem = null;
                }
            }
            pressCount = data.getInt("press_count", 0);
            pressFluid = data.getString("press_fluid", null);
            pressAmount = data.getInt("press_amount", 0);
            element.invalidate();
        }

        @Override
        public void saveCustomData(CompoundTag tag) {
            if (pressItem == null && pressCount == 0 && pressFluid == null && pressAmount == 0) {
                return;
            }
            CompoundTag data = new CompoundTag();
            if (pressItem != null) {
                data.put("press_item", new ByteArrayTag(pressItem.serializeAsBytes()));
            }
            data.putInt("press_count", pressCount);
            if (pressFluid != null && !pressFluid.isEmpty()) {
                data.putString("press_fluid", pressFluid);
            }
            data.putInt("press_amount", pressAmount);
            tag.put(DATA_KEY, data);
        }

        private void markChanged() {
            if (blockEntity.world != null) {
                blockEntity.world.blockEntityChanged(blockEntity.pos);
            }
        }

        @Override
        public void onRemove() {
            // PressingTubBlock#getDrops restores only the ingredient pile;
            // finished tank fluid is deliberately lost on break.
            if (dropContents && pressItem != null && pressCount > 0) {
                Location origin = location();
                for (int remaining = pressCount; remaining > 0; ) {
                    ItemStack stack = pressItem.clone();
                    stack.setAmount(Math.min(remaining, stack.getMaxStackSize()));
                    origin.getWorld().dropItemNaturally(origin, stack);
                    remaining -= stack.getAmount();
                }
            }
            pressItem = null;
            pressCount = 0;
            pressFluid = null;
            pressAmount = 0;
            dropContents = true;
            element.invalidate();
        }

        private void suppressContentDrops() {
            dropContents = false;
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

        private List<Player> trackedPlayers() {
            CEChunk chunk = blockEntity.world.getChunkAtIfLoaded(blockEntity.pos);
            return chunk == null ? List.of() : new ArrayList<>(chunk.getTrackedBy());
        }
    }

    private static final class PressingTubVisualElement implements BlockEntityElement {
        private static final int MAX_ELEMENTS = 17;

        private final Controller controller;
        private final int[] entityIds;
        private final UUID[] entityUuids;
        private final Object removePacket;

        private PressingTubVisualElement(Controller controller) {
            this.controller = controller;
            this.entityIds = new int[MAX_ELEMENTS];
            this.entityUuids = new UUID[MAX_ELEMENTS];
            for (int index = 0; index < MAX_ELEMENTS; index++) {
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
            Handler current = handler;
            if (current == null) {
                return;
            }
            List<StationVisualFurnitureBehavior.Visual> visuals =
                    current.visuals(controller, MAX_ELEMENTS);
            List<Object> packets = new ArrayList<>(visuals.size() * 2 + (replace ? 1 : 0));
            if (replace) {
                packets.add(removePacket);
            }
            for (int index = 0; index < visuals.size(); index++) {
                StationVisualFurnitureBehavior.Visual visual = visuals.get(index);
                packets.add(ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
                        entityIds[index], entityUuids[index],
                        visual.x(), visual.y(), visual.z(),
                        visual.xRot(), visual.yRot(),
                        EntityTypesProxy.ITEM_DISPLAY, 0, Vec3Proxy.ZERO, 0));
                List<Object> metadata = new ArrayList<>(3);
                DisplayData.ItemDisplayData.ItemStack.addEntityData(
                        visual.item().minecraftItem(), metadata);
                DisplayData.Scale.addEntityDataIfNotDefaultValue(
                        new Vector3f(visual.scale()), metadata);
                DisplayData.LeftRotation.addEntityData(visual.leftRotation(), metadata);
                DisplayData.ItemDisplayData.ItemTransform.addEntityData(
                        visual.itemTransform(), metadata);
                DisplayData.ViewRange.addEntityDataIfNotDefaultValue(
                        (float) (1.25F * player.displayEntityViewDistance()), metadata);
                packets.add(ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(
                        entityIds[index], metadata));
            }
            if (!packets.isEmpty()) {
                player.sendPackets(packets, false);
            }
        }

        private void invalidate() {
            for (Player tracked : controller.trackedPlayers()) {
                sendVisuals(tracked, true);
            }
        }
    }
}
