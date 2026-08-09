package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.storage;

import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageBlockConfigTest {
    @Test
    void horizontalCellarBottlesCompensateEastWestModelYaw() {
        StorageBlockConfig.Orientation east = new StorageBlockConfig.Orientation(
                -90, 90,
                StorageBlockConfig.CoordinateExpression.ONE_MINUS_Z,
                StorageBlockConfig.CoordinateExpression.ONE_MINUS_X,
                false);
        StorageBlockConfig.Orientation west = new StorageBlockConfig.Orientation(
                90, 270,
                StorageBlockConfig.CoordinateExpression.Z,
                StorageBlockConfig.CoordinateExpression.X,
                false);

        assertEquals(180, Math.floorMod(
                Math.round(east.modelYaw() - east.positionYaw()), 360));
        assertEquals(180, Math.floorMod(
                Math.round(west.modelYaw() - west.positionYaw()), 360));
        assertEquals(0.75, east.sourceX(0.3, 0.25), 1.0E-6);
        assertEquals(0.7, east.sourceZ(0.3, 0.25), 1.0E-6);
    }

    @Test
    void configuredSelectorsCoverEverySourceStorageLayout() {
        var split = new StorageBlockConfig.Selector(
                StorageBlockConfig.SelectorType.SPLIT,
                1, 1, 2, StorageBlockConfig.Axis.X,
                false, false, false, 0, true);
        assertEquals(0, split.select(0.25, 0.5, 0.5, false));
        assertEquals(1, split.select(0.25, 0.5, 0.5, true));

        var grid = new StorageBlockConfig.Selector(
                StorageBlockConfig.SelectorType.GRID,
                3, 3, 1, StorageBlockConfig.Axis.X,
                false, true, true, 0, true);
        assertEquals(0, grid.select(0.175, 0.78, 0.5, false));
        assertEquals(8, grid.select(0.825, 0.20, 0.5, false));

        var radial = new StorageBlockConfig.Selector(
                StorageBlockConfig.SelectorType.RADIAL,
                1, 1, 6, StorageBlockConfig.Axis.X,
                false, false, false, 4, true);
        double[][] sourcePoints = {
                {0.5, 0.125}, {0.125, 0.3125}, {0.125, 0.6875},
                {0.5, 0.875}, {0.875, 0.6875}, {0.875, 0.3125}
        };
        for (int slot = 0; slot < sourcePoints.length; slot++) {
            assertEquals(slot, radial.select(
                    sourcePoints[slot][0], 0.5, sourcePoints[slot][1], false));
        }
    }

    @Test
    void itemRulesAndTransformsAreOwnedByConfiguration() {
        Key allowed = Key.of("test:allowed");
        Key blocked = Key.of("test:blocked");
        Key exclusive = Key.of("test:exclusive");
        var interaction = new StorageBlockConfig.Interaction(
                Set.of(allowed), Set.of(blocked), Set.of(exclusive),
                0, true, true, true,
                null, null,
                StorageBlockConfig.InteractionFailure.PASS,
                StorageBlockConfig.InteractionFailure.FAIL,
                null, null, null);
        var config = new StorageBlockConfig(
                "test:data", "test:render/", 1.25F,
                List.of(new StorageBlockConfig.SlotVisual(
                        new Vector3f(0.5F), null, null, null,
                        1, 0, 0)),
                new StorageBlockConfig.Selector(
                        StorageBlockConfig.SelectorType.SINGLE,
                        1, 1, 1, StorageBlockConfig.Axis.X,
                        false, false, false, 0, true),
                Map.of(
                        Direction.NORTH, orientation(), Direction.EAST, orientation(),
                        Direction.SOUTH, orientation(), Direction.WEST, orientation()),
                interaction, null, null, Set.of("facing"));

        assertTrue(config.isAllowed(allowed));
        assertTrue(config.isBlocked(blocked));
        assertTrue(config.isExclusive(exclusive));
    }

    @Test
    void particleAlternateRangesPreserveBothRackEdges() {
        var effect = new StorageBlockConfig.ParticleEffect(
                "END_ROD", 1,
                new StorageBlockConfig.Range(0.125, 0.375),
                new StorageBlockConfig.Range(0.625, 0.875),
                new StorageBlockConfig.Range(0, 1),
                new StorageBlockConfig.Range(0.125, 0.375),
                new StorageBlockConfig.Range(0.625, 0.875),
                0.01, 0.01, 0.01, 1);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < 100; index++) {
            double x = effect.sampleX(random);
            double z = effect.sampleZ(random);
            assertTrue(x >= 0.125 && x <= 0.875);
            assertTrue(z >= 0.125 && z <= 0.875);
            assertTrue(x <= 0.375 || x >= 0.625);
            assertTrue(z <= 0.375 || z >= 0.625);
        }
    }

    private static StorageBlockConfig.Orientation orientation() {
        return new StorageBlockConfig.Orientation(
                0, 0,
                StorageBlockConfig.CoordinateExpression.X,
                StorageBlockConfig.CoordinateExpression.Z,
                false);
    }
}
