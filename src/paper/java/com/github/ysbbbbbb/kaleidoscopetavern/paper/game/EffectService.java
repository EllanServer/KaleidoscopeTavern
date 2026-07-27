package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

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
    private final Map<UUID, BossBar> effectHudBars = new HashMap<>();
    private final Map<UUID, String> effectHudLines = new HashMap<>();
    private final Set<UUID> privateTipsyVisual = new HashSet<>();
    private final Map<UUID, Map<UUID, Long>> visionPacketExpiry = new HashMap<>();
    private final Map<UUID, Set<UUID>> upsideDownPacketTargets = new HashMap<>();
    private final Set<UUID> stealthHidden = new HashSet<>();
    private final Map<String, Color> effectColorCache = new HashMap<>();
    private final Map<String, Object> effectParticleOptionCache = new HashMap<>();
    private final Map<String, CompiledBlockTag> blockTagCache = new HashMap<>();
    private final Map<String, CompiledEntityTag> entityTagCache = new HashMap<>();
    private final boolean builtinHud;
    private final boolean cornerHud;
    private final int hudGuiHalfWidth;
    private long elapsedTicks;
    private BukkitTask task;
    private boolean upsideDownPacketsAvailable = true;
    private boolean particlePacketsAvailable = true;

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
        for (UUID uuid : new ArrayList<>(privateTipsyVisual)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                restorePrivateTipsyVisual(player);
            }
        }
        effectHudBars.clear();
        effectHudLines.clear();
        privateTipsyVisual.clear();
        for (UUID uuid : new ArrayList<>(stealthHidden)) {
            if (Bukkit.getEntity(uuid) instanceof LivingEntity living) {
                living.setInvisible(false);
            }
        }
        stealthHidden.clear();
        visionPacketExpiry.clear();
        restoreUpsideDownPackets();
        upsideDownPacketTargets.clear();
        activeEntities.clear();
        active.clear();
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
    public void onPotionEffectsCleared(EntityPotionEffectEvent event) {
        if (event.getAction() == EntityPotionEffectEvent.Action.CLEARED
                && (event.getCause() == EntityPotionEffectEvent.Cause.COMMAND
                || event.getCause() == EntityPotionEffectEvent.Cause.MILK)) {
            clearEffects(event.getEntity());
        }
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
        privateTipsyVisual.remove(event.getPlayer().getUniqueId());
        visionPacketExpiry.remove(event.getPlayer().getUniqueId());
        upsideDownPacketTargets.remove(event.getPlayer().getUniqueId());
        activeEntities.remove(event.getPlayer().getUniqueId());
        active.remove(event.getPlayer().getUniqueId());
        stopTickTaskIfIdle();
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        resetPrivateTipsyVisual(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        resetPrivateTipsyVisual(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrack(PlayerTrackEntityEvent event) {
        Player viewer = event.getPlayer();
        Entity target = event.getEntity();
        Set<UUID> inverted = upsideDownPacketTargets.get(viewer.getUniqueId());
        if (!(target instanceof Mob) || inverted == null
                || !inverted.contains(target.getUniqueId())) {
            return;
        }
        // The tracking event precedes the initial metadata on some Paper
        // revisions. Replay one tick later so our viewer-only name wins.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline() && target.isValid() && target.isTrackedBy(viewer)) {
                sendUpsideDownPacket(viewer, target);
            }
        });
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
                activeEntities.remove(living.getUniqueId());
                active.remove(living.getUniqueId());
            }
        }
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
        clearEffects(living);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity target = event.getEntity();
        for (Set<UUID> inverted : upsideDownPacketTargets.values()) {
            inverted.remove(target.getUniqueId());
        }
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
        clearEffects(target);
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
            // The deployable custom item uses water only as a neutral tooltip
            // base. Forge's DrinkBlockItem was not a water potion, so remove
            // that base before putting it into a projectile entity.
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
        Map<String, ActiveEffect> effects = active.computeIfAbsent(
                target.getUniqueId(), ignored -> new LinkedHashMap<>());
        ActiveEffect previous = effects.get(spec.effect());
        EffectSemantics.EffectState merged = EffectSemantics.mergeEffect(
                previous == null ? null : previous.state(), spec.durationTicks(), spec.amplifier());
        if (merged == null) {
            return;
        }
        effects.put(spec.effect(), new ActiveEffect(spec.effect(), merged));
        activeEntities.put(target.getUniqueId(), target);
        ensureTickTask();
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

    private void tick(long period) {
        elapsedTicks += period;
        boolean persistThisTick = elapsedTicks % 20 == 0;
        Iterator<Map.Entry<UUID, Map<String, ActiveEffect>>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Map<String, ActiveEffect>> entry = iterator.next();
            LivingEntity living = activeEntities.get(entry.getKey());
            if (living == null) {
                iterator.remove();
                continue;
            }
            Map<String, ActiveEffect> effects = entry.getValue();
            boolean changed = false;
            Iterator<Map.Entry<String, ActiveEffect>> effectIterator =
                    effects.entrySet().iterator();
            while (effectIterator.hasNext()) {
                Map.Entry<String, ActiveEffect> effectEntry = effectIterator.next();
                ActiveEffect effect = effectEntry.getValue();
                if (!tickEffect(living, effect)) {
                    effectIterator.remove();
                    changed = true;
                    continue;
                }
                boolean visibleExpired = effect.remainingTicks() <= period;
                boolean remainsActive = effect.advance((int) period);
                if (visibleExpired && living instanceof Player
                        && effect.effect().equals(PREFIX + "ardent_heat")) {
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
            spawnEffectParticles(living, effects);
            if (changed || persistThisTick) {
                save(living);
                // Attribute reconciliation is a repair pass: applies and
                // expiries reconcile immediately through `changed`, so the
                // steady-state check only needs the once-per-second cadence.
                reconcileAttributes(living, effects);
                if (living instanceof Player player) {
                    updateEffectHud(player, effects);
                    syncPrivateTipsyVisual(player, effects);
                }
            }
            if (effects.isEmpty()) {
                iterator.remove();
                activeEntities.remove(entry.getKey());
            }
        }
        stopTickTaskIfIdle();
    }

    private void ensureTickTask() {
        if (task == null && !active.isEmpty()) {
            // Forge evaluates active effects and EffectEvent once per game
            // tick. Keep that cadence, but do not leave an empty global task
            // running when the server has no Tavern effects at all.
            task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(1L), 1L, 1L);
        }
    }

    private void stopTickTaskIfIdle() {
        if (task != null && active.isEmpty()) {
            task.cancel();
            task = null;
        }
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

    /**
     * Replays the vanilla ambient effect swirls with the colours registered by
     * the original mod. The custom effects never reach the client's effect
     * registry, so the server spawns the ENTITY_EFFECT particles itself: one
     * randomly-coloured swirl roughly every three ticks, throttled the same
     * way vanilla throttles invisible entities.
     */
    private void spawnEffectParticles(LivingEntity living, Map<String, ActiveEffect> effects) {
        if (effects.isEmpty() || elapsedTicks % (living.isInvisible() ? 15L : 3L) != 0) {
            return;
        }
        Set<Player> trackedBy = living.getTrackedBy();
        boolean includeSelf = living instanceof Player self && !trackedBy.contains(self);
        if (trackedBy.isEmpty() && !includeSelf) {
            return;
        }
        List<Player> receivers = new ArrayList<>(trackedBy.size() + (includeSelf ? 1 : 0));
        receivers.addAll(trackedBy);
        if (includeSelf) {
            receivers.add((Player) living);
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String chosen = null;
        int index = random.nextInt(effects.size());
        for (String id : effects.keySet()) {
            if (index-- == 0) {
                chosen = id;
                break;
            }
        }
        Color color = effectColorCache.get(chosen);
        if (color == null) {
            Integer rgb = CustomEffectHudSemantics.color(chosen);
            if (rgb == null) {
                return;
            }
            color = Color.fromRGB(rgb);
            effectColorCache.put(chosen, color);
        }
        double x = living.getX() + (random.nextDouble() - 0.5) * living.getWidth();
        double y = living.getY() + random.nextDouble() * living.getHeight();
        double z = living.getZ() + (random.nextDouble() - 0.5) * living.getWidth();
        if (particlePacketsAvailable) {
            try {
                Object particleOption = effectParticleOptionCache.get(chosen);
                if (particleOption == null) {
                    particleOption = ViewerEffectPackets.entityEffectParticle(color);
                    effectParticleOptionCache.put(chosen, particleOption);
                }
                ViewerEffectPackets.sendEntityEffectParticle(
                        living.getWorld(), receivers, particleOption, x, y, z);
                return;
            } catch (RuntimeException | LinkageError error) {
                particlePacketsAvailable = false;
                effectParticleOptionCache.clear();
                plugin.getLogger().warning(
                        "无法使用 Paper 26.2 粒子数据包桥接，将回退 Bukkit API：" + error);
            }
        }
        living.getWorld().spawnParticle(Particle.ENTITY_EFFECT, receivers, null,
                x, y, z, 1, 0.0, 0.0, 0.0, 0.0, color, false);
    }

    private boolean tickEffect(LivingEntity living, ActiveEffect effect) {
        switch (effect.effect()) {
            case PREFIX + "vision" -> {
                if (EffectSemantics.ticksAt(effect.remainingTicks(), 50)) {
                    vision(living, effect.amplifier());
                }
            }
            case PREFIX + "xp_drain" -> {
                if (living instanceof Player player) {
                    player.setExpCooldown(0);
                    if (player.getTicksLived() % 5 == 0) {
                        xpDrain(player);
                    }
                }
            }
            case PREFIX + "grass_stealth" -> {
                if (EffectSemantics.ticksAt(effect.remainingTicks(), 10)) {
                    grassStealth(living);
                }
            }
            case PREFIX + "ardent_heat" -> {
                if (living instanceof Player player && !ardentHeat(player)) {
                    applyHunger(player, 600);
                    return false;
                }
            }
            case PREFIX + "slightly_tipsy", PREFIX + "bloody_mary", PREFIX + "tomb_raider",
                    PREFIX + "high_heels", PREFIX + "long_reach" -> {
                // Source BaseEffect markers and attribute-only effects have no tick callback.
            }
            default -> {
                // Unknown retained effects are inert instead of gaining invented behavior.
            }
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
                Set<UUID> inverted = upsideDownPacketTargets.computeIfAbsent(
                        viewer.getUniqueId(), ignored -> new HashSet<>());
                for (Entity entity : user.getNearbyEntities(16, 16, 16)) {
                    if (entity instanceof Mob mob && !mob.isDead()) {
                        inverted.add(mob.getUniqueId());
                        if (mob.isTrackedBy(viewer)) {
                            sendUpsideDownPacket(viewer, mob);
                        }
                    }
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
        Map<String, ActiveEffect> effects = read(living);
        if (effects.isEmpty()) {
            activeEntities.remove(living.getUniqueId());
            active.remove(living.getUniqueId());
        } else {
            active.put(living.getUniqueId(), effects);
            activeEntities.put(living.getUniqueId(), living);
            ensureTickTask();
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
                result.put(id, new ActiveEffect(id, state));
            }
        }
        return result;
    }

    private void save(LivingEntity living) {
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
            ids.add(effect.effect());
            values.add(EffectSemantics.encodeState(effect.state()));
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
     */
    private void syncPrivateTipsyVisual(Player player, Map<String, ActiveEffect> effects) {
        PotionEffectType nausea = Registry.EFFECT.get(NamespacedKey.minecraft("nausea"));
        if (nausea == null) {
            return;
        }
        ActiveEffect tipsy = effects.get(PREFIX + "slightly_tipsy");
        PotionEffect realNausea = player.getPotionEffect(nausea);
        UUID uuid = player.getUniqueId();
        if (tipsy == null || tipsy.remainingTicks() <= 0) {
            if (privateTipsyVisual.remove(uuid)) {
                restorePotionEffectView(player, nausea, realNausea);
            }
            return;
        }
        if (realNausea != null) {
            if (privateTipsyVisual.remove(uuid)) {
                player.sendPotionEffectChange(player, realNausea);
            }
            return;
        }
        if (privateTipsyVisual.add(uuid)) {
            player.sendPotionEffectChange(player, new PotionEffect(
                    nausea, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }
    }

    private void restorePrivateTipsyVisual(Player player) {
        PotionEffectType nausea = Registry.EFFECT.get(NamespacedKey.minecraft("nausea"));
        if (nausea == null || !privateTipsyVisual.remove(player.getUniqueId())) {
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
        activeEntities.remove(living.getUniqueId());
        active.remove(living.getUniqueId());
        visionPacketExpiry.remove(living.getUniqueId());
        living.getPersistentDataContainer().remove(activeKey);
        removeAttributeModifiers(living);
        restoreStealthVisibility(living);
        if (living instanceof Player player) {
            hideEffectHud(player);
            restorePrivateTipsyVisual(player);
        }
        stopTickTaskIfIdle();
    }

    private static final class ActiveEffect {
        private final String effect;
        private final EffectSemantics.MutableEffectState state;

        private ActiveEffect(String effect, EffectSemantics.EffectState state) {
            this.effect = effect;
            this.state = new EffectSemantics.MutableEffectState(state);
        }

        private String effect() {
            return effect;
        }

        private EffectSemantics.EffectState state() {
            return state.snapshot();
        }

        private int remainingTicks() {
            return state.remainingTicks();
        }

        private int amplifier() {
            return state.amplifier();
        }

        private boolean advance(int elapsedTicks) {
            return state.advance(elapsedTicks);
        }
    }
}
