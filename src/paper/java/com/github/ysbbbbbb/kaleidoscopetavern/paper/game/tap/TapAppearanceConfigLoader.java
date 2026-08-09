package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.tap;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.tap.TapAppearanceConfig.DirectOutput;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.YamlDocument;
import net.momirealms.sparrow.yaml.node.SectionNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Strict Sparrow YAML loader for operator-owned direct tap appearances. */
public final class TapAppearanceConfigLoader {
    private static final String RESOURCE = "visuals/tap.yml";
    private static final int FORMAT_VERSION = 1;
    private static final SparrowYaml YAML = SparrowYaml.builder()
            .setAllowDuplicateKeys(false)
            .build();

    private final ClassLoader resourceLoader;
    private final Path directory;

    public TapAppearanceConfigLoader(ClassLoader resourceLoader, Path directory) {
        this.resourceLoader = Objects.requireNonNull(resourceLoader, "resourceLoader");
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath().normalize();
    }

    public TapAppearanceConfig load() throws IOException {
        Path path = installDefault();
        YamlDocument yaml;
        try {
            yaml = YAML.load(path);
        } catch (IOException | RuntimeException exception) {
            throw new IOException(path + " 不是有效的 YAML", exception);
        }
        Integer version;
        try {
            version = yaml.get(Integer.class, "config-version");
        } catch (RuntimeException exception) {
            throw new IOException(path + " 的 config-version 必须是整数", exception);
        }
        if (version == null || version != FORMAT_VERSION) {
            throw new IOException(path + " 的 config-version 必须为 " + FORMAT_VERSION);
        }
        SectionNode outputs = yaml.getSectionOrNull("outputs");
        if (outputs == null) {
            throw new IOException(path + " 缺少 outputs 映射");
        }
        Map<DirectOutput, TapFlowAppearance> appearances = new EnumMap<>(DirectOutput.class);
        for (DirectOutput output : DirectOutput.values()) {
            String encoded;
            try {
                encoded = outputs.get(String.class, output.key());
            } catch (RuntimeException exception) {
                throw new IOException(path + " 的 outputs." + output.key()
                        + " 必须是外观字符串", exception);
            }
            appearances.put(output, parseAppearance(path, output, encoded));
        }
        return new TapAppearanceConfig(appearances);
    }

    public Path directory() {
        return directory;
    }

    private Path installDefault() throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve("tap.yml").normalize();
        if (!target.getParent().equals(directory)) {
            throw new IOException("Tap appearance target escaped data directory: " + target);
        }
        if (Files.exists(target)) {
            return target;
        }
        try (InputStream stream = resourceLoader.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IOException("Missing bundled tap appearance resource " + RESOURCE);
            }
            try {
                Files.copy(stream, target);
            } catch (FileAlreadyExistsException ignored) {
                // Another startup path completed the same create-only install.
            }
        }
        return target;
    }

    private static TapFlowAppearance parseAppearance(
            Path path, DirectOutput output, String encoded) throws IOException {
        if (encoded == null) {
            throw invalidAppearance(path, output, null);
        }
        return switch (encoded) {
            case "water" -> TapFlowAppearance.WATER;
            case "lava" -> TapFlowAppearance.LAVA;
            case "honey" -> TapFlowAppearance.HONEY;
            default -> {
                if (!encoded.matches("#[0-9a-fA-F]{6}")) {
                    throw invalidAppearance(path, output, encoded);
                }
                yield TapFlowAppearance.colored(
                        Integer.parseInt(encoded.substring(1), 16));
            }
        };
    }

    private static IOException invalidAppearance(
            Path path, DirectOutput output, String encoded) {
        return new IOException(path + " 的 outputs." + output.key()
                + " 必须是 water、lava、honey 或 #RRGGBB，实际为 " + encoded);
    }
}
