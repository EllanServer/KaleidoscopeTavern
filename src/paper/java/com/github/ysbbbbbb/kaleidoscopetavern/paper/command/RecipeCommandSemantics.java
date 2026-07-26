package com.github.ysbbbbbb.kaleidoscopetavern.paper.command;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/** Pure argument, pagination and display semantics for {@code /kt recipes}. */
public final class RecipeCommandSemantics {
    public static final int PAGE_SIZE = 6;
    private static final String COCKTAIL_COLOR_TAG_PREFIX =
            "kaleidoscope_tavern:cocktail_ingredient_";

    private RecipeCommandSemantics() {
    }

    public static Optional<RecipeType> parseType(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(RecipeType.valueOf(raw.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static OptionalInt parsePage(String raw) {
        if (raw == null) {
            return OptionalInt.empty();
        }
        try {
            int page = Integer.parseInt(raw);
            return page > 0 ? OptionalInt.of(page) : OptionalInt.empty();
        } catch (NumberFormatException exception) {
            return OptionalInt.empty();
        }
    }

    public static int pageCount(int entryCount) {
        if (entryCount < 0) {
            throw new IllegalArgumentException("entryCount must not be negative");
        }
        return entryCount == 0 ? 1 : (entryCount - 1) / PAGE_SIZE + 1;
    }

    public static Optional<PageWindow> pageWindow(int entryCount, int requestedPage) {
        if (entryCount < 0 || requestedPage <= 0) {
            return Optional.empty();
        }
        int totalPages = pageCount(entryCount);
        if (requestedPage > totalPages) {
            return Optional.empty();
        }
        int from = (requestedPage - 1) * PAGE_SIZE;
        int to = (int) Math.min((long) entryCount, (long) from + PAGE_SIZE);
        return Optional.of(new PageWindow(
                requestedPage,
                totalPages,
                from,
                to));
    }

    /** Mirrors Minecraft's whole-second tick duration display. */
    public static String formatTicks(int ticks) {
        int totalSeconds = Math.max(0, ticks) / 20;
        return "%d:%02d".formatted(totalSeconds / 60, totalSeconds % 60);
    }

    public static Optional<String> cocktailColorSuffix(String tagId) {
        if (tagId == null || !tagId.startsWith(COCKTAIL_COLOR_TAG_PREFIX)) {
            return Optional.empty();
        }
        String suffix = tagId.substring(COCKTAIL_COLOR_TAG_PREFIX.length());
        return suffix.isBlank() ? Optional.empty() : Optional.of(suffix);
    }

    public enum RecipeType {
        BARREL,
        PRESSING,
        SHAKER
    }

    public record PageWindow(int page, int totalPages, int fromInclusive, int toExclusive) {
    }
}
