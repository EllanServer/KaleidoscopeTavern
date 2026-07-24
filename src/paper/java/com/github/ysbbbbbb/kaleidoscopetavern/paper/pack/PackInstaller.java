package com.github.ysbbbbbb.kaleidoscopetavern.paper.pack;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Installs the bundled CraftEngine project before CraftEngine performs its first reload. */
public final class PackInstaller {
    private static final String BUNDLE_DIRECTORY = "tavern-pack";
    private static final String MANIFEST = ".kaleidoscope-managed-files";

    private PackInstaller() {
    }

    public static Result install(JavaPlugin plugin) throws IOException {
        Plugin craftEngine = plugin.getServer().getPluginManager().getPlugin("CraftEngine");
        if (craftEngine == null) {
            throw new IOException("CraftEngine is not installed");
        }

        Path target = craftEngine.getDataFolder().toPath()
                .resolve("resources")
                .resolve("kaleidoscope_tavern")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(target);

        Set<String> previous = readManifest(target.resolve(MANIFEST));
        Set<String> current = new HashSet<>();
        Counters counters = new Counters();

        URL marker = plugin.getClass().getClassLoader().getResource(BUNDLE_DIRECTORY + "/pack.yml");
        if (marker == null) {
            throw new IOException("The bundled CraftEngine project is missing pack.yml");
        }

        try {
            if ("jar".equals(marker.getProtocol())) {
                copyFromJar(marker, target, current, counters);
            } else if ("file".equals(marker.getProtocol())) {
                copyFromDirectory(marker, target, current, counters);
            } else {
                throw new IOException("Unsupported plugin resource protocol: " + marker.getProtocol());
            }
        } catch (URISyntaxException exception) {
            throw new IOException("Invalid bundled project URI", exception);
        }

        int removed = removeStaleManagedFiles(target, previous, current);
        writeManifest(target.resolve(MANIFEST), current);
        return new Result(target, counters.written, counters.unchanged, removed, current.size());
    }

    private static void copyFromJar(URL marker, Path target, Set<String> current, Counters counters)
            throws IOException {
        JarURLConnection connection = (JarURLConnection) marker.openConnection();
        connection.setUseCaches(false);
        URI jarUri;
        try {
            jarUri = connection.getJarFileURL().toURI();
        } catch (URISyntaxException exception) {
            throw new IOException("Invalid plugin jar URI", exception);
        }

        try (FileSystem fileSystem = FileSystems.newFileSystem(Path.of(jarUri), java.util.Map.of())) {
            Path source = fileSystem.getPath("/" + BUNDLE_DIRECTORY);
            copyTree(source, target, current, counters);
        }
    }

    private static void copyFromDirectory(URL marker, Path target, Set<String> current, Counters counters)
            throws IOException, URISyntaxException {
        Path markerPath = Path.of(marker.toURI());
        copyTree(markerPath.getParent(), target, current, counters);
    }

    private static void copyTree(Path source, Path target, Set<String> current, Counters counters)
            throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = source.relativize(path).toString().replace('\\', '/');
                if (MANIFEST.equals(relative)) {
                    continue;
                }
                current.add(relative);
                Path destination = safeResolve(target, relative);
                Files.createDirectories(destination.getParent());
                if (Files.exists(destination) && Files.mismatch(path, destination) == -1L) {
                    counters.unchanged++;
                    continue;
                }
                Path temporary = Files.createTempFile(destination.getParent(), ".kaleidoscope-", ".tmp");
                try {
                    Files.copy(path, temporary, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
                counters.written++;
            }
        }
    }

    private static int removeStaleManagedFiles(Path target, Set<String> previous, Set<String> current)
            throws IOException {
        int removed = 0;
        List<Path> parents = new ArrayList<>();
        for (String relative : previous) {
            if (current.contains(relative)) {
                continue;
            }
            Path stale = safeResolve(target, relative);
            if (Files.deleteIfExists(stale)) {
                removed++;
                parents.add(stale.getParent());
            }
        }
        parents.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path parent : parents) {
            Path cursor = parent;
            while (cursor != null && !cursor.equals(target)) {
                try {
                    Files.delete(cursor);
                } catch (IOException ignored) {
                    break;
                }
                cursor = cursor.getParent();
            }
        }
        return removed;
    }

    private static Path safeResolve(Path target, String relative) throws IOException {
        Path resolved = target.resolve(relative).normalize();
        if (!resolved.startsWith(target)) {
            throw new IOException("Bundled path escapes the CraftEngine project: " + relative);
        }
        return resolved;
    }

    private static Set<String> readManifest(Path manifest) throws IOException {
        if (!Files.exists(manifest)) {
            return Set.of();
        }
        Set<String> files = new HashSet<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                files.add(line.strip());
            }
        }
        return files;
    }

    private static void writeManifest(Path manifest, Set<String> paths) throws IOException {
        List<String> lines = paths.stream().sorted().toList();
        Files.write(manifest, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static final class Counters {
        private int written;
        private int unchanged;
    }

    public record Result(Path target, int written, int unchanged, int removed, int total) {
    }
}
