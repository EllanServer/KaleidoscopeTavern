package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.shaker;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.List;

/**
 * Pixel layout for the vanilla-client shaker HUD.
 *
 * <p>The presentation independently applies the same general technique used by
 * CustomFishing's accurate-click games: layer resource-pack bitmap glyphs and
 * move the pointer with positive/negative space glyphs. Timing, geometry and
 * artwork remain sourced from the archived Tavern {@code ShakerOverlay}.</p>
 */
final class ShakerHudSemantics {
    static final String FONT_KEY = "kaleidoscope_tavern:shaker_hud";
    static final char BAR_GLYPH = '\uE400';
    static final char POINTER_GLYPH = '\uE401';
    static final char INGREDIENT_GLYPH = '\uE402';

    // The generated glyphs are half-size because vanilla renders title
    // subtitles at 2x. These are their final on-screen advances in GUI pixels.
    static final int BAR_ADVANCE_PIXELS = 182;
    static final int POINTER_ADVANCE_PIXELS = 14;
    static final int INGREDIENT_ADVANCE_PIXELS = 18;
    static final int INGREDIENT_STEP_PIXELS = 20;
    private static final int MAX_POINTER_OFFSET_PIXELS = 165;
    private static final int POSITIVE_OFFSET_BASE = 0xE410;
    private static final int NEGATIVE_OFFSET_BASE = 0xE420;
    private static final int[] OFFSET_POWERS = {1, 2, 4, 8, 16, 32, 64, 128, 256};
    private static final Key FONT = Key.key(FONT_KEY);

    private ShakerHudSemantics() {
    }

    /** Forge moved the pointer by {@code round(ticks * 1.5)} GUI pixels. */
    static int pointerOffsetPixels(int ticks) {
        int offset = Math.round(Math.max(0, ticks) * 1.5F);
        return Math.clamp(offset, 0, MAX_POINTER_OFFSET_PIXELS);
    }

    static ProgressLayout progressLayout(int ticks) {
        int pointerOffset = pointerOffsetPixels(ticks);
        StringBuilder glyphs = new StringBuilder().append(BAR_GLYPH);
        int advance = BAR_ADVANCE_PIXELS;
        advance += appendOffset(glyphs, pointerOffset - advance);
        glyphs.append(POINTER_GLYPH);
        advance += POINTER_ADVANCE_PIXELS;
        advance += appendOffset(glyphs, BAR_ADVANCE_PIXELS - advance);
        return new ProgressLayout(glyphs.toString(), pointerOffset, advance);
    }

    static Component progressSubtitle(int ticks) {
        return Component.text(progressLayout(ticks).glyphs())
                .font(FONT)
                .color(NamedTextColor.WHITE);
    }

    /** Recreates the source overlay's 20px-spaced, tintable rhombus icons. */
    static Component ingredientSubtitle(List<Integer> colors) {
        Component line = Component.empty();
        for (int index = 0; index < colors.size(); index++) {
            if (index > 0) {
                line = line.append(Component.text(offsetGlyphs(
                                INGREDIENT_STEP_PIXELS - INGREDIENT_ADVANCE_PIXELS))
                        .font(FONT));
            }
            line = line.append(Component.text(String.valueOf(INGREDIENT_GLYPH))
                    .font(FONT)
                    .color(TextColor.color(colors.get(index) & 0xFFFFFF)));
        }
        return line;
    }

    static String offsetGlyphs(int pixels) {
        StringBuilder glyphs = new StringBuilder();
        appendOffset(glyphs, pixels);
        return glyphs.toString();
    }

    private static int appendOffset(StringBuilder glyphs, int pixels) {
        int remaining = Math.abs(pixels);
        int base = pixels < 0 ? NEGATIVE_OFFSET_BASE : POSITIVE_OFFSET_BASE;
        for (int index = OFFSET_POWERS.length - 1; index >= 0; index--) {
            int power = OFFSET_POWERS[index];
            while (remaining >= power) {
                glyphs.append((char) (base + index));
                remaining -= power;
            }
        }
        if (remaining != 0) {
            throw new IllegalArgumentException("Unsupported HUD offset: " + pixels);
        }
        return pixels;
    }

    record ProgressLayout(String glyphs, int pointerOffsetPixels, int totalAdvancePixels) {
    }
}
