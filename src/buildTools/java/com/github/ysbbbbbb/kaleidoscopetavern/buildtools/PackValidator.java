package com.github.ysbbbbbb.kaleidoscopetavern.buildtools;

import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.validation.GeneratedPackValidator;
import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.validation.PackConfigRules;
import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.validation.PlacedDrinkSemanticsValidator;
import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.validation.SourceParityValidator;
import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.validation.SourceTokenValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Native Java entry point for strict CraftEngine pack validation. */
public final class PackValidator {
    private PackValidator() {}

    public static void main(String[] args) {
        try {
            Path root = findProjectRoot(args.length == 0 ? Path.of("") : Path.of(args[0]));
            GeneratedPackValidator.Result generated = GeneratedPackValidator.validate(root);
            GeneratedPackValidator.Documents docs = GeneratedPackValidator.documents(root);
            new PlacedDrinkSemanticsValidator(root).validate(docs.items(), docs.renderItems(), docs.furniture());
            new PackConfigRules(root).validate(docs.items(), docs.renderItems(), docs.blocks(), docs.furniture());
            new SourceTokenValidator(root).validate();
            SourceParityValidator.Result result = SourceParityValidator.validate(root);
            System.out.println("CraftEngine pack validation passed");
            System.out.println("  items: " + generated.items());
            System.out.println("  blocks: " + generated.blocks());
            System.out.println("  furniture: " + generated.furniture());
            System.out.println("  appearances: " + generated.appearances());
            System.out.println("  recipes: " + generated.recipes());
            System.out.println("  customcrops-crops: " + generated.customCrops());
            generated.catalogs().forEach((name, count) -> System.out.println("  " + name + ": " + count));
            System.out.println("  source-placeables: " + result.sourceBlocks());
            System.out.println("  source-state-properties: " + result.stateProperties());
            System.out.println("  source-block-entity-renderers: " + result.sourceRenderers());
        } catch (IOException | RuntimeException error) {
            System.err.println("CraftEngine pack validation failed: " + error.getMessage());
            System.exit(1);
        }
    }

    static Path findProjectRoot(Path start) {
        Path current = start.toAbsolutePath().normalize();
        if (Files.isRegularFile(current)) current = current.getParent();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))
                    && Files.isDirectory(current.resolve("src/paper/pack/configuration"))
                    && Files.isDirectory(current.resolve("src/paper/java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalArgumentException("Cannot locate project root from " + start.toAbsolutePath());
    }
}
