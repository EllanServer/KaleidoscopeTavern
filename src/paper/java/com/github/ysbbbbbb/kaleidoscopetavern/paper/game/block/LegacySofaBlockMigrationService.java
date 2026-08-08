package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.chunk.CEChunk;
import net.momirealms.craftengine.core.world.chunk.CESection;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * One-release migration from the sixteen former colour block ids to the
 * shared CE tint-source sofa. The work is bounded and only scans CE sections
 * whose palette contains an old id.
 */
public final class LegacySofaBlockMigrationService implements Listener {
    private static final int CELLS_PER_TICK = 8_192;
    private static final int MIGRATIONS_PER_TICK = 32;
    private static final int LOAD_RETRIES = 20;

    private final JavaPlugin plugin;
    private final Deque<SectionCursor> queue = new ArrayDeque<>();
    private final Set<SectionKey> queued = new HashSet<>();
    private final Set<ChunkKey> pendingChunks = new HashSet<>();
    private final AtomicLong migrated = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private BukkitTask worker;

    public LegacySofaBlockMigrationService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        rescanLoadedChunks();
    }

    public void stop() {
        if (worker != null) {
            worker.cancel();
            worker = null;
        }
        queue.clear();
        queued.clear();
        pendingChunks.clear();
    }

    public void rescanLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scheduleChunk(world, chunk.getX(), chunk.getZ(), LOAD_RETRIES);
            }
        }
    }

    public MigrationStats stats() {
        return new MigrationStats(migrated.get(), failures.get());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        Chunk chunk = event.getChunk();
        scheduleChunk(world, chunk.getX(), chunk.getZ(), LOAD_RETRIES);
    }

    private void scheduleChunk(
            World world, int chunkX, int chunkZ, int retriesRemaining) {
        ChunkKey key = new ChunkKey(world.getUID(), chunkX, chunkZ);
        if (!pendingChunks.add(key)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () ->
                enqueueChunk(world, chunkX, chunkZ, retriesRemaining, key));
    }

    private void enqueueChunk(
            World world,
            int chunkX,
            int chunkZ,
            int retriesRemaining,
            ChunkKey pendingKey) {
        pendingChunks.remove(pendingKey);
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        CEWorld ceWorld = BukkitAdaptor.adapt(world).storageWorld();
        CEChunk chunk = ceWorld.getChunkAtIfLoaded(chunkX, chunkZ);
        if (chunk == null) {
            if (retriesRemaining > 0 && pendingChunks.add(pendingKey)) {
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                        enqueueChunk(world, chunkX, chunkZ,
                                retriesRemaining - 1, pendingKey), 1L);
            }
            return;
        }

        for (CESection section : chunk.sections()) {
            if (section == null || section.statesContainer().isEmpty()
                    || !section.statesContainer().hasAny(
                    LegacySofaBlockMigrationService::isLegacyState)) {
                continue;
            }
            SectionKey key = new SectionKey(
                    world.getUID(), chunkX, chunkZ, section.sectionY());
            if (queued.add(key)) {
                queue.addLast(new SectionCursor(key));
            }
        }
        ensureWorker();
    }

    private void ensureWorker() {
        if (queue.isEmpty() || worker != null) {
            return;
        }
        worker = Bukkit.getScheduler().runTaskTimer(
                plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        int scanned = 0;
        int changed = 0;
        while (!queue.isEmpty()
                && scanned < CELLS_PER_TICK
                && changed < MIGRATIONS_PER_TICK) {
            SectionCursor cursor = queue.peekFirst();
            World world = Bukkit.getWorld(cursor.key.worldId());
            if (world == null || !world.isChunkLoaded(
                    cursor.key.chunkX(), cursor.key.chunkZ())) {
                finish(cursor);
                continue;
            }

            CEWorld ceWorld = BukkitAdaptor.adapt(world).storageWorld();
            CEChunk chunk = ceWorld.getChunkAtIfLoaded(
                    cursor.key.chunkX(), cursor.key.chunkZ());
            if (chunk == null) {
                finish(cursor);
                continue;
            }
            CESection section;
            try {
                section = chunk.sectionById(cursor.key.sectionY());
            } catch (RuntimeException exception) {
                finish(cursor);
                continue;
            }

            while (cursor.nextIndex < 4_096
                    && scanned < CELLS_PER_TICK
                    && changed < MIGRATIONS_PER_TICK) {
                int index = cursor.nextIndex++;
                scanned++;
                ImmutableBlockState state = section.getBlockState(index);
                if (!isLegacyState(state)) {
                    continue;
                }
                int localX = index & 15;
                int localZ = (index >>> 4) & 15;
                int localY = (index >>> 8) & 15;
                BlockPos pos = new BlockPos(
                        (cursor.key.chunkX() << 4) + localX,
                        (cursor.key.sectionY() << 4) + localY,
                        (cursor.key.chunkZ() << 4) + localZ);
                if (migrate(world, pos, state)) {
                    migrated.incrementAndGet();
                } else {
                    failures.incrementAndGet();
                }
                changed++;
            }

            if (cursor.nextIndex >= 4_096) {
                finish(cursor);
            }
        }

        if (queue.isEmpty() && worker != null) {
            worker.cancel();
            worker = null;
        }
    }

    private void finish(SectionCursor cursor) {
        queue.removeFirst();
        queued.remove(cursor.key);
    }

    private boolean migrate(
            World world, BlockPos pos, ImmutableBlockState observed) {
        Key legacyId = observed.owner().value().id();
        Direction facing = facing(observed);
        Block target = world.getBlockAt(pos.x(), pos.y(), pos.z());
        ImmutableBlockState current = CraftEngineBlocks.getCustomBlockState(target);
        if (current == null || !current.owner().value().id().equals(legacyId)) {
            return true;
        }

        CompoundTag properties = new CompoundTag();
        properties.putString(
                "facing", facing.name().toLowerCase(java.util.Locale.ROOT));
        Location placeAt = new Location(
                world, pos.x() + 0.5, pos.y(), pos.z() + 0.5);

        try {
            CraftEngineBlocks.remove(target, false);
            if (!SofaTintSupport.placeShared(placeAt, legacyId, facing)) {
                restoreAlias(placeAt, legacyId, properties);
                return false;
            }
            return true;
        } catch (RuntimeException exception) {
            try {
                if (CraftEngineBlocks.getCustomBlockState(target) == null) {
                    restoreAlias(placeAt, legacyId, properties);
                }
            } catch (RuntimeException suppressed) {
                exception.addSuppressed(suppressed);
            }
            plugin.getLogger().log(Level.WARNING,
                    "旧彩色沙发迁移为共享 tint_source_block 失败："
                            + world.getName() + " " + pos,
                    exception);
            return false;
        }
    }

    private static void restoreAlias(
            Location placeAt, Key legacyId, CompoundTag properties) {
        CraftEngineBlocks.place(placeAt, legacyId, properties, false);
    }

    @SuppressWarnings("unchecked")
    private static Direction facing(ImmutableBlockState state) {
        Property<?> raw = state.getProperty("facing");
        if (raw == null || raw.valueClass() != Direction.class) {
            return Direction.NORTH;
        }
        return state.get((Property<Direction>) raw);
    }

    private static boolean isLegacyState(ImmutableBlockState state) {
        return state != null && !state.isEmpty()
                && SofaBlockIds.isLegacy(state.owner().value().id());
    }

    public record MigrationStats(long migrated, long failures) {
    }

    private record ChunkKey(UUID worldId, int chunkX, int chunkZ) {
    }

    private record SectionKey(
            UUID worldId, int chunkX, int chunkZ, int sectionY) {
    }

    private static final class SectionCursor {
        private final SectionKey key;
        private int nextIndex;

        private SectionCursor(SectionKey key) {
            this.key = key;
        }
    }
}
