package com.github.ysbbbbbb.kaleidoscopetavern.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class Messages {
    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void send(CommandSender recipient, String path) {
        send(recipient, path, Map.of());
    }

    public void send(CommandSender recipient, String path, Map<String, ?> replacements) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        String body = plugin.getConfig().getString("messages." + path, "<red>Missing message: " + path + "</red>");
        for (Map.Entry<String, ?> entry : replacements.entrySet()) {
            body = body.replace('<' + entry.getKey() + '>',
                    MiniMessage.miniMessage().escapeTags(String.valueOf(entry.getValue())));
        }
        recipient.sendMessage(miniMessage.deserialize(prefix + body));
    }

    public Component parse(String source) {
        return miniMessage.deserialize(source);
    }
}
