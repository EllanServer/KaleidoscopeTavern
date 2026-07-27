package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Persists the three bottle-sized portions missing from vanilla lava cauldrons. */
final class LavaCauldronLevelStore implements Listener {
    private final JavaPlugin plugin;

    LavaCauldronLevelStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    int level(Block cauldron) {
        if (cauldron.getType() != Material.LAVA_CAULDRON) {
            reset(cauldron);
            return 0;
        }

        BlockData blockData = cauldron.getBlockData();
        if (blockData instanceof Levelled levelled
                && levelled.getMaximumLevel() == TapSemantics.FULL_LAVA_CAULDRON_LEVEL) {
            return Math.max(1, Math.min(
                    TapSemantics.FULL_LAVA_CAULDRON_LEVEL, levelled.getLevel()));
        }

        PersistentDataContainer data = cauldron.getChunk().getPersistentDataContainer();
        NamespacedKey key = key(cauldron);
        Integer stored = data.get(key, PersistentDataType.INTEGER);
        if (stored == null) {
            return TapSemantics.FULL_LAVA_CAULDRON_LEVEL;
        }
        if (stored < 1 || stored > TapSemantics.FULL_LAVA_CAULDRON_LEVEL) {
            data.remove(key);
            return TapSemantics.FULL_LAVA_CAULDRON_LEVEL;
        }
        return stored;
    }

    void consume(Block cauldron, int extractedLevels) {
        if (cauldron.getType() != Material.LAVA_CAULDRON) {
            reset(cauldron);
            return;
        }

        int remaining = TapSemantics.lavaLevelAfterExtraction(
                level(cauldron), extractedLevels, false);
        if (remaining <= 0) {
            reset(cauldron);
            cauldron.setType(Material.CAULDRON, true);
            return;
        }

        BlockData blockData = cauldron.getBlockData();
        if (blockData instanceof Levelled levelled
                && levelled.getMaximumLevel() == TapSemantics.FULL_LAVA_CAULDRON_LEVEL) {
            reset(cauldron);
            levelled.setLevel(remaining);
            cauldron.setBlockData(levelled, true);
            return;
        }
        cauldron.getChunk().getPersistentDataContainer().set(
                key(cauldron), PersistentDataType.INTEGER, remaining);
    }

    void reset(Block cauldron) {
        cauldron.getChunk().getPersistentDataContainer().remove(key(cauldron));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCauldronLevelChange(CauldronLevelChangeEvent event) {
        if (event.getBlock().getType() == Material.LAVA_CAULDRON
                || event.getNewState().getType() == Material.LAVA_CAULDRON) {
            reset(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLavaCauldronBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.LAVA_CAULDRON) {
            reset(event.getBlock());
        }
    }

    private NamespacedKey key(Block block) {
        return new NamespacedKey(plugin, "lava_cauldron_level/"
                + (block.getX() & 15) + "/" + block.getY() + "/" + (block.getZ() & 15));
    }
}
