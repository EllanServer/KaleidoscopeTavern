package com.github.ysbbbbbb.kaleidoscopetavern.paper.item;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedLoreSemanticsTest {
    @Test
    void replacesOnlyManagedLinesAtTheirOriginalPosition() {
        List<String> result = ManagedLoreSemantics.replace(
                List.of("external-before", "managed-quality", "", "managed-effect", "external-after"),
                line -> line.startsWith("managed-"),
                String::isEmpty,
                List.of("managed-new"));

        assertEquals(List.of("external-before", "managed-new", "external-after"), result);
    }

    @Test
    void preservesUnrelatedBlankLinesAndAppendsWhenNoManagedBlockExists() {
        List<String> result = ManagedLoreSemantics.replace(
                List.of("external", ""),
                line -> line.startsWith("managed-"),
                String::isEmpty,
                List.of("managed-new"));

        assertEquals(List.of("external", "", "managed-new"), result);
    }

    @Test
    void removesAllOldManagedFragmentsWithoutMovingExternalLore() {
        List<String> result = ManagedLoreSemantics.replace(
                List.of("managed-old-a", "external", "managed-old-b"),
                line -> line.startsWith("managed-"),
                String::isEmpty,
                List.of("managed-new-a", "managed-new-b"));

        assertEquals(List.of("managed-new-a", "managed-new-b", "external"), result);
    }

    @Test
    void recognizesTheOriginalShakerArrowWithoutEncodingDependentSourceText() {
        assertTrue(ManagedLoreSemantics.isLegacyShakerLine("\u25B6 "));
        assertFalse(ManagedLoreSemantics.isLegacyShakerLine("\u25B6 Soulbound"));
        assertFalse(ManagedLoreSemantics.isLegacyShakerLine("> ingredient"));
    }
}
