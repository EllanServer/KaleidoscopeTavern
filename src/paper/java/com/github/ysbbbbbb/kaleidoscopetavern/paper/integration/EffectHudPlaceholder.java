package com.github.ysbbbbbb.kaleidoscopetavern.paper.integration;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.EffectService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Exposes the drink-effect HUD to PlaceholderAPI so an external renderer such
 * as CustomNameplates can own the boss bar instead of the built-in one.
 *
 * <p>{@code %kaleidoscopetavern_effect_hud%} is the full MiniMessage line
 * (icons from the {@code kaleidoscope_tavern:custom_effects} bitmap font plus
 * localized name, potency and countdown); {@code
 * %kaleidoscopetavern_effect_count%} supports hide-when-empty conditions.
 */
public final class EffectHudPlaceholder extends PlaceholderExpansion {
    private final JavaPlugin plugin;
    private final EffectService effects;

    public EffectHudPlaceholder(JavaPlugin plugin, EffectService effects) {
        this.plugin = plugin;
        this.effects = effects;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "kaleidoscopetavern";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer offline, @NotNull String params) {
        Player player = offline == null ? null : offline.getPlayer();
        if (player == null) {
            return "";
        }
        return switch (params) {
            case "effect_hud" -> effects.effectHudMiniMessage(player);
            case "effect_count" -> Integer.toString(effects.activeEffectCount(player));
            default -> null;
        };
    }
}
