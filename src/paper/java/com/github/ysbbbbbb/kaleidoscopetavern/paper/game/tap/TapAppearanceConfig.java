package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.tap;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable direct-source appearances decoded from visuals/tap.yml. */
public record TapAppearanceConfig(Map<DirectOutput, TapFlowAppearance> outputs) {
    public TapAppearanceConfig {
        EnumMap<DirectOutput, TapFlowAppearance> copy = new EnumMap<>(DirectOutput.class);
        copy.putAll(Objects.requireNonNull(outputs, "outputs"));
        for (DirectOutput output : DirectOutput.values()) {
            Objects.requireNonNull(copy.get(output), "Missing tap output " + output.key());
        }
        outputs = Collections.unmodifiableMap(copy);
    }

    public TapFlowAppearance appearance(DirectOutput output) {
        return outputs.get(Objects.requireNonNull(output, "output"));
    }

    public enum DirectOutput {
        WATER("water"),
        LAVA("lava"),
        HONEY("honey"),
        DRAGON_BREATH("dragon-breath"),
        WATERMELON("watermelon");

        private final String key;

        DirectOutput(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }
}
