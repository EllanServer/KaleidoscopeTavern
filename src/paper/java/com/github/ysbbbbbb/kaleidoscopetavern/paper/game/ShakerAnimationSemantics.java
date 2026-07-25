package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

/** Source ShakerAnimation.PUT keyframes converted from seconds to game ticks. */
final class ShakerAnimationSemantics {
    static final float LENGTH_TICKS = 0.375F * 20F;

    private static final float[] ROOT_TIMES = seconds(0F, 0.0833F, 0.1667F);
    private static final float[] ROOT_Y = {0F, -4.5F, 0F};
    private static final float[] LID_TIMES = seconds(0F, 0.0833F, 0.2083F, 0.2917F, 0.375F);
    private static final float[] LID_X = {0F, -15F, 7.5F, -7.5F, 0F};
    private static final float[] LID_Y = {0F, 2F, 1.5F, 0.5F, 0F};

    private ShakerAnimationSemantics() {
    }

    static Pose pose(float elapsedTicks) {
        float time = Math.clamp(elapsedTicks, 0F, LENGTH_TICKS);
        return new Pose(
                sample(ROOT_TIMES, ROOT_Y, time),
                sample(LID_TIMES, LID_X, time),
                sample(LID_TIMES, LID_Y, time));
    }

    private static float sample(float[] times, float[] values, float time) {
        if (time <= times[0]) {
            return values[0];
        }
        int last = times.length - 1;
        if (time >= times[last]) {
            return values[last];
        }
        int index = 0;
        while (index + 1 < times.length && time > times[index + 1]) {
            index++;
        }
        float progress = (time - times[index]) / (times[index + 1] - times[index]);
        float before = values[Math.max(0, index - 1)];
        float from = values[index];
        float to = values[index + 1];
        float after = values[Math.min(last, index + 2)];
        return catmullRom(progress, before, from, to, after);
    }

    private static float catmullRom(float progress, float before, float from,
                                    float to, float after) {
        float squared = progress * progress;
        float cubed = squared * progress;
        return 0.5F * (2F * from
                + (to - before) * progress
                + (2F * before - 5F * from + 4F * to - after) * squared
                + (3F * from - before - 3F * to + after) * cubed);
    }

    private static float[] seconds(float... values) {
        float[] result = new float[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = values[index] * 20F;
        }
        return result;
    }

    record Pose(float rootYDegrees, float lidXDegrees, float lidYOffsetPixels) {
    }
}
