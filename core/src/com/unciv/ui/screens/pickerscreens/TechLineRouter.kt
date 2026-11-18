package com.unciv.ui.screens.pickerscreens

import java.util.PriorityQueue
import kotlin.math.abs

/**
 * Pure grid-aware orthogonal channel router for the tech tree.
 *
 * Lattice coordinates:
 * - `gx` in `0..columns`: vertical gutter `gx` is to the right of column `gx - 1`
 *   (so `gx = fromCol + 1` is the gutter immediately to the right of `fromCol`,
 *   and `gx = toCol` is the gutter immediately to the left of `toCol`).
 * - `gy` in `0..(2 * rows)`: even `gy = 2 * row` is the center of `row`;
 *   odd `gy = 2 * row + 1` is the horizontal channel below `row` (and `gy = -1`? No,
 *   we shift by +1 so `gy = 0` is the top channel above row 0, `gy = 2 * r + 1` is row `r` center,
 *   and `gy = 2 * r + 2` is the channel below row `r`).
 */
class TechLineRouter(
    val columns: Int,
    val rows: Int,
    private val isOccupied: (col: Int, row: Int) -> Boolean
) {
    data class LatticePoint(val gx: Int, val gy: Int)

    data class RoutedSegment(
        val start: LatticePoint,
        val end: LatticePoint,
        val lane: Int = 0
    ) {
        val isHorizontal: Boolean get() = start.gy == end.gy
        val isVertical: Boolean get() = start.gx == end.gx
    }

    data class RoutedEdge(
        val id: String,
        val sourceKey: String,
        val points: List<LatticePoint>,
        val segments: List<RoutedSegment>
    )

    private data class EdgeKey(val ax: Int, val ay: Int, val bx: Int, val by: Int) {
        companion object {
            fun of(p1: LatticePoint, p2: LatticePoint): EdgeKey {
                return if (p1.gx < p2.gx || (p1.gx == p2.gx && p1.gy <= p2.gy)) {
                    EdgeKey(p1.gx, p1.gy, p2.gx, p2.gy)
                } else {
                    EdgeKey(p2.gx, p2.gy, p1.gx, p1.gy)
                }
            }
        }
    }

    private val edgeUsage = HashMap<EdgeKey, Int>()

    fun resetUsage() {
        edgeUsage.clear()
    }

    /**
     * Routes an edge from `(fromCol, fromRow)`'s right gutter `(fromCol + 1, 2 * fromRow + 1)`
     * to `(toCol, toRow)`'s left gutter `(toCol, 2 * toRow + 1)`.
     */
    fun route(id: String, sourceKey: String, fromCol: Int, fromRow: Int, toCol: Int, toRow: Int): RoutedEdge {
        val start = LatticePoint(fromCol + 1, 2 * fromRow + 1)
        val goal = LatticePoint(toCol, 2 * toRow + 1)

        if (start == goal) {
            return RoutedEdge(id, sourceKey, listOf(start), emptyList())
        }

        val maxGy = 2 * rows
        val dirs = arrayOf(
            1 to 0,   // right
            -1 to 0,  // left
            0 to 1,   // down
            0 to -1   // up
        )

        data class State(val gx: Int, val gy: Int, val dir: Int)
        data class Node(val state: State, val cost: Int) : Comparable<Node> {
            override fun compareTo(other: Node): Int = cost.compareTo(other.cost)
        }

        val dist = HashMap<State, Int>()
        val prev = HashMap<State, State>()
        val pq = PriorityQueue<Node>()

        // Initial direction: prefer going right if toCol > fromCol
        val initState = State(start.gx, start.gy, -1)
        dist[initState] = 0
        pq.add(Node(initState, 0))

        var goalState: State? = null

        while (pq.isNotEmpty()) {
            val (cur, curCost) = pq.poll()
            if (curCost > (dist[cur] ?: Int.MAX_VALUE)) continue

            if (cur.gx == goal.gx && cur.gy == goal.gy) {
                goalState = cur
                break
            }

            for (d in dirs.indices) {
                val nx = cur.gx + dirs[d].first
                val ny = cur.gy + dirs[d].second

                if (nx < 0 || nx > columns || ny < 0 || ny > maxGy) continue

                // Check if horizontal move passes through an occupied cell
                if (dirs[d].first != 0) {
                    // Moving horizontally at ny: if ny is odd, it's row (ny - 1) / 2
                    if (ny % 2 == 1) {
                        val row = (ny - 1) / 2
                        val col = minOf(cur.gx, nx)
                        if (col in 0 until columns && row in 0 until rows && isOccupied(col, row)) {
                            continue
                        }
                    }
                }

                val stepCost = 10
                val bendCost = if (cur.dir != -1 && cur.dir != d) 15 else 0
                val key = EdgeKey.of(LatticePoint(cur.gx, cur.gy), LatticePoint(nx, ny))
                val congestionCost = (edgeUsage[key] ?: 0) * 6
                val nextCost = curCost + stepCost + bendCost + congestionCost

                val nextState = State(nx, ny, d)
                if (nextCost < (dist[nextState] ?: Int.MAX_VALUE)) {
                    dist[nextState] = nextCost
                    prev[nextState] = cur
                    pq.add(Node(nextState, nextCost))
                }
            }
        }

        val rawPoints = ArrayList<LatticePoint>()
        var trace = goalState
        while (trace != null) {
            rawPoints.add(LatticePoint(trace.gx, trace.gy))
            trace = prev[trace]
        }
        rawPoints.reverse()

        // Record edge usage for congestion avoidance
        for (i in 0 until rawPoints.size - 1) {
            val key = EdgeKey.of(rawPoints[i], rawPoints[i + 1])
            edgeUsage[key] = (edgeUsage[key] ?: 0) + 1
        }

        // Simplify collinear points into segments
        val simplified = simplifyPoints(rawPoints)
        val segments = ArrayList<RoutedSegment>()
        for (i in 0 until simplified.size - 1) {
            segments.add(RoutedSegment(simplified[i], simplified[i + 1]))
        }
        return RoutedEdge(id, sourceKey, simplified, segments)
    }

    private fun simplifyPoints(points: List<LatticePoint>): List<LatticePoint> {
        if (points.size <= 2) return points
        val result = ArrayList<LatticePoint>()
        result.add(points.first())
        for (i in 1 until points.size - 1) {
            val a = result.last()
            val b = points[i]
            val c = points[i + 1]
            val sameX = a.gx == b.gx && b.gx == c.gx
            val sameY = a.gy == b.gy && b.gy == c.gy
            if (!sameX && !sameY) {
                result.add(b)
            }
        }
        result.add(points.last())
        return result
    }

    /**
     * Assigns non-overlapping lane indices (0, 1, -1, 2, -2, ...) to segments sharing
     * the same gutter or horizontal channel, allowing segments from the same [sourceKey] to share a lane.
     */
    fun assignLanes(edges: List<RoutedEdge>): List<RoutedEdge> {
        data class SegRef(val edgeIdx: Int, val segIdx: Int, val sourceKey: String, val minCoord: Int, val maxCoord: Int)

        val hGroups = HashMap<Int, MutableList<SegRef>>() // keyed by gy
        val vGroups = HashMap<Int, MutableList<SegRef>>() // keyed by gx

        for (eIdx in edges.indices) {
            val edge = edges[eIdx]
            for (sIdx in edge.segments.indices) {
                val seg = edge.segments[sIdx]
                if (seg.isHorizontal) {
                    val minX = minOf(seg.start.gx, seg.end.gx)
                    val maxX = maxOf(seg.start.gx, seg.end.gx)
                    hGroups.getOrPut(seg.start.gy) { ArrayList() }
                        .add(SegRef(eIdx, sIdx, edge.sourceKey, minX, maxX))
                } else if (seg.isVertical) {
                    val minY = minOf(seg.start.gy, seg.end.gy)
                    val maxY = maxOf(seg.start.gy, seg.end.gy)
                    vGroups.getOrPut(seg.start.gx) { ArrayList() }
                        .add(SegRef(eIdx, sIdx, edge.sourceKey, minY, maxY))
                }
            }
        }

        val assignedLanes = Array(edges.size) { eIdx -> IntArray(edges[eIdx].segments.size) }

        fun colorGroup(group: List<SegRef>) {
            val sorted = group.sortedWith(compareBy({ it.minCoord }, { it.maxCoord }))
            val placed = ArrayList<Pair<SegRef, Int>>()
            for (ref in sorted) {
                var lane = 0
                var step = 0
                while (true) {
                    val conflict = placed.any { (other, otherLane) ->
                        otherLane == lane &&
                            other.sourceKey != ref.sourceKey &&
                            other.minCoord < ref.maxCoord &&
                            ref.minCoord < other.maxCoord
                    }
                    if (!conflict) break
                    step++
                    lane = if (step % 2 == 1) (step + 1) / 2 else -(step / 2)
                }
                placed.add(ref to lane)
                assignedLanes[ref.edgeIdx][ref.segIdx] = lane
            }
        }

        for (group in hGroups.values) colorGroup(group)
        for (group in vGroups.values) colorGroup(group)

        return edges.mapIndexed { eIdx, edge ->
            val newSegs = edge.segments.mapIndexed { sIdx, seg ->
                seg.copy(lane = assignedLanes[eIdx][sIdx])
            }
            edge.copy(segments = newSegs)
        }
    }
}
