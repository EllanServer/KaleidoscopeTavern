package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.behavior.GrapevineItemBehavior;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Set;
import java.util.logging.Logger;

/** Bukkit-facing interactions for the small set of real, stateful custom blocks. */
public final class BlockService {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final String TRELLIS = PREFIX + "trellis";
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

    private final ContentCatalog catalog;
    private final GrapevineItemBehavior.Handler grapevineHandler = this::useGrapevineOnBlock;

    public BlockService(ContentCatalog catalog) {
        this.catalog = catalog;
    }

    public void start() {
        GrapevineItemBehavior.bind(grapevineHandler);
    }

    public void stop() {
        GrapevineItemBehavior.unbind(grapevineHandler);
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
        // a plain trellis. Other custom blocks are not valid soil targets.
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
        replacement = TrellisBehavior.withNamed(
                replacement, "axis", stringProperty(trellisState, "axis"));
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String format(Property property, Comparable value) {
        return property.valueName(value);
    }
}
