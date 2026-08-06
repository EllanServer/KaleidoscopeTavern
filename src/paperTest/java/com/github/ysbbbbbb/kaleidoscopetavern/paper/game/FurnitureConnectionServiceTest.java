package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FurnitureConnectionServiceTest {
    @Test
    void oppositeFacingEastWestEndpointsMeetFaceToFace() {
        int westEndpoint = FurnitureConnectionService.tableSourcePosition(true, false);
        int eastEndpoint = FurnitureConnectionService.tableSourcePosition(false, true);

        assertEquals(1, westEndpoint, "an east neighbour is source LEFT/position 1");
        assertEquals(3, eastEndpoint, "a west neighbour is source RIGHT/position 3");
        assertEquals(
                "ground_axis_x_position_1",
                FurnitureConnectionService.tableVariantName(
                        BlockFace.SOUTH,
                        FurnitureConnectionService.HorizontalAxis.X,
                        westEndpoint));
        assertEquals(
                "ground_axis_x_position_3_facing_north",
                FurnitureConnectionService.tableVariantName(
                        BlockFace.NORTH,
                        FurnitureConnectionService.HorizontalAxis.X,
                        eastEndpoint));
    }

    @Test
    void oppositeFacingNorthSouthEndpointsMeetFaceToFace() {
        int northEndpoint = FurnitureConnectionService.tableSourcePosition(true, false);
        int southEndpoint = FurnitureConnectionService.tableSourcePosition(false, true);

        assertEquals(
                "ground_axis_z_position_1_facing_west",
                FurnitureConnectionService.tableVariantName(
                        BlockFace.WEST,
                        FurnitureConnectionService.HorizontalAxis.Z,
                        northEndpoint));
        assertEquals(
                "ground_axis_z_position_3_facing_east",
                FurnitureConnectionService.tableVariantName(
                        BlockFace.EAST,
                        FurnitureConnectionService.HorizontalAxis.Z,
                        southEndpoint));
    }

    @Test
    void directionalSingleVariantsNormalizeEveryFurnitureYaw() {
        assertEquals("ground",
                FurnitureConnectionService.tableVariantName(BlockFace.SOUTH, null, 0));
        assertEquals("ground_facing_west",
                FurnitureConnectionService.tableVariantName(BlockFace.WEST, null, 0));
        assertEquals("ground_facing_north",
                FurnitureConnectionService.tableVariantName(BlockFace.NORTH, null, 0));
        assertEquals("ground_facing_east",
                FurnitureConnectionService.tableVariantName(BlockFace.EAST, null, 0));
    }

    @Test
    void sourcePositionRequiresAtLeastOneNeighbour() {
        assertEquals(2, FurnitureConnectionService.tableSourcePosition(true, true));
        assertThrows(IllegalArgumentException.class,
                () -> FurnitureConnectionService.tableSourcePosition(false, false));
    }
}
