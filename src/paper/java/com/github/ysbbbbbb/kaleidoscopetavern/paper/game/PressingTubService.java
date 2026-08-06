package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.PressingRecipe;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.PressingTubBlockBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.visual.DisplayVisual;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 压榨桶玩法：配方查询、插入/取出原料、压榨状态转换、成品取出、无效原料弹出、
 * 声音粒子与权限规则。方块注册、状态、放置、落地检测、区块生命周期、持久化、
 * 玩家追踪、水浸与掉落框架全部由 CraftEngine 承担，本类只处理 CE 尚无法声明的
 * 玩法内容。
 *
 * <p>交互与压榨入口全部来自 CE 上下文：{@link UseOnContext} 提供 CE Player /
 * CE Item / 手 / 潜行 / 点击面与位置，不再重新读取 Bukkit 背包，也不存在 CE
 * context item 与 Bukkit hand item 不一致的问题。</p>
 */
public final class PressingTubService implements PressingTubBlockBehavior.Handler {
    // IPressingTub.MIN_FALL_DISTANCE: the NMS interceptor reports every
    // collision, so the block behavior gates the press on this threshold.
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
        PressingTubState state = controller.snapshot();
        return visualFactory.visuals(state, controller.isTilted(), controller.facing(),
                controller.location(), limit);
    }

    @Override
    public InteractionResult interact(PressingTubBlockBehavior.Controller controller,
                                      UseOnContext context) {
        InteractionResult result = interactPress(controller, context);
        if (result != InteractionResult.SUCCESS_AND_CANCEL) {
            return result;
        }
        // Block interaction is dispatched from CE's useOnBlock on the main
        // thread. By then vanilla may already have started the held
        // milk-bucket/potion use animation. A successful tub interaction owns
        // that same hand, so explicitly cancel the predicted consume state;
        // otherwise pouring grape juice can visibly (and, under latency,
        // functionally) turn into drinking it. Kept until a live-latency test
        // proves CE's SUCCESS_AND_CANCEL fully cancels the prediction.
        net.momirealms.craftengine.core.entity.player.Player cePlayer = context.getPlayer();
        if (cePlayer != null && cePlayer.platformPlayer() instanceof Player player) {
            EquipmentSlot usedHand = context.getHand() == InteractionHand.MAIN_HAND
                    ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
            if (player.hasActiveItem() && player.getActiveItemHand() == usedHand) {
                player.clearActiveItem();
            }
        }
        return result;
    }

    @Override
    public boolean press(PressingTubBlockBehavior.Controller controller,
                         Object nmsEntity, double fallDistance) {
        // The NMS interceptor reports every collision, so the source's own
        // MIN_FALL_DISTANCE gate must live here; the former Bukkit fall-damage
        // bridge applied it implicitly through a real damage event.
        if (controller.isTilted() || fallDistance < PRESS_MIN_FALL_DISTANCE) {
            return false;
        }
        // Players press inside protected regions only; non-player entities
        // follow the vanilla mob-griefing rule like trampling farmland.
        if (PlayerProxy.CLASS.isInstance(nmsEntity)) {
            Player player = ServerPlayerProxy.INSTANCE.getBukkitEntity(nmsEntity);
            if (player == null || !BukkitCraftEngine.instance().antiGriefProvider().test(
                    player, Flag.OPEN_CONTAINER, controller.location())) {
                return false;
            }
        } else if (Boolean.FALSE.equals(
                controller.world().getGameRuleValue(GameRule.MOB_GRIEFING))) {
            return false;
        }
        PressingTubState state = controller.snapshot();
        Item ingredient = state.ingredient();
        int count = ingredient == null ? 0 : ingredient.count();
        if (ingredient == null || count <= 0) {
            if (state.fluidAmount() > 0) {
                playSuccessfulPress(controller.location(), null);
            } else {
                playFailedPress(controller.location(), null);
            }
            return false;
        }
        ItemStack ingredientBukkit = toBukkitItem(ingredient);
        if (ingredientBukkit == null) {
            return false;
        }
        Optional<PressingRecipe> optional = catalog.pressing(items.id(ingredientBukkit));
        if (optional.isEmpty()) {
            playFailedPress(controller.location(), ingredientBukkit);
            ejectInvalidPressContents(controller, ingredient, count);
            return false;
        }
        PressingRecipe recipe = optional.get();
        Key currentFluid = state.fluid();
        int amount = state.fluidAmount();
        if (currentFluid != null && !currentFluid.toString().equals(recipe.fluid())) {
            playFailedPress(controller.location(), ingredientBukkit);
            ejectInvalidPressContents(controller, ingredient, count);
            return false;
        }
        if (amount >= PRESS_CAPACITY) {
            playFinishedPress(controller.location());
            return false;
        }
        controller.updateState(current -> new PressingTubState(
                count == 1 ? null : ingredient.copyWithCount(count - 1),
                Key.of(recipe.fluid()),
                Math.min(PRESS_CAPACITY, amount + recipe.amount())));
        playSuccessfulPress(controller.location(), ingredientBukkit);
        return true;
    }

    private InteractionResult interactPress(PressingTubBlockBehavior.Controller controller,
                                            UseOnContext context) {
        net.momirealms.craftengine.core.entity.player.Player cePlayer = context.getPlayer();
        if (cePlayer == null) {
            return InteractionResult.FAIL;
        }
        Location location = controller.location();
        if (!BukkitCraftEngine.instance().antiGriefProvider().test(
                (Player) cePlayer.platformPlayer(),
                Flag.OPEN_CONTAINER, location)) {
            return InteractionResult.FAIL;
        }
        InteractionHand hand = context.getHand();
        // CE owns the held item for the clicked hand; the same context item is
        // shrunk below, so it can never diverge from the Bukkit inventory.
        Item held = context.getItem();
        boolean emptyHand = held == null || held.isEmpty();
        PressingTubState state = controller.snapshot();
        Item stored = state.ingredient();
        int storedCount = stored == null ? 0 : stored.count();

        if (emptyHand && storedCount > 0) {
            int removeCount = cePlayer.isSecondaryUseActive()
                    ? Math.min(64, storedCount) : 1;
            cePlayer.giveItem(stored.copyWithCount(removeCount));
            int remaining = storedCount - removeCount;
            controller.updateState(current -> new PressingTubState(
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
                controller.updateState(current -> new PressingTubState(
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
                // The stored template must be captured before the hand shrinks.
                Item template = held.copyWithCount(1);
                // PressingTubBlockEntity inserts the carried stack directly;
                // unlike most interactions this also shrinks creative stacks.
                held.shrink(inserted);
                controller.updateState(current -> new PressingTubState(
                        template.copyWithCount(storedCount + inserted),
                        current.fluid(), current.fluidAmount()));
                playItemFrameSound(location, "minecraft:entity.item_frame.add_item");
                cePlayer.swingHand(hand);
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
        }
        return InteractionResult.PASS;
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

    private void ejectInvalidPressContents(PressingTubBlockBehavior.Controller controller,
                                           Item template, int totalCount) {
        if (!plugin.getConfig().getBoolean("stations.press-eject-invalid", true) || totalCount <= 0) {
            return;
        }
        controller.updateState(current -> new PressingTubState(
                null, current.fluid(), current.fluidAmount()));
        int directionCount = Math.min(8, totalCount);
        double diagonal = 1.0 / Math.sqrt(2.0);
        double[][] directions = {
                {1, 0}, {diagonal, diagonal}, {0, 1}, {-diagonal, diagonal},
                {-1, 0}, {-diagonal, -diagonal}, {0, -1}, {diagonal, -diagonal},
        };
        int base = totalCount / directionCount;
        int remainder = totalCount % directionCount;
        Boolean drops = controller.world().getGameRuleValue(GameRule.DO_TILE_DROPS);
        if (Boolean.FALSE.equals(drops)) {
            return;
        }
        ItemStack bukkitTemplate = toBukkitItem(template);
        if (bukkitTemplate == null) {
            return;
        }
        Location origin = controller.location();
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

    private static ItemStack toBukkitItem(Item item) {
        return item instanceof BukkitItem bukkitItem ? bukkitItem.getBukkitItem() : null;
    }
}
