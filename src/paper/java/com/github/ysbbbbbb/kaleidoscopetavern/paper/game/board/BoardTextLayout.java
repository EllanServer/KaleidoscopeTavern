package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.board;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/** Deterministic line wrapping driven by the supplied client glyph advances. */
final class BoardTextLayout {
    private BoardTextLayout() {
    }

    static List<Line> wrap(String text, int maxWidth, int maxLines,
                           IntUnaryOperator glyphAdvance) {
        if (maxWidth <= 0 || maxLines <= 0) {
            throw new IllegalArgumentException("Board text bounds must be positive");
        }

        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<Line> lines = new ArrayList<>(maxLines);
        int paragraphStart = 0;
        while (paragraphStart <= normalized.length() && lines.size() < maxLines) {
            int newline = normalized.indexOf('\n', paragraphStart);
            int paragraphEnd = newline < 0 ? normalized.length() : newline;
            wrapParagraph(normalized.substring(paragraphStart, paragraphEnd),
                    maxWidth, maxLines, glyphAdvance, lines);
            if (newline < 0) {
                break;
            }
            paragraphStart = newline + 1;
        }
        return List.copyOf(lines);
    }

    private static void wrapParagraph(String paragraph, int maxWidth, int maxLines,
                                      IntUnaryOperator glyphAdvance, List<Line> lines) {
        if (paragraph.isEmpty()) {
            lines.add(new Line("", 0));
            return;
        }

        int start = 0;
        while (start < paragraph.length() && lines.size() < maxLines) {
            int index = start;
            int width = 0;
            int lastWhitespaceEnd = -1;
            while (index < paragraph.length()) {
                int codePoint = paragraph.codePointAt(index);
                int next = index + Character.charCount(codePoint);
                int advance = Math.max(0, glyphAdvance.applyAsInt(codePoint));
                if (index > start && width + advance > maxWidth) {
                    break;
                }
                width += advance;
                index = next;
                if (Character.isWhitespace(codePoint)) {
                    lastWhitespaceEnd = index;
                }
                if (width > maxWidth) {
                    break;
                }
            }

            if (index >= paragraph.length()) {
                String line = paragraph.substring(start);
                lines.add(new Line(line, width(line, glyphAdvance)));
                return;
            }

            int lineEnd = index;
            int nextStart = index;
            if (lastWhitespaceEnd > start) {
                lineEnd = lastWhitespaceEnd;
                while (lineEnd > start) {
                    int codePoint = paragraph.codePointBefore(lineEnd);
                    if (!Character.isWhitespace(codePoint)) {
                        break;
                    }
                    lineEnd -= Character.charCount(codePoint);
                }
                if (lineEnd > start) {
                    nextStart = lastWhitespaceEnd;
                    while (nextStart < paragraph.length()) {
                        int codePoint = paragraph.codePointAt(nextStart);
                        if (!Character.isWhitespace(codePoint)) {
                            break;
                        }
                        nextStart += Character.charCount(codePoint);
                    }
                } else {
                    lineEnd = index;
                    nextStart = index;
                }
            }

            String line = paragraph.substring(start, lineEnd);
            lines.add(new Line(line, width(line, glyphAdvance)));
            start = nextStart;
        }
    }

    private static int width(String text, IntUnaryOperator glyphAdvance) {
        int width = 0;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            width += Math.max(0, glyphAdvance.applyAsInt(codePoint));
            index += Character.charCount(codePoint);
        }
        return width;
    }

    record Line(String text, int width) {
    }
}
