package com.unciv.ui

import com.unciv.ui.screens.pickerscreens.TechLineRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TechLineRouterTests {

    @Test
    fun `adjacent column same row produces direct horizontal or empty lattice route`() {
        val router = TechLineRouter(columns = 4, rows = 4) { col, row ->
            (col == 0 && row == 1) || (col == 1 && row == 1)
        }
        val edge = router.route("A->B", "A", fromCol = 0, fromRow = 1, toCol = 1, toRow = 1)
        assertTrue(edge.segments.isEmpty())
        assertEquals(listOf(TechLineRouter.LatticePoint(1, 3)), edge.points)
    }

    @Test
    fun `adjacent column different row produces single vertical segment in gutter`() {
        val router = TechLineRouter(columns = 4, rows = 4) { col, row ->
            (col == 0 && row == 0) || (col == 1 && row == 2)
        }
        val edge = router.route("A->B", "A", fromCol = 0, fromRow = 0, toCol = 1, toRow = 2)
        assertEquals(1, edge.segments.size)
        assertTrue(edge.segments[0].isVertical)
        assertEquals(1, edge.segments[0].start.gx)
    }

    @Test
    fun `skipping occupied intermediate cell detours through horizontal channel`() {
        val occupied = setOf(0 to 1, 1 to 1, 2 to 1)
        val router = TechLineRouter(columns = 4, rows = 4) { col, row -> (col to row) in occupied }
        val edge = router.route("A->C", "A", fromCol = 0, fromRow = 1, toCol = 2, toRow = 1)
        assertTrue(edge.segments.isNotEmpty())
        // Verify no horizontal segment passes through an occupied odd gy
        for (seg in edge.segments) {
            if (seg.isHorizontal && seg.start.gy % 2 == 1) {
                val row = (seg.start.gy - 1) / 2
                val minX = minOf(seg.start.gx, seg.end.gx)
                val maxX = maxOf(seg.start.gx, seg.end.gx)
                for (col in minX until maxX) {
                    assertFalse("Segment passed through occupied ($col, $row)", (col to row) in occupied)
                }
            }
        }
    }

    @Test
    fun `overlapping segments from different sources get distinct lanes`() {
        val router = TechLineRouter(columns = 4, rows = 4) { _, _ -> true }
        val e1 = router.route("A->C", "A", fromCol = 0, fromRow = 0, toCol = 2, toRow = 0)
        val e2 = router.route("B->D", "B", fromCol = 0, fromRow = 1, toCol = 2, toRow = 1)
        val laned = router.assignLanes(listOf(e1, e2))

        val segs1 = laned[0].segments.filter { it.isHorizontal }
        val segs2 = laned[1].segments.filter { it.isHorizontal }
        for (s1 in segs1) {
            for (s2 in segs2) {
                if (s1.start.gy == s2.start.gy) {
                    val overlap = minOf(s1.start.gx, s1.end.gx) < maxOf(s2.start.gx, s2.end.gx) &&
                        minOf(s2.start.gx, s2.end.gx) < maxOf(s1.start.gx, s1.end.gx)
                    if (overlap) {
                        assertTrue("Overlapping segments from different sources must have different lanes", s1.lane != s2.lane)
                    }
                }
            }
        }
    }
}
