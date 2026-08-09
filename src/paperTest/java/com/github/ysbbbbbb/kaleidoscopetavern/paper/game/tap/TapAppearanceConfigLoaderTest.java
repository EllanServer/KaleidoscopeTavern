package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.tap;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.tap.TapAppearanceConfig.DirectOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TapAppearanceConfigLoaderTest {
    @Test
    void installsConfiguredDirectOutputsAndKeepsNativeFluids(@TempDir Path directory)
            throws IOException {
        TapAppearanceConfigLoader loader = new TapAppearanceConfigLoader(
                getClass().getClassLoader(), directory);

        TapAppearanceConfig config = loader.load();

        assertTrue(Files.isRegularFile(directory.resolve("tap.yml")));
        assertEquals(TapFlowAppearance.WATER, config.appearance(DirectOutput.WATER));
        assertEquals(TapFlowAppearance.LAVA, config.appearance(DirectOutput.LAVA));
        assertEquals(TapFlowAppearance.HONEY, config.appearance(DirectOutput.HONEY));
        assertEquals(TapFlowAppearance.OBSIDIAN_TEAR,
                config.appearance(DirectOutput.DRAGON_BREATH));
        assertEquals(TapFlowAppearance.WATER,
                config.appearance(DirectOutput.WATERMELON));
    }

    @Test
    void reloadsOperatorColorsWithoutReplacingTheFile(@TempDir Path directory)
            throws IOException {
        TapAppearanceConfigLoader loader = new TapAppearanceConfigLoader(
                getClass().getClassLoader(), directory);
        loader.load();
        Files.writeString(directory.resolve("tap.yml"), CUSTOM,
                StandardCharsets.UTF_8);

        TapAppearanceRegistry registry = new TapAppearanceRegistry(loader.load());

        assertEquals(0x123ABC, registry.appearance(DirectOutput.WATERMELON).rgb());
        assertEquals(TapFlowAppearance.Style.COLOR,
                registry.appearance(DirectOutput.DRAGON_BREATH).style());
    }

    @Test
    void rejectsMalformedAppearance(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("tap.yml"),
                CUSTOM.replace("#123ABC", "blue"), StandardCharsets.UTF_8);
        TapAppearanceConfigLoader loader = new TapAppearanceConfigLoader(
                getClass().getClassLoader(), directory);

        assertThrows(IOException.class, loader::load);
    }

    private static final String CUSTOM = """
            config-version: 1
            outputs:
              water: "water"
              lava: "lava"
              honey: "honey"
              dragon-breath: "#654321"
              watermelon: "#123ABC"
            """;
}
