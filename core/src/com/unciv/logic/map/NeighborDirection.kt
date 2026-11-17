package com.unciv.logic.map

import yairm210.purity.annotations.Pure

enum class NeighborDirection(val dx: Int, val dy: Int, val isCardinal: Boolean) {
    North(0, 1, true),
    NorthEast(1, 1, false),
    East(1, 0, true),
    SouthEast(1, -1, false),
    South(0, -1, true),
    SouthWest(-1, -1, false),
    West(-1, 0, true),
    NorthWest(-1, 1, false);

    val offset: GridCoord get() = GridCoord.of(dx, dy)

    companion object {
        val cardinals = entries.filter { it.isCardinal }

        @Pure
        fun fromDelta(dx: Int, dy: Int): NeighborDirection? =
            entries.firstOrNull { it.dx == dx && it.dy == dy }
    }
}
