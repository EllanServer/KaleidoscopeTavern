package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.assets;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Ordered UTF-8 JSON utilities shared by the asset migration stage. */
final class AssetJson {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private AssetJson() {}
    static JsonObject read(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        JsonElement value = JsonParser.parseString(text);
        if (!value.isJsonObject()) throw new IOException("Expected JSON object: " + path);
        return value.getAsJsonObject();
    }
    static void write(Path path, JsonElement value) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(value, writer);
            writer.write('\n');
        }
    }
    static JsonArray array(Object... values) {
        JsonArray result = new JsonArray();
        for (Object value : values) {
            if (value instanceof JsonElement element) result.add(element);
            else if (value instanceof Number number) result.add(number);
            else if (value instanceof Boolean bool) result.add(bool);
            else result.add(String.valueOf(value));
        }
        return result;
    }
    static JsonObject object(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = (String) pairs[i]; Object value = pairs[i + 1];
            if (value instanceof JsonElement element) result.add(key, element);
            else if (value instanceof Number number) result.addProperty(key, number);
            else if (value instanceof Boolean bool) result.addProperty(key, bool);
            else result.addProperty(key, String.valueOf(value));
        }
        return result;
    }
}
