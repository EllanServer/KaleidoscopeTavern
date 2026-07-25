package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

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
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
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
    }

    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
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
        List<EffectSpec> specs = effectsFor(consumed);
        if (specs.isEmpty() && !catalog.hasDrinkEffects(itemId)
                && !itemId.equals(PREFIX + "signature_cocktail")) {
            return;
        }
        for (EffectSpec spec : specs) {
            if (ThreadLocalRandom.current().nextDouble() <= spec.probability()) {
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        ItemStack drink = event.getPotion().getItem();
        if (effectsFor(drink).isEmpty()) {
            return;
        }
        event.setCancelled(true);
        splash(drink, event.getPotion().getLocation(), event.getPotion().getShooter() instanceof Entity entity
                ? entity : null);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
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
        LivingEntity killer = target.getLastDamageCause() instanceof EntityDamageByEntityEvent damage
                ? attackingLiving(damage.getDamager()) : null;
        if (killer != null && !killer.equals(target) && has(killer, PREFIX + "bloody_mary")) {
            AttributeInstance targetHealth = target.getAttribute(Attribute.MAX_HEALTH);
            AttributeInstance killerHealth = killer.getAttribute(Attribute.MAX_HEALTH);
            if (targetHealth != null && killerHealth != null) {
                double heal = Math.floor(targetHealth.getValue() / 3.0);
                if (heal > 0) {
                    killer.setHealth(Math.min(killerHealth.getValue(), killer.getHealth() + heal));
                }
            }
        }
        clearEffects(target);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        LivingEntity attacker = attackingLiving(event.getDamager());
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
        target.getWorld().dropItemNaturally(target.getLocation(), mainHand).setPickupDelay(40);
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

    /** Applies a placed or launched drink as a splash potion around an impact point. */
    public boolean splash(ItemStack drink, Location center, Entity owner) {
        List<EffectSpec> specs = effectsFor(drink);
        if (specs.isEmpty()) {
            return false;
        }
        List<EffectSpec> rolled = specs.stream()
                .filter(spec -> ThreadLocalRandom.current().nextDouble() <= spec.probability())
                .toList();
        for (Entity nearby : center.getWorld().getNearbyEntities(center, 4, 2, 4,
                candidate -> candidate instanceof LivingEntity && !candidate.isDead())) {
            LivingEntity target = (LivingEntity) nearby;
            double distanceSquared = target.getLocation().distanceSquared(center);
            if (distanceSquared >= 16.0) {
                continue;
            }
            double intensity = 1.0 - Math.sqrt(distanceSquared) / 4.0;
            for (EffectSpec spec : rolled) {
                int duration = spec.durationTicks() == 0 ? 0
                        : Math.max(1, (int) Math.round(spec.durationTicks() * intensity));
                apply(target, new EffectSpec(spec.effect(), duration, spec.amplifier(), 1.0));
            }
        }
        center.getWorld().spawnParticle(Particle.ENTITY_EFFECT, center, 45,
                0.6, 0.35, 0.6, 0.08);
        center.getWorld().playSound(center, "minecraft:entity.splash_potion.break", 1.0F, 1.0F);
        return true;
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
        int duration = Math.max(spec.durationTicks(), previous == null ? 0 : previous.remainingTicks());
        effects.put(spec.effect(), new ActiveEffect(spec.effect(), duration,
                Math.max(spec.amplifier(), previous == null ? 0 : previous.amplifier())));
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
                int remaining = effect.remainingTicks() - (int) period;
                if (remaining <= 0) {
                    if (effect.effect().equals(PREFIX + "ardent_heat")) {
                        applyHunger(living, 600);
                    }
                    effects.remove(effect.effect());
                    changed = true;
                } else {
                    effects.put(effect.effect(), new ActiveEffect(
                            effect.effect(), remaining, effect.amplifier()));
                }
            }
            reconcileAttributes(living, effects);
            if (changed || elapsedTicks % 20 == 0) {
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
            Vector delta = player.getLocation().add(0, 0.5, 0).toVector().subtract(orb.getLocation().toVector());
            double distance = Math.max(0.01, delta.length());
            if (distance < 1.5) {
                player.giveExp(orb.getExperience(), true);
                orb.remove();
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
        if (player.getFoodLevel() <= 0 && player.getSaturation() <= 0.01F) {
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
                    block.breakNaturally(player.getInventory().getItemInMainHand(), true, true);
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
        if (block.getBlockData() instanceof Ageable ageable && ageable.getAge() < ageable.getMaximumAge()) {
            return false;
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
                for (Entity entity : user.getNearbyEntities(16, 16, 16)) {
                    if (entity instanceof Mob mob && !mob.isDead()) {
                        mob.customName(Component.text("Grumm"));
                        mob.setCustomNameVisible(false);
                    }
                }
            }
            case PREFIX + "zenith" -> {
                Location current = user.getLocation();
                int surface = current.getWorld().getHighestBlockYAt(current, HeightMap.MOTION_BLOCKING);
                if (current.getBlockY() < surface) {
                    current.getWorld().playSound(current, "minecraft:item.chorus_fruit.teleport", 1.0F, 1.0F);
                    Location destination = new Location(current.getWorld(), current.getBlockX() + 0.5,
                            surface, current.getBlockZ() + 0.5, current.getYaw(), current.getPitch());
                    user.teleport(destination);
                    user.setFallDistance(0F);
                    destination.getWorld().playSound(destination, "minecraft:item.chorus_fruit.teleport", 1.0F, 1.0F);
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
        user.getWorld().playSound(user.getLocation(), "minecraft:entity.warden.sonic_boom", 1.0F, 1.0F);
        for (int distance = 2; distance <= 32; distance += 2) {
            user.getWorld().spawnParticle(Particle.SONIC_BOOM,
                    eye.clone().add(look.clone().multiply(distance)), 1, 0, 0, 0, 0);
        }
    }

    private void reconcileAttributes(LivingEntity living, Map<String, ActiveEffect> effects) {
        reconcileModifier(living, Attribute.STEP_HEIGHT, heelsModifierKey,
                effects.containsKey(PREFIX + "high_heels") ? 0.5 : null);
        boolean longReach = effects.containsKey(PREFIX + "long_reach");
        reconcileModifier(living, Attribute.BLOCK_INTERACTION_RANGE, blockReachModifierKey, longReach ? 3.0 : null);
        reconcileModifier(living, Attribute.ENTITY_INTERACTION_RANGE, entityReachModifierKey, longReach ? 3.0 : null);
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
        } else if (existing == null) {
            instance.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    private void removeAttributeModifiers(LivingEntity living) {
        reconcileModifier(living, Attribute.STEP_HEIGHT, heelsModifierKey, null);
        reconcileModifier(living, Attribute.BLOCK_INTERACTION_RANGE, blockReachModifierKey, null);
        reconcileModifier(living, Attribute.ENTITY_INTERACTION_RANGE, entityReachModifierKey, null);
    }

    private static LivingEntity attackingLiving(Entity damager) {
        if (damager instanceof LivingEntity living) {
            return living;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            return source instanceof LivingEntity living ? living : null;
        }
        return null;
    }

    private static void applyHunger(LivingEntity living, int duration) {
        PotionEffectType hunger = Registry.EFFECT.get(NamespacedKey.minecraft("hunger"));
        if (hunger != null) {
            living.addPotionEffect(new PotionEffect(hunger, duration, 0, false, true, true));
        }
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
        boolean legacyEpochMillis = !encoded.startsWith("v2|");
        if (!legacyEpochMillis) {
            encoded = encoded.substring(3);
        }
        for (String entry : encoded.split(";")) {
            String[] fields = entry.split(",", -1);
            if (fields.length != 3) {
                continue;
            }
            try {
                long storedTime = Long.parseLong(fields[1]);
                // Releases before this semantic audit persisted an epoch-millis expiry.
                // Convert it once without allowing offline time to advance thereafter.
                int remaining = EffectSemantics.decodeRemainingTicks(
                        storedTime, System.currentTimeMillis(), legacyEpochMillis);
                ActiveEffect effect = new ActiveEffect(fields[0], remaining,
                        Integer.parseInt(fields[2]));
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
        String encoded = effects.values().stream()
                .map(effect -> effect.effect() + ',' + effect.remainingTicks() + ',' + effect.amplifier())
                .reduce((left, right) -> left + ';' + right)
                .orElse("");
        living.getPersistentDataContainer().set(activeKey, PersistentDataType.STRING, "v2|" + encoded);
    }

    private void clearEffects(LivingEntity living) {
        active.remove(living.getUniqueId());
        living.getPersistentDataContainer().remove(activeKey);
        removeAttributeModifiers(living);
    }

    private record ActiveEffect(String effect, int remainingTicks, int amplifier) {
    }
}
