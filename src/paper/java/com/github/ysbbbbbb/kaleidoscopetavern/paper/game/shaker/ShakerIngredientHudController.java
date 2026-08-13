package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.shaker;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import org.bukkit.entity.ItemDisplay;
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
import java.util.function.Predicate;

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
    private final Predicate<BukkitFurniture> ingredientPresence;
    private final Function<BukkitFurniture, Optional<Component>> subtitleProvider;

    /** Non-empty shaker owners. A furniture owner's UUID is its CE base ItemDisplay UUID. */
    private final Set<UUID> activeOwners = new HashSet<>();
    private final Map<UUID, Set<UUID>> ownersByPlayer = new HashMap<>();
    private final Map<UUID, Set<UUID>> playersByOwner = new HashMap<>();
    private final Map<UUID, DisplayedHud> displayed = new HashMap<>();
    private final Set<UUID> suppressedPlayers = new HashSet<>();

    private BukkitTask targetTask;
    private boolean started;
    private boolean trackingListenersRegistered;

    ShakerIngredientHudController(
            JavaPlugin plugin,
            Map<UUID, BukkitFurniture> loaded,
            ShakerHudTargetResolver targetResolver,
            Predicate<BukkitFurniture> ingredientPresence,
            Function<BukkitFurniture, Optional<Component>> subtitleProvider) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        this.targetResolver = Objects.requireNonNull(targetResolver, "targetResolver");
        this.ingredientPresence = Objects.requireNonNull(
                ingredientPresence, "ingredientPresence");
        this.subtitleProvider = Objects.requireNonNull(subtitleProvider, "subtitleProvider");
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        for (BukkitFurniture furniture : loaded.values()) {
            furnitureAvailable(furniture);
        }
    }

    void stop() {
        if (!started) {
            return;
        }
        started = false;
        if (trackingListenersRegistered) {
            HandlerList.unregisterAll(this);
            trackingListenersRegistered = false;
        }
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
        activeOwners.clear();
    }

    void furnitureAvailable(BukkitFurniture furniture) {
        if (!started || !furniture.isValid()) {
            return;
        }
        if (!ingredientPresence.test(furniture)) {
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
                || !ingredientPresence.test(furniture)) {
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
        activeOwners.add(owner);
        ensureTrackingListeners();

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
        if (event.getEntity() instanceof ItemDisplay itemDisplay) {
            UUID owner = itemDisplay.getUniqueId();
            if (activeOwners.contains(owner)) {
                track(event.getPlayer(), owner);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUntrack(PlayerUntrackEntityEvent event) {
        if (event.getEntity() instanceof ItemDisplay itemDisplay) {
            UUID owner = itemDisplay.getUniqueId();
            if (activeOwners.contains(owner)) {
                untrack(event.getPlayer(), owner);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer().getUniqueId());
    }

    private void track(Player player, UUID owner) {
        if (!player.isOnline() || !activeOwners.contains(owner)
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
        if (!activeOwners.remove(owner) && !playersByOwner.containsKey(owner)) {
            return;
        }
        detachOwnerTracking(owner);
        stopTrackingListenersIfIdle();
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

    private void ensureTrackingListeners() {
        if (started && !trackingListenersRegistered && !activeOwners.isEmpty()) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            trackingListenersRegistered = true;
        }
    }

    private void stopTrackingListenersIfIdle() {
        if (trackingListenersRegistered && activeOwners.isEmpty()) {
            HandlerList.unregisterAll(this);
            trackingListenersRegistered = false;
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
        Set<UUID> trackedNonEmptyOwners = ownersByPlayer.get(playerId);
        BukkitFurniture target = targetResolver.resolve(
                player, loaded, trackedNonEmptyOwners);
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
