package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.tap;

import java.util.Objects;

/** Immutable visual description selected before a tap extraction cycle starts. */
public record TapFlowAppearance(Style style, int rgb) {
    public static final TapFlowAppearance WATER = new TapFlowAppearance(Style.WATER, 0);
    public static final TapFlowAppearance LAVA = new TapFlowAppearance(Style.LAVA, 0);
    public static final TapFlowAppearance HONEY = new TapFlowAppearance(Style.HONEY, 0);

    public TapFlowAppearance {
        Objects.requireNonNull(style, "style");
        if (style == Style.COLOR && (rgb < 0 || rgb > 0xFFFFFF)) {
            throw new IllegalArgumentException("Tap liquid color must be a 24-bit RGB value");
        }
        if (style != Style.COLOR) {
            rgb = 0;
        }
    }

    public static TapFlowAppearance colored(int rgb) {
        return new TapFlowAppearance(Style.COLOR, rgb);
    }

    public enum Style {
        WATER,
        LAVA,
        HONEY,
        COLOR
    }
}
