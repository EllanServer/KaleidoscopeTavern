package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.shaker;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Drives the placed-shaker ingredient HUD from Paper entity tracking.
 *
 * <p>The vanilla client computes its crosshair target locally and does not send target-change
 * packets. Consequently, exact server-side hover state still needs a ray trace. This controller
 * limits that work to players whose clients currently track at least one loaded shaker, starts
 * the task only while such players exist, and sends titles only when the visible HUD changes.</p>
 */
final class ShakerIngredientHudController implements Listener {
    private static final long TARGET_REFRESH_PERIOD_TICKS = 2L;
    private static final Title.Times INGREDIENT_HUD_TIMES = Title.Times.times(
            Duration.ZERO, Duration.ofDays(1), Duration.ZERO);

    private final JavaPlugin plugin;
    private final Map<UUID, BukkitFurniture> loaded;
    private final ShakerHudTargetResolver targetResolver;
    private final Function<BukkitFurniture, Optional<Component>> subtitleProvider;

    /** CE base-entity id to furniture owner. The integer lookup keeps unrelated track events cheap. */
    private final Map<Integer, UUID> ownerByBaseEntity = new HashMap<>();
    private final Map<UUID, Integer> baseEntityByOwner = new HashMap<>();
    private final Map<UUID, Set<UUID>> ownersByPlayer = new HashMap<>();
    private final Map<UUID, Set<UUID>> playersByOwner = new HashMap<>();
    private final Map<UUID, DisplayedHud> displayed = new HashMap<>();
    private final Set<UUID> suppressedPlayers = new HashSet<>();

    private BukkitTask targetTask;
    private boolean started;

    ShakerIngredientHudController(
            JavaPlugin plugin,
            Map<UUID, BukkitFurniture> loaded,
            ShakerHudTargetResolver targetResolver,
            Function<BukkitFurniture, Optional<Component>> subtitleProvider) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        this.targetResolver = Objects.requireNonNull(targetResolver, "targetResolver");
        this.subtitleProvider = Objects.requireNonNull(subtitleProvider, "subtitleProvider");
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (BukkitFurniture furniture : loaded.values()) {
            furnitureAvailable(furniture);
        }
    }

    void stop() {
        if (!started) {
            return;
        }
        started = false;
        HandlerList.unregisterAll(this);
        if (targetTask != null) {
            targetTask.cancel();
            targetTask = null;
        }
        for (UUID playerId : displayed.keySet()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.clearTitle();
            }
        }
        displayed.clear();
        suppressedPlayers.clear();
        ownersByPlayer.clear();
        playersByOwner.clear();
        ownerByBaseEntity.clear();
        baseEntityByOwner.clear();
    }

    void furnitureAvailable(BukkitFurniture furniture) {
        if (!started || !furniture.isValid()) {
            return;
        }
        if (subtitleProvider.apply(furniture).isEmpty()) {
            // An empty shaker has no HUD, so do not admit it to the tracking interest set.
            detachOwner(furniture.uuid());
            return;
        }
        attachOwner(furniture);
    }

    void furnitureUnavailable(UUID owner) {
        detachOwner(owner);
    }

    void furnitureChanged(BukkitFurniture furniture) {
        if (!started || !furniture.isValid()
                || subtitleProvider.apply(furniture).isEmpty()) {
            detachOwner(furniture.uuid());
            return;
        }
        attachOwner(furniture);
        UUID owner = furniture.uuid();
        Set<UUID> viewers = playersByOwner.get(owner);
        if (viewers == null) {
            return;
        }
        for (UUID playerId : viewers) {
            DisplayedHud current = displayed.get(playerId);
            if (current == null || !current.owner().equals(owner)
                    || suppressedPlayers.contains(playerId)) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                refresh(player);
            }
        }
    }

    private void attachOwner(BukkitFurniture furniture) {
        UUID owner = furniture.uuid();
        int entityId = furniture.bukkitEntity().getEntityId();
        Integer previousEntityId = baseEntityByOwner.get(owner);
        if (previousEntityId != null && previousEntityId != entityId) {
            detachOwner(owner);
        }

        UUID previousOwner = ownerByBaseEntity.put(entityId, owner);
        if (previousOwner != null && !previousOwner.equals(owner)) {
            baseEntityByOwner.remove(previousOwner, entityId);
            detachOwnerTracking(previousOwner);
        }
        baseEntityByOwner.put(owner, entityId);

        // Covers plugin reloads and furniture that became tracked before the lifecycle callback.
        for (Player player : furniture.bukkitEntity().getTrackedBy()) {
            track(player, owner);
        }
    }

    void suppress(Player player) {
        UUID playerId = player.getUniqueId();
        suppressedPlayers.add(playerId);
        clearDisplayed(playerId, player);
    }

    void resume(Player player) {
        UUID playerId = player.getUniqueId();
        suppressedPlayers.remove(playerId);
        if (player.isOnline() && ownersByPlayer.containsKey(playerId)) {
            refresh(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrack(PlayerTrackEntityEvent event) {
        UUID owner = ownerByBaseEntity.get(event.getEntity().getEntityId());
        if (owner != null) {
            track(event.getPlayer(), owner);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUntrack(PlayerUntrackEntityEvent event) {
        UUID owner = ownerByBaseEntity.get(event.getEntity().getEntityId());
        if (owner != null) {
            untrack(event.getPlayer(), owner);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer().getUniqueId());
    }

    private void track(Player player, UUID owner) {
        if (!player.isOnline() || !baseEntityByOwner.containsKey(owner)
                || loaded.get(owner) == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Set<UUID> owners = ownersByPlayer.computeIfAbsent(
                playerId, ignored -> new HashSet<>());
        if (!owners.add(owner)) {
            return;
        }
        playersByOwner.computeIfAbsent(owner, ignored -> new HashSet<>()).add(playerId);
        ensureTargetTask();
    }

    private void untrack(Player player, UUID owner) {
        UUID playerId = player.getUniqueId();
        Set<UUID> owners = ownersByPlayer.get(playerId);
        if (owners == null || !owners.remove(owner)) {
            return;
        }
        removeReverseOwner(owner, playerId);
        if (owners.isEmpty()) {
            ownersByPlayer.remove(playerId, owners);
        }
        DisplayedHud current = displayed.get(playerId);
        if (current != null && current.owner().equals(owner)) {
            clearDisplayed(playerId, player);
        }
        stopTargetTaskIfIdle();
    }

    private void detachOwner(UUID owner) {
        Integer entityId = baseEntityByOwner.remove(owner);
        if (entityId != null) {
            ownerByBaseEntity.remove(entityId, owner);
        }
        detachOwnerTracking(owner);
    }

    private void detachOwnerTracking(UUID owner) {
        Set<UUID> viewers = playersByOwner.remove(owner);
        if (viewers == null) {
            return;
        }
        for (UUID playerId : viewers) {
            Set<UUID> owners = ownersByPlayer.get(playerId);
            if (owners != null) {
                owners.remove(owner);
                if (owners.isEmpty()) {
                    ownersByPlayer.remove(playerId, owners);
                }
            }
            DisplayedHud current = displayed.get(playerId);
            if (current != null && current.owner().equals(owner)) {
                clearDisplayed(playerId, null);
            }
        }
        stopTargetTaskIfIdle();
    }

    private void removePlayer(UUID playerId) {
        Set<UUID> owners = ownersByPlayer.remove(playerId);
        if (owners != null) {
            for (UUID owner : owners) {
                removeReverseOwner(owner, playerId);
            }
        }
        displayed.remove(playerId);
        suppressedPlayers.remove(playerId);
        stopTargetTaskIfIdle();
    }

    private void removeReverseOwner(UUID owner, UUID playerId) {
        Set<UUID> viewers = playersByOwner.get(owner);
        if (viewers == null) {
            return;
        }
        viewers.remove(playerId);
        if (viewers.isEmpty()) {
            playersByOwner.remove(owner, viewers);
        }
    }

    private void ensureTargetTask() {
        if (targetTask == null && !ownersByPlayer.isEmpty()) {
            targetTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin, this::refreshTrackedPlayers,
                    1L, TARGET_REFRESH_PERIOD_TICKS);
        }
    }

    private void stopTargetTaskIfIdle() {
        if (targetTask != null && ownersByPlayer.isEmpty()) {
            targetTask.cancel();
            targetTask = null;
        }
    }

    private void refreshTrackedPlayers() {
        Iterator<Map.Entry<UUID, Set<UUID>>> iterator =
                ownersByPlayer.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Set<UUID>> entry = iterator.next();
            UUID playerId = entry.getKey();
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                for (UUID owner : entry.getValue()) {
                    removeReverseOwner(owner, playerId);
                }
                iterator.remove();
                displayed.remove(playerId);
                suppressedPlayers.remove(playerId);
                continue;
            }
            if (!suppressedPlayers.contains(playerId)) {
                refresh(player);
            }
        }
        stopTargetTaskIfIdle();
    }

    private void refresh(Player player) {
        UUID playerId = player.getUniqueId();
        BukkitFurniture target = targetResolver.resolve(player, loaded);
        if (target == null || !isTracked(playerId, target.uuid())) {
            clearDisplayed(playerId, player);
            return;
        }
        Optional<Component> subtitle = subtitleProvider.apply(target);
        if (subtitle.isEmpty()) {
            clearDisplayed(playerId, player);
            return;
        }
        DisplayedHud next = new DisplayedHud(target.uuid(), subtitle.orElseThrow());
        if (next.equals(displayed.get(playerId))) {
            return;
        }
        player.showTitle(Title.title(
                Component.empty(), next.subtitle(), INGREDIENT_HUD_TIMES));
        displayed.put(playerId, next);
    }

    private boolean isTracked(UUID playerId, UUID owner) {
        Set<UUID> owners = ownersByPlayer.get(playerId);
        return owners != null && owners.contains(owner);
    }

    private void clearDisplayed(UUID playerId, Player knownPlayer) {
        if (displayed.remove(playerId) == null) {
            return;
        }
        Player player = knownPlayer != null
                ? knownPlayer : plugin.getServer().getPlayer(playerId);
        if (player != null && player.isOnline()) {
            player.clearTitle();
        }
    }

    private record DisplayedHud(UUID owner, Component subtitle) {
    }
}
