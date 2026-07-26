package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MinecraftFont;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent editable text for the chalkboard and sandwich-board furniture families. */
public final class BoardTextService implements Listener {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private static final String CHALKBOARD = PREFIX + "chalkboard";
    private static final String BASE_SANDWICH_BOARD = PREFIX + "base_sandwich_board";
    private static final float VANILLA_TEXT_SCALE = 0.025F;
    private static final float SANDWICH_TEXT_SCALE = 0.01F;
    private static final float CHALKBOARD_TEXT_SCALE = 0.012F;
    private static final int FONT_UNITS_PER_PIXEL = 2;
    private static final int TEXT_DISPLAY_SINGLE_LINE_HEIGHT = 9;
    private static final int LEGACY_FIRST_LINE_Y = -19;
    private static final int FIRST_LINE_ENTITY_OFFSET =
            -LEGACY_FIRST_LINE_Y - TEXT_DISPLAY_SINGLE_LINE_HEIGHT;
    private static final int SANDWICH_LINE_HEIGHT = 10;
    private static final int CHALKBOARD_LINE_HEIGHT = 12;
    private static final int SANDWICH_MAX_LINES = 8;
    private static final int CHALKBOARD_MAX_LINES = 11;
    private static final double SANDWICH_TILT_RADIANS = Math.toRadians(22.5);
    private static final Map<Material, String> SANDWICH_VARIANTS = Map.ofEntries(
            Map.entry(Material.SHORT_GRASS, "grass"),
            Map.entry(Material.ALLIUM, "allium"),
            Map.entry(Material.AZURE_BLUET, "azure_bluet"),
            Map.entry(Material.OXEYE_DAISY, "azure_bluet"),
            Map.entry(Material.LILY_OF_THE_VALLEY, "azure_bluet"),
            Map.entry(Material.CORNFLOWER, "cornflower"),
            Map.entry(Material.BLUE_ORCHID, "orchid"),
            Map.entry(Material.PEONY, "peony"),
            Map.entry(Material.LILAC, "peony"),
            Map.entry(Material.PINK_PETALS, "pink_petals"),
            Map.entry(Material.PITCHER_PLANT, "pitcher_plant"),
            Map.entry(Material.POPPY, "poppy"),
            Map.entry(Material.ROSE_BUSH, "poppy"),
            Map.entry(Material.SUNFLOWER, "sunflower"),
            Map.entry(Material.DANDELION, "sunflower"),
            Map.entry(Material.TORCHFLOWER, "torchflower"),
            Map.entry(Material.RED_TULIP, "tulip"),
            Map.entry(Material.ORANGE_TULIP, "tulip"),
            Map.entry(Material.WHITE_TULIP, "tulip"),
            Map.entry(Material.PINK_TULIP, "tulip"),
            Map.entry(Material.WITHER_ROSE, "wither_rose")
    );

    private final JavaPlugin plugin;
    private final ItemService items;
    private final NamespacedKey boardOwnerKey;
    private final NamespacedKey boardLineKey;
    // AsyncChatEvent removes entries off the main thread.
    private final Map<UUID, EditSession> editors = new ConcurrentHashMap<>();

    public BoardTextService(JavaPlugin plugin, ItemService items) {
        this.plugin = plugin;
        this.items = items;
        this.boardOwnerKey = new NamespacedKey(plugin, "board_owner");
        this.boardLineKey = new NamespacedKey(plugin, "board_line");
    }

    public void start() {
        Bukkit.getScheduler().runTask(plugin, this::bootstrapDisplays);
    }

    public void stop() {
        editors.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFurnitureInteract(FurnitureInteractEvent event) {
        if (event.hand() != InteractionHand.MAIN_HAND || !isBoard(event.furniture())) {
            return;
        }
        BukkitFurniture furniture = event.furniture();
        FurnitureState state = new FurnitureState(plugin, furniture);
        Player player = event.player();
        ItemStack hand = player.getInventory().getItemInMainHand();

        // SandwichBoardBlock applies its flower transformation before it
        // delegates to TextBlockEntity, so waxing locks text properties but
        // deliberately does not lock the board's decorative variant.
        if (isSandwichBoard(furniture) && transformSandwichBoard(player, furniture, state, hand)) {
            event.setCancelled(true);
            return;
        }
        if (state.bool("board_waxed")) {
            player.playSound(furniture.location(), "minecraft:block.waxed_sign.interact_fail", 1F, 1F);
            event.setCancelled(true);
            return;
        }
        DyeColor dye = dyeColor(hand.getType());
        if (dye != null && !dye.name().equals(state.string("board_color", "WHITE"))) {
            state.putString("board_color", dye.name());
            consumeUnlessCreative(player, hand);
            player.playSound(furniture.location(), Sound.ITEM_DYE_USE, 1F, 1F);
            refreshDisplay(furniture);
            event.setCancelled(true);
            return;
        }
        if (hand.getType() == Material.GLOW_INK_SAC && !state.bool("board_glowing")) {
            state.bool("board_glowing", true);
            consumeUnlessCreative(player, hand);
            player.playSound(furniture.location(), Sound.ITEM_GLOW_INK_SAC_USE, 1F, 1F);
            refreshDisplay(furniture);
            event.setCancelled(true);
            return;
        }
        if (hand.getType() == Material.INK_SAC && state.bool("board_glowing")) {
            state.bool("board_glowing", false);
            consumeUnlessCreative(player, hand);
            player.playSound(furniture.location(), Sound.ITEM_INK_SAC_USE, 1F, 1F);
            refreshDisplay(furniture);
            event.setCancelled(true);
            return;
        }
        if (hand.getType() == Material.HONEYCOMB) {
            state.bool("board_waxed", true);
            consumeUnlessCreative(player, hand);
            World world = furniture.location().getWorld();
            world.playSound(furniture.location(), Sound.ITEM_HONEYCOMB_WAX_ON, 1F, 1F);
            world.spawnParticle(Particle.WAX_ON, furniture.location().clone().add(0, 1, 0),
                    10, 0.4, 0.4, 0.4, 0.05);
            event.setCancelled(true);
            return;
        }

        Entity entity = furniture.bukkitEntity();
        if (entity != null) {
            editors.put(player.getUniqueId(), new EditSession(entity.getUniqueId()));
            player.sendMessage(Component.text("请在聊天栏输入文字（\\n 换行；[left]/[center]/[right] 设置对齐；!clear 清空；!cancel 取消）。"));
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        EditSession session = editors.remove(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> applyChatInput(event.getPlayer(), session, input));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurniturePlace(FurniturePlaceEvent event) {
        if (!isBoard(event.furniture())) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            BukkitFurniture furniture = event.furniture();
            if (!furniture.isValid()) {
                return;
            }
            if (furniture.id().toString().equals(CHALKBOARD) && !event.player().isSneaking()) {
                tryMergeChalkboards(furniture);
            }
            refreshDisplay(furniture);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent event) {
        if (!isBoard(event.furniture())) {
            return;
        }
        FurnitureState state = new FurnitureState(plugin, event.furniture());
        removeDisplay(state);
        Entity entity = event.furniture().bukkitEntity();
        if (entity != null) {
            editors.entrySet().removeIf(entry -> entry.getValue().furniture().equals(entity.getUniqueId()));
        }
        if (event.dropItems() && state.integer("board_large_count") == 3) {
            items.build(CHALKBOARD, event.player()).ifPresent(stack -> {
                stack.setAmount(2);
                event.location().getWorld().dropItemNaturally(event.location(), stack);
            });
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Entity entity : event.getEntities()) {
                if (entity instanceof ItemDisplay && CraftEngineFurniture.isFurniture(entity)) {
                    BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
                    if (furniture != null && isBoard(furniture)) {
                        refreshDisplay(furniture);
                    }
                }
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        editors.remove(event.getPlayer().getUniqueId());
    }

    private void applyChatInput(Player player, EditSession session, String rawInput) {
        if (rawInput.equalsIgnoreCase("!cancel")) {
            player.sendMessage(Component.text("已取消编辑。"));
            return;
        }
        Entity entity = Bukkit.getEntity(session.furniture());
        if (entity == null || !entity.isValid() || !CraftEngineFurniture.isFurniture(entity)) {
            player.sendMessage(Component.text("写字板已经不存在或所在区块未加载。"));
            return;
        }
        BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
        if (furniture == null || !isBoard(furniture)
                || !furniture.location().getWorld().equals(player.getWorld())
                || furniture.location().distanceSquared(player.getLocation()) > 64) {
            player.sendMessage(Component.text("你离写字板太远，编辑已取消。"));
            return;
        }
        FurnitureState state = new FurnitureState(plugin, furniture);
        if (state.bool("board_waxed")) {
            player.sendMessage(Component.text("写字板已打蜡，不能修改。"));
            return;
        }

        String input = rawInput.replace("\\n", "\n");
        boolean clearRequested = input.equalsIgnoreCase("!clear");
        if (clearRequested) {
            input = "";
        }
        String lower = input.toLowerCase(Locale.ROOT);
        boolean alignmentUpdated = false;
        for (String alignment : List.of("left", "center", "right")) {
            String prefix = '[' + alignment + ']';
            if (lower.startsWith(prefix)) {
                state.putString("board_alignment", alignment);
                input = input.substring(prefix.length()).stripLeading();
                alignmentUpdated = true;
                break;
            }
        }
        if (alignmentUpdated && input.isEmpty() && !clearRequested) {
            // The source GUI pre-fills the current text, so submitting only a
            // new alignment never wipes the board.
            refreshDisplay(furniture);
            player.sendMessage(Component.text("对齐方式已更新。"));
            return;
        }
        int maxLength = maxTextLength(furniture);
        if (input.length() > maxLength) {
            player.sendMessage(Component.text("文字过长：最多 " + maxLength + " 个字符。"));
            return;
        }
        state.putString("board_text", input);
        refreshDisplay(furniture);
        player.sendMessage(Component.text(input.isBlank() ? "已清空写字板。" : "写字板文字已更新。"));
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        Set<UUID> unloading = event.getEntities().stream()
                .map(Entity::getUniqueId)
                .collect(java.util.stream.Collectors.toSet());
        editors.entrySet().removeIf(entry -> unloading.contains(entry.getValue().furniture()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || !editors.containsKey(event.getPlayer().getUniqueId())
                || event.getFrom().getX() == event.getTo().getX()
                && event.getFrom().getY() == event.getTo().getY()
                && event.getFrom().getZ() == event.getTo().getZ()) {
            return;
        }
        validateEditDistance(event.getPlayer());
    }

    /** Event-driven equivalent of TextBlockEntity.tick's eight-block editor check. */
    private void validateEditDistance(Player player) {
        EditSession session = editors.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(session.furniture());
        if (entity == null || !entity.isValid() || !player.getWorld().equals(entity.getWorld())
                || player.getLocation().distanceSquared(entity.getLocation()) > 64) {
            editors.remove(player.getUniqueId());
        }
    }

    private boolean transformSandwichBoard(Player player, BukkitFurniture furniture,
                                           FurnitureState oldState, ItemStack hand) {
        String variant = SANDWICH_VARIANTS.get(hand.getType());
        if (variant == null) {
            return false;
        }
        String targetId = PREFIX + variant + "_sandwich_board";
        if (furniture.id().toString().equals(targetId)) {
            return false;
        }
        BoardStateSnapshot snapshot = BoardStateSnapshot.capture(oldState);
        Location location = furniture.location().clone();
        removeDisplay(oldState);
        CraftEngineFurniture.remove(furniture, false, false);
        BukkitFurniture replacement = CraftEngineFurniture.place(location, Key.of(targetId), "ground", false);
        if (replacement == null) {
            replacement = CraftEngineFurniture.place(location, Key.of(BASE_SANDWICH_BOARD), "ground", false);
            player.sendMessage(Component.text("展板样式切换失败，已恢复为基础展板。"));
            if (replacement != null) {
                snapshot.apply(new FurnitureState(plugin, replacement));
                refreshDisplay(replacement);
            }
            return true;
        }
        snapshot.apply(new FurnitureState(plugin, replacement));
        consumeUnlessCreative(player, hand);
        location.getWorld().playSound(location, Sound.BLOCK_GRASS_PLACE, 1F, 1F);
        refreshDisplay(replacement);
        return true;
    }

    private void tryMergeChalkboards(BukkitFurniture placed) {
        String variant = placed.currentVariant().name();
        if (!placed.isValid() || !(variant.equals("ground") || variant.equals("wall"))
                || !new FurnitureState(plugin, placed).string("board_text", "").isBlank()) {
            return;
        }
        List<BukkitFurniture> candidates = nearbyFurniture(placed.location(), 2.25).stream()
                .filter(furniture -> furniture.id().toString().equals(CHALKBOARD))
                .filter(furniture -> {
                    String candidateVariant = furniture.currentVariant().name();
                    return candidateVariant.equals("ground") || candidateVariant.equals("wall");
                })
                .filter(furniture -> new FurnitureState(plugin, furniture).string("board_text", "").isBlank())
                .filter(furniture -> yawDistance(furniture.location().getYaw(), placed.location().getYaw()) < 1F)
                .toList();
        if (candidates.size() < 3) {
            return;
        }
        Vector right = horizontalRight(placed.location().getYaw());
        for (BukkitFurniture center : candidates) {
            BukkitFurniture left = sideAt(candidates, center, right, -1);
            BukkitFurniture rightBoard = sideAt(candidates, center, right, 1);
            if (left == null || rightBoard == null || left == rightBoard) {
                continue;
            }
            removeDisplay(new FurnitureState(plugin, left));
            removeDisplay(new FurnitureState(plugin, rightBoard));
            CraftEngineFurniture.remove(left, false, false);
            CraftEngineFurniture.remove(rightBoard, false, false);
            center.setVariant(center.currentVariant().name() + "_large", true);
            new FurnitureState(plugin, center).integer("board_large_count", 3);
            refreshDisplay(center);
            center.location().getWorld().playSound(center.location(), Sound.BLOCK_WOOD_PLACE, 1F, 0.9F);
            return;
        }
    }

    private static BukkitFurniture sideAt(List<BukkitFurniture> candidates, BukkitFurniture center,
                                          Vector right, int side) {
        Location origin = chalkboardMergeOrigin(center);
        BukkitFurniture best = null;
        double bestError = Double.MAX_VALUE;
        for (BukkitFurniture candidate : candidates) {
            if (candidate == center) {
                continue;
            }
            Vector delta = chalkboardMergeOrigin(candidate).toVector().subtract(origin.toVector());
            double projection = delta.dot(right);
            Vector lateral = delta.clone().subtract(right.clone().multiply(projection));
            double error = Math.abs(projection - side) + lateral.length() * 2;
            if (Math.signum(projection) == side && error < 0.35 && error < bestError) {
                best = candidate;
                bestError = error;
            }
        }
        return best;
    }

    private static Location chalkboardMergeOrigin(BukkitFurniture furniture) {
        Location origin = furniture.location().clone();
        if (!isWallBoard(furniture)) {
            return origin;
        }
        // CE stores a centred wall anchor half a block above and half a block
        // behind the equivalent ground anchor.  Normalise to the rendered panel
        // so boards placed through either Forge-supported gesture share a row.
        Vector forward = origin.getDirection().setY(0);
        if (forward.lengthSquared() > 0) {
            origin.add(forward.normalize().multiply(0.5));
        }
        return origin.subtract(0, 0.5, 0);
    }

    private void refreshDisplay(BukkitFurniture furniture) {
        if (!furniture.isValid()) {
            return;
        }
        FurnitureState state = new FurnitureState(plugin, furniture);
        String text = state.string("board_text", "");
        if (text.isBlank()) {
            removeDisplay(state);
            return;
        }
        Entity owner = furniture.bukkitEntity();
        if (owner == null) {
            return;
        }

        boolean sandwich = isSandwichBoard(furniture);
        int maxWidth = textMaxWidth(furniture);
        int maxWidthUnits = maxWidth * FONT_UNITS_PER_PIXEL;
        int maxLines = sandwich ? SANDWICH_MAX_LINES : CHALKBOARD_MAX_LINES;
        List<BoardTextLayout.Line> lines = BoardTextLayout.wrap(
                text, maxWidthUnits, maxLines,
                codePoint -> minecraftGlyphAdvanceUnits(codePoint, sandwich));

        List<TextDisplay> existing = findDisplays(furniture, state);
        Map<Integer, TextDisplay> indexed = new HashMap<>();
        List<TextDisplay> reusable = new ArrayList<>();
        for (TextDisplay display : existing) {
            Integer lineIndex = display.getPersistentDataContainer().get(
                    boardLineKey, PersistentDataType.INTEGER);
            if (lineIndex != null && lineIndex >= 0 && lineIndex < maxLines
                    && indexed.putIfAbsent(lineIndex, display) == null) {
                continue;
            }
            reusable.add(display);
        }

        DyeColor dye = parseDye(state.string("board_color", "WHITE"));
        boolean glowing = state.bool("board_glowing");
        int rgb = dye.getColor().asRGB();
        if (!glowing) {
            rgb = darken(rgb, 0.6);
        }
        TextDisplay.TextAlignment alignment = parseAlignment(
                state.string("board_alignment", "center"));
        List<TextDisplay> active = new ArrayList<>(lines.size());
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            BoardTextLayout.Line line = lines.get(lineIndex);
            if (line.text().isBlank()) {
                continue;
            }
            TextDisplay display = indexed.remove(lineIndex);
            if (display == null && !reusable.isEmpty()) {
                display = reusable.remove(reusable.size() - 1);
            }
            Location position = textLineLocation(
                    furniture, lineIndex, line.width(), maxWidthUnits, alignment);
            if (display == null) {
                display = position.getWorld().spawn(position, TextDisplay.class, spawned -> {
                    spawned.setPersistent(true);
                    spawned.setGravity(false);
                    spawned.setInvulnerable(true);
                    spawned.setSilent(true);
                });
            } else {
                display.teleport(position);
            }
            display.getPersistentDataContainer().set(
                    boardOwnerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
            display.getPersistentDataContainer().set(
                    boardLineKey, PersistentDataType.INTEGER, lineIndex);

            Component component = Component.text(line.text(), TextColor.color(rgb));
            if (sandwich) {
                component = component.decorate(net.kyori.adventure.text.format.TextDecoration.BOLD);
            }
            display.text(component);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            // Lines are already wrapped to the archived renderer's pixel width.
            // Keeping this effectively unbounded prevents a client-side second wrap.
            display.setLineWidth(Integer.MAX_VALUE);
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.setDefaultBackground(false);
            display.setShadowed(false);
            display.setSeeThrough(false);
            display.setBillboard(Display.Billboard.FIXED);
            display.setTransformation(textTransformation(furniture));
            // TextBlockEntityRender culls text beyond 48 blocks.
            display.setViewRange(0.75F);
            display.setShadowRadius(0F);
            display.setGlowing(glowing);
            display.setGlowColorOverride(glowing ? dye.getColor() : null);
            active.add(display);
        }

        indexed.values().forEach(Entity::remove);
        reusable.forEach(Entity::remove);
        state.strings("board_displays", active.stream()
                .map(display -> display.getUniqueId().toString())
                .toList());
    }

    private List<TextDisplay> findDisplays(BukkitFurniture furniture, FurnitureState state) {
        Map<UUID, TextDisplay> displays = new LinkedHashMap<>();
        addStoredDisplays(state.strings("board_displays"), displays);
        Entity owner = furniture.bukkitEntity();
        if (owner == null) {
            return new ArrayList<>(displays.values());
        }
        String ownerId = owner.getUniqueId().toString();
        for (Entity entity : furniture.location().getWorld().getNearbyEntities(
                furniture.location(), 3, 3, 3, nearby -> nearby instanceof TextDisplay)) {
            String candidate = entity.getPersistentDataContainer().get(
                    boardOwnerKey, PersistentDataType.STRING);
            if (ownerId.equals(candidate) && entity.isValid()) {
                displays.putIfAbsent(entity.getUniqueId(), (TextDisplay) entity);
            }
        }
        return new ArrayList<>(displays.values());
    }

    private static void addStoredDisplays(List<String> stored, Map<UUID, TextDisplay> displays) {
        for (String token : stored) {
            try {
                Entity entity = Bukkit.getEntity(UUID.fromString(token));
                if (entity instanceof TextDisplay textDisplay && entity.isValid()) {
                    displays.putIfAbsent(entity.getUniqueId(), textDisplay);
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed state; the caller rewrites it after refresh.
            }
        }
    }

    private void removeDisplay(FurnitureState state) {
        Map<UUID, TextDisplay> displays = new LinkedHashMap<>();
        addStoredDisplays(state.strings("board_displays"), displays);
        displays.values().forEach(Entity::remove);
        state.clear("board_displays");
    }

    private void bootstrapDisplays() {
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (!CraftEngineFurniture.isFurniture(display)) {
                    continue;
                }
                BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(display);
                if (furniture != null && isBoard(furniture)) {
                    refreshDisplay(furniture);
                }
            }
        }
    }

    private List<BukkitFurniture> nearbyFurniture(Location center, double radius) {
        List<BukkitFurniture> result = new ArrayList<>();
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!CraftEngineFurniture.isFurniture(entity)) {
                continue;
            }
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture != null) {
                result.add(furniture);
            }
        }
        return result;
    }

    private static Location textLineLocation(BukkitFurniture furniture, int lineIndex,
                                             int lineWidthUnits, int maxWidthUnits,
                                             TextDisplay.TextAlignment alignment) {
        Location origin = furniture.location().clone();
        boolean sandwich = isSandwichBoard(furniture);
        boolean wall = !sandwich && isWallBoard(furniture);
        float scale = sandwich ? SANDWICH_TEXT_SCALE : CHALKBOARD_TEXT_SCALE;
        int lineHeight = sandwich ? SANDWICH_LINE_HEIGHT : CHALKBOARD_LINE_HEIGHT;
        double localVerticalOffset = (FIRST_LINE_ENTITY_OFFSET - lineIndex * lineHeight) * scale;
        // A ground chalkboard's panel hangs at the back of its cell, 0.4375
        // behind the furniture origin; a wall chalkboard's origin sits on the
        // wall plane with the panel 0..1/16 in front of it, so its text goes
        // just outside the panel's outward face instead.
        double forwardOffset = sandwich
                ? 0.06 - localVerticalOffset * Math.sin(SANDWICH_TILT_RADIANS)
                : wall ? 0.07 : -0.43;
        Vector forward = origin.getDirection().setY(0);
        if (forward.lengthSquared() > 0) {
            forward.normalize().multiply(forwardOffset);
            origin.add(forward);
        }
        double verticalOffset = sandwich
                ? localVerticalOffset * Math.cos(SANDWICH_TILT_RADIANS)
                : localVerticalOffset;
        // The wall anchor's centre alignment stores the furniture origin half
        // a block above a ground board's, while the board itself renders at
        // the same world height, so wall text drops the same half block.
        origin.add(0, (sandwich ? 1.06 : wall ? 1.035 : 1.535) + verticalOffset, 0);

        double alignedCenterUnits = switch (alignment) {
            case LEFT -> (lineWidthUnits - maxWidthUnits) / 2.0;
            case RIGHT -> (maxWidthUnits - lineWidthUnits) / 2.0;
            case CENTER -> 0.0;
        };
        double alignedCenterPixels = alignedCenterUnits / FONT_UNITS_PER_PIXEL;
        // The 26.2 TextDisplay renderer adds a one-pixel left background margin.
        // Counter it so alignment matches TextBlockEntityRender#getPosX exactly.
        origin.add(horizontalRight(origin.getYaw()).multiply((alignedCenterPixels - 1.0) * scale));
        origin.setPitch(sandwich ? -22.5F : 0F);
        return origin;
    }

    private static Transformation textTransformation(BukkitFurniture furniture) {
        float legacyScale = legacyTextScale(furniture);
        float displayScale = legacyScale / VANILLA_TEXT_SCALE;
        return new Transformation(
                new Vector3f(),
                new AxisAngle4f(),
                new Vector3f(displayScale),
                new AxisAngle4f());
    }

    private static float legacyTextScale(BukkitFurniture furniture) {
        return isSandwichBoard(furniture) ? SANDWICH_TEXT_SCALE : CHALKBOARD_TEXT_SCALE;
    }

    private static int textMaxWidth(BukkitFurniture furniture) {
        if (isLargeBoard(furniture)) {
            return 232;
        }
        return isSandwichBoard(furniture) ? 55 : 63;
    }

    /** Returns 26.2 font advance in half-pixel units. */
    private static int minecraftGlyphAdvanceUnits(int codePoint, boolean bold) {
        if (codePoint == '\t') {
            return (bold ? 20 : 16) * FONT_UNITS_PER_PIXEL;
        }
        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.FORMAT) {
            return 0;
        }

        int advanceUnits;
        if (codePoint >= 32 && codePoint <= 126) {
            advanceUnits = asciiGlyphAdvance((char) codePoint) * FONT_UNITS_PER_PIXEL;
            if (bold) {
                advanceUnits += FONT_UNITS_PER_PIXEL;
            }
        } else if (usesFullWidthUnihexGlyph(codePoint)) {
            // The official 26.2 unifont provider overrides CJK glyphs to
            // 16 source pixels: 16 / 2 + 1 = 9 rendered pixels. Unihex's
            // bold offset is half a pixel rather than the bitmap font's one.
            advanceUnits = 18 + (bold ? 1 : 0);
        } else if (Character.isBmpCodePoint(codePoint)) {
            String glyph = Character.toString((char) codePoint);
            if (MinecraftFont.Font.isValid(glyph)) {
                // MapFont excludes the trailing pixel that Font#width includes.
                advanceUnits = (MinecraftFont.Font.getWidth(glyph) + 1)
                        * FONT_UNITS_PER_PIXEL;
                if (bold) {
                    advanceUnits += FONT_UNITS_PER_PIXEL;
                }
            } else {
                advanceUnits = 18 + (bold ? 1 : 0);
            }
        } else {
            advanceUnits = 18 + (bold ? 1 : 0);
        }
        return advanceUnits;
    }

    private static boolean usesFullWidthUnihexGlyph(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA;
    }

    private static int asciiGlyphAdvance(char character) {
        return switch (character) {
            case '!', '\'', ',', '.', ':', ';', 'i', '|' -> 2;
            case '`', 'l' -> 3;
            case ' ', '"', '(', ')', '*', 'I', '[', ']', 't', '{', '}' -> 4;
            case '<', '>', 'f', 'k' -> 5;
            case '@', '~' -> 7;
            default -> 6;
        };
    }

    private static Vector horizontalRight(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(Math.cos(radians), 0, Math.sin(radians)).normalize();
    }

    private static float yawDistance(float left, float right) {
        return Math.abs(((left - right + 540F) % 360F) - 180F);
    }

    private static int maxTextLength(BukkitFurniture furniture) {
        if (furniture.id().toString().equals(CHALKBOARD)) {
            return isLargeBoard(furniture) ? 1_500 : 350;
        }
        return 320;
    }

    private static boolean isBoard(BukkitFurniture furniture) {
        return furniture.id().toString().equals(CHALKBOARD) || isSandwichBoard(furniture);
    }

    /** Matches ground_large and wall_large; sandwich boards have no large variants. */
    private static boolean isLargeBoard(BukkitFurniture furniture) {
        return furniture.currentVariant().name().endsWith("_large");
    }

    private static boolean isWallBoard(BukkitFurniture furniture) {
        return furniture.currentVariant().name().startsWith("wall");
    }

    private static boolean isSandwichBoard(BukkitFurniture furniture) {
        String id = furniture.id().toString();
        return id.startsWith(PREFIX) && id.endsWith("_sandwich_board");
    }

    private static DyeColor dyeColor(Material material) {
        String name = material.name();
        if (!name.endsWith("_DYE")) {
            return null;
        }
        try {
            return DyeColor.valueOf(name.substring(0, name.length() - "_DYE".length()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static DyeColor parseDye(String name) {
        try {
            return DyeColor.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return DyeColor.WHITE;
        }
    }

    private static TextDisplay.TextAlignment parseAlignment(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "left" -> TextDisplay.TextAlignment.LEFT;
            case "right" -> TextDisplay.TextAlignment.RIGHT;
            default -> TextDisplay.TextAlignment.CENTER;
        };
    }

    private static int darken(int rgb, double factor) {
        int red = (int) ((rgb >> 16 & 0xFF) * factor);
        int green = (int) ((rgb >> 8 & 0xFF) * factor);
        int blue = (int) ((rgb & 0xFF) * factor);
        return red << 16 | green << 8 | blue;
    }

    private static void consumeUnlessCreative(Player player, ItemStack stack) {
        if (player.getGameMode() != GameMode.CREATIVE) {
            stack.subtract(1);
        }
    }

    private record EditSession(UUID furniture) {
    }

    private record BoardStateSnapshot(String text, String color, String alignment,
                                      boolean glowing, boolean waxed) {
        private static BoardStateSnapshot capture(FurnitureState state) {
            return new BoardStateSnapshot(
                    state.string("board_text", ""),
                    state.string("board_color", "WHITE"),
                    state.string("board_alignment", "center"),
                    state.bool("board_glowing"),
                    state.bool("board_waxed"));
        }

        private void apply(FurnitureState state) {
            state.putString("board_text", text);
            state.putString("board_color", color);
            state.putString("board_alignment", alignment);
            state.bool("board_glowing", glowing);
            state.bool("board_waxed", waxed);
        }
    }
}
