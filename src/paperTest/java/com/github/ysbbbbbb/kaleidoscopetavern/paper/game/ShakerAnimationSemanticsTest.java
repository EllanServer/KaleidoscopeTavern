package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShakerAnimationSemanticsTest {
    private static final float EPSILON = 1.0E-4F;

    @Test
    void sourceKeyframesArePreserved() {
        assertPose(0F, 0F, 0F, 0F);
        assertPose(0.0833F * 20F, -4.5F, -15F, 2F);
        ShakerAnimationSemantics.Pose rebound =
                ShakerAnimationSemantics.pose(0.2083F * 20F);
        assertEquals(7.5F, rebound.lidXDegrees(), EPSILON);
        assertEquals(1.5F, rebound.lidYOffsetPixels(), EPSILON);
        assertPose(ShakerAnimationSemantics.LENGTH_TICKS, 0F, 0F, 0F);
    }

    @Test
    void completedAnimationStaysAtRest() {
        assertPose(100F, 0F, 0F, 0F);
    }

    private static void assertPose(float tick, float rootY, float lidX, float lidY) {
        ShakerAnimationSemantics.Pose pose = ShakerAnimationSemantics.pose(tick);
        assertEquals(rootY, pose.rootYDegrees(), EPSILON);
        assertEquals(lidX, pose.lidXDegrees(), EPSILON);
        assertEquals(lidY, pose.lidYOffsetPixels(), EPSILON);
    }
}
