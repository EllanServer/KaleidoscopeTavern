package com.github.ysbbbbbb.kaleidoscopetavern.paper.recipe;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.Objects;

/** Installs and loads the two operator-owned station recipe files. */
public final class StationRecipeLoader {
    private static final String BARREL_RESOURCE = "recipes/barrel.yml";
    private static final String SHAKER_RESOURCE = "recipes/shaker.yml";

    private final ClassLoader resourceLoader;
    private final Path directory;

    public StationRecipeLoader(ClassLoader resourceLoader, Path directory) {
        this.resourceLoader = Objects.requireNonNull(resourceLoader, "resourceLoader");
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath().normalize();
    }

    /**
     * Loads both files as one unit. Missing files receive bundled defaults;
     * existing operator files are never overwritten.
     */
    public StationRecipeSet load() throws IOException {
        installDefault(BARREL_RESOURCE);
        installDefault(SHAKER_RESOURCE);
        return StationRecipeParser.parse(
                directory.resolve("barrel.yml"), directory.resolve("shaker.yml"));
    }

    public Path directory() {
        return directory;
    }

    private void installDefault(String resource) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(Path.of(resource).getFileName()).normalize();
        if (!target.getParent().equals(directory)) {
            throw new IOException("Recipe target escaped data directory: " + target);
        }
        if (Files.exists(target)) {
            return;
        }
        try (InputStream stream = resourceLoader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Missing bundled recipe resource " + resource);
            }
            try {
                Files.copy(stream, target);
            } catch (FileAlreadyExistsException ignored) {
                // Another startup path completed the same create-only install.
            }
        }
    }
}
