package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import net.momirealms.craftengine.core.util.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyConnectedBlockMigrationFurnitureBehaviorTest {
    @Test
    void directionalTableVariantsAlreadyEncodeWorldAxis() {
        var state = LegacyConnectedBlockMigrationSemantics.tableProperties(
                "ground_axis_x_position_3_facing_north", Direction.NORTH);
        assertEquals("x", state.axis());
        assertEquals(3, state.position());
    }

    @Test
    void oldUnsuffixedTableXWasLocalLateralAxis() {
        assertEquals("x", LegacyConnectedBlockMigrationSemantics.tableProperties(
                "ground_axis_x_position_1", Direction.SOUTH).axis());
        assertEquals("z", LegacyConnectedBlockMigrationSemantics.tableProperties(
                "ground_axis_x_position_1", Direction.EAST).axis());
    }

    @Test
    void oldUnsuffixedTableZWasLocalLongitudinalAxis() {
        assertEquals("z", LegacyConnectedBlockMigrationSemantics.tableProperties(
                "ground_axis_z_position_2", Direction.SOUTH).axis());
        assertEquals("x", LegacyConnectedBlockMigrationSemantics.tableProperties(
                "ground_axis_z_position_2", Direction.EAST).axis());
    }

    @Test
    void singleTableCarriesNoDirectionalAxisCommitment() {
        var state = LegacyConnectedBlockMigrationSemantics.tableProperties(
                "ground_facing_west", Direction.WEST);
        assertEquals("z", state.axis());
        assertEquals(0, state.position());
    }
}
