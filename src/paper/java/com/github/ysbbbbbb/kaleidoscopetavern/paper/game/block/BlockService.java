package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.integration.CustomCropsBridge;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.behavior.GrapevineItemBehavior;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockInteractEvent;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.logging.Logger;

/** Bukkit-facing interactions for the small set of real, stateful custom blocks. */
public final class BlockService implements Listener {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final String TRELLIS = PREFIX + "trellis";
    private static final String GRAPEVINE = PREFIX + "grapevine";
    private static final Set<String> VINE_TRELLISES = Set.of(
            PREFIX + "grapevine_trellis",
            PREFIX + "ice_grapevine_trellis",
            PREFIX + "gold_grapevine_trellis");
    // Fallback materials for soil detection when Bukkit.getTag() fails or
    // returns null (can happen on newer Paper versions where the legacy
    // tag API is deprecated).  Mirrors the #minecraft:dirt tag contents.
    private static final Set<Material> DIRT_LIKE = Set.of(
            Material.DIRT, Material.GRASS_BLOCK, Material.PODZOL,
            Material.COARSE_DIRT, Material.MYCELIUM, Material.ROOTED_DIRT,
            Material.MOSS_BLOCK, Material.DIRT_PATH, Material.FARMLAND,
            Material.MUD, Material.MUDDY_MANGROVE_ROOTS);
    // Mirrors the #minecraft:ice tag contents.
    private static final Set<Material> ICE_LIKE = Set.of(
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE,
            Material.FROSTED_ICE);

    private static final Logger LOGGER = Logger.getLogger("KaleidoscopeTavern");

    private final JavaPlugin plugin;
    private final ContentCatalog catalog;
    private final ItemService items;
    private final GrapevineItemBehavior.Handler grapevineHandler = this::useGrapevineOnBlock;

    public BlockService(JavaPlugin plugin, ContentCatalog catalog, ItemService items) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.items = items;
    }

    public void start() {
        GrapevineItemBehavior.bind(grapevineHandler);
    }

    public void stop() {
        GrapevineItemBehavior.unbind(grapevineHandler);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCustomBlockInteract(CustomBlockInteractEvent event) {
        if (event.action() != CustomBlockInteractEvent.Action.RIGHT_CLICK) {
            return;
        }
        Player player = event.player();
        EquipmentSlot usedSlot = event.hand() == InteractionHand.MAIN_HAND
                ? EquipmentSlot.HAND
                : EquipmentSlot.OFF_HAND;
        ItemStack hand = usedSlot == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        // CE supplies the exact stack that triggered this interaction. Prefer it
        // over the inventory snapshot because simulated/off-hand interactions can
        // otherwise make a custom grapevine look like paper or air.
        ItemStack eventItem = event.item();
        String handId = items.id(eventItem == null ? hand : eventItem);
        ImmutableBlockState state = event.blockState();
        String blockId = event.customBlock().id().toString();

        LOGGER.fine(() -> "onCustomBlockInteract: blockId=" + blockId + " handId=" + handId
                + " eventItemNull=" + (eventItem == null) + " handType=" + hand.getType()
                + " at " + event.bukkitBlock().getLocation());

        boolean handled = blockId.startsWith(PREFIX + "string_lights_")
                // String-lights dyeing now lives in the generated CE block
                // events (transform_block); nothing to do here.
                ? false
                : switch (blockId) {
            // Grapevine planting is dispatched by the grapevine item's CE
            // behavior after this block event returns without cancellation.
            case TRELLIS -> false;
            case PREFIX + "grapevine_trellis", PREFIX + "ice_grapevine_trellis",
                    PREFIX + "gold_grapevine_trellis" -> interactVineTrellis(
                            player, event.bukkitBlock(), state, hand, event.hand());
            // wild_grapevine shearing lives in the generated CE block events.
            default -> false;
        };
        if (handled) {
            event.setCancelled(true);
        }
    }

    /**
     * Handles grapevine planting in the CE item behavior before its native
     * {@code block_item} fallback.
     * <p>
     * This handles two scenarios:
     * <ol>
     *   <li>The player right-clicks a plain trellis directly while holding a
     *       grapevine — the trellis is replaced with the appropriate
     *       grapevine-trellis variant based on the soil below.</li>
     *   <li>The player right-clicks the soil block directly below a trellis
     *       (aim was slightly off) — same outcome as above.</li>
     * </ol>
     * Both recognized clicks return {@code SUCCESS_AND_CANCEL}, including
     * failed planting, so the fallback cannot leak a wild vine beside a trellis.
     */
    private InteractionResult useGrapevineOnBlock(UseOnContext context) {
        BlockPos clickedPos = context.getClickedPos();
        Block clicked = ((org.bukkit.World) context.getLevel().platformWorld())
                .getBlockAt(clickedPos.x(), clickedPos.y(), clickedPos.z());
        ImmutableBlockState clickedState = CraftEngineBlocks.getCustomBlockState(clicked);
        String clickedId = clickedState == null
                ? ""
                : clickedState.owner().value().id().toString();

        if (TRELLIS.equals(clickedId)) {
            Block soil = clicked.getRelative(BlockFace.DOWN);
            String planted = grapevineFor(soil);
            if (planted != null) {
                plantGrapevineOnTrellis(context, clicked, clickedState, planted);
            }
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        if (VINE_TRELLISES.contains(clickedId)) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        // Case 2: player right-clicked a vanilla block (soil) directly below
        // a plain trellis.  Skip other custom blocks — CustomBlockInteractEvent
        // handles those.
        if (clickedState == null) {
            Block above = clicked.getRelative(BlockFace.UP);
            ImmutableBlockState aboveState = CraftEngineBlocks.getCustomBlockState(above);
            if (aboveState != null && TRELLIS.equals(aboveState.owner().value().id().toString())) {
                String planted = grapevineFor(clicked);
                if (planted != null) {
                    plantGrapevineOnTrellis(context, above, aboveState, planted);
                }
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
        }
        return InteractionResult.PASS;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCustomBlockBreak(CustomBlockBreakEvent event) {
        if (VINE_TRELLISES.contains(event.customBlock().id().toString())) {
            Location cropLocation = event.bukkitBlock().getRelative(BlockFace.DOWN).getLocation();
            Bukkit.getScheduler().runTask(plugin, () -> {
                ImmutableBlockState support = CraftEngineBlocks.getCustomBlockState(
                        cropLocation.getBlock().getRelative(BlockFace.UP));
                if (support == null || !VINE_TRELLISES.contains(
                        support.owner().value().id().toString())) {
                    CustomCropsBridge.removeCrop(cropLocation);
                }
            });
        }
    }

    /**
     * Replaces a plain trellis with the appropriate grapevine-trellis variant
     * based on the soil below it. Used both by direct trellis interaction and
     * by the soil-below-trellis interceptor.
     */
    private void plantGrapevineOnTrellis(UseOnContext context, Block trellisBlock,
                                         ImmutableBlockState trellisState, String planted) {
        // TrellisBlock#use only accepts a grapevine on the SINGLE shape;
        // cross/six-way trellises reject planting without consuming the item.
        if (!"single".equals(stringProperty(trellisState, "type"))) {
            return;
        }
        net.momirealms.craftengine.core.block.BlockDefinition definition =
                CraftEngineBlocks.byId(net.momirealms.craftengine.core.util.Key.of(planted));
        if (definition == null) {
            LOGGER.warning(() -> "plantGrapevineOnTrellis: definition not found for " + planted);
            return;
        }
        ImmutableBlockState replacement = definition.defaultState();
        replacement = TrellisBehavior.withNamed(replacement, "type", stringProperty(trellisState, "type"));
        replacement = TrellisBehavior.withNamed(replacement, "waterlogged",
                Boolean.toString(booleanProperty(trellisState, "waterlogged")));
        if (CraftEngineBlocks.place(trellisBlock.getLocation(), replacement, false)) {
            if (!context.getPlayer().isCreativeMode()) {
                context.getItem().shrink(1);
            }
            context.getPlayer().swingHand(context.getHand());
            trellisBlock.getWorld().playSound(trellisBlock.getLocation(), "minecraft:block.crop.planted", 1F, 1F);
        } else {
            LOGGER.warning(() -> "plantGrapevineOnTrellis: CraftEngineBlocks.place failed for " + planted
                    + " at " + trellisBlock.getLocation());
        }
    }

    private boolean interactVineTrellis(Player player, Block block, ImmutableBlockState state,
                                        ItemStack hand, InteractionHand usedHand) {
        if (hand.getType() == Material.SHEARS) {
            net.momirealms.craftengine.core.block.BlockDefinition definition =
                    CraftEngineBlocks.byId(net.momirealms.craftengine.core.util.Key.of(TRELLIS));
            if (definition == null) {
                return true;
            }
            ImmutableBlockState replacement = definition.defaultState();
            replacement = TrellisBehavior.withNamed(replacement, "type", stringProperty(state, "type"));
            replacement = TrellisBehavior.withNamed(replacement, "waterlogged",
                    Boolean.toString(booleanProperty(state, "waterlogged")));
            CustomCropsBridge.removeCrop(block.getRelative(BlockFace.DOWN).getLocation());
            if (CraftEngineBlocks.place(block.getLocation(), replacement, false)) {
                items.build(GRAPEVINE, player)
                        .ifPresent(drop -> block.getWorld().dropItemNaturally(block.getLocation(), drop));
                damageTool(player, hand, usedHand);
                block.getWorld().playSound(block.getLocation(), "minecraft:block.beehive.shear", 1F, 1F);
            }
            return true;
        }
        return false;
    }

    private String grapevineFor(Block soil) {
        if (matchesBlockTag(soil, PREFIX + "can_grow_ice_grape")) {
            return PREFIX + "ice_grapevine_trellis";
        }
        if (matchesBlockTag(soil, PREFIX + "can_grow_gold_grape")) {
            return PREFIX + "gold_grapevine_trellis";
        }
        if (matchesBlockTag(soil, PREFIX + "can_grow_grape")) {
            return PREFIX + "grapevine_trellis";
        }
        return null;
    }

    private boolean matchesBlockTag(Block block, String tagId) {
        ImmutableBlockState custom = CraftEngineBlocks.getCustomBlockState(block);
        String customId = custom == null ? "" : custom.owner().value().id().toString();
        String vanillaId = block.getType().getKey().asString();
        Material material = block.getType();
        Set<String> members = catalog.blockTag(tagId);
        for (String member : members) {
            if (member.startsWith("#")) {
                String tagRef = member.substring(1);
                // Try Bukkit tag API first.  Catch Throwable (not just Exception)
                // because Paper 26.2 can throw NoSuchMethodError /
                // IncompatibleClassChangeError when the legacy tag API is
                // accessed at runtime.
                try {
                    NamespacedKey key = NamespacedKey.fromString(tagRef);
                    Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                    if (tag != null && tag.isTagged(material)) {
                        return true;
                    }
                } catch (Throwable e) {
                    LOGGER.fine(() -> "matchesBlockTag: Bukkit.getTag failed for " + tagRef + ": " + e);
                }
                // Fallback: check known material sets for vanilla tags
                if (tagRef.equals("minecraft:dirt") && DIRT_LIKE.contains(material)) {
                    return true;
                }
                if (tagRef.equals("minecraft:ice") && ICE_LIKE.contains(material)) {
                    return true;
                }
            } else if (member.equals(vanillaId) || member.equals(customId)) {
                return true;
            }
        }
        return false;
    }

    private static void damageTool(Player player, ItemStack hand, InteractionHand usedHand) {
        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack damaged = hand.damage(1, player);
            if (usedHand == InteractionHand.MAIN_HAND) {
                player.getInventory().setItemInMainHand(damaged);
            } else {
                player.getInventory().setItemInOffHand(damaged);
            }
        }
    }

    private static boolean booleanProperty(ImmutableBlockState state, String name) {
        Property<?> property = state.getProperty(name);
        return property != null && Boolean.TRUE.equals(state.propertyEntries().get(property));
    }

    private static String stringProperty(ImmutableBlockState state, String name) {
        Property<?> property = state.getProperty(name);
        if (property == null) {
            return "";
        }
        Comparable<?> value = state.propertyEntries().get(property);
        return format(property, value);
    }

    private static ImmutableBlockState copyNamed(ImmutableBlockState from, ImmutableBlockState to, String name) {
        Property<?> source = from.getProperty(name);
        if (source == null || to.getProperty(name) == null) {
            return to;
        }
        Comparable<?> value = from.propertyEntries().get(source);
        return TrellisBehavior.withNamed(to, name, Property.formatValue(source, value));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String format(Property property, Comparable value) {
        return property.valueName(value);
    }
}
