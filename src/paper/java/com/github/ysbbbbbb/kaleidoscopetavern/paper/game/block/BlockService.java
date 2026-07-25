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
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;

/** Bukkit-facing interactions for the small set of real, stateful custom blocks. */
public final class BlockService implements Listener {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final String TRELLIS = PREFIX + "trellis";
    private static final String GRAPEVINE = PREFIX + "grapevine";
    private static final Set<String> VINE_TRELLISES = Set.of(
            PREFIX + "grapevine_trellis",
            PREFIX + "ice_grapevine_trellis",
            PREFIX + "gold_grapevine_trellis");

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
        ItemStack hand = event.hand() == InteractionHand.MAIN_HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        // CE supplies the exact stack that triggered this interaction. Prefer it
        // over the inventory snapshot because simulated/off-hand interactions can
        // otherwise make a custom grapevine look like paper or air.
        ItemStack eventItem = event.item();
        String handId = items.id(eventItem == null ? hand : eventItem);
        ImmutableBlockState state = event.blockState();
        String blockId = event.customBlock().id().toString();

        boolean handled = blockId.startsWith(PREFIX + "string_lights_")
                ? interactStringLights(player, event.bukkitBlock(), state, hand)
                : switch (blockId) {
            case TRELLIS -> interactPlainTrellis(
                    player, event.bukkitBlock(), state, hand, handId);
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

    private boolean interactStringLights(Player player, Block block, ImmutableBlockState state, ItemStack hand) {
        String material = hand.getType().name();
        if (!material.endsWith("_DYE")) {
            return false;
        }
        String color = material.substring(0, material.length() - "_DYE".length())
                .toLowerCase(java.util.Locale.ROOT);
        String targetId = PREFIX + "string_lights_" + color;
        if (state.owner().value().id().toString().equals(targetId)) {
            return false;
        }
        net.momirealms.craftengine.core.block.BlockDefinition definition =
                CraftEngineBlocks.byId(net.momirealms.craftengine.core.util.Key.of(targetId));
        if (definition == null) {
            return false;
        }
        ImmutableBlockState replacement = definition.defaultState();
        replacement = copyNamed(state, replacement, "facing");
        replacement = copyNamed(state, replacement, "waterlogged");
        if (CraftEngineBlocks.place(block.getLocation(), replacement, false)) {
            consumeUnlessCreative(player, hand);
            block.getWorld().playSound(block.getLocation(), "minecraft:item.dye.use", 1F, 1F);
            block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                    block.getLocation().add(0.5, 0.5, 0.5), 8, 0.25, 0.25, 0.25, 0);
        }
        return true;
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
                                         ItemStack hand, String handId) {
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
            return false;
        }
        // Consume the interaction for every ordinary trellis shape. Connected
        // trellises carry cross/axis type values, but the replacement definition
        // preserves that value, so they must remain plantable instead of falling
        // through to the grapevine block_item behavior beside the trellis.
        String planted = grapevineFor(block.getRelative(BlockFace.DOWN));
        if (planted == null) {
            return true;
        }
        net.momirealms.craftengine.core.block.BlockDefinition definition =
                CraftEngineBlocks.byId(net.momirealms.craftengine.core.util.Key.of(planted));
        if (definition == null) {
            return true;
        }
        ImmutableBlockState replacement = definition.defaultState();
        replacement = TrellisBehavior.withNamed(replacement, "type", stringProperty(state, "type"));
        replacement = TrellisBehavior.withNamed(replacement, "waterlogged",
                Boolean.toString(booleanProperty(state, "waterlogged")));
        if (CraftEngineBlocks.place(block.getLocation(), replacement, false)) {
            consumeUnlessCreative(player, hand);
            block.getWorld().playSound(block.getLocation(), "minecraft:block.crop.planted", 1F, 1F);
        }
        return true;
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
        for (String member : catalog.blockTag(tagId)) {
            if (member.startsWith("#")) {
                NamespacedKey key = NamespacedKey.fromString(member.substring(1));
                Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                if (tag != null && tag.isTagged(block.getType())) {
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
