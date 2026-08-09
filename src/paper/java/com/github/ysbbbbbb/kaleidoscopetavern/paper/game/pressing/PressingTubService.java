package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.pressing;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.PressingRecipe;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.FurnitureState;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.station.StationVisualFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.visual.DisplayVisual;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.libraries.antigrieflib.Flag;
import net.momirealms.craftengine.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.player.PlayerProxy;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.UnaryOperator;

/**
 * 压榨桶业务层。CraftEngine 配置负责载体选择、地面/墙面放置、朝向、含水、
 * 家具碰撞、家具掉落和生命周期；本类只保留 CE 配置无法声明的原料/液体状态机、
 * 配方查询、权限规则、压榨反馈与动态内容布局。
 */
public final class PressingTubService implements PressingTubBlockBehavior.Handler {
    public static final Key WALL_FURNITURE_ID =
            Key.of("kaleidoscope_tavern", "_internal/wall_pressing_tub");
    private static final double PRESS_MIN_FALL_DISTANCE = 0.5;
    private static final int PRESS_CAPACITY = 1_000;
    private static final Key EMPTY_BUCKET = Key.of("minecraft:bucket");

    private final JavaPlugin plugin;
    private final ContentCatalog catalog;
    private final ItemService items;
    private final PressingTubVisualFactory visualFactory;

    public PressingTubService(JavaPlugin plugin, ContentCatalog catalog, ItemService items) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.items = items;
        this.visualFactory = new PressingTubVisualFactory(items);
    }

    public void start() {
        PressingTubBlockBehavior.bind(this);
    }

    public void stop() {
        PressingTubBlockBehavior.unbind(this);
    }

    @Override
    public List<DisplayVisual> visuals(PressingTubBlockBehavior.Controller controller, int limit) {
        TubAccess tub = new BlockTub(controller);
        return visualFactory.visuals(
                tub.snapshot(), false, tub.facing(), tub.visualOrigin(), limit);
    }

    /** Dynamic contents for the CE wall-furniture variant. */
    public List<DisplayVisual> furnitureVisuals(BukkitFurniture furniture, int limit) {
        if (!isPressingTubFurniture(furniture)) {
            return List.of();
        }
        TubAccess tub = new FurnitureTub(furniture);
        return visualFactory.visuals(
                tub.snapshot(), tub.tilted(), tub.facing(), tub.visualOrigin(), limit);
    }

    @Override
    public InteractionResult interact(PressingTubBlockBehavior.Controller controller,
                                      UseOnContext context) {
        return finishInteraction(
                interactPress(new BlockTub(controller), context.getPlayer(),
                        context.getHand(), context.getItem()),
                context.getPlayer(), context.getHand());
    }

    /** Interaction entry used by the generic station furniture adapter. */
    public InteractionResult interactFurniture(BukkitFurniture furniture,
                                        InteractEntityContext context) {
        if (!isPressingTubFurniture(furniture)) {
            return InteractionResult.PASS;
        }
        return finishInteraction(
                interactPress(new FurnitureTub(furniture), context.getPlayer(),
                        context.getHand(), context.getItem()),
                context.getPlayer(), context.getHand());
    }

    /** Captures the wall furniture's ingredient pile for the CE break event. */
    public Optional<ItemStack> furnitureIngredientDrop(BukkitFurniture furniture) {
        if (!isPressingTubFurniture(furniture)) {
            return Optional.empty();
        }
        Item ingredient = new FurnitureTub(furniture).snapshot().ingredient();
        ItemStack stack = toBukkitItem(ingredient);
        return stack == null || stack.isEmpty()
                ? Optional.empty() : Optional.of(stack.clone());
    }

    @Override
    public boolean press(PressingTubBlockBehavior.Controller controller,
                         Object nmsEntity, double fallDistance) {
        if (fallDistance < PRESS_MIN_FALL_DISTANCE) {
            return false;
        }
        TubAccess tub = new BlockTub(controller);
        // Players press inside protected regions only; non-player entities
        // follow the vanilla mob-griefing rule like trampling farmland.
        if (PlayerProxy.CLASS.isInstance(nmsEntity)) {
            Player player = ServerPlayerProxy.INSTANCE.getBukkitEntity(nmsEntity);
            if (player == null || !BukkitCraftEngine.instance().antiGriefProvider().test(
                    player, Flag.OPEN_CONTAINER, tub.interactionLocation())) {
                return false;
            }
        } else if (Boolean.FALSE.equals(
                controller.world().getGameRuleValue(GameRule.MOB_GRIEFING))) {
            return false;
        }

        PressingTubState state = tub.snapshot();
        Item ingredient = state.ingredient();
        int count = ingredient == null ? 0 : ingredient.count();
        if (ingredient == null || count <= 0) {
            if (state.fluidAmount() > 0) {
                playSuccessfulPress(tub.interactionLocation(), null);
            } else {
                playFailedPress(tub.interactionLocation(), null);
            }
            return false;
        }
        ItemStack ingredientBukkit = toBukkitItem(ingredient);
        if (ingredientBukkit == null) {
            return false;
        }
        Optional<PressingRecipe> optional = catalog.pressing(items.id(ingredientBukkit));
        if (optional.isEmpty()) {
            playFailedPress(tub.interactionLocation(), ingredientBukkit);
            ejectInvalidPressContents(tub, ingredient, count);
            return false;
        }
        PressingRecipe recipe = optional.get();
        Key currentFluid = state.fluid();
        int amount = state.fluidAmount();
        if (currentFluid != null && !currentFluid.toString().equals(recipe.fluid())) {
            playFailedPress(tub.interactionLocation(), ingredientBukkit);
            ejectInvalidPressContents(tub, ingredient, count);
            return false;
        }
        if (amount >= PRESS_CAPACITY) {
            playFinishedPress(tub.interactionLocation());
            return false;
        }
        tub.updateState(current -> new PressingTubState(
                count == 1 ? null : ingredient.copyWithCount(count - 1),
                Key.of(recipe.fluid()),
                Math.min(PRESS_CAPACITY, amount + recipe.amount())));
        playSuccessfulPress(tub.interactionLocation(), ingredientBukkit);
        return true;
    }

    private InteractionResult interactPress(
            TubAccess tub,
            net.momirealms.craftengine.core.entity.player.Player cePlayer,
            InteractionHand hand,
            Item held
    ) {
        if (cePlayer == null) {
            return InteractionResult.FAIL;
        }
        Location location = tub.interactionLocation();
        if (!BukkitCraftEngine.instance().antiGriefProvider().test(
                (Player) cePlayer.platformPlayer(), Flag.OPEN_CONTAINER, location)) {
            return InteractionResult.FAIL;
        }

        boolean emptyHand = held == null || held.isEmpty();
        PressingTubState state = tub.snapshot();
        Item stored = state.ingredient();
        int storedCount = stored == null ? 0 : stored.count();

        if (emptyHand && storedCount > 0) {
            int removeCount = cePlayer.isSecondaryUseActive()
                    ? Math.min(64, storedCount) : 1;
            cePlayer.giveItem(stored.copyWithCount(removeCount));
            int remaining = storedCount - removeCount;
            tub.updateState(current -> new PressingTubState(
                    remaining <= 0 ? null : stored.copyWithCount(remaining),
                    current.fluid(), current.fluidAmount()));
            playItemFrameSound(location, "minecraft:entity.item_frame.remove_item");
            cePlayer.swingHand(hand);
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        if (held != null && EMPTY_BUCKET.equals(held.id())
                && state.fluidAmount() >= PRESS_CAPACITY) {
            Key fluid = state.fluid();
            Optional<PressingRecipe> recipe = catalog.pressingByFluid(
                    fluid == null ? "" : fluid.toString());
            Optional<ItemStack> result = recipe.flatMap(value -> items.build(
                    value.bucket(), (Player) cePlayer.platformPlayer()));
            if (result.isPresent()) {
                if (!cePlayer.isCreativeMode()) {
                    held.shrink(1);
                }
                cePlayer.giveItem(BukkitAdaptor.adapt(result.get()));
                tub.updateState(current -> new PressingTubState(
                        current.ingredient(), null, 0));
                location.getWorld().playSound(location,
                        "minecraft:item.bucket.fill", SoundCategory.BLOCKS, 1.0F, 1.0F);
                cePlayer.swingHand(hand);
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
        }

        if (!emptyHand) {
            int capacity = Math.min(64,
                    stored == null ? held.maxStackSize() : stored.maxStackSize());
            if ((stored == null || stored.isSimilar(held)) && storedCount < capacity) {
                int inserted = Math.min(held.count(), capacity - storedCount);
                Item template = held.copyWithCount(1);
                // Source behavior consumes inserted items in creative too.
                held.shrink(inserted);
                tub.updateState(current -> new PressingTubState(
                        template.copyWithCount(storedCount + inserted),
                        current.fluid(), current.fluidAmount()));
                playItemFrameSound(location, "minecraft:entity.item_frame.add_item");
                cePlayer.swingHand(hand);
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
        }
        return InteractionResult.PASS;
    }

    private static InteractionResult finishInteraction(
            InteractionResult result,
            net.momirealms.craftengine.core.entity.player.Player cePlayer,
            InteractionHand hand
    ) {
        if (result != InteractionResult.SUCCESS_AND_CANCEL
                || cePlayer == null
                || !(cePlayer.platformPlayer() instanceof Player player)) {
            return result;
        }
        EquipmentSlot usedHand = hand == InteractionHand.MAIN_HAND
                ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        if (player.hasActiveItem() && player.getActiveItemHand() == usedHand) {
            player.clearActiveItem();
        }
        return result;
    }

    private void playSuccessfulPress(Location location, ItemStack ingredient) {
        Location point = location.clone().add(0, 0.5, 0);
        playPressSound(point, "minecraft:block.slime_block.fall");
        if (ingredient == null) {
            point.getWorld().spawnParticle(Particle.RAIN, point, 10,
                    0.25, 0.2, 0.25, 0.05);
        } else {
            point.getWorld().spawnParticle(Particle.ITEM, point, 10,
                    0.25, 0.2, 0.25, 0.05, ingredient);
        }
    }

    private void playFailedPress(Location location, ItemStack ingredient) {
        Location point = location.clone().add(0, 0.5, 0);
        playPressSound(point, "minecraft:block.wood.fall");
        if (ingredient == null) {
            point.getWorld().spawnParticle(Particle.BLOCK, point, 10,
                    0.25, 0.2, 0.25, 0.05, Material.OAK_PLANKS.createBlockData());
        } else {
            point.getWorld().spawnParticle(Particle.ITEM, point, 10,
                    0.25, 0.2, 0.25, 0.05, ingredient);
        }
    }

    private void playFinishedPress(Location location) {
        Location point = location.clone().add(0, 0.5, 0);
        playPressSound(point, "minecraft:block.honey_block.hit");
        point.getWorld().spawnParticle(Particle.RAIN, point, 10,
                0.25, 0.2, 0.25, 0.05);
    }

    private static void playPressSound(Location location, String sound) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        location.getWorld().playSound(location, sound,
                SoundCategory.BLOCKS,
                0.5F + random.nextFloat(), random.nextFloat() * 0.3F + 0.7F);
    }

    private static void playItemFrameSound(Location location, String sound) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        location.getWorld().playSound(location, sound,
                SoundCategory.BLOCKS,
                0.5F + random.nextFloat(), random.nextFloat() * 0.7F + 0.6F);
    }

    private void ejectInvalidPressContents(TubAccess tub, Item template, int totalCount) {
        if (!plugin.getConfig().getBoolean("stations.press-eject-invalid", true)
                || totalCount <= 0) {
            return;
        }
        tub.updateState(current -> new PressingTubState(
                null, current.fluid(), current.fluidAmount()));
        int directionCount = Math.min(8, totalCount);
        double diagonal = 1.0 / Math.sqrt(2.0);
        double[][] directions = {
                {1, 0}, {diagonal, diagonal}, {0, 1}, {-diagonal, diagonal},
                {-1, 0}, {-diagonal, -diagonal}, {0, -1}, {diagonal, -diagonal},
        };
        int base = totalCount / directionCount;
        int remainder = totalCount % directionCount;
        Location origin = tub.interactionLocation();
        if (Boolean.FALSE.equals(origin.getWorld().getGameRuleValue(GameRule.DO_TILE_DROPS))) {
            return;
        }
        ItemStack bukkitTemplate = toBukkitItem(template);
        if (bukkitTemplate == null) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < directionCount; index++) {
            ItemStack dropped = bukkitTemplate.clone();
            dropped.setAmount(base + (index < remainder ? 1 : 0));
            double[] direction = directions[index];
            Location spawn = origin.clone().add(
                    direction[0] * 0.3 + random.nextDouble(-0.05, 0.05),
                    0.5 + random.nextDouble(0, 0.1),
                    direction[1] * 0.3 + random.nextDouble(-0.05, 0.05));
            origin.getWorld().dropItem(spawn, dropped, entity -> entity.setVelocity(new Vector(
                    direction[0] * 0.15 + random.nextDouble(-0.02, 0.02),
                    0.1 + random.nextDouble(-0.02, 0.02),
                    direction[1] * 0.15 + random.nextDouble(-0.02, 0.02))));
        }
    }

    private static boolean isPressingTubFurniture(BukkitFurniture furniture) {
        return furniture != null && WALL_FURNITURE_ID.equals(furniture.id());
    }

    private static ItemStack toBukkitItem(Item item) {
        if (!(item instanceof BukkitItem bukkitItem) || item.isEmpty()) {
            return null;
        }
        ItemStack stack = bukkitItem.getBukkitItem().clone();
        stack.setAmount(Math.min(item.count(), stack.getMaxStackSize()));
        return stack;
    }

    private static Direction facingFromYaw(float yaw) {
        return switch (Math.floorMod(Math.round(yaw), 360)) {
            case 90 -> Direction.WEST;
            case 180 -> Direction.NORTH;
            case 270 -> Direction.EAST;
            default -> Direction.SOUTH;
        };
    }

    private static boolean sameState(PressingTubState left, PressingTubState right) {
        if (left.fluidAmount() != right.fluidAmount()
                || !Objects.equals(left.fluid(), right.fluid())) {
            return false;
        }
        Item leftItem = left.ingredient();
        Item rightItem = right.ingredient();
        if (leftItem == null || rightItem == null) {
            return leftItem == null && rightItem == null;
        }
        return leftItem.count() == rightItem.count() && leftItem.isSimilar(rightItem);
    }

    private interface TubAccess {
        PressingTubState snapshot();

        boolean updateState(UnaryOperator<PressingTubState> mutation);

        Location interactionLocation();

        Location visualOrigin();

        Direction facing();

        boolean tilted();
    }

    private record BlockTub(PressingTubBlockBehavior.Controller controller)
            implements TubAccess {
        @Override
        public PressingTubState snapshot() {
            return controller.snapshot();
        }

        @Override
        public boolean updateState(UnaryOperator<PressingTubState> mutation) {
            return controller.updateState(mutation);
        }

        @Override
        public Location interactionLocation() {
            return controller.location();
        }

        @Override
        public Location visualOrigin() {
            return controller.location();
        }

        @Override
        public Direction facing() {
            return controller.facing();
        }

        @Override
        public boolean tilted() {
            return false;
        }
    }

    private static final class FurnitureTub implements TubAccess {
        private final BukkitFurniture furniture;
        private final FurnitureState data;

        private FurnitureTub(BukkitFurniture furniture) {
            this.furniture = furniture;
            this.data = new FurnitureState(furniture);
        }

        @Override
        public PressingTubState snapshot() {
            Item ingredient = null;
            ItemStack stored = data.item("press_item");
            int count = Math.max(0, data.integer("press_count"));
            if (stored != null && count > 0) {
                ingredient = BukkitAdaptor.adapt(stored).copyWithCount(count);
            }
            Key fluid = null;
            String fluidId = data.string("press_fluid");
            if (fluidId != null && !fluidId.isEmpty()) {
                try {
                    fluid = Key.of(fluidId);
                } catch (RuntimeException ignored) {
                    fluid = null;
                }
            }
            return new PressingTubState(ingredient, fluid, data.integer("press_amount"));
        }

        @Override
        public boolean updateState(UnaryOperator<PressingTubState> mutation) {
            PressingTubState current = snapshot();
            PressingTubState next = mutation.apply(current);
            if (sameState(current, next)) {
                return false;
            }
            Item ingredient = next.ingredient();
            ItemStack bukkit = toBukkitItem(ingredient);
            if (bukkit == null) {
                data.clear("press_item", "press_count");
            } else {
                bukkit.setAmount(1);
                data.item("press_item", bukkit);
                data.integer("press_count", ingredient.count());
            }
            data.putString("press_fluid",
                    next.fluid() == null ? null : next.fluid().toString());
            data.integer("press_amount", next.fluidAmount());
            StationVisualFurnitureBehavior.refresh(furniture);
            return true;
        }

        @Override
        public Location interactionLocation() {
            return furniture.location().clone();
        }

        @Override
        public Location visualOrigin() {
            if (!tilted()) {
                return furniture.location().clone();
            }
            Vec3d center = furniture.getRelativePosition(new Vector3f(0, 0, 0.5F));
            return new Location(
                    furniture.location().getWorld(),
                    center.x,
                    furniture.location().getY() - 0.5,
                    center.z);
        }

        @Override
        public Direction facing() {
            return facingFromYaw(furniture.location().getYaw());
        }

        @Override
        public boolean tilted() {
            return "wall".equals(furniture.currentVariant().name());
        }
    }
}
