package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** UTF-8 JSON I/O matching the migration script's pretty-print contract. */
final class JsonFiles {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private JsonFiles() {}

    static JsonElement read(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        } catch (IOException | RuntimeException error) {
            throw new CoreMigrationException("cannot read JSON " + path + ": " + error.getMessage(), error);
        }
    }

    static void write(Path path, JsonElement value) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(value, writer);
                writer.write('\n');
            }
        } catch (IOException error) {
            throw new CoreMigrationException("cannot write JSON " + path + ": " + error.getMessage(), error);
        }
    }
}
