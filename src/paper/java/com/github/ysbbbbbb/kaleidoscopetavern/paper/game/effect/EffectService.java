package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.effect;

import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.EffectSpec;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.NativeDrinkEffectSemantics;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.ListPersistentDataType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Applies migrated drink data, including the twelve custom Forge effects. */
public final class EffectService implements Listener {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final ListPersistentDataType<String, String> STRING_LIST =
            PersistentDataType.LIST.strings();
    private static final ListPersistentDataType<long[], long[]> LONG_ARRAY_LIST =
            PersistentDataType.LIST.longArrays();
    private static final Set<String> INSTANT_EFFECTS = Set.of(
            PREFIX + "shriek_attack", PREFIX + "upside_down", PREFIX + "zenith");
    private static final Set<Material> VANILLA_CROP_BLOCKS = Set.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES,
            Material.BEETROOTS, Material.TORCHFLOWER_CROP);
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final long MAINTENANCE_INTERVAL_TICKS = 20L;
    private static final long PERSIST_INTERVAL_TICKS = 100L;

    private final JavaPlugin plugin;
    private final ContentCatalog catalog;
    private final ItemService items;
    private final NamespacedKey activeKey;
    private final NamespacedKey effectIdsKey;
    private final NamespacedKey effectValuesKey;
    private final NamespacedKey collisionKey;
    private final NamespacedKey heelsModifierKey;
    private final NamespacedKey blockReachModifierKey;
    private final NamespacedKey entityReachModifierKey;
    private final NamespacedKey splashPreparedKey;
    private final NamespacedKey splashCustomEffectsKey;
    private final Map<UUID, Map<String, ActiveEffect>> active = new HashMap<>();
    private final Map<UUID, LivingEntity> activeEntities = new HashMap<>();
    /** Only entities whose effects have gameplay work every tick enter the hot loop. */
    private final Set<UUID> fastTickEntities = new HashSet<>();
    private final Map<UUID, BossBar> effectHudBars = new HashMap<>();
    private final Map<UUID, String> effectHudLines = new HashMap<>();
    /** Client-side nausea proxies are keyed by logical expiry, not a per-second countdown. */
    private final Map<UUID, Long> privateTipsyExpiry = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> visionPacketExpiry = new HashMap<>();
    private final Map<UUID, Set<UUID>> upsideDownPacketTargets = new HashMap<>();
    private final Set<UUID> stealthHidden = new HashSet<>();
    private final Map<String, Color> effectColorCache = new HashMap<>();
    private final Map<String, Object> effectParticleOptionCache = new HashMap<>();
    /** 每个有效果实体的「纯原版」粒子快照，合并 Tavern 粒子时必须基于它。 */
    private final Map<UUID, EffectParticleState> particleStates = new HashMap<>();
    private final Set<UUID> pendingEffectParticleRefresh = new HashSet<>();
    private final Map<String, CompiledBlockTag> blockTagCache = new HashMap<>();
    private final Map<String, CompiledEntityTag> entityTagCache = new HashMap<>();
    private final boolean builtinHud;
    private final boolean cornerHud;
    private final int hudGuiHalfWidth;
    private long elapsedTicks;
    private long nextPassiveExpiryTick = Long.MAX_VALUE;
    private long scheduledWakeTick = Long.MAX_VALUE;
    private BukkitTask task;
    private boolean fastTickTask;
    private final Runnable tickAction = this::tick;
    private boolean upsideDownPacketsAvailable = true;
    private boolean particleMetadataAvailable = true;
    /** 唯一的 PlayerTrackEntityEvent 监听器：独立成类以便整体注册/注销。 */
    private final TrackReplayListener trackReplayListener =
            new TrackReplayListener(this);
    private boolean trackReplayListenerRegistered;
    private boolean trackReplayFlushScheduled;
    /** 一个 tick 内所有追踪关系只入队，flush 时按目标实体分组处理。 */
    private final Map<UUID, PendingTrackTarget> pendingTrackReplays = new HashMap<>();
    // 性能计数器：trackEvents/Hits 反映事件成本，Flushes 反映批处理效果，
    // metadataBuilds 必须与效果/药水状态变化次数接近而不是观察者数量。
    private long trackEvents;
    private long trackHits;
    private long trackFlushes;
    private long particleMetadataBuilds;

    public EffectService(JavaPlugin plugin, ContentCatalog catalog, ItemService items) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.items = items;
        // auto: an installed CustomNameplates is expected to render the
        // %kaleidoscopetavern_effect_hud% placeholder instead of the built-in
        // boss bar. builtin/external force one side regardless.
        String hudMode = plugin.getConfig().getString("effect-hud.mode", "auto");
        this.builtinHud = switch (hudMode) {
            case "builtin" -> true;
            case "external" -> false;
            default -> Bukkit.getPluginManager().getPlugin("CustomNameplates") == null;
        };
        this.cornerHud = !"line".equals(plugin.getConfig().getString("effect-hud.style", "corner"));
        this.hudGuiHalfWidth = plugin.getConfig().getInt("effect-hud.gui-half-width", 240);
        this.activeKey = new NamespacedKey(plugin, "active_drink_effects");
        this.effectIdsKey = new NamespacedKey(plugin, "effect_ids");
        this.effectValuesKey = new NamespacedKey(plugin, "effect_values");
        this.collisionKey = new NamespacedKey(plugin, "ardent_heat_collisions");
        this.heelsModifierKey = new NamespacedKey(plugin, "effect_high_heels");
        this.blockReachModifierKey = new NamespacedKey(plugin, "effect_long_reach_block");
        this.entityReachModifierKey = new NamespacedKey(plugin, "effect_long_reach_entity");
        this.splashPreparedKey = new NamespacedKey(plugin, "thrown_drink_prepared");
        this.splashCustomEffectsKey = new NamespacedKey(plugin, "thrown_drink_custom_effects");
    }

    public void start() {
        syncCurrentTick();
        for (Player player : Bukkit.getOnlinePlayers()) {
            load(player);
        }
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (LivingEntity living : world.getLivingEntities()) {
                if (!(living instanceof Player)
                        && hasPersistedEffects(living)) {
                    load(living);
                }
            }
        }
        ensureTickTask();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        fastTickTask = false;
        scheduledWakeTick = Long.MAX_VALUE;
        restoreAllEffectParticleMetadata();
        for (UUID uuid : new ArrayList<>(active.keySet())) {
            LivingEntity living = activeEntities.get(uuid);
            if (living == null && Bukkit.getEntity(uuid) instanceof LivingEntity recovered) {
                living = recovered;
            }
            if (living != null) {
                save(living);
                removeAttributeModifiers(living);
            }
        }
        for (Map.Entry<UUID, BossBar> entry : new ArrayList<>(effectHudBars.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                player.hideBossBar(entry.getValue());
            }
        }
        for (UUID uuid : new ArrayList<>(privateTipsyExpiry.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                restorePrivateTipsyVisual(player);
            }
        }
        effectHudBars.clear();
        effectHudLines.clear();
        privateTipsyExpiry.clear();
        for (UUID uuid : new ArrayList<>(stealthHidden)) {
            if (Bukkit.getEntity(uuid) instanceof LivingEntity living) {
                living.setInvisible(false);
            }
        }
        stealthHidden.clear();
        visionPacketExpiry.clear();
        restoreUpsideDownPackets();
        upsideDownPacketTargets.clear();
        pendingEffectParticleRefresh.clear();
        stopTrackReplayListenerIfIdle();
        activeEntities.clear();
        active.clear();
        fastTickEntities.clear();
        nextPassiveExpiryTick = Long.MAX_VALUE;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack consumed = event.getItem();
        String itemId = items.id(consumed);
        if (consumed.getType() == Material.MILK_BUCKET || catalog.pressingByBucket(itemId).isPresent()) {
            clearEffects(event.getPlayer());
            return;
        }
        List<EffectSpec> specs = effectsFor(consumed);
        if (specs.isEmpty() && !catalog.hasDrinkEffects(itemId)
                && !itemId.equals(PREFIX + "signature_cocktail")) {
            return;
        }
        for (EffectSpec spec : specs) {
            if (isEmbeddedVanillaEffect(consumed, spec)) {
                continue;
            }
            if (EffectSemantics.rolls(ThreadLocalRandom.current().nextDouble(), spec.probability())) {
                apply(event.getPlayer(), spec);
            }
        }
        // The empty bottle or glassware now comes back through CraftEngine's
        // settings.consume_replacement on each drink item; this handler only
        // applies the migrated effects.
    }

    private static boolean isEmbeddedVanillaEffect(ItemStack consumed, EffectSpec spec) {
        if (!NativeDrinkEffectSemantics.shouldEmbed(spec.effect(), spec.probability())
                || !(consumed.getItemMeta() instanceof PotionMeta potionMeta)) {
            return false;
        }
        NamespacedKey key = NamespacedKey.fromString(spec.effect());
        PotionEffectType type = key == null ? null : Registry.EFFECT.get(key);
        if (type == null) {
            return false;
        }
        int duration = NativeDrinkEffectSemantics.duration(type.isInstant(), spec.durationTicks());
        for (PotionEffect effect : potionMeta.getCustomEffects()) {
            if (effect.getType().equals(type)
                    && effect.getDuration() == duration
                    && effect.getAmplifier() == spec.amplifier()) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPotionLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof ThrownPotion potion)
                || !isThrownDrink(potion.getItem())) {
            return;
        }
        prepareThrownDrink(potion);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        if (!event.getPotion().getPersistentDataContainer()
                .has(splashPreparedKey, PersistentDataType.BYTE)) {
            return;
        }

        // Do not cancel the real event. Vanilla applies the rolled Minecraft
        // effects with its own direct-hit handling and instantaneous-effect
        // intensity. Only the custom Forge effects need a Paper-side bridge.
        List<EffectSpec> customEffects = readSplashEffects(
                event.getPotion().getPersistentDataContainer());
        for (LivingEntity target : event.getAffectedEntities()) {
            if (target.isDead()) {
                continue;
            }
            double intensity = event.getIntensity(target);
            for (EffectSpec spec : customEffects) {
                if (INSTANT_EFFECTS.contains(spec.effect())) {
                    // All three archived custom instantaneous effects ignore
                    // the health/intensity argument in Forge.
                    apply(target, spec);
                    continue;
                }
                int duration = SplashSemantics.scaledDuration(spec.durationTicks(), intensity);
                if (duration > 0) {
                    apply(target, new EffectSpec(spec.effect(), duration, spec.amplifier(), 1.0));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        detectEffectClear(event.getPlayer(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        detectEffectClear(event.getSender(), event.getCommand());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionEffectChange(EntityPotionEffectEvent event) {
        if (event.getAction() == EntityPotionEffectEvent.Action.CLEARED
                && (event.getCause() == EntityPotionEffectEvent.Cause.COMMAND
                || event.getCause() == EntityPotionEffectEvent.Cause.MILK)) {
            clearEffects(event.getEntity());
            return;
        }
        // NMS marks effectsDirty during this event and may not rebuild the
        // server-owned particle metadata until the following entity tick.
        // Bukkit scheduler tasks run before that tick, so wait two ticks.
        scheduleEffectParticleRefresh(event.getEntity(), 2L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        save(event.getPlayer());
        hideEffectHud(event.getPlayer());
        restoreStealthVisibility(event.getPlayer());
        privateTipsyExpiry.remove(event.getPlayer().getUniqueId());
        visionPacketExpiry.remove(event.getPlayer().getUniqueId());
        upsideDownPacketTargets.remove(event.getPlayer().getUniqueId());
        particleStates.remove(event.getPlayer().getUniqueId());
        pendingEffectParticleRefresh.remove(event.getPlayer().getUniqueId());
        activeEntities.remove(event.getPlayer().getUniqueId());
        active.remove(event.getPlayer().getUniqueId());
        fastTickEntities.remove(event.getPlayer().getUniqueId());
        recomputeNextPassiveExpiryTick();
        stopTrackReplayListenerIfIdle();
        stopTickTaskIfIdle();
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        resetPrivateTipsyVisual(event.getPlayer());
        scheduleEffectParticleRefresh(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        resetPrivateTipsyVisual(event.getPlayer());
        scheduleEffectParticleRefresh(event.getPlayer());
    }

    /**
     * PlayerTrackEntityEvent 的分发入口（由 {@link TrackReplayListener} 转发）。
     * 效果粒子在 Phase 2 已写入实体真实 SynchedEntityData，新追踪者会从原版
     * addPairing 初始 metadata 自动获得，因此这里只处理 viewer-specific 的
     * upside_down：无状态时整体退出，命中时按目标实体合并到
     * {@link #pendingTrackReplays}，由下一个 tick 的单次 flush 统一发送。
     */
    void onTrackReplay(PlayerTrackEntityEvent event) {
        trackEvents++;
        if (upsideDownPacketTargets.isEmpty()) {
            return;
        }
        Player viewer = event.getPlayer();
        Entity entity = event.getEntity();
        if (!(entity instanceof Mob target)) {
            return;
        }
        Set<UUID> inverted = upsideDownPacketTargets.get(viewer.getUniqueId());
        if (inverted == null || !inverted.contains(target.getUniqueId())) {
            return;
        }
        trackHits++;
        PendingTrackTarget pending = pendingTrackReplays.computeIfAbsent(
                target.getUniqueId(), ignored -> new PendingTrackTarget(target));
        pending.upsideDownViewers.add(viewer);
        ensureTrackReplayFlush();
    }

    private void ensureTrackReplayFlush() {
        if (trackReplayFlushScheduled) {
            return;
        }
        trackReplayFlushScheduled = true;
        Bukkit.getScheduler().runTask(plugin, this::flushTrackReplays);
    }

    /** 一个 tick 最多运行一次：按目标实体分组，逐个 viewer 校验。 */
    private void flushTrackReplays() {
        trackReplayFlushScheduled = false;
        trackFlushes++;
        if (pendingTrackReplays.isEmpty()) {
            stopTrackReplayListenerIfIdle();
            return;
        }
        Map<UUID, PendingTrackTarget> batch = new HashMap<>(pendingTrackReplays);
        pendingTrackReplays.clear();
        for (PendingTrackTarget pending : batch.values()) {
            LivingEntity target = pending.target;
            if (!target.isValid()) {
                continue;
            }
            for (Player viewer : pending.upsideDownViewers) {
                if (viewer.isOnline() && target.isTrackedBy(viewer)) {
                    Set<UUID> currentInverted =
                            upsideDownPacketTargets.get(viewer.getUniqueId());
                    if (currentInverted != null
                            && currentInverted.contains(target.getUniqueId())) {
                        sendUpsideDownPacket(viewer, target);
                    }
                }
            }
        }
        stopTrackReplayListenerIfIdle();
    }

    /**
     * 首次出现 upside_down 目标时注册 PlayerTrackEntityEvent 监听器；没有任何
     * 目标后由 {@link #stopTrackReplayListenerIfIdle()} 注销，使该事件恢复
     * Paper 的零监听器快速路径。
     */
    private void ensureTrackReplayListener() {
        if (trackReplayListenerRegistered) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(trackReplayListener, plugin);
        trackReplayListenerRegistered = true;
    }

    private void stopTrackReplayListenerIfIdle() {
        if (!trackReplayListenerRegistered) {
            return;
        }
        if (!upsideDownPacketTargets.isEmpty()) {
            return;
        }
        HandlerList.unregisterAll(trackReplayListener);
        trackReplayListenerRegistered = false;
        pendingTrackReplays.clear();
        trackReplayFlushScheduled = false;
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity living
                    && hasPersistedEffects(living)) {
                load(living);
            }
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity living && active.containsKey(living.getUniqueId())) {
                save(living);
                restoreStealthVisibility(living);
                // 区块 unload 直接丢弃运行时快照：重载时 NMS 会根据存档里的
                // 真实药水效果重新生成纯原版粒子，不需要（也不能）写回合并列表。
                particleStates.remove(living.getUniqueId());
                pendingEffectParticleRefresh.remove(living.getUniqueId());
                activeEntities.remove(living.getUniqueId());
                active.remove(living.getUniqueId());
                fastTickEntities.remove(living.getUniqueId());
            }
        }
        recomputeNextPassiveExpiryTick();
        stopTrackReplayListenerIfIdle();
        stopTickTaskIfIdle();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        // Dedicated events below preserve state for players/chunk unloads and
        // run source death callbacks. Every other removal permanently retires
        // the entity, so discard its runtime custom-effect state immediately.
        if (event.getCause() == EntityRemoveEvent.Cause.PLAYER_QUIT
                || event.getCause() == EntityRemoveEvent.Cause.UNLOAD
                || event.getCause() == EntityRemoveEvent.Cause.DEATH
                || !(event.getEntity() instanceof LivingEntity living)
                || !active.containsKey(living.getUniqueId())) {
            return;
        }
        // 永久移除：丢弃快照，clearEffects 里 restoreVanillaParticleState 会
        // 因快照已不存在而安全跳过，不会对已移除的 NMS handle 写 metadata。
        particleStates.remove(living.getUniqueId());
        pendingEffectParticleRefresh.remove(living.getUniqueId());
        clearEffects(living);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity target = event.getEntity();
        UUID targetId = target.getUniqueId();
        pendingEffectParticleRefresh.remove(targetId);
        Iterator<Map.Entry<UUID, Set<UUID>>> invertedEntries =
                upsideDownPacketTargets.entrySet().iterator();
        while (invertedEntries.hasNext()) {
            Set<UUID> inverted = invertedEntries.next().getValue();
            inverted.remove(targetId);
            if (inverted.isEmpty()) {
                invertedEntries.remove();
            }
        }
        stopTrackReplayListenerIfIdle();
        // Resolving Paper's DamageSource is unnecessary for nearly every
        // death on a normal server. Keep the exact Forge kill-heal behavior,
        // but only enter that bridge while Bloody Mary exists anywhere.
        if (hasAnyActiveEffect(PREFIX + "bloody_mary")) {
            EntityDamageEvent lastDamage = target.getLastDamageCause();
            LivingEntity killer = lastDamage == null ? null : attackingLiving(lastDamage);
            if (killer != null && !killer.equals(target) && has(killer, PREFIX + "bloody_mary")) {
                AttributeInstance targetHealth = target.getAttribute(Attribute.MAX_HEALTH);
                if (targetHealth != null) {
                    double heal = Math.floor(targetHealth.getValue() / 3.0);
                    if (heal > 0) {
                        killer.heal(heal);
                    }
                }
            }
        }
        // Every valid persisted effect is loaded into active on join/chunk load.
        // Ordinary deaths therefore have no Tavern state to clear and should
        // avoid PDC access, three attribute lookups and a global expiry rescan.
        if (active.containsKey(targetId)) {
            clearEffects(target);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!hasAnyActiveEffect(PREFIX + "tomb_raider")) {
            return;
        }
        LivingEntity attacker = attackingLiving(event);
        if (attacker == null || !has(attacker, PREFIX + "tomb_raider")
                || !(event.getEntity() instanceof LivingEntity target)
                || !matchesEntityTag(target.getType(), PREFIX + "tomb_raider_disarmable")
                || ThreadLocalRandom.current().nextDouble() >= 0.3) {
            return;
        }
        EntityEquipment equipment = target.getEquipment();
        if (equipment == null) {
            return;
        }
        ItemStack mainHand = equipment.getItemInMainHand();
        if (mainHand.isEmpty()) {
            return;
        }
        if (mainHand.getItemMeta() instanceof Damageable damageable && mainHand.getType().getMaxDurability() > 0) {
            damageable.setDamage(mainHand.getType().getMaxDurability() - 1);
            mainHand.setItemMeta(damageable);
        }
        equipment.setItemInMainHand(null);
        // World#dropItem uses the same ItemEntity constructor as the source,
        // so the disarmed item pops with the identical random velocity.
        org.bukkit.entity.Item dropped = target.getWorld().dropItem(target.getLocation(), mainHand);
        dropped.setPickupDelay(40);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getTarget() instanceof LivingEntity target) || !has(target, PREFIX + "grass_stealth")
                || !target.isSneaking() || !isStealthPlant(target)) {
            return;
        }
        event.setCancelled(true);
    }

    public boolean has(LivingEntity living, String effect) {
        ActiveEffect value = active.getOrDefault(living.getUniqueId(), Map.of()).get(effect);
        return value != null && value.remainingTicks() > 0;
    }

    private boolean hasAnyActiveEffect(String effect) {
        for (Map<String, ActiveEffect> effects : active.values()) {
            ActiveEffect value = effects.get(effect);
            if (value != null && value.remainingTicks() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Launches the same zero-velocity ThrownPotion used by DrinkBlock in the
     * archived Forge implementation. Gravity decides the delay and final
     * splash point; ProjectileLaunchEvent prepares its rolled effect payload.
     */
    public boolean launchSplash(ItemStack drink, Location origin, Entity owner) {
        if (!isThrownDrink(drink)) {
            return false;
        }
        ItemStack payload = drink.clone();
        payload.setAmount(1);
        origin.getWorld().spawn(origin, ThrownPotion.class, potion -> {
            potion.setItem(payload);
            potion.setVelocity(new Vector());
            if (owner instanceof ProjectileSource source) {
                potion.setShooter(source);
            }
        });
        return true;
    }

    /** @deprecated use {@link #launchSplash(ItemStack, Location, Entity)}. */
    @Deprecated(forRemoval = false)
    public boolean splash(ItemStack drink, Location center, Entity owner) {
        return launchSplash(drink, center, owner);
    }

    private boolean isThrownDrink(ItemStack drink) {
        String id = items.id(drink);
        return catalog.hasDrinkEffects(id) && !catalog.isCocktail(id);
    }

    private void prepareThrownDrink(ThrownPotion potion) {
        List<EffectSpec> rolled = effectsFor(potion.getItem()).stream()
                .filter(spec -> EffectSemantics.rolls(
                        ThreadLocalRandom.current().nextDouble(), spec.probability()))
                .map(spec -> new EffectSpec(spec.effect(), spec.durationTicks(), spec.amplifier(), 1.0))
                .toList();
        List<EffectSpec> customEffects = new ArrayList<>();
        ItemStack payload = potion.getItem().clone();
        payload.setAmount(1);
        if (payload.getItemMeta() instanceof PotionMeta potionMeta) {
            // The deployable custom item uses mundane only as an effectless
            // consume base. Forge's DrinkBlockItem had no vanilla base potion,
            // so remove it before putting the authored effects into a projectile.
            potionMeta.setBasePotionType(null);
            potionMeta.clearCustomEffects();
            boolean hasVanillaEffect = false;
            for (EffectSpec spec : rolled) {
                NamespacedKey key = NamespacedKey.fromString(spec.effect());
                PotionEffectType type = key == null ? null : Registry.EFFECT.get(key);
                if (type == null) {
                    customEffects.add(spec);
                    continue;
                }
                int duration = type.isInstant() ? 1 : Math.max(1, spec.durationTicks());
                potionMeta.addCustomEffect(new PotionEffect(
                        type, duration, spec.amplifier(), false, true, true), true);
                hasVanillaEffect = true;
            }

            // A harmless one-tick effect forces Minecraft to create the real
            // PotionSplashEvent for a payload containing only custom effects.
            // Vanilla's splash loop discards non-instant effects <= 20 ticks.
            if (!customEffects.isEmpty() && !hasVanillaEffect) {
                PotionEffectType marker = Registry.EFFECT.get(NamespacedKey.minecraft("luck"));
                if (marker != null) {
                    potionMeta.addCustomEffect(
                            new PotionEffect(marker, 1, 0, false, false, false), true);
                }
            }
            payload.setItemMeta(potionMeta);
        }
        potion.setItem(payload);
        potion.getPersistentDataContainer().set(splashPreparedKey, PersistentDataType.BYTE, (byte) 1);
        if (!customEffects.isEmpty()) {
            writeSplashEffects(potion.getPersistentDataContainer(), customEffects);
        }
    }

    private void writeSplashEffects(PersistentDataContainer owner, List<EffectSpec> effects) {
        PersistentDataContainer encoded = owner.getAdapterContext().newPersistentDataContainer();
        List<String> ids = new ArrayList<>(effects.size());
        List<long[]> values = new ArrayList<>(effects.size());
        for (EffectSpec effect : effects) {
            ids.add(effect.effect());
            values.add(new long[]{effect.durationTicks(), effect.amplifier()});
        }
        encoded.set(effectIdsKey, STRING_LIST, List.copyOf(ids));
        encoded.set(effectValuesKey, LONG_ARRAY_LIST, List.copyOf(values));
        owner.set(splashCustomEffectsKey, PersistentDataType.TAG_CONTAINER, encoded);
    }

    private List<EffectSpec> readSplashEffects(PersistentDataContainer owner) {
        PersistentDataContainer encoded = owner.get(
                splashCustomEffectsKey, PersistentDataType.TAG_CONTAINER);
        if (encoded == null) {
            return List.of();
        }
        List<String> ids = encoded.get(effectIdsKey, STRING_LIST);
        List<long[]> values = encoded.get(effectValuesKey, LONG_ARRAY_LIST);
        if (ids == null || values == null) {
            return List.of();
        }
        List<EffectSpec> effects = new ArrayList<>();
        for (int index = 0; index < Math.min(ids.size(), values.size()); index++) {
            long[] packed = values.get(index);
            if (packed == null || packed.length != 2
                    || packed[0] <= 0 || packed[0] > Integer.MAX_VALUE
                    || packed[1] < Integer.MIN_VALUE || packed[1] > Integer.MAX_VALUE) {
                continue;
            }
            effects.add(new EffectSpec(ids.get(index), (int) packed[0],
                    (int) packed[1], 1.0));
        }
        return List.copyOf(effects);
    }

    private List<EffectSpec> effectsFor(ItemStack drink) {
        List<EffectSpec> specs = items.signatureEffects(drink);
        if (!specs.isEmpty()) {
            return specs;
        }
        String itemId = items.id(drink);
        int level = catalog.isCocktail(itemId) ? 1 : items.brewLevel(drink);
        return level > 0 ? catalog.effects(itemId, level) : List.of();
    }

    private void apply(LivingEntity target, EffectSpec spec) {
        if (spec.effect().startsWith("minecraft:")) {
            applyVanilla(target, spec);
            return;
        }
        if (INSTANT_EFFECTS.contains(spec.effect())) {
            applyInstant(target, spec.effect());
            return;
        }
        long currentTick = syncCurrentTick();
        Map<String, ActiveEffect> effects = active.computeIfAbsent(
                target.getUniqueId(), ignored -> new LinkedHashMap<>());
        ActiveEffect previous = effects.get(spec.effect());
        EffectSemantics.EffectState merged = EffectSemantics.mergeEffect(
                previous == null ? null : previous.stateAt(currentTick),
                spec.durationTicks(), spec.amplifier());
        if (merged == null) {
            return;
        }
        UUID uuid = target.getUniqueId();
        effects.put(spec.effect(), new ActiveEffect(spec.effect(), merged, currentTick));
        activeEntities.put(uuid, target);
        refreshFastTickMembership(uuid, effects);
        recomputeNextPassiveExpiryTick();
        ensureTickTask();
        syncEffectParticleMetadata(target, effects);
        save(target);
        reconcileAttributes(target, effects);
        if (target instanceof Player player) {
            updateEffectHud(player, effects);
            syncPrivateTipsyVisual(player, effects);
        }
    }

    private static void applyVanilla(LivingEntity target, EffectSpec spec) {
        NamespacedKey key = NamespacedKey.fromString(spec.effect());
        PotionEffectType type = key == null ? null : Registry.EFFECT.get(key);
        if (type != null) {
            int duration = type.isInstant() ? Math.max(1, spec.durationTicks()) : spec.durationTicks();
            target.addPotionEffect(new PotionEffect(type, duration, spec.amplifier(), false, true, true));
        }
    }

    private void tick() {
        if (!fastTickTask) {
            // A passive wake is one-shot; release the completed task before
            // calculating the next maintenance/expiry wake.
            task = null;
            scheduledWakeTick = Long.MAX_VALUE;
        }
        syncCurrentTick();

        // Only four effects own per-tick gameplay. Passive countdowns are
        // advanced in one-second batches, with an extra exact wake on their
        // visible expiry tick. This keeps ordinary drinks out of the hot loop.
        tickFastEffects();
        boolean maintenanceTick = elapsedTicks % MAINTENANCE_INTERVAL_TICKS == 0
                || elapsedTicks >= nextPassiveExpiryTick;
        if (maintenanceTick) {
            maintainEffects(elapsedTicks % PERSIST_INTERVAL_TICKS == 0);
        }
        stopTickTaskIfIdle();
    }

    private void tickFastEffects() {
        Iterator<UUID> identities = fastTickEntities.iterator();
        while (identities.hasNext()) {
            UUID uuid = identities.next();
            Map<String, ActiveEffect> effects = active.get(uuid);
            LivingEntity living = activeEntities.get(uuid);
            if (effects == null || living == null) {
                identities.remove();
                continue;
            }

            boolean changed = false;
            Iterator<Map.Entry<String, ActiveEffect>> effectIterator =
                    effects.entrySet().iterator();
            while (effectIterator.hasNext()) {
                ActiveEffect effect = effectIterator.next().getValue();
                if (effect.tickKind() == TickKind.NONE) {
                    continue;
                }
                if (!tickEffect(living, effect)) {
                    effectIterator.remove();
                    changed = true;
                    continue;
                }
                int elapsed = effect.elapsedSince(elapsedTicks);
                boolean visibleExpired = effect.remainingTicks() <= elapsed;
                boolean remainsActive = effect.advanceTo(elapsedTicks);
                if (visibleExpired && living instanceof Player
                        && effect.tickKind() == TickKind.ARDENT_HEAT) {
                    applyHunger(living, 600);
                }
                if (!remainsActive) {
                    effectIterator.remove();
                    changed = true;
                } else {
                    changed |= visibleExpired;
                }
            }

            if (living instanceof Player player
                    && !effects.containsKey(PREFIX + "vision")) {
                visionPacketExpiry.remove(player.getUniqueId());
            }
            updateStealthVisibility(living, effects);
            if (changed) {
                syncEffectParticleMetadata(living, effects);
                save(living);
                reconcileAttributes(living, effects);
                if (living instanceof Player player) {
                    updateEffectHud(player, effects);
                    syncPrivateTipsyVisual(player, effects);
                }
            }
            if (effects.isEmpty()) {
                active.remove(uuid);
                activeEntities.remove(uuid);
                identities.remove();
            } else if (!hasFastTickEffect(effects)) {
                identities.remove();
            }
        }
    }

    private void maintainEffects(boolean persistThisTick) {
        long nextPassiveExpiry = Long.MAX_VALUE;
        Iterator<Map.Entry<UUID, Map<String, ActiveEffect>>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Map<String, ActiveEffect>> entry = iterator.next();
            LivingEntity living = activeEntities.get(entry.getKey());
            if (living == null) {
                fastTickEntities.remove(entry.getKey());
                iterator.remove();
                continue;
            }
            Map<String, ActiveEffect> effects = entry.getValue();
            boolean changed = false;
            Iterator<Map.Entry<String, ActiveEffect>> effectIterator =
                    effects.entrySet().iterator();
            while (effectIterator.hasNext()) {
                ActiveEffect effect = effectIterator.next().getValue();
                int elapsed = effect.elapsedSince(elapsedTicks);
                if (elapsed == 0) {
                    continue;
                }
                boolean visibleExpired = effect.remainingTicks() <= elapsed;
                boolean remainsActive = effect.advanceTo(elapsedTicks);
                if (visibleExpired && living instanceof Player
                        && effect.tickKind() == TickKind.ARDENT_HEAT) {
                    applyHunger(living, 600);
                }
                if (!remainsActive) {
                    effectIterator.remove();
                    changed = true;
                } else {
                    changed |= visibleExpired;
                }
            }
            if (living instanceof Player player
                    && !effects.containsKey(PREFIX + "vision")) {
                visionPacketExpiry.remove(player.getUniqueId());
            }
            if (stealthHidden.contains(entry.getKey())) {
                updateStealthVisibility(living, effects);
            }
            if (changed) {
                syncEffectParticleMetadata(living, effects);
            }
            if (changed || persistThisTick) {
                save(living);
            }

            // Line-style HUD countdowns remain one-second work. Corner HUD,
            // client particles and tipsy countdowns need no periodic refresh;
            // attribute repair shares the five-second persistence cadence.
            if (changed || persistThisTick) {
                reconcileAttributes(living, effects);
            }
            if (living instanceof Player player) {
                if (!cornerHud || changed) {
                    updateEffectHud(player, effects);
                }
                if (changed) {
                    syncPrivateTipsyVisual(player, effects);
                }
            }
            if (effects.isEmpty()) {
                fastTickEntities.remove(entry.getKey());
                iterator.remove();
                activeEntities.remove(entry.getKey());
            } else {
                refreshFastTickMembership(entry.getKey(), effects);
                for (ActiveEffect effect : effects.values()) {
                    if (effect.tickKind() == TickKind.NONE) {
                        nextPassiveExpiry = Math.min(
                                nextPassiveExpiry, effect.visibleExpiryTick());
                    }
                }
            }
        }
        nextPassiveExpiryTick = nextPassiveExpiry;
    }

    private void ensureTickTask() {
        if (active.isEmpty()) {
            return;
        }
        if (!fastTickEntities.isEmpty()) {
            if (task != null && fastTickTask) {
                return;
            }
            if (task != null) {
                task.cancel();
            }
            task = Bukkit.getScheduler().runTaskTimer(plugin, tickAction, 1L, 1L);
            fastTickTask = true;
            scheduledWakeTick = Long.MAX_VALUE;
            return;
        }

        long currentTick = syncCurrentTick();
        long nextMaintenanceTick = nextIntervalTick(
                currentTick, MAINTENANCE_INTERVAL_TICKS);
        long nextWakeTick = Math.min(nextMaintenanceTick, nextPassiveExpiryTick);
        if (task != null) {
            if (!fastTickTask && scheduledWakeTick <= nextWakeTick) {
                return;
            }
            task.cancel();
        }
        long delay = Math.max(1L, nextWakeTick - currentTick);
        task = Bukkit.getScheduler().runTaskLater(plugin, tickAction, delay);
        fastTickTask = false;
        scheduledWakeTick = nextWakeTick;
    }

    private void stopTickTaskIfIdle() {
        if (!active.isEmpty()) {
            ensureTickTask();
            return;
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
        fastTickTask = false;
        scheduledWakeTick = Long.MAX_VALUE;
        nextPassiveExpiryTick = Long.MAX_VALUE;
    }

    private long syncCurrentTick() {
        elapsedTicks = Integer.toUnsignedLong(Bukkit.getCurrentTick());
        return elapsedTicks;
    }

    private static long nextIntervalTick(long currentTick, long interval) {
        long remainder = currentTick % interval;
        return currentTick + (remainder == 0L ? interval : interval - remainder);
    }

    private void refreshFastTickMembership(UUID uuid, Map<String, ActiveEffect> effects) {
        if (hasFastTickEffect(effects)) {
            fastTickEntities.add(uuid);
        } else {
            fastTickEntities.remove(uuid);
        }
    }

    private static boolean hasFastTickEffect(Map<String, ActiveEffect> effects) {
        for (ActiveEffect effect : effects.values()) {
            if (effect.tickKind() != TickKind.NONE) {
                return true;
            }
        }
        return false;
    }

    private void recomputeNextPassiveExpiryTick() {
        long next = Long.MAX_VALUE;
        for (Map<String, ActiveEffect> effects : active.values()) {
            for (ActiveEffect effect : effects.values()) {
                if (effect.tickKind() == TickKind.NONE) {
                    next = Math.min(next, effect.visibleExpiryTick());
                }
            }
        }
        nextPassiveExpiryTick = next;
    }

    /**
     * The Forge client cancels the whole player render while stealthed in a
     * plant; the closest vanilla-protocol equivalent is the invisibility flag
     * (equipment stays visible). Tracking our own set keeps the flag from
     * clobbering invisibility granted by other sources.
     */
    private void updateStealthVisibility(LivingEntity living, Map<String, ActiveEffect> effects) {
        UUID uuid = living.getUniqueId();
        boolean hidden = stealthHidden.contains(uuid);
        boolean desired = effects.containsKey(PREFIX + "grass_stealth")
                && living.isSneaking() && isStealthPlant(living);
        if (desired == hidden) {
            return;
        }
        living.setInvisible(desired);
        if (desired) {
            stealthHidden.add(uuid);
        } else {
            stealthHidden.remove(uuid);
        }
    }

    private void restoreStealthVisibility(LivingEntity living) {
        if (stealthHidden.remove(living.getUniqueId())) {
            living.setInvisible(false);
        }
    }

    private void scheduleEffectParticleRefresh(LivingEntity living) {
        scheduleEffectParticleRefresh(living, 1L);
    }

    /**
     * 原版药水变化（或实体加载）后延迟两 tick 刷新粒子：NMS 会先根据真实
     * PotionEffect 重新生成 DATA_EFFECT_PARTICLES（覆盖我们写入的合并列表），
     * 此时重新捕获纯原版快照再合并，才不会把 Tavern 粒子重复叠加。
     */
    private void scheduleEffectParticleRefresh(LivingEntity living, long delay) {
        UUID uuid = living.getUniqueId();
        if (!particleMetadataAvailable || !active.containsKey(uuid)
                || !pendingEffectParticleRefresh.add(uuid)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingEffectParticleRefresh.remove(uuid);
            LivingEntity current = activeEntities.get(uuid);
            if (current != null) {
                refreshAfterVanillaPotionChange(current);
            }
        }, delay);
    }

    private void refreshAfterVanillaPotionChange(LivingEntity living) {
        Map<String, ActiveEffect> effects = active.get(living.getUniqueId());
        if (effects == null || effects.isEmpty()) {
            particleStates.remove(living.getUniqueId());
            return;
        }
        // NMS 已把 DATA_EFFECT_PARTICLES 恢复为纯原版粒子：重捕快照后合并。
        EffectParticleState vanilla = new EffectParticleState(
                ViewerEffectPackets.readEffectParticles(living),
                ViewerEffectPackets.readEffectAmbience(living));
        particleStates.put(living.getUniqueId(), vanilla);
        syncEffectParticleMetadata(living, effects);
    }

    private void syncEffectParticleMetadata(LivingEntity living,
                                            Map<String, ActiveEffect> effects) {
        if (!particleMetadataAvailable) {
            return;
        }
        if (effects.isEmpty()) {
            restoreVanillaParticleState(living);
            return;
        }
        try {
            applyMergedParticleState(living, effects);
        } catch (RuntimeException | LinkageError error) {
            disableEffectParticleMetadata(error);
        }
    }

    /**
     * 首次出现 Tavern 效果时捕获实体的纯原版粒子状态；之后每次合并都基于该
     * 快照，避免把已经写入真实字段的合并列表再次叠加（原版+Tavern+Tavern...）。
     */
    private EffectParticleState getOrCaptureVanillaState(LivingEntity living) {
        return particleStates.computeIfAbsent(living.getUniqueId(),
                ignored -> new EffectParticleState(
                        ViewerEffectPackets.readEffectParticles(living),
                        ViewerEffectPackets.readEffectAmbience(living)));
    }

    private void applyMergedParticleState(LivingEntity living,
                                          Map<String, ActiveEffect> effects) {
        EffectParticleState state = getOrCaptureVanillaState(living);
        List<Object> custom = buildCustomParticleOptions(effects);
        List<Object> merged = new ArrayList<>(
                state.vanillaParticles().size() + custom.size());
        merged.addAll(state.vanillaParticles());
        merged.addAll(custom);
        particleMetadataBuilds++;
        ViewerEffectPackets.setEffectParticleMetadata(living, merged, false);
    }

    /** 只根据 effect id 读取已缓存的粒子选项，不调用 packAll。 */
    private List<Object> buildCustomParticleOptions(Map<String, ActiveEffect> effects) {
        List<Object> particles = new ArrayList<>(effects.size());
        for (String effect : effects.keySet()) {
            Integer rgb = CustomEffectHudSemantics.color(effect);
            if (rgb == null) {
                continue;
            }
            Color color = effectColorCache.get(effect);
            if (color == null) {
                color = Color.fromRGB(rgb);
                effectColorCache.put(effect, color);
            }
            Object particle = effectParticleOptionCache.get(effect);
            if (particle == null) {
                particle = ViewerEffectPackets.entityEffectParticle(color);
                effectParticleOptionCache.put(effect, particle);
            }
            particles.add(particle);
        }
        return particles;
    }

    /** 最后一个 Tavern 效果到期/清除时恢复纯原版粒子 metadata。 */
    private void restoreVanillaParticleState(LivingEntity living) {
        pendingEffectParticleRefresh.remove(living.getUniqueId());
        EffectParticleState state = particleStates.remove(living.getUniqueId());
        if (!particleMetadataAvailable || state == null) {
            return;
        }
        particleMetadataBuilds++;
        try {
            ViewerEffectPackets.setEffectParticleMetadata(
                    living, state.vanillaParticles(), state.vanillaAmbient());
        } catch (RuntimeException | LinkageError error) {
            disableEffectParticleMetadata(error);
        }
    }

    private void restoreAllEffectParticleMetadata() {
        if (!particleMetadataAvailable || particleStates.isEmpty()) {
            return;
        }
        Throwable firstFailure = null;
        for (UUID uuid : new ArrayList<>(particleStates.keySet())) {
            LivingEntity living = activeEntities.get(uuid);
            if (living == null) {
                continue;
            }
            try {
                EffectParticleState state = particleStates.get(uuid);
                ViewerEffectPackets.setEffectParticleMetadata(
                        living, state.vanillaParticles(), state.vanillaAmbient());
            } catch (RuntimeException | LinkageError error) {
                if (firstFailure == null) {
                    firstFailure = error;
                }
            }
        }
        if (firstFailure != null) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "无法恢复部分实体的客户端原版药水粒子 metadata", firstFailure);
        }
        particleStates.clear();
        pendingEffectParticleRefresh.clear();
    }

    private void disableEffectParticleMetadata(Throwable error) {
        if (!particleMetadataAvailable) {
            return;
        }
        particleMetadataAvailable = false;
        // A late bridge failure may occur after other entities already received
        // merged metadata. Restore those snapshots, then disable only Tavern's
        // decorative particles; gameplay must not fall back to server tick work.
        for (UUID uuid : new ArrayList<>(particleStates.keySet())) {
            LivingEntity living = activeEntities.get(uuid);
            if (living == null) {
                continue;
            }
            try {
                EffectParticleState state = particleStates.get(uuid);
                ViewerEffectPackets.setEffectParticleMetadata(
                        living, state.vanillaParticles(), state.vanillaAmbient());
            } catch (RuntimeException | LinkageError ignored) {
                // The original bridge failure is logged below.
            }
        }
        particleStates.clear();
        pendingEffectParticleRefresh.clear();
        plugin.getLogger().log(java.util.logging.Level.WARNING,
                "无法使用 Paper 26.2 效果粒子 metadata 桥接；已禁用 Tavern 装饰粒子", error);
    }

    private boolean tickEffect(LivingEntity living, ActiveEffect effect) {
        switch (effect.tickKind()) {
            case VISION -> {
                if (EffectSemantics.ticksAt(effect.remainingTicks(), 50)) {
                    vision(living, effect.amplifier());
                }
            }
            case XP_DRAIN -> {
                if (living instanceof Player player) {
                    player.setExpCooldown(0);
                    if (player.getTicksLived() % 5 == 0) {
                        xpDrain(player);
                    }
                }
            }
            case GRASS_STEALTH -> {
                if (EffectSemantics.ticksAt(effect.remainingTicks(), 10)) {
                    grassStealth(living);
                }
            }
            case ARDENT_HEAT -> {
                if (living instanceof Player player && !ardentHeat(player)) {
                    applyHunger(player, 600);
                    return false;
                }
            }
            case NONE -> throw new IllegalStateException("Inert effect entered tickEffect");
        }
        return true;
    }

    private void vision(LivingEntity user, int amplifier) {
        if (!(user instanceof Player viewer)) {
            // This outline is a point-of-view effect. A non-player hit by a
            // splash has no client to receive it and must not expose the
            // outline globally to unrelated players.
            return;
        }
        double radius = Math.min(amplifier + 1, 3) * 6.0;
        boolean applied = false;
        PotionEffectType glowing = Registry.EFFECT.get(NamespacedKey.minecraft("glowing"));
        if (glowing == null) {
            return;
        }
        Map<UUID, Long> packetExpiry = visionPacketExpiry.computeIfAbsent(
                viewer.getUniqueId(), ignored -> new HashMap<>());
        packetExpiry.values().removeIf(expiry -> expiry <= elapsedTicks);
        for (Entity entity : user.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof LivingEntity living && !living.equals(user) && !living.isDead()) {
                PotionEffect serverEffect = living.getPotionEffect(glowing);
                long previousExpiry = packetExpiry.getOrDefault(
                        living.getUniqueId(), Long.MIN_VALUE);
                if (serverEffect == null && previousExpiry <= elapsedTicks) {
                    applied = true;
                }
                if (living.isTrackedBy(viewer)
                        && (serverEffect == null || serverEffect.getDuration() < 60)) {
                    viewer.sendPotionEffectChange(living,
                            new PotionEffect(glowing, 60, 0, false, true, true));
                }
                packetExpiry.put(living.getUniqueId(), elapsedTicks + 60);
            }
        }
        if (applied) {
            user.getWorld().playSound(user.getLocation(),
                    "kaleidoscope_tavern:effect.vision", SoundCategory.PLAYERS, 1.0F, 1.0F);
        }
    }

    private static void xpDrain(Player player) {
        for (Entity entity : player.getNearbyEntities(8, 8, 8)) {
            if (!(entity instanceof ExperienceOrb orb) || !orb.isValid()) {
                continue;
            }
            Location playerLocation = player.getLocation();
            Location orbLocation = orb.getLocation();
            Vector delta = new Vector(
                    playerLocation.getX() - orbLocation.getX(),
                    playerLocation.getY() + 0.5 - orbLocation.getY(),
                    playerLocation.getZ() - orbLocation.getZ());
            double distance = Math.max(0.01, playerLocation.distance(orbLocation));
            if (distance < 1.5) {
                PlayerPickupExperienceEvent pickup = new PlayerPickupExperienceEvent(player, orb);
                Bukkit.getPluginManager().callEvent(pickup);
                if (!pickup.isCancelled()) {
                    player.giveExp(orb.getExperience(), true);
                    int remainingCount = EffectSemantics.remainingOrbCountAfterPickup(orb.getCount());
                    if (remainingCount == 0) {
                        orb.remove();
                    } else {
                        orb.setCount(remainingCount);
                    }
                }
                player.setExpCooldown(0);
                continue;
            }
            double speed = Math.min(0.5 + 1.0 / distance, 1.5);
            orb.setVelocity(delta.normalize().multiply(speed));
        }
    }

    private void grassStealth(LivingEntity user) {
        if (!user.isSneaking() || !isStealthPlant(user)) {
            return;
        }
        if (user instanceof Player player) {
            player.setExhaustion(Math.min(40F, player.getExhaustion() + 0.1F));
        }
        for (Entity entity : user.getNearbyEntities(32, 32, 32)) {
            if (entity instanceof Mob mob && user.equals(mob.getTarget())) {
                mob.setTarget(null);
            }
        }
    }

    private boolean ardentHeat(Player player) {
        if (EffectSemantics.ardentHeatExhausted(player.getFoodLevel(), player.getSaturation())) {
            return false;
        }
        if (!player.isSprinting()) {
            return true;
        }
        BlockFace face = player.getFacing();
        boolean alongZ = face == BlockFace.NORTH || face == BlockFace.SOUTH;
        Block origin = player.getLocation().getBlock().getRelative(face);
        int broken = 0;
        for (int y = 0; y < 3; y++) {
            for (int lateral = -1; lateral <= 1; lateral++) {
                Block block = origin.getRelative(alongZ ? lateral : 0, y, alongZ ? 0 : lateral);
                if (matchesBlockTag(block, PREFIX + "ardent_heat_breakable")) {
                    // Level#destroyBlock drops loot but never block experience.
                    block.breakNaturally(true, false);
                    broken++;
                }
            }
        }
        if (broken == 0) {
            return true;
        }
        player.setExhaustion(Math.min(40F, player.getExhaustion() + 1.2F));
        List<EquipmentSlot> armor = new ArrayList<>();
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (!player.getInventory().getItem(slot).isEmpty()) {
                armor.add(slot);
            }
        }
        if (!armor.isEmpty()) {
            EquipmentSlot selected = armor.get(ThreadLocalRandom.current().nextInt(armor.size()));
            player.damageItemStack(selected, 1);
        } else {
            int collisions = player.getPersistentDataContainer()
                    .getOrDefault(collisionKey, PersistentDataType.INTEGER, 0) + 1;
            if (collisions >= 5) {
                player.damage(1.0);
                collisions -= 5;
            }
            player.getPersistentDataContainer().set(collisionKey, PersistentDataType.INTEGER, collisions);
        }
        return true;
    }

    private boolean isStealthPlant(LivingEntity living) {
        Block feet = living.getLocation().getBlock();
        return isStealthPlant(feet) || isStealthPlant(feet.getRelative(BlockFace.UP));
    }

    private boolean isStealthPlant(Block block) {
        if (VANILLA_CROP_BLOCKS.contains(block.getType())) {
            return block.getBlockData() instanceof Ageable ageable
                    && ageable.getAge() == ageable.getMaximumAge();
        }
        return matchesBlockTag(block, PREFIX + "grass_stealth_plants");
    }

    // The ardent-heat sprint check matches nine blocks per tick, so tag
    // members resolve once instead of hitting the registry on every block.
    private record CompiledBlockTag(List<Tag<Material>> materialTags, Set<String> ids,
                                    boolean hasCustomIds) {
    }

    private record CompiledEntityTag(List<Tag<EntityType>> entityTags, Set<String> ids) {
    }

    private boolean matchesBlockTag(Block block, String tagId) {
        CompiledBlockTag compiled = blockTagCache.computeIfAbsent(tagId, id -> {
            List<Tag<Material>> tags = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            boolean custom = false;
            for (String member : catalog.blockTag(id)) {
                if (member.startsWith("#")) {
                    NamespacedKey key = NamespacedKey.fromString(member.substring(1));
                    Tag<Material> tag = key == null ? null
                            : Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                    if (tag != null) {
                        tags.add(tag);
                    }
                } else {
                    ids.add(member);
                    custom |= !member.startsWith("minecraft:");
                }
            }
            return new CompiledBlockTag(List.copyOf(tags), Set.copyOf(ids), custom);
        });
        Material type = block.getType();
        for (Tag<Material> tag : compiled.materialTags()) {
            if (tag.isTagged(type)) {
                return true;
            }
        }
        if (compiled.ids().contains(type.getKey().asString())) {
            return true;
        }
        return compiled.hasCustomIds() && compiled.ids().contains(customBlockId(block));
    }

    private boolean matchesEntityTag(EntityType type, String tagId) {
        CompiledEntityTag compiled = entityTagCache.computeIfAbsent(tagId, id -> {
            List<Tag<EntityType>> tags = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (String member : catalog.entityTypeTag(id)) {
                if (member.startsWith("#")) {
                    NamespacedKey key = NamespacedKey.fromString(member.substring(1));
                    Tag<EntityType> tag = key == null ? null
                            : Bukkit.getTag(Tag.REGISTRY_ENTITY_TYPES, key, EntityType.class);
                    if (tag != null) {
                        tags.add(tag);
                    }
                } else {
                    ids.add(member);
                }
            }
            return new CompiledEntityTag(List.copyOf(tags), Set.copyOf(ids));
        });
        for (Tag<EntityType> tag : compiled.entityTags()) {
            if (tag.isTagged(type)) {
                return true;
            }
        }
        return compiled.ids().contains(type.getKey().asString());
    }

    private static String customBlockId(Block block) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        return state == null ? "" : state.owner().value().id().toString();
    }

    private void applyInstant(LivingEntity user, String effect) {
        switch (effect) {
            case PREFIX + "upside_down" -> {
                if (!(user instanceof Player viewer)) {
                    return;
                }
                UUID viewerId = viewer.getUniqueId();
                Set<UUID> inverted = upsideDownPacketTargets.computeIfAbsent(
                        viewerId, ignored -> new HashSet<>());
                for (Entity entity : user.getNearbyEntities(16, 16, 16)) {
                    if (entity instanceof Mob mob && !mob.isDead()) {
                        inverted.add(mob.getUniqueId());
                        if (mob.isTrackedBy(viewer)) {
                            sendUpsideDownPacket(viewer, mob);
                        }
                    }
                }
                if (inverted.isEmpty()) {
                    upsideDownPacketTargets.remove(viewerId);
                } else {
                    // 新的倒置目标需要追踪重放：确保 PlayerTrackEntityEvent 监听器在线。
                    ensureTrackReplayListener();
                }
            }
            case PREFIX + "zenith" -> {
                Location current = user.getLocation();
                int surface = EffectSemantics.surfaceY(
                        current.getWorld().getHighestBlockYAt(current, HeightMap.MOTION_BLOCKING));
                if (current.getBlockY() < surface) {
                    current.getWorld().playSound(current, "minecraft:item.chorus_fruit.teleport",
                            SoundCategory.PLAYERS, 1.0F, 1.0F);
                    Location destination = new Location(current.getWorld(), current.getBlockX() + 0.5,
                            surface, current.getBlockZ() + 0.5, current.getYaw(), current.getPitch());
                    user.teleport(destination);
                    user.setFallDistance(0F);
                    destination.getWorld().playSound(destination, "minecraft:item.chorus_fruit.teleport",
                            SoundCategory.PLAYERS, 1.0F, 1.0F);
                    applyHunger(user, 600);
                }
            }
            case PREFIX + "shriek_attack" -> shriek(user);
            default -> {
                // The caller only invokes registered instant effects.
            }
        }
    }

    private void sendUpsideDownPacket(Player viewer, Entity target) {
        if (!upsideDownPacketsAvailable) {
            return;
        }
        try {
            ViewerEffectPackets.showUpsideDown(viewer, target);
        } catch (RuntimeException | LinkageError error) {
            upsideDownPacketsAvailable = false;
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "无法发送仅本人可见的 upside_down 实体数据包；已停用该视觉桥接", error);
        }
    }

    private void restoreUpsideDownPackets() {
        if (!upsideDownPacketsAvailable) {
            return;
        }
        try {
            for (Map.Entry<UUID, Set<UUID>> entry : upsideDownPacketTargets.entrySet()) {
                Player viewer = Bukkit.getPlayer(entry.getKey());
                if (viewer == null) {
                    continue;
                }
                for (UUID targetId : entry.getValue()) {
                    Entity target = Bukkit.getEntity(targetId);
                    if (target != null && target.isValid() && target.isTrackedBy(viewer)) {
                        ViewerEffectPackets.restoreCustomName(viewer, target);
                    }
                }
            }
        } catch (RuntimeException | LinkageError error) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "无法恢复 upside_down 的客户端实体数据", error);
        }
    }

    private static void shriek(LivingEntity user) {
        Location eye = user.getEyeLocation();
        Vector look = eye.getDirection().normalize();
        Vector horizontal = new Vector(look.getX(), 0, look.getZ());
        if (horizontal.lengthSquared() > 0.001) {
            horizontal.normalize();
        } else {
            // Looking straight up or down knocks targets purely upward.
            horizontal = new Vector();
        }
        double damage = user.getHealth() * 1.2;
        DamageSource damageSource = DamageSource.builder(DamageType.SONIC_BOOM)
                .withCausingEntity(user)
                .withDirectEntity(user)
                .withDamageLocation(eye)
                .build();
        for (Entity entity : user.getNearbyEntities(32, 32, 32)) {
            if (!(entity instanceof LivingEntity target) || target.equals(user) || target.isDead()) {
                continue;
            }
            Vector targetCenter = target.getLocation().add(0, target.getHeight() / 2.0, 0).toVector();
            Vector toTarget = targetCenter.subtract(eye.toVector());
            double projection = toTarget.dot(look);
            if (projection < 0 || projection > 32) {
                continue;
            }
            Vector closest = eye.toVector().add(look.clone().multiply(projection));
            if (closest.distance(targetCenter) > 1.0 + target.getWidth() / 2.0) {
                continue;
            }
            target.damage(damage, damageSource);
            target.setVelocity(target.getVelocity().add(horizontal.clone().multiply(0.63)).add(new Vector(0, 0.28, 0)));
        }
        user.getWorld().playSound(user.getLocation(), "minecraft:entity.warden.sonic_boom",
                SoundCategory.PLAYERS, 1.0F, 1.0F);
        for (int distance = 2; distance <= 32; distance += 2) {
            user.getWorld().spawnParticle(Particle.SONIC_BOOM,
                    eye.clone().add(look.clone().multiply(distance)), 1, 0, 0, 0, 0);
        }
    }

    private void reconcileAttributes(LivingEntity living, Map<String, ActiveEffect> effects) {
        ActiveEffect heels = effects.get(PREFIX + "high_heels");
        reconcileModifier(living, Attribute.STEP_HEIGHT, heelsModifierKey,
                heels == null ? null : 0.5 * (heels.amplifier() + 1));
        ActiveEffect longReach = effects.get(PREFIX + "long_reach");
        Double reachAmount = longReach == null ? null : 3.0 * (longReach.amplifier() + 1);
        reconcileModifier(living, Attribute.BLOCK_INTERACTION_RANGE, blockReachModifierKey, reachAmount);
        reconcileModifier(living, Attribute.ENTITY_INTERACTION_RANGE, entityReachModifierKey, reachAmount);
    }

    private static void reconcileModifier(LivingEntity living, Attribute attribute, NamespacedKey key, Double amount) {
        AttributeInstance instance = living.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        AttributeModifier existing = instance.getModifier(key);
        if (amount == null) {
            if (existing != null) {
                instance.removeModifier(existing);
            }
        } else if (existing == null || Double.compare(existing.getAmount(), amount) != 0) {
            if (existing != null) {
                instance.removeModifier(existing);
            }
            instance.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    private void removeAttributeModifiers(LivingEntity living) {
        reconcileModifier(living, Attribute.STEP_HEIGHT, heelsModifierKey, null);
        reconcileModifier(living, Attribute.BLOCK_INTERACTION_RANGE, blockReachModifierKey, null);
        reconcileModifier(living, Attribute.ENTITY_INTERACTION_RANGE, entityReachModifierKey, null);
    }

    private static LivingEntity attackingLiving(EntityDamageEvent damage) {
        Entity source = damage.getDamageSource().getCausingEntity();
        return source instanceof LivingEntity living ? living : null;
    }

    private static void applyHunger(LivingEntity living, int duration) {
        PotionEffectType hunger = Registry.EFFECT.get(NamespacedKey.minecraft("hunger"));
        if (hunger != null) {
            living.addPotionEffect(new PotionEffect(hunger, duration, 0, false, true, true));
        }
    }

    private void detectEffectClear(CommandSender sender, String rawCommand) {
        Optional<EffectSemantics.ClearCommand> parsed = EffectSemantics.parseClearCommand(rawCommand);
        if (parsed.isEmpty() || !sender.hasPermission("minecraft.command.effect")) {
            return;
        }
        List<UUID> targets = new ArrayList<>();
        EffectSemantics.ClearCommand command = parsed.get();
        if (command.targetsSender()) {
            if (sender instanceof LivingEntity living) {
                targets.add(living.getUniqueId());
            }
        } else {
            try {
                for (Entity entity : Bukkit.selectEntities(sender, command.target())) {
                    if (entity instanceof LivingEntity living) {
                        targets.add(living.getUniqueId());
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid selectors are rejected by the vanilla command too.
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (UUID target : targets) {
                Entity entity = Bukkit.getEntity(target);
                if (entity instanceof LivingEntity living) {
                    clearEffects(living);
                }
            }
        });
    }

    private void load(LivingEntity living) {
        syncCurrentTick();
        UUID uuid = living.getUniqueId();
        Map<String, ActiveEffect> effects = read(living);
        if (effects.isEmpty()) {
            restoreVanillaParticleState(living);
            activeEntities.remove(uuid);
            active.remove(uuid);
            fastTickEntities.remove(uuid);
            recomputeNextPassiveExpiryTick();
        } else {
            active.put(uuid, effects);
            activeEntities.put(uuid, living);
            refreshFastTickMembership(uuid, effects);
            recomputeNextPassiveExpiryTick();
            ensureTickTask();
            // 实体加载后不立即读取可能尚未稳定的 metadata：等两 tick 让 NMS
            // 根据存档里的真实药水效果恢复纯原版粒子，再合并写入。代价只是
            // 加载后约 0.1 秒内 Tavern 粒子暂不可见，比读取错误/重复粒子安全。
            scheduleEffectParticleRefresh(living, 2L);
        }
        reconcileAttributes(living, effects);
        if (living instanceof Player player) {
            updateEffectHud(player, effects);
            syncPrivateTipsyVisual(player, effects);
        }
    }

    private Map<String, ActiveEffect> read(LivingEntity living) {
        Map<String, ActiveEffect> result = new LinkedHashMap<>();
        PersistentDataContainer encoded = living.getPersistentDataContainer()
                .get(activeKey, PersistentDataType.TAG_CONTAINER);
        if (encoded == null) {
            return result;
        }
        List<String> ids = encoded.get(effectIdsKey, STRING_LIST);
        List<long[]> values = encoded.get(effectValuesKey, LONG_ARRAY_LIST);
        if (ids == null || values == null) {
            return result;
        }
        for (int index = 0; index < Math.min(ids.size(), values.size()); index++) {
            EffectSemantics.EffectState state = EffectSemantics.decodeState(values.get(index));
            if (state == null || state.remainingTicks() <= 0) {
                continue;
            }
            String id = ids.get(index);
            if (id != null && NamespacedKey.fromString(id) != null) {
                result.put(id, new ActiveEffect(id, state, elapsedTicks));
            }
        }
        return result;
    }

    private void save(LivingEntity living) {
        long currentTick = syncCurrentTick();
        Map<String, ActiveEffect> effects = active.getOrDefault(living.getUniqueId(), Map.of());
        if (effects.isEmpty()) {
            living.getPersistentDataContainer().remove(activeKey);
            return;
        }
        PersistentDataContainer owner = living.getPersistentDataContainer();
        PersistentDataContainer encoded = owner.getAdapterContext().newPersistentDataContainer();
        List<String> ids = new ArrayList<>(effects.size());
        List<long[]> values = new ArrayList<>(effects.size());
        for (ActiveEffect effect : effects.values()) {
            EffectSemantics.EffectState state = effect.stateAt(currentTick);
            if (state == null) {
                continue;
            }
            ids.add(effect.effect());
            values.add(EffectSemantics.encodeState(state));
        }
        if (ids.isEmpty()) {
            owner.remove(activeKey);
            return;
        }
        encoded.set(effectIdsKey, STRING_LIST, List.copyOf(ids));
        encoded.set(effectValuesKey, LONG_ARRAY_LIST, List.copyOf(values));
        owner.set(activeKey, PersistentDataType.TAG_CONTAINER, encoded);
    }

    private boolean hasPersistedEffects(LivingEntity living) {
        return living.getPersistentDataContainer().has(activeKey, PersistentDataType.TAG_CONTAINER);
    }

    /**
     * The MiniMessage HUD line for PlaceholderAPI consumers such as
     * CustomNameplates. Empty when the player has no tavern effects.
     */
    public String effectHudMiniMessage(Player player) {
        Map<String, ActiveEffect> effects = active.get(player.getUniqueId());
        if (effects == null) {
            return "";
        }
        return hudLine(effects);
    }

    private String hudLine(Map<String, ActiveEffect> effects) {
        List<CustomEffectHudSemantics.EffectEntry> entries = hudEntries(effects);
        return cornerHud
                ? CustomEffectHudSemantics.cornerLine(entries, hudGuiHalfWidth)
                : CustomEffectHudSemantics.miniMessageLine(entries);
    }

    public int activeEffectCount(Player player) {
        Map<String, ActiveEffect> effects = active.get(player.getUniqueId());
        return effects == null ? 0 : effects.size();
    }

    private void updateEffectHud(Player player, Map<String, ActiveEffect> effects) {
        if (!builtinHud) {
            return;
        }
        if (effects.isEmpty()) {
            hideEffectHud(player);
            return;
        }
        // The corner style only changes when the effect set changes, so the
        // per-second refresh usually skips the MiniMessage re-parse entirely.
        String line = hudLine(effects);
        if (line.equals(effectHudLines.get(player.getUniqueId()))
                && effectHudBars.containsKey(player.getUniqueId())) {
            return;
        }
        Component title = MiniMessage.miniMessage().deserialize(line);
        effectHudLines.put(player.getUniqueId(), line);
        BossBar bar = effectHudBars.get(player.getUniqueId());
        if (bar == null) {
            // The corner style hides the bar itself: the pack overrides the
            // YELLOW boss bar sprites with fully transparent textures.
            bar = BossBar.bossBar(title, 1.0F,
                    cornerHud ? BossBar.Color.YELLOW : BossBar.Color.BLUE,
                    BossBar.Overlay.PROGRESS);
            effectHudBars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(title);
        }
    }

    private static List<CustomEffectHudSemantics.EffectEntry> hudEntries(
            Map<String, ActiveEffect> effects) {
        return effects.values().stream()
                .map(effect -> new CustomEffectHudSemantics.EffectEntry(
                        effect.effect(), effect.remainingTicks(), effect.amplifier()))
                .toList();
    }

    private void hideEffectHud(Player player) {
        effectHudLines.remove(player.getUniqueId());
        BossBar bar = effectHudBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    /**
     * The archived Forge client applied {@code slightly_tipsy} only to its
     * local camera. Vanilla has no camera-roll packet, so a hidden, client-only
     * nausea effect is the closest server-only approximation. This never enters
     * the player's real potion-effect collection or leaks to other viewers.
     *
     * <p>The proxy carries the remaining tipsy duration and is sent only when
     * that duration actually changes (first drink, refill, expiry), so the
     * client counts it down on its own and a missed restore can never leave a
     * permanent nausea behind.
     */
    private void syncPrivateTipsyVisual(Player player, Map<String, ActiveEffect> effects) {
        long currentTick = syncCurrentTick();
        PotionEffectType nausea = Registry.EFFECT.get(NamespacedKey.minecraft("nausea"));
        if (nausea == null) {
            return;
        }
        ActiveEffect tipsy = effects.get(PREFIX + "slightly_tipsy");
        PotionEffect realNausea = player.getPotionEffect(nausea);
        UUID uuid = player.getUniqueId();
        EffectSemantics.EffectState tipsyState = tipsy == null
                ? null
                : tipsy.stateAt(currentTick);
        if (tipsyState == null) {
            if (privateTipsyExpiry.remove(uuid) != null) {
                restorePotionEffectView(player, nausea, realNausea);
            }
            return;
        }
        if (realNausea != null) {
            // A genuine vanilla nausea is present: never hide it behind the
            // tipsy proxy, and clear any previously sent proxy so the real
            // effect is shown with its own remaining duration.
            if (privateTipsyExpiry.remove(uuid) != null) {
                player.sendPotionEffectChange(player, realNausea);
            }
            return;
        }
        int remaining = Math.max(1, tipsyState.remainingTicks());
        long expiryTick = tipsy.visibleExpiryTick();
        if (privateTipsyExpiry.getOrDefault(uuid, Long.MIN_VALUE) != expiryTick) {
            privateTipsyExpiry.put(uuid, expiryTick);
            player.sendPotionEffectChange(player, new PotionEffect(
                    nausea, remaining, 0, false, false, false));
        }
    }

    private void restorePrivateTipsyVisual(Player player) {
        PotionEffectType nausea = Registry.EFFECT.get(NamespacedKey.minecraft("nausea"));
        if (nausea == null || privateTipsyExpiry.remove(player.getUniqueId()) == null) {
            return;
        }
        restorePotionEffectView(player, nausea, player.getPotionEffect(nausea));
    }

    private static void restorePotionEffectView(Player player, PotionEffectType type,
                                                PotionEffect realEffect) {
        if (realEffect == null) {
            player.sendPotionEffectChangeRemove(player, type);
        } else {
            player.sendPotionEffectChange(player, realEffect);
        }
    }

    private void resetPrivateTipsyVisual(Player player) {
        restorePrivateTipsyVisual(player);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                syncPrivateTipsyVisual(player,
                        active.getOrDefault(player.getUniqueId(), Map.of()));
            }
        });
    }

    private void clearEffects(LivingEntity living) {
        UUID uuid = living.getUniqueId();
        restoreVanillaParticleState(living);
        activeEntities.remove(uuid);
        active.remove(uuid);
        fastTickEntities.remove(uuid);
        visionPacketExpiry.remove(uuid);
        living.getPersistentDataContainer().remove(activeKey);
        removeAttributeModifiers(living);
        restoreStealthVisibility(living);
        if (living instanceof Player player) {
            hideEffectHud(player);
            restorePrivateTipsyVisual(player);
        }
        recomputeNextPassiveExpiryTick();
        stopTrackReplayListenerIfIdle();
        stopTickTaskIfIdle();
    }

    /** 只读的追踪重放与粒子构建统计，供 /kt status 与排障使用。 */
    public record EffectStats(long trackEvents, long trackHits, long trackFlushes,
                              long metadataBuilds) {
    }

    public EffectStats effectStats() {
        return new EffectStats(trackEvents, trackHits, trackFlushes,
                particleMetadataBuilds);
    }

    /** 纯原版粒子快照：写入合并列表后必须基于它，不能二次读取真实字段。 */
    private record EffectParticleState(List<Object> vanillaParticles,
                                       boolean vanillaAmbient) {
    }

    /** 一个 tick 内同一目标实体的 upside_down 追踪重放，按 viewer 分组。 */
    private static final class PendingTrackTarget {
        private final LivingEntity target;
        private final List<Player> upsideDownViewers = new ArrayList<>();

        private PendingTrackTarget(LivingEntity target) {
            this.target = target;
        }
    }

    private enum TickKind {
        NONE,
        VISION,
        XP_DRAIN,
        GRASS_STEALTH,
        ARDENT_HEAT;

        private static TickKind forEffect(String effect) {
            return switch (effect) {
                case PREFIX + "vision" -> VISION;
                case PREFIX + "xp_drain" -> XP_DRAIN;
                case PREFIX + "grass_stealth" -> GRASS_STEALTH;
                case PREFIX + "ardent_heat" -> ARDENT_HEAT;
                default -> NONE;
            };
        }
    }

    private static final class ActiveEffect {
        private final String effect;
        private final TickKind tickKind;
        private final EffectSemantics.MutableEffectState state;
        private long lastAdvancedTick;

        private ActiveEffect(String effect, EffectSemantics.EffectState state,
                             long currentTick) {
            this.effect = effect;
            this.tickKind = TickKind.forEffect(effect);
            this.state = new EffectSemantics.MutableEffectState(state);
            this.lastAdvancedTick = currentTick;
        }

        private String effect() {
            return effect;
        }

        private EffectSemantics.EffectState stateAt(long currentTick) {
            return state.snapshotAfter(elapsedSince(currentTick));
        }

        private TickKind tickKind() {
            return tickKind;
        }

        private int remainingTicks() {
            return state.remainingTicks();
        }

        private int amplifier() {
            return state.amplifier();
        }

        private int elapsedSince(long currentTick) {
            long elapsed = Math.max(0L, currentTick - lastAdvancedTick);
            return (int) Math.min(Integer.MAX_VALUE, elapsed);
        }

        private boolean advanceTo(long currentTick) {
            boolean active = state.advance(elapsedSince(currentTick));
            lastAdvancedTick = currentTick;
            return active;
        }

        private long visibleExpiryTick() {
            return lastAdvancedTick + remainingTicks();
        }
    }
}
