package com.github.ysbbbbbb.kaleidoscopetavern.paper;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.Selector;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.SelectorKind;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.command.RecipeCommandSemantics;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.command.RecipeCommandSemantics.PageWindow;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.command.RecipeCommandSemantics.RecipeType;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.AmbientFurnitureService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.BarStoolVisualService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.EffectService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.BoardTextService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.BottlePlacementService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.BottleFurnitureService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.DisplayStorageService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.FurnitureConnectionService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.MolotovService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.ShakerVisualService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.StationService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.TapService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.BlockService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.HangingGrapeCropBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.TrellisBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.TrellisBlockShape;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.WildGrapevineBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.integration.CustomCropsBridge;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.integration.EffectHudPlaceholder;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.pack.CustomCropsInstaller;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.pack.PackInstaller;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;

/** Paper 26.2 entry point for the CraftEngine rewrite. */
public final class KaleidoscopeTavernPlugin extends JavaPlugin implements Listener, TabExecutor {
    private static final String NAMESPACE = "kaleidoscope_tavern";
    private static final int EXPECTED_ITEMS = 711; // 157 public items + 554 private render helpers
    private static final int EXPECTED_BLOCKS = 41;
    private static final int EXPECTED_FURNITURE = 133;

    private PackInstaller.Result packResult;
    private CustomCropsInstaller.Result customCropsResult;
    private Throwable loadFailure;
    private ContentCatalog catalog;
    private ItemService items;
    private StationService stations;
    private EffectService effects;
    private BoardTextService boards;
    private TapService taps;
    private DisplayStorageService displayStorage;
    private AmbientFurnitureService ambientFurniture;
    private BarStoolVisualService barStoolVisuals;
    private ShakerVisualService shakerVisuals;
    private EffectHudPlaceholder effectHudPlaceholder;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        try {
            TrellisBehavior.register();
            HangingGrapeCropBehavior.register();
            WildGrapevineBehavior.register();
            if (getConfig().getBoolean("pack.install-on-startup", true)) {
                packResult = PackInstaller.install(this);
            }
            if (getConfig().getBoolean("custom-crops.install-managed-config", true)) {
                customCropsResult = CustomCropsInstaller.install(this);
            }
        } catch (IOException | RuntimeException exception) {
            loadFailure = exception;
            getLogger().log(Level.SEVERE, "无法安装 Kaleidoscope Tavern 托管内容", exception);
        }
    }

    @Override
    public void onEnable() {
        if (loadFailure != null) {
            getLogger().severe("插件已停用：onLoad 阶段未能准备 CraftEngine 内容包。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            CustomCropsBridge.requireReady();
        } catch (RuntimeException | LinkageError exception) {
            getLogger().log(Level.SEVERE, "CustomCrops 3.6.52+ API 无法初始化，插件已停止。", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            catalog = ContentCatalog.load(getClassLoader());
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "运行时配方目录损坏，插件无法启动", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        items = new ItemService(this, catalog);
        Messages messages = new Messages(this);
        shakerVisuals = new ShakerVisualService(this, items);
        stations = new StationService(this, catalog, items, messages, shakerVisuals);
        effects = new EffectService(this, catalog, items);
        boards = new BoardTextService(this, items);
        taps = new TapService(this, stations, items);
        displayStorage = new DisplayStorageService(this, catalog, items);
        ambientFurniture = new AmbientFurnitureService(this, displayStorage);
        barStoolVisuals = new BarStoolVisualService(this, items);
        FurnitureConnectionService furnitureConnections = new FurnitureConnectionService(this);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(items, this);
        getServer().getPluginManager().registerEvents(new BlockService(this, catalog, items), this);
        getServer().getPluginManager().registerEvents(furnitureConnections, this);
        getServer().getPluginManager().registerEvents(new MolotovService(this, items), this);
        getServer().getPluginManager().registerEvents(new BottlePlacementService(this, catalog, items), this);
        getServer().getPluginManager().registerEvents(new BottleFurnitureService(this, catalog, items, effects), this);
        getServer().getPluginManager().registerEvents(displayStorage, this);
        getServer().getPluginManager().registerEvents(ambientFurniture, this);
        getServer().getPluginManager().registerEvents(barStoolVisuals, this);
        getServer().getPluginManager().registerEvents(shakerVisuals, this);
        getServer().getPluginManager().registerEvents(boards, this);
        getServer().getPluginManager().registerEvents(taps, this);
        getServer().getPluginManager().registerEvents(stations, this);
        getServer().getPluginManager().registerEvents(effects, this);
        stations.start();
        effects.start();
        boards.start();
        taps.start();
        displayStorage.start();
        ambientFurniture.start();
        barStoolVisuals.start();
        shakerVisuals.start();
        furnitureConnections.start();

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            effectHudPlaceholder = new EffectHudPlaceholder(this, effects);
            effectHudPlaceholder.register();
        }

        PluginCommand command = Objects.requireNonNull(getCommand("kaleidoscopetavern"),
                "plugin.yml command kaleidoscopetavern");
        command.setExecutor(this);
        command.setTabCompleter(this);

        Bukkit.getScheduler().runTask(this, () -> refreshRuntimeContent(true));
        if (packResult != null) {
            getLogger().info("CraftEngine 内容包已同步到 " + packResult.target()
                    + "（写入 " + packResult.written() + "，未变 " + packResult.unchanged()
                    + "，清理 " + packResult.removed() + "）。");
        }
        if (customCropsResult != null) {
            getLogger().info("CustomCrops 葡萄定义已同步到 " + customCropsResult.target()
                    + (customCropsResult.written() ? "（已更新）。" : "（未变化）。"));
        }
    }

    @Override
    public void onDisable() {
        if (effectHudPlaceholder != null) {
            effectHudPlaceholder.unregister();
            effectHudPlaceholder = null;
        }
        if (stations != null) {
            stations.stop();
        }
        if (effects != null) {
            effects.stop();
        }
        if (boards != null) {
            boards.stop();
        }
        if (taps != null) {
            taps.stop();
        }
        if (displayStorage != null) {
            displayStorage.stop();
        }
        if (ambientFurniture != null) {
            ambientFurniture.stop();
        }
        if (barStoolVisuals != null) {
            barStoolVisuals.stop();
        }
        if (shakerVisuals != null) {
            shakerVisuals.stop();
        }
    }

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        Bukkit.getScheduler().runTask(this, () -> refreshRuntimeContent(false));
    }

    private void refreshRuntimeContent(boolean startup) {
        int trellisShapes = TrellisBlockShape.install();
        if (trellisShapes == 0) {
            getLogger().warning("No trellis carrier shapes were available after CraftEngine loading");
        }
        verifyContent(startup);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("recipes")) {
            if (!sender.hasPermission("kaleidoscopetavern.recipes")) {
                sender.sendMessage(Component.text("你没有权限查看配方。"));
                return true;
            }
            return recipes(sender, label, args);
        }
        if (!sender.hasPermission("kaleidoscopetavern.admin")) {
            sender.sendMessage(Component.text("用法：/" + label + " recipes <barrel|pressing|shaker> [页码]"));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("give")) {
            return give(sender, args);
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            if (stations != null) {
                stations.stop();
                stations.start();
            }
            if (effects != null) {
                effects.stop();
                effects.start();
            }
            boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ce reload all");
            boolean cropsReloaded = true;
            try {
                CustomCropsBridge.reload();
            } catch (RuntimeException exception) {
                cropsReloaded = false;
                getLogger().log(Level.SEVERE, "CustomCrops 重载失败", exception);
            }
            sender.sendMessage(Component.text(dispatched
                    && cropsReloaded
                    ? "已重载本插件、CraftEngine 与 CustomCrops 内容。"
                    : "本插件配置已重载，但至少一个依赖内容重载失败，请检查日志。"));
            return true;
        }
        sender.sendMessage(Component.text("用法：/" + label + " <status|give|reload|recipes>"));
        return true;
    }

    private boolean recipes(CommandSender sender, String label, String[] args) {
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(Component.text(
                    "用法：/" + label + " recipes <barrel|pressing|shaker> [页码]"));
            return true;
        }

        Optional<RecipeType> parsedType = RecipeCommandSemantics.parseType(args[1]);
        if (parsedType.isEmpty()) {
            sender.sendMessage(Component.text("未知配方类型：" + args[1]
                    + "；可用类型：barrel、pressing、shaker。"));
            return true;
        }
        RecipeType type = parsedType.get();
        int requestedPage = 1;
        if (args.length == 3) {
            var parsedPage = RecipeCommandSemantics.parsePage(args[2]);
            if (parsedPage.isEmpty()) {
                sender.sendMessage(Component.text("页码必须是正整数。"));
                return true;
            }
            requestedPage = parsedPage.getAsInt();
        }

        int entryCount = recipeCount(type);
        Optional<PageWindow> page = RecipeCommandSemantics.pageWindow(entryCount, requestedPage);
        if (page.isEmpty()) {
            sender.sendMessage(Component.text("页码超出范围：该类型共有 "
                    + RecipeCommandSemantics.pageCount(entryCount) + " 页。"));
            return true;
        }

        Player context = sender instanceof Player player ? player : null;
        PageWindow window = page.get();
        sender.sendMessage(Component.text("=== ")
                .append(recipeTitle(type))
                .append(Component.text("配方（" + window.page() + '/' + window.totalPages() + "） ===")));
        switch (type) {
            case PRESSING -> catalog.pressingRecipes()
                    .subList(window.fromInclusive(), window.toExclusive())
                    .forEach(recipe -> sender.sendMessage(pressingRecipeLine(recipe, context)));
            case BARREL -> catalog.barrelRecipes()
                    .subList(window.fromInclusive(), window.toExclusive())
                    .forEach(recipe -> sender.sendMessage(barrelRecipeLine(recipe, context)));
            case SHAKER -> catalog.shakerRecipes()
                    .subList(window.fromInclusive(), window.toExclusive())
                    .forEach(recipe -> sender.sendMessage(shakerRecipeLine(recipe, context)));
        }
        return true;
    }

    private Component pressingRecipeLine(ContentCatalog.PressingRecipe recipe, Player context) {
        return Component.text("• ")
                .append(selectorName(recipe.ingredient(), context))
                .append(Component.text(" → "))
                .append(fluidName(recipe.fluid()))
                .append(Component.text(" +" + recipe.amount() + " mB/次（装满后："))
                .append(itemName(recipe.bucket(), context))
                .append(Component.text("）"));
    }

    private Component barrelRecipeLine(ContentCatalog.BarrelRecipe recipe, Player context) {
        List<Component> inputs = new ArrayList<>();
        inputs.add(fluidName(recipe.fluid()));
        recipe.ingredients().stream().map(selector -> selectorName(selector, context)).forEach(inputs::add);
        return Component.text("• ")
                .append(joinComponents(inputs, " + "))
                .append(Component.text(" → "))
                .append(itemName(recipe.result(), context))
                .append(Component.text("（取酒容器："))
                .append(selectorName(recipe.carrier(), context))
                .append(Component.text("；每级："
                        + RecipeCommandSemantics.formatTicks(recipe.unitTicks()) + "）"));
    }

    private Component shakerRecipeLine(ContentCatalog.ShakerRecipe recipe, Player context) {
        List<Component> ingredients = recipe.ingredients().stream()
                .map(selector -> selectorName(selector, context))
                .toList();
        return Component.text("• ")
                .append(joinComponents(ingredients, " + "))
                .append(Component.text(" → "))
                .append(itemName(recipe.result(), context));
    }

    private Component selectorName(Selector selector, Player context) {
        if (selector.kind() == SelectorKind.ITEM) {
            return itemName(selector.value(), context);
        }
        Optional<String> color = RecipeCommandSemantics.cocktailColorSuffix(selector.value());
        if (color.isPresent()) {
            return Component.translatable("color.kaleidoscope_tavern." + color.get());
        }
        List<Component> expanded = catalog.tag(selector.value()).stream()
                .sorted()
                .map(id -> itemName(id, context))
                .toList();
        return expanded.isEmpty()
                ? Component.text('#' + selector.value())
                : joinComponents(expanded, " / ");
    }

    private Component itemName(String id, Player context) {
        return items.build(id, context)
                .map(ItemStack::effectiveName)
                .orElse(Component.text(id));
    }

    private static Component fluidName(String id) {
        int separator = id.indexOf(':');
        if (separator <= 0 || separator == id.length() - 1) {
            return Component.text(id);
        }
        return Component.translatable("block." + id.substring(0, separator) + '.'
                + id.substring(separator + 1));
    }

    private static Component joinComponents(List<Component> parts, String separator) {
        if (parts.isEmpty()) {
            return Component.empty();
        }
        Component joined = parts.getFirst();
        for (int index = 1; index < parts.size(); index++) {
            joined = joined.append(Component.text(separator)).append(parts.get(index));
        }
        return joined;
    }

    private int recipeCount(RecipeType type) {
        return switch (type) {
            case BARREL -> catalog.barrelRecipes().size();
            case PRESSING -> catalog.pressingRecipes().size();
            case SHAKER -> catalog.shakerRecipes().size();
        };
    }

    private static Component recipeTitle(RecipeType type) {
        return Component.translatable(switch (type) {
            case BARREL -> "block.kaleidoscope_tavern.barrel";
            case PRESSING -> "block.kaleidoscope_tavern.pressing_tub";
            case SHAKER -> "block.kaleidoscope_tavern.shaker";
        });
    }

    private boolean give(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法：/kt give <物品ID> [数量] [玩家]"));
            return true;
        }
        Player target;
        if (args.length >= 4) {
            target = Bukkit.getPlayerExact(args[3]);
            if (target == null) {
                sender.sendMessage(Component.text("找不到在线玩家：" + args[3]));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(Component.text("控制台使用时必须指定玩家。"));
            return true;
        }

        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException ignored) {
                sender.sendMessage(Component.text("数量必须是整数。"));
                return true;
            }
        }
        if (amount < 1 || amount > 10_000) {
            sender.sendMessage(Component.text("数量必须在 1 到 10000 之间。"));
            return true;
        }

        String id = args[1].contains(":") ? args[1] : NAMESPACE + ':' + args[1];
        Optional<ItemStack> sample = items.build(id, target);
        if (sample.isEmpty()) {
            sender.sendMessage(Component.text("未知物品：" + id));
            return true;
        }
        int remaining = amount;
        while (remaining > 0) {
            ItemStack stack = sample.get().clone();
            stack.setAmount(Math.min(remaining, stack.getMaxStackSize()));
            items.give(target, stack);
            remaining -= stack.getAmount();
        }
        sender.sendMessage(Component.text("已给予 " + target.getName() + " " + amount + " 个 " + id + "。"));
        return true;
    }

    private void sendStatus(CommandSender sender) {
        Counts counts = contentCounts();
        sender.sendMessage(Component.text("Kaleidoscope Tavern " + getPluginMeta().getVersion()));
        sender.sendMessage(Component.text("CustomCrops："
                + Objects.requireNonNull(getServer().getPluginManager().getPlugin("CustomCrops"))
                .getPluginMeta().getVersion()
                + "（作物生命周期）"));
        sender.sendMessage(Component.text("CraftEngine：物品 " + counts.items + '/' + EXPECTED_ITEMS
                + "，方块 " + counts.blocks + '/' + EXPECTED_BLOCKS
                + "，家具 " + counts.furniture + '/' + EXPECTED_FURNITURE));
        sender.sendMessage(Component.text("玩法目录：压榨 " + catalog.pressingRecipes().size()
                + "，酒桶 " + catalog.barrelRecipes().size()
                + "，摇壶 " + catalog.shakerRecipes().size()
                + "，饮用效果 " + catalog.effectEntryCount()));
        if (packResult != null) {
            sender.sendMessage(Component.text("内容包目录：" + packResult.target()));
        }
        if (customCropsResult != null) {
            sender.sendMessage(Component.text("作物定义目录：" + customCropsResult.target()));
        }
    }

    private void verifyContent(boolean startup) {
        Counts counts = contentCounts();
        if (counts.items == EXPECTED_ITEMS && counts.blocks == EXPECTED_BLOCKS
                && counts.furniture == EXPECTED_FURNITURE) {
            getLogger().info("CraftEngine 内容校验通过：" + counts.items + " 物品 / "
                    + counts.blocks + " 方块 / " + counts.furniture + " 家具。");
            return;
        }
        String message = "CraftEngine 内容未完整加载：物品 " + counts.items + '/' + EXPECTED_ITEMS
                + "，方块 " + counts.blocks + '/' + EXPECTED_BLOCKS
                + "，家具 " + counts.furniture + '/' + EXPECTED_FURNITURE
                + "。请检查 CraftEngine 日志并执行 /ce reload all。";
        if (startup) {
            getLogger().severe(message);
        } else {
            getLogger().warning(message);
        }
    }

    private static Counts contentCounts() {
        long itemCount = CraftEngineItems.loadedItems().keySet().stream()
                .filter(key -> key.namespace().equals(NAMESPACE)).count();
        long blockCount = CraftEngineBlocks.loadedBlocks().keySet().stream()
                .filter(key -> key.namespace().equals(NAMESPACE)).count();
        long furnitureCount = CraftEngineFurniture.loadedFurniture().keySet().stream()
                .filter(key -> key.namespace().equals(NAMESPACE)).count();
        return new Counts(Math.toIntExact(itemCount), Math.toIntExact(blockCount), Math.toIntExact(furnitureCount));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission("kaleidoscopetavern.admin");
        if (args.length == 1) {
            List<String> subcommands;
            if (admin) {
                subcommands = List.of("status", "give", "reload", "recipes");
            } else if (sender.hasPermission("kaleidoscopetavern.recipes")) {
                subcommands = List.of("recipes");
            } else {
                subcommands = List.of();
            }
            return matching(subcommands, args[0]);
        }
        if (!admin && !args[0].equalsIgnoreCase("recipes")) {
            return List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> ids = CraftEngineItems.loadedItems().keySet().stream()
                    .filter(key -> key.namespace().equals(NAMESPACE) && !key.value().startsWith("_render/"))
                    .map(Key::toString)
                    .sorted()
                    .toList();
            return matching(ids, args[1]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            return matching(Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList(), args[3]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("recipes")) {
            return matching(List.of("barrel", "pressing", "shaker"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("recipes")) {
            Optional<RecipeType> type = RecipeCommandSemantics.parseType(args[1]);
            if (type.isEmpty()) {
                return List.of();
            }
            List<String> pages = new ArrayList<>();
            int pageCount = RecipeCommandSemantics.pageCount(recipeCount(type.get()));
            for (int page = 1; page <= pageCount; page++) {
                pages.add(Integer.toString(page));
            }
            return matching(pages, args[2]);
        }
        return List.of();
    }

    private static List<String> matching(List<String> candidates, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        candidates.stream().filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted(Comparator.naturalOrder()).forEach(matches::add);
        return matches;
    }

    private record Counts(int items, int blocks, int furniture) {
    }

}
