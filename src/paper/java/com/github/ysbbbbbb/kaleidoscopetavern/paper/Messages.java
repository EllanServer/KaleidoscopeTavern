package com.github.ysbbbbbb.kaleidoscopetavern.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class Messages {
    private static final String BUNDLED_CONFIG = "config.yml";

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final YamlConfiguration bundled;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        this.bundled = loadBundled(plugin);
    }

    public void send(CommandSender recipient, String path) {
        send(recipient, path, Map.of());
    }

    public void send(CommandSender recipient, String path, Map<String, ?> replacements) {
        String prefix = string("messages.prefix", "");
        String body = string("messages." + path, "<red>Missing message: " + path + "</red>");
        for (Map.Entry<String, ?> entry : replacements.entrySet()) {
            body = body.replace('<' + entry.getKey() + '>',
                    MiniMessage.miniMessage().escapeTags(String.valueOf(entry.getValue())));
        }
        recipient.sendMessage(miniMessage.deserialize(prefix + body));
    }

    public Component parse(String source) {
        return miniMessage.deserialize(source);
    }

    /**
     * Resolves a config path, falling back to the value shipped inside the jar.
     *
     * <p>{@code saveDefaultConfig} only writes config.yml when it is absent, so a
     * server that installed an earlier build keeps a file without the keys added
     * since. Consulting the bundled copy means new messages work on upgrade
     * instead of rendering as "Missing message", without discarding the operator's
     * own edits or forcing them to delete the file.
     */
    private String string(String path, String fallback) {
        String configured = plugin.getConfig().getString(path);
        if (configured != null) {
            return configured;
        }
        return bundled == null ? fallback : bundled.getString(path, fallback);
    }

    private static YamlConfiguration loadBundled(JavaPlugin plugin) {
        try (InputStream stream = plugin.getResource(BUNDLED_CONFIG)) {
            if (stream == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (Exception exception) {
            plugin.getLogger().warning(
                    "Could not read the bundled config.yml for message defaults: " + exception.getMessage());
            return null;
        }
    }
}
