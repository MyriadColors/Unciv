package com.unciv.logic.map

import com.unciv.logic.map.mapunit.movement.MovementCost
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.Terrain
import com.unciv.models.ruleset.tile.TerrainType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class SquareTileMapTests {
    @Test
    fun squareTileMapNeighborsAndWrapBounds() {
        val ruleset = Ruleset().apply {
            terrains["Plains"] = Terrain().apply { name = "Plains"; type = TerrainType.Land }
        }
        val mapOdd = TileMap(23, 15, ruleset, worldWrap = false)
        Assert.assertEquals(23 * 15, mapOdd.values.size)
        Assert.assertEquals(8, mapOdd[0, 0].neighbors.count())

        val mapEven = TileMap(24, 16, ruleset, worldWrap = true)
        val leftEven = mapEven[mapEven.leftX, 0]
        val rightEven = mapEven[mapEven.leftX + mapEven.width - 1, 0]
        Assert.assertTrue(leftEven.neighbors.contains(rightEven))
        Assert.assertTrue(rightEven.neighbors.contains(leftEven))
        Assert.assertEquals(8, leftEven.neighbors.count())
    }

    @Test
    fun cardinalRiversDoNotConnectDiagonally() {
        val ruleset = Ruleset().apply {
            terrains["Plains"] = Terrain().apply { name = "Plains"; type = TerrainType.Land }
        }
        val map = TileMap(10, 10, ruleset, worldWrap = false)
        val origin = map[0, 0]
        val east = map[1, 0]
        val south = map[0, -1]
        val southEast = map[1, -1]

        Assert.assertTrue(origin.setConnectedByRiver(east, true))
        Assert.assertTrue(origin.setConnectedByRiver(south, true))
        Assert.assertTrue(origin.isConnectedByRiver(east))
        Assert.assertTrue(origin.isConnectedByRiver(south))
        Assert.assertFalse(origin.isConnectedByRiver(southEast))
        Assert.assertFalse(southEast.isAdjacentToRiver())
    }

    @Test
    fun diagonalMoveIgnoresCardinalRiverPenalty() {
        val game = TestGame()
        game.makeRectangularMap(5, 5)
        val civ = game.addCiv()
        val origin = game.getTile(GridCoord.Zero)
        val east = game.getTile(GridCoord.of(1, 0))
        val south = game.getTile(GridCoord.of(0, -1))
        val southEast = game.getTile(GridCoord.of(1, -1))
        origin.setConnectedByRiver(east, true)
        origin.setConnectedByRiver(south, true)
        val unit = game.addUnit("Warrior", civ, origin)

        Assert.assertEquals(1f, MovementCost.getMovementCostBetweenAdjacentTiles(unit, origin, southEast), 0.001f)
        Assert.assertEquals(100f, MovementCost.getMovementCostBetweenAdjacentTiles(unit, origin, east), 0.001f)
    }
}
