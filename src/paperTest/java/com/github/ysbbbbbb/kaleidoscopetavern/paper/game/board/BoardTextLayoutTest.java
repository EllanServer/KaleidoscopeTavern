package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.board;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoardTextLayoutTest {
    @Test
    void preservesExplicitLineOrderAndEmptyLines() {
        List<BoardTextLayout.Line> lines = BoardTextLayout.wrap(
                "first\n\nthird", 20, 11, ignored -> 1);

        assertEquals(List.of("first", "", "third"),
                lines.stream().map(BoardTextLayout.Line::text).toList());
    }

    @Test
    void wrapsLongLinesBeforeFollowingText() {
        List<BoardTextLayout.Line> lines = BoardTextLayout.wrap(
                "abcdef", 3, 11, ignored -> 1);

        assertEquals(List.of("abc", "def"),
                lines.stream().map(BoardTextLayout.Line::text).toList());
    }

    @Test
    void usesWhitespaceAsThePreferredWrapPoint() {
        List<BoardTextLayout.Line> lines = BoardTextLayout.wrap(
                "one two", 5, 11, ignored -> 1);

        assertEquals(List.of("one", "two"),
                lines.stream().map(BoardTextLayout.Line::text).toList());
    }

    @Test
    void truncatesAnythingBelowTheOriginalRendererLineLimit() {
        List<BoardTextLayout.Line> lines = BoardTextLayout.wrap(
                "1\n2\n3\n4", 20, 3, ignored -> 1);

        assertEquals(List.of("1", "2", "3"),
                lines.stream().map(BoardTextLayout.Line::text).toList());
    }

    @Test
    void preservesChineseAndWrapsItByFullWidthGlyphAdvance() {
        List<BoardTextLayout.Line> lines = BoardTextLayout.wrap(
                "中文测试", 36, 8, ignored -> 18);

        assertEquals(List.of("中文", "测试"),
                lines.stream().map(BoardTextLayout.Line::text).toList());
    }
}
