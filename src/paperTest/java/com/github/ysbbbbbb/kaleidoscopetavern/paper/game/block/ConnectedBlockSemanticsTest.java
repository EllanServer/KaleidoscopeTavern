package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block;

import org.junit.jupiter.api.Test;

import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.ConnectedBlockSemantics.Axis.X;
import static com.github.ysbbbbbb.kaleidoscopetavern.paper.game.block.ConnectedBlockSemantics.Axis.Z;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectedBlockSemanticsTest {
    @Test
    void sideAndFrontChangesCanMoveCornerOwnership() {
        assertTrue(ConnectedBlockSemantics.cornerNeighbourAffectsState(true, false));
        assertTrue(ConnectedBlockSemantics.cornerNeighbourAffectsState(false, true));
        assertFalse(ConnectedBlockSemantics.cornerNeighbourAffectsState(false, false));
    }

    @Test
    void cornerConnectionPreservesAllSixSourceStates() {
        assertEquals("single", ConnectedBlockSemantics.cornerConnection(false, false, false, false));
        assertEquals("right", ConnectedBlockSemantics.cornerConnection(true, false, false, false));
        assertEquals("left", ConnectedBlockSemantics.cornerConnection(false, true, false, false));
        assertEquals("middle", ConnectedBlockSemantics.cornerConnection(true, true, true, true));
        assertEquals("right_corner", ConnectedBlockSemantics.cornerConnection(false, false, true, false));
        assertEquals("left_corner", ConnectedBlockSemantics.cornerConnection(false, false, false, true));
    }

    @Test
    void straightNeighboursWinOverCornerCandidates() {
        assertEquals("left", ConnectedBlockSemantics.cornerConnection(false, true, true, false));
        assertEquals("right", ConnectedBlockSemantics.cornerConnection(true, false, false, true));
        assertEquals("middle", ConnectedBlockSemantics.cornerConnection(true, true, true, false));
    }

    @Test
    void linearPositionMatchesLegacyCabinetNames() {
        assertEquals("single", ConnectedBlockSemantics.linearPosition(false, false));
        assertEquals("right", ConnectedBlockSemantics.linearPosition(true, false));
        assertEquals("left", ConnectedBlockSemantics.linearPosition(false, true));
        assertEquals("middle", ConnectedBlockSemantics.linearPosition(true, true));
    }

    @Test
    void tableEastWestUsesWorldAxisAndSourceEndpointNumbers() {
        var single = new ConnectedBlockSemantics.TableState(Z, 0);
        assertEquals(new ConnectedBlockSemantics.TableState(X, 1),
                ConnectedBlockSemantics.eastWest(single, true, false));
        assertEquals(new ConnectedBlockSemantics.TableState(X, 3),
                ConnectedBlockSemantics.eastWest(single, false, true));
        assertEquals(new ConnectedBlockSemantics.TableState(X, 2),
                ConnectedBlockSemantics.eastWest(single, true, true));
    }

    @Test
    void tableNorthSouthUsesWorldAxisAndSourceEndpointNumbers() {
        var single = new ConnectedBlockSemantics.TableState(X, 0);
        assertEquals(new ConnectedBlockSemantics.TableState(Z, 1),
                ConnectedBlockSemantics.northSouth(single, true, false));
        assertEquals(new ConnectedBlockSemantics.TableState(Z, 3),
                ConnectedBlockSemantics.northSouth(single, false, true));
        assertEquals(new ConnectedBlockSemantics.TableState(Z, 2),
                ConnectedBlockSemantics.northSouth(single, true, true));
    }

    @Test
    void connectedTableDoesNotTurnOntoTheOtherAxis() {
        var xMiddle = new ConnectedBlockSemantics.TableState(X, 2);
        var zLeft = new ConnectedBlockSemantics.TableState(Z, 1);
        assertEquals(xMiddle, ConnectedBlockSemantics.northSouth(xMiddle, true, true));
        assertEquals(zLeft, ConnectedBlockSemantics.eastWest(zLeft, true, true));
    }
}
