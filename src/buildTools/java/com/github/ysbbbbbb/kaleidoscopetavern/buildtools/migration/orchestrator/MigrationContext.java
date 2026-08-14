package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.orchestrator;

import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.core.CoreDataStage;
import java.nio.file.Path;

/** Immutable shared paths and audited core input for native stages. */
public record MigrationContext(Path projectRoot, Path outputRoot, CoreDataStage.Result core) {
    public static final String NAMESPACE = "kaleidoscope_tavern";
    public MigrationContext {
        projectRoot = projectRoot.toAbsolutePath().normalize();
        outputRoot = outputRoot.toAbsolutePath().normalize();
    }
    public Path configuration() { return outputRoot.resolve("src/paper/pack/configuration"); }
    public Path catalog() { return outputRoot.resolve("src/paper/resources/catalog"); }
    public Path namespaceAssets() { return outputRoot.resolve(
            "src/paper/pack/resourcepack/assets/" + NAMESPACE); }
}
