package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.tap;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.tap.TapAppearanceConfig.DirectOutput;

import java.util.Objects;

/** Atomically replaceable runtime snapshot of the tiny tap appearance table. */
public final class TapAppearanceRegistry {
    private volatile TapAppearanceConfig snapshot;

    public TapAppearanceRegistry(TapAppearanceConfig initial) {
        replace(initial);
    }

    public TapFlowAppearance appearance(DirectOutput output) {
        return snapshot.appearance(output);
    }

    public void replace(TapAppearanceConfig next) {
        snapshot = Objects.requireNonNull(next, "next");
    }
}
