package com.github.ysbbbbbb.kaleidoscopetavern.paper.item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Pure replacement semantics for plugin-owned lore lines. */
final class ManagedLoreSemantics {
    private ManagedLoreSemantics() {
    }

    static boolean isLegacyShakerLine(String text) {
        // The old tooltip root contained only the arrow prefix; the item name
        // was a separate child component. Matching startsWith would also
        // delete unrelated third-party lore such as "▶ Soulbound".
        return "\u25B6 ".equals(text);
    }

    static <T> List<T> replace(List<T> existing, Predicate<T> managed,
                               Predicate<T> blank, List<T> replacement) {
        List<T> source = existing == null ? List.of() : existing;
        boolean[] removed = new boolean[source.size()];
        for (int index = 0; index < source.size(); index++) {
            removed[index] = managed.test(source.get(index));
        }

        // The previous implementation inserted an unmarked empty line between
        // quality and effects. Remove only an empty line enclosed by two known
        // Tavern lines so an unrelated plugin's spacing remains untouched.
        for (int index = 1; index + 1 < source.size(); index++) {
            if (blank.test(source.get(index)) && removed[index - 1] && removed[index + 1]) {
                removed[index] = true;
            }
        }

        List<T> result = new ArrayList<>(source.size() + replacement.size());
        int insertionIndex = -1;
        for (int index = 0; index < source.size(); index++) {
            if (removed[index]) {
                if (insertionIndex < 0) {
                    insertionIndex = result.size();
                }
            } else {
                result.add(source.get(index));
            }
        }
        if (insertionIndex < 0) {
            insertionIndex = result.size();
        }
        result.addAll(insertionIndex, replacement);
        return List.copyOf(result);
    }
}
