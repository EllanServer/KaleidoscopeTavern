package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.integration.CustomCropsBridge;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockInteractEvent;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
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

    public BlockService(JavaPlugin plugin, ContentCatalog catalog, ItemService items) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.items = items;
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
            case TRELLIS -> interactPlainTrellis(
                    player, event.bukkitBlock(), state, hand, handId, usedSlot);
            case PREFIX + "grapevine_trellis", PREFIX + "ice_grapevine_trellis",
                    PREFIX + "gold_grapevine_trellis" -> interactVineTrellis(
                            player, event.bukkitBlock(), state, hand, event.hand());
            case PREFIX + "wild_grapevine" -> interactWildHead(
                    player, event.bukkitBlock(), state, hand, event.hand());
            case PREFIX + "wild_grapevine_plant" -> interactWildBody(player, event.bukkitBlock(), hand);
            default -> false;
        };
        if (handled) {
            event.setCancelled(true);
        }
    }

    /**
     * Intercepts grapevine placement at the Bukkit {@link PlayerInteractEvent}
     * level, before CraftEngine's own handler (which runs at {@code HIGHEST})
     * can fire {@link CustomBlockInteractEvent} or process the item's
     * {@code block_item} behavior.
     * <p>
     * This handles two scenarios:
     * <ol>
     *   <li>The player right-clicks a plain trellis directly while holding a
     *       grapevine — the trellis is replaced with the appropriate
     *       grapevine-trellis variant based on the soil below.</li>
     *   <li>The player right-clicks the soil block directly below a trellis
     *       (aim was slightly off) — same outcome as above.</li>
     * </ol>
     * In both cases the event is cancelled so CraftEngine cannot fire
     * {@code CustomBlockInteractEvent} or place a {@code wild_grapevine}
     * via the item's {@code block_item} behavior.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRightClickWithGrapevine(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        EquipmentSlot usedSlot = event.getHand() == EquipmentSlot.OFF_HAND
                ? EquipmentSlot.OFF_HAND
                : EquipmentSlot.HAND;
        ItemStack hand = usedSlot == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        String handId = items.id(hand);
        if (!GRAPEVINE.equals(handId)) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        ImmutableBlockState clickedState = CraftEngineBlocks.getCustomBlockState(clicked);

        // Case 1: player right-clicked a plain trellis directly.
        if (clickedState != null && TRELLIS.equals(clickedState.owner().value().id().toString())) {
            Block soil = clicked.getRelative(BlockFace.DOWN);
            String planted = grapevineFor(soil);
            if (planted != null) {
                plantGrapevineOnTrellis(player, clicked, clickedState, hand, soil, planted, usedSlot);
            }
            // Always cancel to prevent block_item from placing wild_grapevine,
            // even when planting fails (wrong soil).
            event.setCancelled(true);
            return;
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
                    plantGrapevineOnTrellis(player, above, aboveState, hand, clicked, planted, usedSlot);
                }
                event.setCancelled(true);
            }
        }
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

    private boolean interactWildHead(Player player, Block block, ImmutableBlockState state,
                                     ItemStack hand, InteractionHand usedHand) {
        if (hand.getType() == Material.SHEARS) {
            if (!booleanProperty(state, "sheared")) {
                ImmutableBlockState changed = TrellisBehavior.withNamed(state, "sheared", "true");
                if (CraftEngineBlocks.place(block.getLocation(), changed, false)) {
                    damageTool(player, hand, usedHand);
                    block.getWorld().playSound(block.getLocation(), "minecraft:entity.sheep.shear", 1F, 1F);
                }
            }
            return true;
        }
        if (hand.getType() == Material.BONE_MEAL && !booleanProperty(state, "sheared")) {
            if (WildGrapevineBehavior.extend(block, state)) {
                consumeUnlessCreative(player, hand);
                block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                        block.getLocation().add(0.5, 0.5, 0.5), 10, 0.25, 0.25, 0.25, 0);
            }
            return true;
        }
        return false;
    }

    private boolean interactWildBody(Player player, Block block, ItemStack hand) {
        if (hand.getType() != Material.BONE_MEAL) {
            return false;
        }
        Block head = WildGrapevineBehavior.findHead(block);
        if (head == null) {
            return true;
        }
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(head);
        if (state != null && !booleanProperty(state, "sheared")
                && WildGrapevineBehavior.extend(head, state)) {
            consumeUnlessCreative(player, hand);
            head.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                    head.getLocation().add(0.5, 0.5, 0.5), 10, 0.25, 0.25, 0.25, 0);
        }
        return true;
    }

    private boolean interactPlainTrellis(Player player, Block block, ImmutableBlockState state,
                                         ItemStack hand, String handId, EquipmentSlot usedSlot) {
        boolean waxed = booleanProperty(state, "waxed");
        if (hand.getType() == Material.HONEYCOMB && !waxed) {
            ImmutableBlockState changed = TrellisBehavior.withNamed(state, "waxed", "true");
            if (CraftEngineBlocks.place(block.getLocation(), changed, false)) {
                block.getWorld().playSound(block.getLocation(), "minecraft:item.honeycomb.wax_on", 1F, 1F);
                block.getWorld().spawnParticle(Particle.WAX_ON, block.getLocation().add(0.5, 0.5, 0.5),
                        8, 0.35, 0.35, 0.35, 0);
            }
            return true;
        }
        if (waxed && hand.getType().name().endsWith("_AXE")) {
            ImmutableBlockState changed = TrellisBehavior.withNamed(state, "waxed", "false");
            if (CraftEngineBlocks.place(block.getLocation(), changed, false)) {
                block.getWorld().playSound(block.getLocation(), "minecraft:item.axe.wax_off", 1F, 1F);
                block.getWorld().spawnParticle(Particle.WAX_OFF, block.getLocation().add(0.5, 0.5, 0.5),
                        8, 0.35, 0.35, 0.35, 0);
            }
            return true;
        }
        if (!GRAPEVINE.equals(handId)) {
            LOGGER.fine(() -> "interactPlainTrellis: handId=" + handId + " does not match " + GRAPEVINE
                    + ", handType=" + hand.getType());
            return false;
        }
        Block soil = block.getRelative(BlockFace.DOWN);
        String planted = grapevineFor(soil);
        if (planted == null) {
            return true; // still cancel to prevent block_item from placing wild_grapevine
        }
        plantGrapevineOnTrellis(player, block, state, hand, soil, planted, usedSlot);
        return true;
    }

    /**
     * Replaces a plain trellis with the appropriate grapevine-trellis variant
     * based on the soil below it. Used both by direct trellis interaction and
     * by the soil-below-trellis interceptor.
     */
    private void plantGrapevineOnTrellis(Player player, Block trellisBlock,
                                          ImmutableBlockState trellisState,
                                          ItemStack hand, Block soil, String planted,
                                          EquipmentSlot usedSlot) {
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
            consumeUnlessCreative(player, hand);
            player.swingHand(usedSlot);
            trellisBlock.getWorld().playSound(trellisBlock.getLocation(), "minecraft:block.crop.planted", 1F, 1F);
        } else {
            LOGGER.warning(() -> "plantGrapevineOnTrellis: CraftEngineBlocks.place failed for " + planted
                    + " at " + trellisBlock.getLocation());
        }
    }

    private boolean interactVineTrellis(Player player, Block block, ImmutableBlockState state,
                                        ItemStack hand, InteractionHand usedHand) {
        // Cancel grapevine item placement on vine trellises to prevent
        // wild_grapevine from being placed adjacent to the trellis.
        if (GRAPEVINE.equals(items.id(hand))) {
            return true;
        }
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
        if (hand.getType() == Material.BONE_MEAL) {
            if (TrellisBehavior.grow(block.getLocation(), state)) {
                consumeUnlessCreative(player, hand);
                block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                        block.getLocation().add(0.5, 0.5, 0.5), 15, 0.25, 0.25, 0.25, 0);
                return true;
            }
            return false;
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

    private static void consumeUnlessCreative(Player player, ItemStack hand) {
        if (player.getGameMode() != GameMode.CREATIVE) {
            hand.subtract(1);
        }
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
