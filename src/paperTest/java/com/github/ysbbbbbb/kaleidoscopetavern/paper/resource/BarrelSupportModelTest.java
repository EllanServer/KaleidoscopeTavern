package com.github.ysbbbbbb.kaleidoscopetavern.paper.resource;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the asymmetric open-barrel support strip. */
class BarrelSupportModelTest {
    private static final Path MODEL_ROOT = Path.of(
            "src", "paper", "pack", "resourcepack", "assets",
            "kaleidoscope_tavern", "models", "furniture");

    @Test
    void supportUsesTheFurnitureFacingBasisInsteadOfTheForgeBlockBasis() throws IOException {
        String body = Files.readString(MODEL_ROOT.resolve("barrel_body.json"),
                StandardCharsets.UTF_8);
        String lid = Files.readString(MODEL_ROOT.resolve("barrel_open_lid.json"),
                StandardCharsets.UTF_8);
        String compactBody = body.replaceAll("\\s+", "");

        assertEquals(13, occurrences(body, "\"from\""),
                "open body must contain twelve body elements plus open_r1");
        assertEquals(1, occurrences(lid, "\"from\""),
                "open lid display must contain only open_r2");

        assertTrue(compactBody.contains(
                        "\"from\":[14.99,28.215627824,-1.72625203],"
                                + "\"to\":[15.01,30.215627824,18.27374797]"),
                "open_r1 must move to the right side without flipping front-back");
        assertTrue(compactBody.contains(
                        "\"rotation\":{\"origin\":[15,28.215627824,-1.72625203],"
                                + "\"axis\":\"x\",\"angle\":-39.998183678,\"rescale\":false}"),
                "open_r1 must preserve the source combined tilt direction and magnitude");
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
