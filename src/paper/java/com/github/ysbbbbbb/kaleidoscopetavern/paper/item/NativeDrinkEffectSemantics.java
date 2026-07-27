package com.github.ysbbbbbb.kaleidoscopetavern.paper.item;

/** Rules for effects that Minecraft can apply directly from potion contents. */
public final class NativeDrinkEffectSemantics {
    private static final String MINECRAFT_PREFIX = "minecraft:";

    private NativeDrinkEffectSemantics() {
    }

    public static boolean shouldEmbed(String effectId, double probability) {
        return effectId != null
                && effectId.startsWith(MINECRAFT_PREFIX)
                && probability >= 1.0;
    }

    public static int duration(boolean instant, int durationTicks) {
        return instant ? Math.max(1, durationTicks) : durationTicks;
    }
}
