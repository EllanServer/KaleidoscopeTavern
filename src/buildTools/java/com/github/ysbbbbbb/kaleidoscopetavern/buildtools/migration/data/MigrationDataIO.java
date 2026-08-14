package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic low-level I/O used by the legacy content migration. */
public final class MigrationDataIO {
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
            .serializeNulls().create();

    private MigrationDataIO() {}

    public static JsonElement readJson(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return JsonParser.parseString(stripBom(Files.readString(path, StandardCharsets.UTF_8)));
    }

    public static void writeJson(Path path, JsonElement value) throws IOException {
        createParent(path);
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            PRETTY_GSON.toJson(value, writer);
            writer.write('\n');
        }
    }

    public static void writeTsv(Path path, Iterable<?> header, Iterable<? extends Iterable<?>> rows)
            throws IOException {
        createParent(path);
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writeTsvRow(writer, header);
            for (Iterable<?> row : rows) writeTsvRow(writer, row);
        }
    }

    private static void writeTsvRow(Writer writer, Iterable<?> cells) throws IOException {
        boolean first = true;
        for (Object cell : cells) {
            if (!first) writer.write('\t');
            first = false;
            writer.write(String.valueOf(cell).replace('\t', ' ').replace('\r', ' ').replace('\n', ' '));
        }
        writer.write('\n');
    }

    /** YAML scalar contract: booleans and integral values are bare; all else is a JSON string. */
    public static String yamlScalar(Object value) {
        if (value instanceof Boolean bool) return bool ? "true" : "false";
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        return new GsonBuilder().disableHtmlEscaping().create().toJson(String.valueOf(value));
    }

    public static List<String> registryIds(Path path, String owner) throws IOException {
        String source = stripBom(Files.readString(path, StandardCharsets.UTF_8));
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(owner)
                + "\\.register\\(\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(source);
        List<String> ids = new ArrayList<>();
        while (matcher.find()) {
            String id = matcher.group(1);
            if (ids.contains(id)) throw new IllegalArgumentException("Duplicate ids in " + path);
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    static String stripBom(String value) {
        return !value.isEmpty() && value.charAt(0) == 0xfeff ? value.substring(1) : value;
    }

    private static void createParent(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
    }
}
