package com.github.ysbbbbbb.kaleidoscopetavern.buildtools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates the CraftEngine server-side custom block-state budget. */
public final class ServerStateBudgetValidator {
    private static final Path DEFAULT_BLOCKS = Path.of("src/paper/pack/configuration/blocks.json");
    private static final long DEFAULT_CAPACITY = 2_000;
    private static final long DEFAULT_RESERVE = 1_000;
    private static final long CAPACITY_STEP = 1_000;
    private static final Pattern RANGE = Pattern.compile("\\s*(-?\\d+)\\s*~\\s*(-?\\d+)\\s*");

    private ServerStateBudgetValidator() {}

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Arguments arguments;
        try {
            arguments = parseArguments(args);
        } catch (IllegalArgumentException error) {
            System.err.println("ServerStateBudgetValidator: error: " + error.getMessage());
            return 2;
        }
        final Map<String, Long> counts;
        final long capacity;
        final String capacitySource;
        try {
            counts = loadStateCounts(arguments.blocks());
            if (arguments.craftengineConfig() != null) {
                capacity = readCraftengineCapacity(arguments.craftengineConfig());
                capacitySource = arguments.craftengineConfig().toString();
            } else {
                capacity = arguments.capacity() == null ? DEFAULT_CAPACITY : arguments.capacity();
                capacitySource = "declared project deployment capacity";
            }
        } catch (IOException | RuntimeException error) {
            System.err.println("CraftEngine state-budget validation failed: " + error.getMessage());
            return 2;
        }
        final long tavernStates;
        final long requiredWithReserve;
        try {
            tavernStates = counts.values().stream().reduce(0L, Math::addExact);
            requiredWithReserve = Math.addExact(tavernStates, arguments.reserve());
        } catch (ArithmeticException error) {
            System.err.println("CraftEngine state-budget validation failed: state count exceeds integer range");
            return 2;
        }
        long recommendation = roundedCapacity(requiredWithReserve);
        long remaining = capacity - tavernStates;
        System.out.println("CraftEngine server-side block-state budget");
        System.out.println("  Tavern generated states: " + tavernStates);
        System.out.println("  Capacity (" + capacitySource + "): " + capacity);
        System.out.println("  Reserved for other CE resources/future changes: " + arguments.reserve());
        System.out.println("  Remaining before reserve: " + remaining);
        if (arguments.top() != 0) {
            System.out.println("  Largest state consumers:");
            counts.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                            .reversed().thenComparing(Map.Entry::getKey))
                    .limit(arguments.top())
                    .forEach(entry -> System.out.printf("    %4d  %s%n", entry.getValue(), entry.getKey()));
        }
        if (capacity < requiredWithReserve) {
            System.err.println("\nInsufficient CraftEngine server-side block-state capacity.");
            System.err.println("Set block.serverside-blocks to at least " + recommendation
                    + " in plugins/CraftEngine/config.yml, then perform a complete server restart. "
                    + "Do not use /ce reload for this setting and do not delete cache/custom_block_states.json.");
            return 1;
        }
        System.out.println("Budget passed: " + (capacity - requiredWithReserve)
                + " states remain after the requested reserve.");
        return 0;
    }

    private static Arguments parseArguments(String[] args) {
        Path blocks = DEFAULT_BLOCKS;
        Long capacity = null;
        Path craftengineConfig = null;
        long reserve = DEFAULT_RESERVE;
        long top = 12;
        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            switch (option) {
                case "--blocks" -> blocks = Path.of(requireValue(args, ++index, option));
                case "--capacity" -> {
                    if (craftengineConfig != null) throw new IllegalArgumentException("--capacity cannot be combined with --craftengine-config");
                    capacity = nonNegativeLong(requireValue(args, ++index, option), option);
                }
                case "--craftengine-config" -> {
                    if (capacity != null) throw new IllegalArgumentException("--craftengine-config cannot be combined with --capacity");
                    craftengineConfig = Path.of(requireValue(args, ++index, option));
                }
                case "--reserve" -> reserve = nonNegativeLong(requireValue(args, ++index, option), option);
                case "--top" -> top = nonNegativeLong(requireValue(args, ++index, option), option);
                default -> throw new IllegalArgumentException("unrecognized argument: " + option);
            }
        }
        return new Arguments(blocks, capacity, craftengineConfig, reserve, top);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) throw new IllegalArgumentException(option + " requires a value");
        return args[index];
    }

    private static long nonNegativeLong(String value, String option) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw new IllegalArgumentException(option + " value must be non-negative");
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(option + " requires a non-negative integer: " + value, error);
        }
    }

    private static Map<String, Long> loadStateCounts(Path blocksPath) throws IOException {
        String text = stripBom(Files.readString(blocksPath, StandardCharsets.UTF_8));
        JsonElement document = JsonParser.parseString(text);
        if (!document.isJsonObject()) throw new IllegalArgumentException(blocksPath + " must contain a JSON object");
        JsonElement blocksElement = document.getAsJsonObject().get("blocks");
        if (blocksElement == null || !blocksElement.isJsonObject()) {
            throw new IllegalArgumentException(blocksPath + " must contain an object at key 'blocks'");
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : blocksElement.getAsJsonObject().entrySet()) {
            counts.put(entry.getKey(), blockStateCount(entry.getKey(), entry.getValue()));
        }
        return counts;
    }

    private static long blockStateCount(String blockId, JsonElement definition) {
        if (!definition.isJsonObject()) throw new IllegalArgumentException("Block '" + blockId + "' must be an object");
        JsonElement statesElement = definition.getAsJsonObject().get("states");
        if (statesElement == null || statesElement.isJsonNull()) return 1;
        if (!statesElement.isJsonObject()) throw new IllegalArgumentException("Block '" + blockId + "'.states must be an object");
        JsonObject states = statesElement.getAsJsonObject();
        JsonElement variantsElement = states.get("variants");
        if (variantsElement != null && variantsElement.isJsonObject()) {
            int size = variantsElement.getAsJsonObject().size();
            if (size == 0) throw new IllegalArgumentException("Block '" + blockId + "' has an empty variants map");
            return size;
        }
        JsonElement propertiesElement = states.get("properties");
        if (propertiesElement == null) return 1;
        if (!propertiesElement.isJsonObject()) throw new IllegalArgumentException("Block '" + blockId + "'.states.properties must be an object");
        long count = 1;
        for (Map.Entry<String, JsonElement> property : propertiesElement.getAsJsonObject().entrySet()) {
            count = Math.multiplyExact(count, propertyValueCount(property.getKey(), property.getValue()));
        }
        return count;
    }

    private static long propertyValueCount(String name, JsonElement specification) {
        if (!specification.isJsonObject()) throw new IllegalArgumentException("Property '" + name + "' must be an object");
        JsonObject object = specification.getAsJsonObject();
        JsonElement values = object.get("values");
        if (values != null && values.isJsonArray()) {
            if (values.getAsJsonArray().isEmpty()) throw new IllegalArgumentException("Property '" + name + "' has an empty values list");
            return values.getAsJsonArray().size();
        }
        JsonElement type = object.get("type");
        if (type != null && type.isJsonPrimitive() && "boolean".equals(type.getAsString())) return 2;
        JsonElement range = object.get("range");
        if (range != null && !range.isJsonNull()) {
            String rawRange = range.isJsonPrimitive() ? range.getAsString() : range.toString();
            Matcher matcher = RANGE.matcher(rawRange);
            if (!matcher.matches()) throw new IllegalArgumentException("Property '" + name + "' has invalid range '" + rawRange + "'");
            BigInteger lower = new BigInteger(matcher.group(1));
            BigInteger upper = new BigInteger(matcher.group(2));
            if (upper.compareTo(lower) < 0) throw new IllegalArgumentException("Property '" + name + "' has descending range '" + rawRange + "'");
            return upper.subtract(lower).add(BigInteger.ONE).longValueExact();
        }
        throw new IllegalArgumentException("Cannot determine the number of values for property '" + name + "': " + object);
    }

    private static long readCraftengineCapacity(Path configPath) throws IOException {
        Integer blockIndent = null;
        int lineNumber = 0;
        for (String rawLine : stripBom(Files.readString(configPath, StandardCharsets.UTF_8)).split("\\R")) {
            lineNumber++;
            String content = rawLine.split("#", 2)[0].stripTrailing();
            if (content.isBlank()) continue;
            int indent = 0;
            while (indent < content.length() && content.charAt(indent) == ' ') indent++;
            String stripped = content.strip();
            if (indent == 0) {
                String key = stripped.split(":", 2)[0].strip();
                blockIndent = "block".equals(key) ? 0 : null;
                continue;
            }
            if (blockIndent == null || indent <= blockIndent) continue;
            int separator = stripped.indexOf(':');
            if (separator < 0) continue;
            String key = stripped.substring(0, separator).strip();
            if (!"serverside-blocks".equals(key) && !"serverside_blocks".equals(key)) continue;
            String rawValue = stripped.substring(separator + 1).strip();
            String value = rawValue.replace("_", "");
            if (!value.matches("\\d+")) {
                throw new IllegalArgumentException(configPath + ":" + lineNumber + ": invalid serverside-blocks value '" + rawValue + "'");
            }
            return Long.parseLong(value);
        }
        throw new IllegalArgumentException(configPath + " does not contain block.serverside-blocks");
    }

    private static long roundedCapacity(long required) {
        if (required <= CAPACITY_STEP) return CAPACITY_STEP;
        return Math.multiplyExact(Math.floorDiv(Math.addExact(required, CAPACITY_STEP - 1), CAPACITY_STEP), CAPACITY_STEP);
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
    }

    private record Arguments(Path blocks, Long capacity, Path craftengineConfig, long reserve, long top) {}
}
