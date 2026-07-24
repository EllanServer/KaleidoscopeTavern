package com.github.ysbbbbbb.kaleidoscopetavern.paper.pack;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Installs the managed crop definitions before CustomCrops parses its contents. */
public final class CustomCropsInstaller {
    private static final String RESOURCE =
            "customcrops/contents/crops/kaleidoscope_tavern.yml";
    private static final Path RELATIVE_TARGET =
            Path.of("contents", "crops", "kaleidoscope_tavern.yml");

    private CustomCropsInstaller() {
    }

    public static Result install(JavaPlugin plugin) throws IOException {
        Plugin customCrops = plugin.getServer().getPluginManager().getPlugin("CustomCrops");
        if (customCrops == null) {
            throw new IOException("CustomCrops is not installed");
        }

        Path dataFolder = customCrops.getDataFolder().toPath().toAbsolutePath().normalize();
        Path target = dataFolder.resolve(RELATIVE_TARGET).normalize();
        if (!target.startsWith(dataFolder)) {
            throw new IOException("Managed CustomCrops path escapes the plugin data folder");
        }
        Files.createDirectories(target.getParent());

        Path temporary = Files.createTempFile(target.getParent(), ".kaleidoscope-", ".tmp");
        try (InputStream input = plugin.getResource(RESOURCE)) {
            if (input == null) {
                throw new IOException("The bundled CustomCrops definition is missing");
            }
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (Files.exists(target) && Files.mismatch(temporary, target) == -1L) {
                return new Result(target, false);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new Result(target, true);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record Result(Path target, boolean written) {
    }
}
