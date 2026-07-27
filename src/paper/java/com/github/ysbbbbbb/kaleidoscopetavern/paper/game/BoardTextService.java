package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.LifecycleFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.BoardTextFurnitureBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MinecraftFont;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final LifecycleFurnitureBehavior.Handler lifecycleHandler;
    private final BoardTextFurnitureBehavior.Handler boardVisualHandler =
            this::boardVisuals;
    private final BoardTextFurnitureBehavior.InteractionHandler boardInteractionHandler =
            this::interactBoard;
    // AsyncChatEvent removes entries off the main thread.
    private final Map<UUID, EditSession> editors = new ConcurrentHashMap<>();
    private final EditMoveListener editMoveListener = new EditMoveListener();
    private boolean editMoveListenerRegistered;

    public BoardTextService(JavaPlugin plugin, ItemService items) {
        this.plugin = plugin;
        this.items = items;
        this.lifecycleHandler = new LifecycleFurnitureBehavior.Handler() {
            @Override
            public void onReady(BukkitFurniture furniture,
                                LifecycleFurnitureBehavior.ReadyReason reason) {
            }

            @Override
            public void onUnavailable(BukkitFurniture furniture,
                                      boolean removed, boolean stopping) {
                UUID owner = furnitureOwner(furniture);
                editors.entrySet().removeIf(
                        entry -> entry.getValue().furniture().equals(owner));
                stopEditMoveListenerIfIdle();
            }
        };
    }

    public void start() {
        BoardTextFurnitureBehavior.bind(boardVisualHandler);
        BoardTextFurnitureBehavior.bindInteraction(boardInteractionHandler);
        LifecycleFurnitureBehavior.bind(
                LifecycleFurnitureBehavior.Channel.BOARD, lifecycleHandler);
    }

    public void stop() {
        HandlerList.unregisterAll(editMoveListener);
        editMoveListenerRegistered = false;
        BoardTextFurnitureBehavior.unbindInteraction(boardInteractionHandler);
        BoardTextFurnitureBehavior.unbind(boardVisualHandler);
        LifecycleFurnitureBehavior.unbind(
                LifecycleFurnitureBehavior.Channel.BOARD, lifecycleHandler);
        editors.clear();
    }

    private InteractionResult interactBoard(BukkitFurniture furniture,
                                            InteractEntityContext context) {
        if (context.getHand() != InteractionHand.MAIN_HAND || !isBoard(furniture)) {
            return InteractionResult.PASS;
        }
        FurnitureState state = new FurnitureState(furniture);
        Player player = (Player) context.getPlayer().platformPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();

        // SandwichBoardBlock applies its flower transformation before it
        // delegates to TextBlockEntity, so waxing locks text properties but
        // deliberately does not lock the board's decorative variant.
        if (isSandwichBoard(furniture) && transformSandwichBoard(player, furniture, state, hand)) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        if (state.bool("board_waxed")) {
            player.playSound(furniture.location(), "minecraft:block.waxed_sign.interact_fail", 1F, 1F);
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        DyeColor dye = dyeColor(hand.getType());
        if (dye != null && !dye.name().equals(state.string("board_color", "WHITE"))) {
            state.putString("board_color", dye.name());
            consumeUnlessCreative(player, hand);
            player.playSound(furniture.location(), Sound.ITEM_DYE_USE, 1F, 1F);
            refreshDisplay(furniture);
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        if (hand.getType() == Material.GLOW_INK_SAC && !state.bool("board_glowing")) {
            state.bool("board_glowing", true);
            consumeUnlessCreative(player, hand);
            player.playSound(furniture.location(), Sound.ITEM_GLOW_INK_SAC_USE, 1F, 1F);
            refreshDisplay(furniture);
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        if (hand.getType() == Material.INK_SAC && state.bool("board_glowing")) {
            state.bool("board_glowing", false);
            consumeUnlessCreative(player, hand);
            player.playSound(furniture.location(), Sound.ITEM_INK_SAC_USE, 1F, 1F);
            refreshDisplay(furniture);
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        if (hand.getType() == Material.HONEYCOMB) {
            state.bool("board_waxed", true);
            consumeUnlessCreative(player, hand);
            World world = furniture.location().getWorld();
            world.playSound(furniture.location(), Sound.ITEM_HONEYCOMB_WAX_ON, 1F, 1F);
            world.spawnParticle(Particle.WAX_ON, furniture.location().clone().add(0, 1, 0),
                    10, 0.4, 0.4, 0.4, 0.05);
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        Entity entity = furniture.bukkitEntity();
        if (entity != null) {
            editors.put(player.getUniqueId(), new EditSession(entity.getUniqueId()));
            ensureEditMoveListener();
            player.sendMessage(Component.text("请在聊天栏输入文字（\\n 换行；[left]/[center]/[right] 设置对齐；!clear 清空；!cancel 取消）。"));
        }
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        EditSession session = editors.remove(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> {
            stopEditMoveListenerIfIdle();
            applyChatInput(event.getPlayer(), session, input);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurniturePlace(FurniturePlaceEvent event) {
        BukkitFurniture placed = event.furniture();
        if (!isBoard(placed)) {
            return;
        }
        boolean mergeChalkboards = placed.id().toString().equals(CHALKBOARD)
                && !event.player().isSneaking();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!placed.isValid()) {
                return;
            }
            if (mergeChalkboards) {
                tryMergeChalkboards(placed);
            }
            refreshDisplay(placed);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent event) {
        if (!isBoard(event.furniture())) {
            return;
        }
        FurnitureState state = new FurnitureState(event.furniture());
        Entity entity = event.furniture().bukkitEntity();
        if (entity != null) {
            editors.entrySet().removeIf(entry -> entry.getValue().furniture().equals(entity.getUniqueId()));
            stopEditMoveListenerIfIdle();
        }
        if (event.dropItems() && state.integer("board_large_count") == 3) {
            items.build(CHALKBOARD, event.player()).ifPresent(stack -> {
                stack.setAmount(2);
                event.location().getWorld().dropItemNaturally(event.location(), stack);
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        editors.remove(event.getPlayer().getUniqueId());
        stopEditMoveListenerIfIdle();
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
        FurnitureState state = new FurnitureState(furniture);
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
            stopEditMoveListenerIfIdle();
        }
    }

    private void ensureEditMoveListener() {
        if (editMoveListenerRegistered) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(editMoveListener, plugin);
        editMoveListenerRegistered = true;
    }

    private void stopEditMoveListenerIfIdle() {
        if (!editMoveListenerRegistered || !editors.isEmpty()) {
            return;
        }
        HandlerList.unregisterAll(editMoveListener);
        editMoveListenerRegistered = false;
    }

    private final class EditMoveListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onMove(PlayerMoveEvent event) {
            if (event.getTo() == null
                    || !editors.containsKey(event.getPlayer().getUniqueId())
                    || event.getFrom().getX() == event.getTo().getX()
                    && event.getFrom().getY() == event.getTo().getY()
                    && event.getFrom().getZ() == event.getTo().getZ()) {
                return;
            }
            validateEditDistance(event.getPlayer());
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
        CraftEngineFurniture.remove(furniture, false, false);
        BukkitFurniture replacement = CraftEngineFurniture.place(location, Key.of(targetId), "ground", false);
        if (replacement == null) {
            replacement = CraftEngineFurniture.place(location, Key.of(BASE_SANDWICH_BOARD), "ground", false);
            player.sendMessage(Component.text("展板样式切换失败，已恢复为基础展板。"));
            if (replacement != null) {
                snapshot.apply(new FurnitureState(replacement));
                refreshDisplay(replacement);
            }
            return true;
        }
        snapshot.apply(new FurnitureState(replacement));
        consumeUnlessCreative(player, hand);
        location.getWorld().playSound(location, Sound.BLOCK_GRASS_PLACE, 1F, 1F);
        refreshDisplay(replacement);
        return true;
    }

    private void tryMergeChalkboards(BukkitFurniture placed) {
        String variant = placed.currentVariant().name();
        if (!placed.isValid() || !(variant.equals("ground") || variant.equals("wall"))
                || !new FurnitureState(placed).string("board_text", "").isBlank()) {
            return;
        }
        List<BukkitFurniture> candidates = nearbyFurniture(placed.location(), 2.25).stream()
                .filter(furniture -> furniture.id().toString().equals(CHALKBOARD))
                .filter(furniture -> {
                    String candidateVariant = furniture.currentVariant().name();
                    return candidateVariant.equals("ground") || candidateVariant.equals("wall");
                })
                .filter(furniture -> new FurnitureState(furniture).string("board_text", "").isBlank())
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
            CraftEngineFurniture.remove(left, false, false);
            CraftEngineFurniture.remove(rightBoard, false, false);
            center.setVariant(center.currentVariant().name() + "_large", true);
            new FurnitureState(center).integer("board_large_count", 3);
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
        if (furniture.isValid()) {
            BoardTextFurnitureBehavior.refresh(furniture);
        }
    }

    private List<BoardTextFurnitureBehavior.Visual> boardVisuals(
            BukkitFurniture furniture) {
        if (!furniture.isValid()) {
            return List.of();
        }
        FurnitureState state = new FurnitureState(furniture);
        String text = state.string("board_text", "");
        if (text.isBlank()) {
            return List.of();
        }

        boolean sandwich = isSandwichBoard(furniture);
        int maxWidth = textMaxWidth(furniture);
        int maxWidthUnits = maxWidth * FONT_UNITS_PER_PIXEL;
        int maxLines = sandwich ? SANDWICH_MAX_LINES : CHALKBOARD_MAX_LINES;
        List<BoardTextLayout.Line> lines = BoardTextLayout.wrap(
                text, maxWidthUnits, maxLines,
                codePoint -> minecraftGlyphAdvanceUnits(codePoint, sandwich));

        DyeColor dye = parseDye(state.string("board_color", "WHITE"));
        boolean glowing = state.bool("board_glowing");
        int rgb = dye.getColor().asRGB();
        if (!glowing) {
            rgb = darken(rgb, 0.6);
        }
        BoardAlignment alignment = parseAlignment(
                state.string("board_alignment", "center"));
        float displayScale = legacyTextScale(furniture) / VANILLA_TEXT_SCALE;
        List<BoardTextFurnitureBehavior.Visual> result =
                new ArrayList<>(lines.size());
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            BoardTextLayout.Line line = lines.get(lineIndex);
            if (line.text().isBlank()) {
                continue;
            }
            Location position = textLineLocation(
                    furniture, lineIndex, line.width(), maxWidthUnits, alignment);
            Component component = Component.text(line.text(), TextColor.color(rgb));
            if (sandwich) {
                component = component.decorate(
                        net.kyori.adventure.text.format.TextDecoration.BOLD);
            }
            result.add(new BoardTextFurnitureBehavior.Visual(
                    component,
                    position.getX(), position.getY(), position.getZ(),
                    position.getYaw(), position.getPitch(), displayScale,
                    glowing, dye.getColor().asRGB()));
        }
        return result;
    }

    private List<BukkitFurniture> nearbyFurniture(Location center, double radius) {
        return LifecycleFurnitureBehavior.nearby(
                LifecycleFurnitureBehavior.Channel.BOARD, center, radius, radius);
    }

    private static UUID furnitureOwner(BukkitFurniture furniture) {
        Entity entity = furniture.bukkitEntity();
        return entity == null ? furniture.uuid() : entity.getUniqueId();
    }

    private static Location textLineLocation(BukkitFurniture furniture, int lineIndex,
                                             int lineWidthUnits, int maxWidthUnits,
                                             BoardAlignment alignment) {
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

    private static BoardAlignment parseAlignment(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "left" -> BoardAlignment.LEFT;
            case "right" -> BoardAlignment.RIGHT;
            default -> BoardAlignment.CENTER;
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

    private enum BoardAlignment {
        LEFT,
        CENTER,
        RIGHT
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
