package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Adds CraftEngine wild grapevine blocks to newly generated oak and birch canopies. */
public final class WorldgenService implements Listener {
    private static final String HEAD_ID = "kaleidoscope_tavern:wild_grapevine";
    private static final String BODY_ID = "kaleidoscope_tavern:wild_grapevine_plant";
    private static final int GENERATION_VERSION = 1;

    private final JavaPlugin plugin;
    private final NamespacedKey generatedKey;

    public WorldgenService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.generatedKey = new NamespacedKey(plugin, "wild_grapevine_generation");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPopulate(ChunkPopulateEvent event) {
        generate(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onNewChunkLoad(ChunkLoadEvent event) {
        if (event.isNewChunk()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> generate(event.getChunk()));
        }
    }

    private void generate(Chunk chunk) {
        if (!plugin.getConfig().getBoolean("worldgen.wild-grapevines", true)
                || chunk.getWorld().getEnvironment() != World.Environment.NORMAL
                || chunk.getPersistentDataContainer().getOrDefault(
                generatedKey, PersistentDataType.INTEGER, 0) >= GENERATION_VERSION) {
            return;
        }
        BlockDefinition head = CraftEngineBlocks.byId(Key.of(HEAD_ID));
        BlockDefinition body = CraftEngineBlocks.byId(Key.of(BODY_ID));
        if (head == null || body == null) {
            return;
        }

        Random random = new Random(mixSeed(chunk));
        double chunkChance = Math.max(0, Math.min(1,
                plugin.getConfig().getDouble("worldgen.chunk-chance", 0.08)));
        if (random.nextDouble() <= chunkChance) {
            int maxChains = Math.max(1, plugin.getConfig().getInt("worldgen.max-chains-per-chunk", 5));
            int maxLength = Math.max(1, plugin.getConfig().getInt("worldgen.max-chain-length", 7));
            List<Block> candidates = candidates(chunk);
            Collections.shuffle(candidates, random);
            int target = candidates.isEmpty() ? 0 : random.nextInt(maxChains) + 1;
            int placed = 0;
            for (Block leaf : candidates) {
                if (placed >= target) {
                    break;
                }
                if (placeChain(leaf.getRelative(BlockFace.DOWN), random.nextInt(maxLength) + 1,
                        head.defaultState(), body.defaultState())) {
                    placed++;
                }
            }
        }
        chunk.getPersistentDataContainer().set(generatedKey, PersistentDataType.INTEGER, GENERATION_VERSION);
    }

    private static List<Block> candidates(Chunk chunk) {
        List<Block> result = new ArrayList<>();
        World world = chunk.getWorld();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        for (int x = baseX; x < baseX + 16; x++) {
            for (int z = baseZ; z < baseZ + 16; z++) {
                int top = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
                int bottom = Math.max(world.getMinHeight(), top - 14);
                for (int y = top; y >= bottom; y--) {
                    Block leaf = world.getBlockAt(x, y, z);
                    if ((leaf.getType() == Material.OAK_LEAVES || leaf.getType() == Material.BIRCH_LEAVES)
                            && leaf.getRelative(BlockFace.DOWN).isEmpty()
                            && leaf.getRelative(BlockFace.DOWN, 2).isEmpty()) {
                        result.add(leaf);
                    }
                }
            }
        }
        return result;
    }

    private static boolean placeChain(Block start, int requestedLength,
                                      ImmutableBlockState head, ImmutableBlockState body) {
        List<Block> spaces = new ArrayList<>();
        Block cursor = start;
        while (spaces.size() < requestedLength && cursor.isEmpty()) {
            spaces.add(cursor);
            cursor = cursor.getRelative(BlockFace.DOWN);
        }
        if (spaces.isEmpty()) {
            return false;
        }
        for (int index = 0; index < spaces.size(); index++) {
            ImmutableBlockState state = index == spaces.size() - 1 ? head : body;
            if (!CraftEngineBlocks.place(spaces.get(index).getLocation(), state, false)) {
                return index > 0;
            }
        }
        return true;
    }

    private static long mixSeed(Chunk chunk) {
        long value = chunk.getWorld().getSeed();
        value ^= (long) chunk.getX() * 341873128712L;
        value ^= (long) chunk.getZ() * 132897987541L;
        value ^= 0x4B5447524150454CL;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return value;
    }
}
