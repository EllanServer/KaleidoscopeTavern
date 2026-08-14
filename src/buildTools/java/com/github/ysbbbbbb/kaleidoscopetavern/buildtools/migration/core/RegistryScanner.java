package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts registration ids from the archived Forge Java registries. */
public final class RegistryScanner {
    public List<String> scan(Path path, String owner) {
        final String source;
        try {
            source = stripBom(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new CoreMigrationException("cannot read registry " + path, error);
        }
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(owner)
                + "\\.register\\(\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(source);
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        while (matcher.find()) {
            String id = matcher.group(1);
            if (!unique.add(id)) throw new CoreMigrationException("duplicate id " + id + " in " + path);
            result.add(id);
        }
        return List.copyOf(result);
    }

    private static String stripBom(String value) {
        return !value.isEmpty() && value.charAt(0) == '\ufeff' ? value.substring(1) : value;
    }
}
