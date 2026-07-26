package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure formatting and icon mapping for the vanilla-client custom-effect HUD. */
final class CustomEffectHudSemantics {
    static final String FONT_KEY = "kaleidoscope_tavern:custom_effects";
    private static final String SEPARATOR = "<dark_gray>  |  </dark_gray>";

    private static final Map<String, String> ICONS = Map.ofEntries(
            Map.entry("kaleidoscope_tavern:slightly_tipsy", "\uE100"),
            Map.entry("kaleidoscope_tavern:high_heels", "\uE101"),
            Map.entry("kaleidoscope_tavern:grass_stealth", "\uE102"),
            Map.entry("kaleidoscope_tavern:vision", "\uE103"),
            Map.entry("kaleidoscope_tavern:bloody_mary", "\uE104"),
            Map.entry("kaleidoscope_tavern:ardent_heat", "\uE105"),
            Map.entry("kaleidoscope_tavern:long_reach", "\uE106"),
            Map.entry("kaleidoscope_tavern:tomb_raider", "\uE107"),
            Map.entry("kaleidoscope_tavern:xp_drain", "\uE108")
    );

    private CustomEffectHudSemantics() {
    }

    static Display describe(String effectId, int remainingTicks, int amplifier) {
        return new Display(
                effectKey(effectId),
                ICONS.get(effectId),
                amplifier > 0 ? "potion.potency." + amplifier : null,
                formatDuration(remainingTicks));
    }

    static String effectKey(String effectId) {
        int separator = effectId.indexOf(':');
        String namespace = separator < 0 ? "minecraft" : effectId.substring(0, separator);
        String path = separator < 0 ? effectId : effectId.substring(separator + 1);
        return "effect." + namespace + "." + path;
    }

    /**
     * Builds the MiniMessage HUD line shared by the built-in boss bar and the
     * {@code %kaleidoscopetavern_effect_hud%} placeholder, so external
     * renderers such as CustomNameplates show exactly the same content.
     */
    static String miniMessageLine(List<EffectEntry> entries) {
        StringBuilder line = new StringBuilder("<!i>");
        boolean first = true;
        for (EffectEntry entry : entries) {
            if (!first) {
                line.append(SEPARATOR);
            }
            line.append(miniMessageEntry(entry));
            first = false;
        }
        return first ? "" : line.toString();
    }

    private static String miniMessageEntry(EffectEntry entry) {
        Display display = describe(entry.effectId(), entry.remainingTicks(), entry.amplifier());
        // Vanilla nests the amplifier inside the duration format, so the inner
        // argument keeps single quotes while the outer argument uses double
        // quotes to stay parseable.
        String name = "<lang:" + display.effectKey() + ">";
        if (display.potencyKey() != null) {
            name = "<lang:potion.withAmplifier:'" + name
                    + "':'<lang:" + display.potencyKey() + ">'>";
        }
        name = "<lang:potion.withDuration:\"" + name + "\":\"" + display.duration() + "\">";
        String color = "kaleidoscope_tavern:slightly_tipsy".equals(entry.effectId())
                ? "gray" : "blue";
        StringBuilder text = new StringBuilder();
        if (display.icon() != null) {
            text.append("<white><font:").append(FONT_KEY).append('>')
                    .append(display.icon()).append("</font></white> ");
        }
        return text.append('<').append(color).append('>')
                .append(name)
                .append("</").append(color).append('>')
                .toString();
    }

    static String formatDuration(int ticks) {
        long totalSeconds = Math.max(1L, ticks / 20L);
        long hours = totalSeconds / 3_600L;
        long minutes = totalSeconds % 3_600L / 60L;
        long seconds = totalSeconds % 60L;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    record Display(String effectKey, String icon, String potencyKey, String duration) {
    }

    record EffectEntry(String effectId, int remainingTicks, int amplifier) {
    }
}
