package com.unciv.view

import com.badlogic.gdx.math.Vector2
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.GridCoord
import com.unciv.logic.map.GridMath
import com.unciv.logic.map.NeighborDirection
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.map.toGridCoord
import yairm210.purity.annotations.Cache
import yairm210.purity.annotations.Readonly

/** Lazy cache of [TileView]s for a [TileMap] from the perspective of [viewer]. */
class TileMapView(private val tileMap: TileMap,
                  /** Null in map editor */ viewer: Civilization?,
                  spectatorMode: Boolean = false,
                  val gameView: GameView? = null) : View<TileMap>(tileMap, viewer, spectatorMode) {
    @Cache private val tileViews: Array<TileView?> by lazy { arrayOfNulls(tileMap.tileList.size) }

    /** Note that since this requires the original Tile, this CAN return tiles that are NOT visible to the viewing civ
     * This is currently required for the map since to even create the empty tiles we need to know it exists
     * I don't have a good solution for this data leak at the moment 
     * since calculating paths to tiles that don't exist will currently break pathfinding */
    @Readonly fun getTile(tile: Tile): TileView {
        val idx = tile.zeroBasedIndex
        return tileViews[idx] ?: TileView(tile, this, viewer, spectatorMode).also { tileViews[idx] = it }
    }

    @Readonly private fun Tile.toViewIfExplored(): TileView? {
        if (viewer != null && !isExplored(viewer)) return null
        return TileView(this, this@TileMapView, viewer, spectatorMode)
    }

    /** Returns the [TileView] at [position], or `null` if it isn't explored by [viewer]. */
    @Readonly fun getTile(position: GridCoord): TileView? = tileMap[position].toViewIfExplored()

    /** Position of [position] normalized to the map's non-wrapped bounds, for world-wrap rendering. */
    @Readonly fun getUnwrappedPosition(position: GridCoord): GridCoord = tileMap.getUnwrappedPosition(position)

    // Not sure if I want these as part of the API -
    // we can separate the "get coord" part and put it in HexMath,
    // And add a new function of "get tile by coord" in here :thunk:
    // These are really only used for borders so IDK
    @Readonly fun getLeftSharedNeighbor(tile: TileView, neighbor: TileView): TileView? {
        val dir = tileMap.getNeighborDirection(tile.unwrap(), neighbor.unwrap()) ?: return null
        val leftDir = when (dir) {
            NeighborDirection.North -> NeighborDirection.West
            NeighborDirection.East -> NeighborDirection.North
            NeighborDirection.South -> NeighborDirection.East
            NeighborDirection.West -> NeighborDirection.South
            else -> return null
        }
        return tileMap.getNeighborTile(tile.unwrap(), leftDir)?.toViewIfExplored()
    }

    @Readonly fun getRightSharedNeighbor(tile: TileView, neighbor: TileView): TileView? {
        val dir = tileMap.getNeighborDirection(tile.unwrap(), neighbor.unwrap()) ?: return null
        val rightDir = when (dir) {
            NeighborDirection.North -> NeighborDirection.East
            NeighborDirection.East -> NeighborDirection.South
            NeighborDirection.South -> NeighborDirection.West
            NeighborDirection.West -> NeighborDirection.North
            else -> return null
        }
        return tileMap.getNeighborTile(tile.unwrap(), rightDir)?.toViewIfExplored()
    }

    @Readonly fun getNeighborTilePositionAsWorldCoords(tile: TileView, neighbor: TileView): Vector2 =
        tileMap.getNeighborTilePositionAsWorldCoords(tile.unwrap(), neighbor.unwrap())

    /** World-coordinate offset of [to] relative to [from], accounting for map wraparound. */
    @Readonly fun getRelativeWorldPosition(from: TileView, to: TileView): Vector2 {
        val fromTile = from.unwrap()
        var targetPos = Vector2(to.unwrap().position.toVector2())
        if (tileMap.mapParameters.worldWrap)
            targetPos = GridMath.getUnwrappedNearestTo(targetPos.toGridCoord(), fromTile.position, tileMap.width)
        val targetWorld = GridMath.grid2WorldCoords(targetPos.toGridCoord())
        val fromWorld = GridMath.grid2WorldCoords(fromTile.position)
        return Vector2(targetWorld.x - fromWorld.x, targetWorld.y - fromWorld.y)
    }

    /** Names of nations with a starting location on [tile] (map editor only). */
    @Readonly fun getStartingLocationNationNames(tile: TileView): List<String> {
        val rawTile = tile.unwrap()
        return tileMap.startingLocationsByNation.filter { rawTile in it.value }.keys.toList()
    }

}
