package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.pressing;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.VirtualEntityIdentity;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.visual.DifferentialItemDisplayElement;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.visual.DisplayVisual;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.behavior.PrioritizedFallOnHandler;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.render.BlockEntityRenderer;
import net.momirealms.craftengine.core.block.entity.render.element.BlockEntityElement;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.chunk.CEChunk;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.libraries.nbt.ByteArrayTag;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.LivingEntityProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * CE server-side custom block for the pressing tub.
 *
 * <p>The ground tub hosts on a released vanilla state (cut_copper_slab
 * bottom half, see CraftEngine's mappings.yml), while the non-pressable wall
 * variant remains native CE furniture. The block therefore declares only
 * facing/waterlogged properties and lets CraftEngine attach their standard
 * placement and fluid behaviors. The client still renders vanilla
 * cut-copper slabs, so no hand-picked host can collide with built-in content.
 * The 8px bottom-half collision routes
 * {@link PrioritizedFallOnHandler#fallOn} from CraftEngine's NMS interceptor
 * and {@code waterlogged=true} mirrors the Forge block's
 * SimpleWaterloggedBlock support, so the previous global move-event bridge
 * and its reverse spatial index are gone entirely.</p>
 *
 * <p>State (ingredient pile, pressed fluid) lives in the CE block entity and
 * drives a packet-only item-pile/fluid-plane visual ({@link DifferentialItemDisplayElement})
 * that only renders the exact content difference, mirroring the former
 * station visuals without any Bukkit furniture/PDC lookup. CE owns the element
 * lifecycle; the controller only invalidates the content and asks the CE
 * renderer to push updates to its tracked players.</p>
 */
public final class PressingTubBlockBehavior extends BukkitBlockBehavior
        implements EntityBlock, PrioritizedFallOnHandler {
    public static final Key TYPE = Key.of("kaleidoscope_tavern", "pressing_tub_block");

    /** 原料视觉 + 液体视觉最多占用的动态实体槽位数（与源渲染器一致）。 */
    private static final int MAX_ELEMENTS = 17;
    private static final float VIEW_RANGE = 1.25F;
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static volatile Handler handler;

    private final Property<Direction> facingProperty;

    private PressingTubBlockBehavior(BlockDefinition block, ConfigSection section) {
        super(block);
        this.facingProperty = BlockBehaviorFactory.getProperty(
                section.path(), block, "facing", Direction.class);
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            VirtualEntityIdentity.prewarm();
            Controller.prewarm();
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
    public void fallOn(Object thisBlock, Object[] args) {
        // args: Level, BlockState, BlockPos, Entity, double fallDistance
        Controller controller = controller(args[0], args[2]);
        Handler current = handler;
        if (controller != null
                && LivingEntityProxy.CLASS.isInstance(args[3])
                && current != null
                && current.press(controller, args[3],
                ((Number) args[4]).doubleValue())) {
            // The press consumed the landing: skip the default fall damage.
            return;
        }
        super.fallOn(thisBlock, args);
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) {
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
        // Both main and off hand are allowed; CE resolves the held item from
        // the hand context, so the business layer never re-reads the Bukkit
        // inventory. While sneaking with an item CE skips useOnBlock entirely
        // (vanilla "place beside the interactable" semantics), so no Paper
        // sneak patch is needed here.
        return current.interact(controller, context);
    }

    @Override
    public boolean hasAnalogOutputSignal(Object thisBlock, Object[] args) {
        // args: BlockState, Level, BlockPos
        return true;
    }

    @Override
    public int getAnalogOutputSignal(Object thisBlock, Object[] args) {
        // args: BlockState, Level, BlockPos
        Controller controller = controller(args[1], args[2]);
        return controller == null ? 0
                : PressingTubState.comparatorSignal(controller.fluidAmount());
    }

    @Override
    public void affectNeighborsAfterRemoval(Object thisBlock, Object[] args) {
        super.affectNeighborsAfterRemoval(thisBlock, args);
        // args: BlockState, Level, BlockPos — a removed tub must drop the
        // comparator signal to 0 immediately, not on the next random update.
        try {
            LevelProxy.INSTANCE.updateNeighbourForOutputSignal(
                    args[1], args[2], BlockStateUtils.getBlockOwner(args[0]));
        } catch (RuntimeException | LinkageError ignored) {
            // Best-effort refresh during block removal.
        }
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

    /** Gameplay bridge implemented by PressingTubService. */
    public interface Handler {
        /**
         * Handles a right-click on the tub. The full CE use-on context is
         * passed through so the business layer reads the held item, player,
         * hand, sneak state and click position from one consistent source.
         */
        InteractionResult interact(Controller controller, UseOnContext context);

        /**
         * 返回是否成功压榨（成功则跳过默认摔落伤害）。
         *
         * @param nmsEntity the NMS entity that landed on the tub; the business
         *                  layer converts it once for its permission rule
         */
        boolean press(Controller controller, Object nmsEntity, double fallDistance);

        /** Builds at most {@code limit} visuals (including the fluid slot). */
        List<DisplayVisual> visuals(Controller controller, int limit);
    }

    public static final class Controller extends BlockEntityController
            implements DifferentialItemDisplayElement.VisualProvider {
        private static final String DATA_KEY = "kaleidoscope_tavern:press";
        private static final int DATA_VERSION = 1;

        private final PressingTubBlockBehavior behavior;
        private final DifferentialItemDisplayElement element;
        private Item ingredient;
        private Key fluid;
        private int fluidAmount;
        private boolean dropContents = true;

        private Controller(BlockEntity blockEntity, PressingTubBlockBehavior behavior) {
            super(blockEntity);
            this.behavior = behavior;
            this.element = new DifferentialItemDisplayElement(
                    this, MAX_ELEMENTS, VIEW_RANGE);
        }

        private static void prewarm() {
            DifferentialItemDisplayElement.prewarm();
        }

        @Override
        public List<DisplayVisual> visuals(int limit) {
            Handler current = handler;
            return current == null ? List.of()
                    : current.visuals(this, limit);
        }

        public Key id() {
            return behavior.blockDefinition.id();
        }

        public Item ingredient() {
            return ingredient;
        }

        public Key fluid() {
            return fluid;
        }

        public int fluidAmount() {
            return fluidAmount;
        }

        public PressingTubState snapshot() {
            return new PressingTubState(
                    ingredient == null ? null : ingredient.copy(), fluid, fluidAmount);
        }

        /**
         * Applies a state mutation and notifies CE (dirty marker) plus the
         * block-entity renderer's tracked players when something changed.
         * A comparator signal change also refreshes neighbouring redstone.
         *
         * @param mutation maps the current snapshot to the desired next state
         * @return whether the stored state actually changed
         */
        public boolean updateState(UnaryOperator<PressingTubState> mutation) {
            PressingTubState next = mutation.apply(snapshot());
            if (sameItem(next.ingredient(), ingredient)
                    && Objects.equals(next.fluid(), fluid)
                    && next.fluidAmount() == fluidAmount) {
                return false;
            }
            int oldSignal = PressingTubState.comparatorSignal(fluidAmount);
            ingredient = next.ingredient();
            fluid = next.fluid();
            fluidAmount = next.fluidAmount();
            markChanged();
            updateTrackedPlayers();
            notifyComparator(oldSignal);
            return true;
        }

        /** CE's blockEntityChanged only marks the chunk dirty; a comparator
         *  needs an explicit neighbour update or its signal stays stale. */
        private void notifyComparator(int oldSignal) {
            int newSignal = PressingTubState.comparatorSignal(fluidAmount);
            if (oldSignal == newSignal || blockEntity.world == null) {
                return;
            }
            try {
                LevelProxy.INSTANCE.updateNeighbourForOutputSignal(
                        blockEntity.world.world().minecraftWorld(),
                        LocationUtils.toBlockPos(blockEntity.pos),
                        BlockStateUtils.getBlockOwner(
                                blockEntity.blockState.customBlockState()
                                        .minecraftState()));
            } catch (RuntimeException | LinkageError ignored) {
                // Best-effort refresh; the stored state itself is already
                // durable via markChanged().
            }
        }

        private static boolean sameItem(Item left, Item right) {
            if (left == null || right == null) {
                return left == null && right == null;
            }
            // snapshot() copies the ingredient, so identity comparison would
            // report every no-op mutation as a change.
            return left.count() == right.count() && left.isSimilar(right);
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

        @Override
        public boolean hasElement() {
            return true;
        }

        @Override
        public void gatherElements(Consumer<BlockEntityElement> consumer) {
            consumer.accept(new PressingTubVisualElement(this));
        }

        @Override
        public void loadCustomData(CompoundTag tag) {
            // Deserialization runs before the block entity is attached to a
            // world, so this method must stay free of world/chunk access and
            // visual refresh; the CE renderer lifecycle shows the element once
            // the entity becomes visible to a tracked player.
            ingredient = null;
            fluid = null;
            fluidAmount = 0;
            CompoundTag data = tag.getCompound(DATA_KEY);
            if (data == null) {
                return;
            }
            if (data.getInt("version", 1) >= 1) {
                byte[] encodedItem = data.getByteArray("ingredient");
                if (encodedItem != null) {
                    try {
                        Item item = Item.fromBytes(encodedItem);
                        if (!item.isEmpty()) {
                            ingredient = item;
                        }
                    } catch (RuntimeException ignored) {
                        ingredient = null;
                    }
                }
                String fluidId = data.getString("fluid", null);
                if (fluidId != null && !fluidId.isEmpty()) {
                    try {
                        fluid = Key.of(fluidId);
                    } catch (RuntimeException ignored) {
                        fluid = null;
                    }
                }
                fluidAmount = data.getInt("amount", 0);
            }
            // Normalize corrupt saves once at load time so a damaged archive
            // can never loop-spawn items on break.
            PressingTubState normalized = new PressingTubState(ingredient, fluid, fluidAmount);
            ingredient = normalized.ingredient();
            fluid = normalized.fluid();
            fluidAmount = normalized.fluidAmount();
        }

        @Override
        public void saveCustomData(CompoundTag tag) {
            if (ingredient == null && fluid == null && fluidAmount == 0) {
                return;
            }
            CompoundTag data = new CompoundTag();
            data.putInt("version", DATA_VERSION);
            if (ingredient != null) {
                data.put("ingredient", new ByteArrayTag(ingredient.toBytes()));
            }
            if (fluid != null) {
                data.putString("fluid", fluid.toString());
            }
            data.putInt("amount", fluidAmount);
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
            // finished tank fluid is deliberately lost on break. This is the
            // sanctioned thin fallback until the CE loot pipeline replaces it:
            // the block's own drop is owned by CE's block loot, and content
            // drops are suppressed only for creative breaks (playerWillDestroy)
            // and when DO_TILE_DROPS is off, so a damaged save can never
            // loop-spawn items on break. Explosions intentionally keep the
            // ingredient drop, mirroring the source getDrops behaviour.
            if (dropContents && ingredient != null && blockEntity.world != null) {
                World bukkitWorld = (World) blockEntity.world.world().platformWorld();
                if (bukkitWorld != null
                        && !Boolean.FALSE.equals(
                        bukkitWorld.getGameRuleValue(GameRule.DO_TILE_DROPS))
                        && ingredient instanceof BukkitItem bukkitItem) {
                    Location origin = location();
                    int remaining = ingredient.count();
                    while (remaining > 0) {
                        int amount = Math.min(remaining, ingredient.maxStackSize());
                        ItemStack stack = bukkitItem.getBukkitItem().clone();
                        stack.setAmount(amount);
                        origin.getWorld().dropItemNaturally(origin, stack);
                        remaining -= amount;
                    }
                }
            }
            ingredient = null;
            fluid = null;
            fluidAmount = 0;
            dropContents = true;
            // Element hide/removal is owned by the CE block-entity renderer
            // lifecycle; do not touch tracked players here.
        }

        private void suppressContentDrops() {
            dropContents = false;
        }

        /**
         * 状态变化后让 CE renderer 把差量推给该区块的追踪玩家。元素隐藏/卸载
         * 完全由 CE BlockEntityRenderer 生命周期负责，这里不直接调用私有元素，
         * 也不自行维护全局玩家集合。
         */
        private void updateTrackedPlayers() {
            if (blockEntity.world == null || !blockEntity.isValid()) {
                return;
            }
            CEChunk chunk = blockEntity.world.getChunkAtIfLoaded(blockEntity.pos);
            if (chunk == null) {
                return;
            }
            element.invalidate();
            BlockEntityRenderer renderer = blockEntity.renderer();
            for (Player tracked : chunk.getTrackedBy()) {
                renderer.update(tracked);
            }
        }
    }

    /**
     * 极薄的 BlockEntityElement 适配：内容差量、ID 延迟分配、跨玩家共享 packet
     * 全部由 {@link DifferentialItemDisplayElement} 承担。
     */
    private static final class PressingTubVisualElement implements BlockEntityElement {
        private final Controller controller;

        private PressingTubVisualElement(Controller controller) {
            this.controller = controller;
        }

        @Override
        public void show(Player player) {
            controller.element.show(player);
        }

        @Override
        public void hide(Player player) {
            controller.element.hide(player);
        }

        @Override
        public void update(Player player) {
            controller.element.update(player);
        }
    }
}
