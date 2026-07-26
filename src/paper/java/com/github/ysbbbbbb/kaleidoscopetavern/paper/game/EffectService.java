package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.EffectSpec;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Bukkit;
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
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
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
    private final NamespacedKey collisionKey;
    private final NamespacedKey heelsModifierKey;
    private final NamespacedKey blockReachModifierKey;
    private final NamespacedKey entityReachModifierKey;
    private final NamespacedKey splashPreparedKey;
    private final NamespacedKey splashCustomEffectsKey;
    private final Map<UUID, Map<String, ActiveEffect>> active = new HashMap<>();
    private long elapsedTicks;
    private BukkitTask task;

    public EffectService(JavaPlugin plugin, ContentCatalog catalog, ItemService items) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.items = items;
        this.activeKey = new NamespacedKey(plugin, "active_drink_effects");
        this.collisionKey = new NamespacedKey(plugin, "ardent_heat_collisions");
        this.heelsModifierKey = new NamespacedKey(plugin, "effect_high_heels");
        this.blockReachModifierKey = new NamespacedKey(plugin, "effect_long_reach_block");
        this.entityReachModifierKey = new NamespacedKey(plugin, "effect_long_reach_entity");
        this.splashPreparedKey = new NamespacedKey(plugin, "thrown_drink_prepared");
        this.splashCustomEffectsKey = new NamespacedKey(plugin, "thrown_drink_custom_effects");
    }

    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            items.refreshInventory(player);
            load(player);
        }
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (LivingEntity living : world.getLivingEntities()) {
                if (!(living instanceof Player)
                        && living.getPersistentDataContainer().has(activeKey, PersistentDataType.STRING)) {
                    load(living);
                }
            }
        }
        // Forge evaluates active effects and EffectEvent once per game tick.
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(1L), 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID uuid : new ArrayList<>(active.keySet())) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity instanceof LivingEntity living) {
                save(living);
                removeAttributeModifiers(living);
            }
        }
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
            if (EffectSemantics.rolls(ThreadLocalRandom.current().nextDouble(), spec.probability())) {
                apply(event.getPlayer(), spec);
            }
        }
        String remainderId = catalog.isCocktail(itemId)
                ? PREFIX + "empty_glassware"
                : PREFIX + "empty_bottle";
        items.build(remainderId, event.getPlayer()).ifPresent(container -> {
            EffectSemantics.ContainerResult result = EffectSemantics.consumedContainer(
                    consumed.getAmount(), event.getPlayer().getGameMode() == GameMode.CREATIVE);
            if (result.containerReplacesHand()) {
                event.setReplacement(container);
            } else {
                ItemStack remaining = consumed.clone();
                remaining.setAmount(result.remainingDrinks());
                event.setReplacement(remaining);
            }
            if (result.returnContainerToInventory()) {
                container.setAmount(1);
                items.give(event.getPlayer(), container);
            }
        });
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
        String encoded = event.getPotion().getPersistentDataContainer()
                .get(splashCustomEffectsKey, PersistentDataType.STRING);
        if (!event.getPotion().getPersistentDataContainer()
                .has(splashPreparedKey, PersistentDataType.BYTE)) {
            return;
        }

        // Do not cancel the real event. Vanilla applies the rolled Minecraft
        // effects with its own direct-hit handling and instantaneous-effect
        // intensity. Only the custom Forge effects need a Paper-side bridge.
        List<EffectSpec> customEffects = decodeSplashEffects(encoded);
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
        items.refreshInventory(event.getPlayer());
        load(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        save(event.getPlayer());
        active.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity living
                    && living.getPersistentDataContainer().has(activeKey, PersistentDataType.STRING)) {
                load(living);
            }
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity living && active.containsKey(living.getUniqueId())) {
                save(living);
                active.remove(living.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity target = event.getEntity();
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
        clearEffects(target);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
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
        org.bukkit.entity.Item dropped = target.getWorld().dropItem(target.getLocation(), mainHand);
        dropped.setVelocity(new Vector());
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
            potion.getPersistentDataContainer().set(splashCustomEffectsKey,
                    PersistentDataType.STRING, encodeSplashEffects(customEffects));
        }
    }

    private static String encodeSplashEffects(List<EffectSpec> effects) {
        return effects.stream()
                .map(spec -> spec.effect() + "," + spec.durationTicks() + "," + spec.amplifier())
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
    }

    private static List<EffectSpec> decodeSplashEffects(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<EffectSpec> effects = new ArrayList<>();
        for (String entry : encoded.split(";")) {
            String[] fields = entry.split(",", 3);
            if (fields.length != 3) {
                continue;
            }
            try {
                effects.add(new EffectSpec(fields[0], Integer.parseInt(fields[1]),
                        Integer.parseInt(fields[2]), 1.0));
            } catch (NumberFormatException ignored) {
                // A malformed or externally edited projectile payload should
                // break harmlessly rather than aborting the splash event.
            }
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
        save(target);
        reconcileAttributes(target, effects);
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
        for (UUID uuid : new ArrayList<>(active.keySet())) {
            Entity entity = Bukkit.getEntity(uuid);
            if (!(entity instanceof LivingEntity living) || !entity.isValid() || living.isDead()) {
                continue;
            }
            Map<String, ActiveEffect> effects = active.get(uuid);
            boolean changed = false;
            for (ActiveEffect effect : new ArrayList<>(effects.values())) {
                if (!tickEffect(living, effect, effects)) {
                    changed = true;
                    continue;
                }
                boolean visibleExpired = effect.remainingTicks() <= period;
                EffectSemantics.EffectState next = EffectSemantics.advanceEffect(
                        effect.state(), (int) period);
                if (visibleExpired && living instanceof Player
                        && effect.effect().equals(PREFIX + "ardent_heat")) {
                    applyHunger(living, 600);
                }
                if (next == null) {
                    effects.remove(effect.effect());
                    changed = true;
                } else {
                    effects.put(effect.effect(), new ActiveEffect(effect.effect(), next));
                    changed |= visibleExpired;
                }
            }
            reconcileAttributes(living, effects);
            if (changed || persistThisTick) {
                save(living);
            }
            if (effects.isEmpty()) {
                active.remove(uuid);
            }
        }
    }

    private boolean tickEffect(LivingEntity living, ActiveEffect effect,
                               Map<String, ActiveEffect> effects) {
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
                    effects.remove(effect.effect());
                    applyHunger(player, 600);
                    save(player);
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
        double radius = Math.min(amplifier + 1, 3) * 6.0;
        boolean applied = false;
        PotionEffectType glowing = Registry.EFFECT.get(NamespacedKey.minecraft("glowing"));
        if (glowing == null) {
            return;
        }
        for (Entity entity : user.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof LivingEntity living && !living.equals(user) && !living.isDead()) {
                if (!living.hasPotionEffect(glowing)) {
                    applied = true;
                }
                living.addPotionEffect(new PotionEffect(glowing, 60, 0, false, true, true));
            }
        }
        if (applied) {
            user.getWorld().playSound(user.getLocation(),
                    "kaleidoscope_tavern:effect.vision", 1.0F, 1.0F);
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
                    block.breakNaturally(true, true);
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

    private boolean matchesBlockTag(Block block, String tagId) {
        String customId = customBlockId(block);
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

    private boolean matchesEntityTag(EntityType type, String tagId) {
        String entityId = type.getKey().asString();
        for (String member : catalog.entityTypeTag(tagId)) {
            if (member.startsWith("#")) {
                NamespacedKey key = NamespacedKey.fromString(member.substring(1));
                Tag<EntityType> tag = key == null ? null
                        : Bukkit.getTag(Tag.REGISTRY_ENTITY_TYPES, key, EntityType.class);
                if (tag != null && tag.isTagged(type)) {
                    return true;
                }
            } else if (member.equals(entityId)) {
                return true;
            }
        }
        return false;
    }

    private static String customBlockId(Block block) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        return state == null ? "" : state.owner().value().id().toString();
    }

    private void applyInstant(LivingEntity user, String effect) {
        switch (effect) {
            case PREFIX + "upside_down" -> {
                if (user instanceof Mob self && !self.isDead()) {
                    self.customName(Component.text("Grumm"));
                    self.setCustomNameVisible(false);
                }
                for (Entity entity : user.getNearbyEntities(16, 16, 16)) {
                    if (entity instanceof Mob mob && !mob.isDead()) {
                        mob.customName(Component.text("Grumm"));
                        mob.setCustomNameVisible(false);
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

    private static void shriek(LivingEntity user) {
        Location eye = user.getEyeLocation();
        Vector look = eye.getDirection().normalize();
        Vector horizontal = new Vector(look.getX(), 0, look.getZ());
        if (horizontal.lengthSquared() > 0.001) {
            horizontal.normalize();
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
            active.remove(living.getUniqueId());
        } else {
            active.put(living.getUniqueId(), effects);
        }
        reconcileAttributes(living, effects);
    }

    private Map<String, ActiveEffect> read(LivingEntity living) {
        String encoded = living.getPersistentDataContainer().get(activeKey, PersistentDataType.STRING);
        Map<String, ActiveEffect> result = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return result;
        }
        boolean versionThree = encoded.startsWith("v3|");
        boolean versionTwo = encoded.startsWith("v2|");
        boolean legacyEpochMillis = !versionThree && !versionTwo;
        if (versionThree || versionTwo) {
            encoded = encoded.substring(3);
        }
        for (String entry : encoded.split(";")) {
            String[] fields = entry.split(",", -1);
            if ((!versionThree && fields.length != 3)
                    || (versionThree && (fields.length < 3 || fields.length % 2 == 0))) {
                continue;
            }
            try {
                long storedTime = Long.parseLong(fields[1]);
                // Releases before this semantic audit persisted an epoch-millis expiry.
                // Convert it once without allowing offline time to advance thereafter.
                int remaining = EffectSemantics.decodeRemainingTicks(
                        storedTime, System.currentTimeMillis(), legacyEpochMillis);
                EffectSemantics.EffectState hidden = null;
                if (versionThree) {
                    for (int index = fields.length - 2; index >= 3; index -= 2) {
                        hidden = new EffectSemantics.EffectState(
                                Integer.parseInt(fields[index]), Integer.parseInt(fields[index + 1]), hidden);
                    }
                }
                EffectSemantics.EffectState state = new EffectSemantics.EffectState(
                        remaining, Integer.parseInt(fields[2]), hidden);
                ActiveEffect effect = new ActiveEffect(fields[0], state);
                if (effect.remainingTicks() > 0) {
                    result.put(effect.effect(), effect);
                }
            } catch (NumberFormatException ignored) {
                // Ignore one corrupt entry and retain all valid persisted effects.
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
        StringBuilder encoded = new StringBuilder("v3|");
        boolean first = true;
        for (ActiveEffect effect : effects.values()) {
            if (!first) {
                encoded.append(';');
            }
            appendEncoded(encoded, effect);
            first = false;
        }
        living.getPersistentDataContainer().set(activeKey, PersistentDataType.STRING, encoded.toString());
    }

    private static void appendEncoded(StringBuilder encoded, ActiveEffect effect) {
        encoded.append(effect.effect());
        EffectSemantics.EffectState state = effect.state();
        while (state != null) {
            encoded.append(',').append(state.remainingTicks())
                    .append(',').append(state.amplifier());
            state = state.hidden();
        }
    }

    private void clearEffects(LivingEntity living) {
        active.remove(living.getUniqueId());
        living.getPersistentDataContainer().remove(activeKey);
        removeAttributeModifiers(living);
    }

    private record ActiveEffect(String effect, EffectSemantics.EffectState state) {
        private int remainingTicks() {
            return state.remainingTicks();
        }

        private int amplifier() {
            return state.amplifier();
        }
    }
}
