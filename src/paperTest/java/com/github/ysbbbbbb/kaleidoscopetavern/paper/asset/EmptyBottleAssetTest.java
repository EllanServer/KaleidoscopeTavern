package com.github.ysbbbbbb.kaleidoscopetavern.paper.asset;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmptyBottleAssetTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path SOURCE_ASSETS = ROOT.resolve(
            "src/main/resources/assets/kaleidoscope_tavern");
    private static final Path PACK_ASSETS = ROOT.resolve(
            "src/paper/pack/resourcepack/assets/kaleidoscope_tavern");
    private static final Set<Integer> CORK_RGB = Set.of(
            0x953616, 0xA74625, 0xD87450);
    private static final Pattern UV = Pattern.compile(
            "\\\"uv\\\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);

    @Test
    void emptyBottleKeepsSourceRgbAndOnlyCorkIsOpaque() throws IOException {
        assertTexture("block/brew/empty_bottle.png", 8, 68);
        assertTexture("item/empty_bottle.png", 6, 42);
    }

    @Test
    void placedBottlePartitionsShoulderWithoutChangingArtwork() throws IOException {
        String source = Files.readString(SOURCE_ASSETS.resolve(
                "models/block/brew/empty_bottle.json"));
        String migrated = Files.readString(PACK_ASSETS.resolve(
                "models/furniture/placed_drink/kaleidoscope_tavern/block/brew/empty_bottle.json"));
        List<String> sourceUvs = uvs(source);
        List<String> migratedUvs = uvs(migrated);

        assertEquals(21, sourceUvs.size());
        List<String> expectedUvs = new ArrayList<>(sourceUvs);
        for (int index = 12; index <= 15; index++) {
            expectedUvs.set(index, "9,7,13,15");
        }
        String topUv = expectedUvs.remove(16);
        expectedUvs.add(topUv);
        assertEquals(expectedUvs, migratedUvs,
                "CE furniture must partition the shoulder while preserving its authored UVs");
        assertTrue(Pattern.compile("\"to\":\\s*\\[\\s*10,\\s*9,\\s*10\\s*]")
                        .matcher(migrated).find(),
                "The empty-bottle body must stop below the shoulder band");
        assertTrue(migrated.contains("\"force_translucent\": true"));

        String itemModel = Files.readString(PACK_ASSETS.resolve(
                "models/item/empty_bottle.json"));
        assertTrue(itemModel.contains("\"force_translucent\": true"));
    }

    private static void assertTexture(
            String relative, int expectedCork, int expectedGlass) throws IOException {
        BufferedImage source = ImageIO.read(
                SOURCE_ASSETS.resolve("textures").resolve(relative).toFile());
        BufferedImage migrated = ImageIO.read(
                PACK_ASSETS.resolve("textures").resolve(relative).toFile());
        assertEquals(source.getWidth(), migrated.getWidth());
        assertEquals(source.getHeight(), migrated.getHeight());

        int cork = 0;
        int glass = 0;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int sourceArgb = source.getRGB(x, y);
                int migratedArgb = migrated.getRGB(x, y);
                int sourceAlpha = sourceArgb >>> 24;
                int migratedAlpha = migratedArgb >>> 24;
                if (sourceAlpha == 0) {
                    assertEquals(0, migratedAlpha);
                    continue;
                }
                int sourceRgb = sourceArgb & 0xFFFFFF;
                assertEquals(sourceRgb, migratedArgb & 0xFFFFFF,
                        "RGB drift at " + relative + " " + x + "," + y);
                if (CORK_RGB.contains(sourceRgb)) {
                    assertEquals(255, migratedAlpha);
                    cork++;
                } else {
                    assertEquals(176, migratedAlpha);
                    glass++;
                }
            }
        }
        assertEquals(expectedCork, cork);
        assertEquals(expectedGlass, glass);
    }

    private static List<String> uvs(String json) {
        Matcher matcher = UV.matcher(json);
        List<String> result = new ArrayList<>();
        while (matcher.find()) {
            result.add(matcher.group(1).replaceAll("\\s+", ""));
        }
        return result;
    }
}
