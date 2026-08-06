package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压榨桶落地反向索引的纯数据行为测试：覆盖正负坐标、±0.5 精确边界、
 * 四个落脚单元上限、墙面桶不登记、ground/wall 切换、跨列跨世界移动。
 */
class PressingTubLandingIndexTest {

    @Test
    void groundTubRegistersAtMostFourLandingCells() {
        PressingTubLandingIndex<Object> index = new PressingTubLandingIndex<>();
        Object tub = new Object();
        // baseX=10, baseZ=-2 → floor(9.5)=9..floor(10.5)=10, floor(-2.5)=-3..floor(-1.5)=-2
        index.add(tub, true, 10, -2, 9, 10, -3, -2);

        assertTrue(index.hasGroundTubs());
        assertEquals(1, index.groundTubCount());
        assertTrue(index.landingCandidatesAt(9, -3).contains(tub));
        assertTrue(index.landingCandidatesAt(9, -2).contains(tub));
        assertTrue(index.landingCandidatesAt(10, -3).contains(tub));
        assertTrue(index.landingCandidatesAt(10, -2).contains(tub));
        // 第五个相邻单元不应命中。
        assertNull(index.landingCandidatesAt(11, -2));
        assertNull(index.landingCandidatesAt(9, -4));
        assertNull(index.landingCandidatesAt(8, -2));
        assertTrue(index.originCandidatesAt(10, -2).contains(tub));
    }

    @Test
    void halfCellBoundariesMapToBothSidesOfTheOriginColumn() {
        PressingTubLandingIndex<Object> index = new PressingTubLandingIndex<>();
        Object tub = new Object();
        // baseX=0.5 → floor(0)=0..floor(1)=1；baseZ=0.0 → floor(-0.5)=-1..floor(0.5)=0
        index.add(tub, true, 0, 0, 0, 1, -1, 0);

        assertTrue(index.landingCandidatesAt(0, -1).contains(tub));
        assertTrue(index.landingCandidatesAt(1, 0).contains(tub));
        assertNull(index.landingCandidatesAt(2, 0));
    }

    @Test
    void negativeCoordinatesUseFloorSemantics() {
        PressingTubLandingIndex<Object> index = new PressingTubLandingIndex<>();
        Object tub = new Object();
        // baseX=-0.2 → floor(-0.7)=-1..floor(0.3)=0，与 (int) 截断语义不同。
        index.add(tub, true, -1, 3, -1, 0, 3, 4);

        // 实体脚部 x=-0.2 → blockX=floor(-0.2)=-1 应命中。
        assertTrue(index.landingCandidatesAt(-1, 3).contains(tub));
        assertTrue(index.landingCandidatesAt(0, 3).contains(tub));
        assertNull(index.landingCandidatesAt(1, 3));
        assertNull(index.landingCandidatesAt(-2, 3));
    }

    @Test
    void wallTubRegistersNoLandingCells() {
        PressingTubLandingIndex<Object> index = new PressingTubLandingIndex<>();
        Object wallTub = new Object();
        index.add(wallTub, false, 5, 7, 5, 6, 7, 8);

        assertFalse(index.hasGroundTubs());
        assertEquals(0, index.groundTubCount());
        assertTrue(index.originCandidatesAt(5, 7).contains(wallTub));
        assertNull(index.landingCandidatesAt(5, 7));
        assertNull(index.landingCandidatesAt(6, 8));
        assertFalse(index.isEmpty());
    }

    @Test
    void groundToWallAndBackTransitionsUpdateGroundCount() {
        PressingTubLandingIndex<Object> index = new PressingTubLandingIndex<>();
        Object tub = new Object();
        index.add(tub, true, 1, 1, 0, 2, 0, 2);
        assertEquals(1, index.groundTubCount());

        index.remove(tub, true, 1, 1, 0, 2, 0, 2);
        index.add(tub, false, 1, 1, 0, 2, 0, 2);
        assertEquals(0, index.groundTubCount());
        assertNull(index.landingCandidatesAt(1, 1));

        index.remove(tub, false, 1, 1, 0, 2, 0, 2);
        index.add(tub, true, 1, 1, 0, 2, 0, 2);
        assertEquals(1, index.groundTubCount());
        assertTrue(index.landingCandidatesAt(1, 1).contains(tub));
    }

    @Test
    void movingToAnotherColumnOrWorldRebuildsOnlyTheNewCells() {
        PressingTubLandingIndex<Object> first = new PressingTubLandingIndex<>();
        PressingTubLandingIndex<Object> second = new PressingTubLandingIndex<>();
        Object tub = new Object();

        first.add(tub, true, 3, 3, 2, 4, 2, 4);
        // 同一世界内移动到另一列：旧列清空，新列持有桶。
        first.remove(tub, true, 3, 3, 2, 4, 2, 4);
        first.add(tub, true, 20, 20, 19, 21, 19, 21);
        assertNull(first.landingCandidatesAt(3, 3));
        assertNull(first.originCandidatesAt(3, 3));
        assertTrue(first.landingCandidatesAt(20, 20).contains(tub));

        // 移动到另一个世界：旧世界索引清空，新世界索引持有桶。
        first.remove(tub, true, 20, 20, 19, 21, 19, 21);
        second.add(tub, true, 20, 20, 19, 21, 19, 21);
        assertTrue(first.isEmpty());
        assertTrue(second.landingCandidatesAt(20, 20).contains(tub));
    }

    @Test
    void removingTheLastEntryLeavesAnEmptyIndex() {
        PressingTubLandingIndex<Object> index = new PressingTubLandingIndex<>();
        Object wallTub = new Object();
        index.add(wallTub, false, 1, 1, 0, 2, 0, 2);
        assertFalse(index.isEmpty());

        index.remove(wallTub, false, 1, 1, 0, 2, 0, 2);
        assertTrue(index.isEmpty());
        assertEquals(0, index.groundTubCount());
    }
}
