package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.furniture;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Native legacy furniture migration stage backed by FurnitureBuilder. */
public final class FurnitureMigrationStage {
    private final Path projectRoot;
    private final Path outputRoot;

    public FurnitureMigrationStage(Path projectRoot, Path outputRoot) {
        this.projectRoot = Objects.requireNonNull(projectRoot).toAbsolutePath().normalize();
        this.outputRoot = Objects.requireNonNull(outputRoot).toAbsolutePath().normalize();
    }

    public Result run(List<String> furnitureIds, Set<String> itemIds) throws IOException {
        return build(furnitureIds, itemIds);
    }

    public Result build(List<String> furnitureIds, Set<String> itemIds) throws IOException {
        return new FurnitureBuilder(projectRoot, outputRoot).build(furnitureIds, itemIds);
    }

    public static Result migrate(Path projectRoot, Path outputRoot, List<String> furnitureIds,
                                 Set<String> itemIds) throws IOException {
        return new FurnitureMigrationStage(projectRoot, outputRoot).run(furnitureIds, itemIds);
    }

    public static Result buildMigration(Path projectRoot, Path outputRoot, List<String> furnitureIds,
                                        Set<String> itemIds) throws IOException {
        return migrate(projectRoot, outputRoot, furnitureIds, itemIds);
    }

    public record Result(
            JsonObject furniture,
            JsonObject renderItems,
            java.util.LinkedHashMap<String, JsonObject> placement,
            java.util.LinkedHashMap<String, Integer> metrics) {}
}
