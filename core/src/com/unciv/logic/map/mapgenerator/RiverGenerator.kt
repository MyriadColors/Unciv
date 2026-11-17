package com.unciv.logic.map.mapgenerator

import com.unciv.logic.map.GridCoord
import com.unciv.logic.map.NeighborDirection
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.utils.debug
import kotlin.math.roundToInt

/**
 *  Handles River generation for [MapGenerator], [UniqueType.OneTimeChangeTerrain] and console.
 *
 *  Map generation follows the [vertices of map hexes][RiverCoordinate]: [spawnRivers].
 *  In-game new rivers work on edges: [continueRiverOn]
 */
class RiverGenerator(
    private val tileMap: TileMap,
    private val randomness: MapGenerationRandomness,
    ruleset: Ruleset
) {
    private val riverCountMultiplier = ruleset.modOptions.constants.riverCountMultiplier
    private val minRiverLength = ruleset.modOptions.constants.minRiverLength
    private val maxRiverLength = ruleset.modOptions.constants.maxRiverLength

    fun spawnRivers(resultingTiles: MutableSet<Tile>? = null) {
        if (maxRiverLength <= 0) return
        if (tileMap.values.none { it.isWater }) return
        val numberOfRivers = (tileMap.values.count { it.isLand } * riverCountMultiplier).roundToInt()

        var optionalTiles = tileMap.values.asSequence()
            .filter { it.getBaseTerrain().isMountain && it.isFarEnoughFromWater() }
            .toMutableList()
        if (optionalTiles.size < numberOfRivers)
            optionalTiles.addAll(tileMap.values.filter { it.isHill() && it.isFarEnoughFromWater() })
        if (optionalTiles.size < numberOfRivers)
            optionalTiles =
                tileMap.values.filter { it.isLand && it.isFarEnoughFromWater() }.toMutableList()

        val mapRadius = tileMap.mapParameters.mapSize.radius
        val riverStarts =
            randomness.chooseSpreadOutLocations(numberOfRivers, optionalTiles, mapRadius)
        for (tile in riverStarts) spawnRiver(tile, resultingTiles)
    }

    private fun Tile.isFarEnoughFromWater(): Boolean {
        for (distance in 1 until minRiverLength) {
            if (getTilesAtDistance(distance).any { it.isWater }) return false
        }
        return true
    }

    fun getClosestWaterTile(tile: Tile): Tile? {
        for (distance in 1..maxRiverLength) {
            val waterTiles = tile.getTilesAtDistance(distance).filter { it.isWater }
            if (waterTiles.any())
                return waterTiles.toList().random(randomness.RNG)
        }
        return null
    }

    private fun spawnRiver(initialPosition: Tile, resultingTiles: MutableSet<Tile>?) {
        val endPosition = getClosestWaterTile(initialPosition)
            ?: error("No water found for river destination")
        spawnRiver(initialPosition, endPosition, resultingTiles)
    }

    /** Spawns a river from [initialPosition] to [endPosition].
     *  If [resultingTiles] is supplied, it will contain all affected tiles, for map editor. */
    fun spawnRiver(initialPosition: Tile, endPosition: Tile, resultingTiles: MutableSet<Tile>?) {
        // Recommendation: Draw a bunch of hexagons on paper before trying to understand this, it's super helpful!

        var riverCoordinate = RiverCoordinate(tileMap, initialPosition.position)

        repeat(maxRiverLength) {     // Arbitrary max on river length, otherwise this will go in circles - rarely
            if (riverCoordinate.getAdjacentTiles().any { it.isWater }) return
            val possibleCoordinates = riverCoordinate.getAdjacentPositions().toList()
            if (possibleCoordinates.isEmpty()) return // end of the line
            val newCoordinate = possibleCoordinates
                .groupBy { newCoordinate ->
                    newCoordinate.getAdjacentTiles().map { it.aerialDistanceTo(endPosition) }
                        .minOrNull() ?: Int.MAX_VALUE
                }
                .minByOrNull { it.key }!!
                .value.random(randomness.RNG)

            // set one new river edge in place
            riverCoordinate.paintTo(newCoordinate, resultingTiles)
            // Move on
            riverCoordinate = newCoordinate
        }
        debug("River reached max length!")
    }

    /** Describes a _Vertex_ on our square grid: the bottom-right corner of tile [position]. */
    class RiverCoordinate(
        private val tileMap: TileMap,
        private val position: GridCoord
    ) {
        private val x = position.x
        private val y = position.y
        private val topLeft = tileMap.getIfTileExistsOrNull(x, y)
        private val topRight = tileMap.getIfTileExistsOrNull(x + 1, y)
        private val bottomLeft = tileMap.getIfTileExistsOrNull(x, y - 1)
        private val bottomRight = tileMap.getIfTileExistsOrNull(x + 1, y - 1)

        /** Lists the four neighboring vertices (N, S, E, W) that remain within map bounds */
        fun getAdjacentPositions(): Sequence<RiverCoordinate> = sequence {
            tileMap.getIfTileExistsOrNull(x, y + 1)?.let { yield(RiverCoordinate(tileMap, it.position)) }
            tileMap.getIfTileExistsOrNull(x, y - 1)?.let { yield(RiverCoordinate(tileMap, it.position)) }
            tileMap.getIfTileExistsOrNull(x - 1, y)?.let { yield(RiverCoordinate(tileMap, it.position)) }
            tileMap.getIfTileExistsOrNull(x + 1, y)?.let { yield(RiverCoordinate(tileMap, it.position)) }
        }

        /** Lists up to four tiles touching this vertex */
        fun getAdjacentTiles(): Sequence<Tile> = sequence {
            topLeft?.let { yield(it) }
            topRight?.let { yield(it) }
            bottomLeft?.let { yield(it) }
            bottomRight?.let { yield(it) }
        }

        fun paintTo(newCoordinate: RiverCoordinate, resultingTiles: MutableSet<Tile>?) {
            val dx = newCoordinate.x - x
            val dy = newCoordinate.y - y
            when {
                dy == 1 && dx == 0 -> { // Moved North: right edge of topLeft
                    topLeft?.hasRightRiver = true
                    if (resultingTiles != null) {
                        topLeft?.let { resultingTiles.add(it) }
                        topRight?.let { resultingTiles.add(it) }
                    }
                }
                dy == -1 && dx == 0 -> { // Moved South: right edge of bottomLeft
                    bottomLeft?.hasRightRiver = true
                    if (resultingTiles != null) {
                        bottomLeft?.let { resultingTiles.add(it) }
                        bottomRight?.let { resultingTiles.add(it) }
                    }
                }
                dx == -1 && dy == 0 -> { // Moved West: bottom edge of topLeft
                    topLeft?.hasBottomRiver = true
                    if (resultingTiles != null) {
                        topLeft?.let { resultingTiles.add(it) }
                        bottomLeft?.let { resultingTiles.add(it) }
                    }
                }
                dx == 1 && dy == 0 -> { // Moved East: bottom edge of topRight
                    topRight?.hasBottomRiver = true
                    if (resultingTiles != null) {
                        topRight?.let { resultingTiles.add(it) }
                        bottomRight?.let { resultingTiles.add(it) }
                    }
                }
            }
        }
    }

    enum class RiverDirections(private val direction: NeighborDirection) {
        North(NeighborDirection.North),
        East(NeighborDirection.East),
        South(NeighborDirection.South),
        West(NeighborDirection.West);
        fun getNeighborTile(selectedTile: Tile): Tile? =
            selectedTile.tileMap.getNeighborTile(selectedTile, direction)
        companion object {
            val names get() = entries.map { it.name }
        }
    }

    companion object {
        fun continueRiverOn(tile: Tile): Boolean {
            if (!tile.isLand) return false
            val tileMap = tile.tileMap

            class NeighborData(val otherTile: Tile) {
                val direction = tileMap.getNeighborDirection(tile, otherTile)!!
                val isConnectedByRiver = tile.isConnectedByRiver(otherTile)
                val edgeLeadsToSea: Boolean
                val connectedRiverCount: Int

                init {
                    val perpA: NeighborDirection
                    val perpB: NeighborDirection
                    if (direction == NeighborDirection.North || direction == NeighborDirection.South) {
                        perpA = NeighborDirection.West
                        perpB = NeighborDirection.East
                    } else {
                        perpA = NeighborDirection.North
                        perpB = NeighborDirection.South
                    }
                    val sideA = tileMap.getNeighborTile(tile, perpA)
                    val sideB = tileMap.getNeighborTile(tile, perpB)
                    val otherSideA = tileMap.getNeighborTile(otherTile, perpA)
                    val otherSideB = tileMap.getNeighborTile(otherTile, perpB)

                    edgeLeadsToSea = sideA?.isWater == true || sideB?.isWater == true ||
                        otherSideA?.isWater == true || otherSideB?.isWater == true
                    connectedRiverCount = listOfNotNull(
                        sideA?.let { tile.isConnectedByRiver(it) },
                        sideB?.let { tile.isConnectedByRiver(it) },
                        otherSideA?.let { otherTile.isConnectedByRiver(it) },
                        otherSideB?.let { otherTile.isConnectedByRiver(it) }
                    ).count { it }
                }

                fun getPriority(edgeToSeaPriority: Int) = when {
                    isConnectedByRiver -> -9
                    edgeLeadsToSea -> edgeToSeaPriority + connectedRiverCount
                    else -> connectedRiverCount * 2
                }
            }

            val viableNeighbors = tile.neighbors
                .filter { it.isLand && tileMap.getNeighborDirection(tile, it)?.isCardinal == true }
                .map { NeighborData(it) }
                .toList()
            if (viableNeighbors.isEmpty() || viableNeighbors.all { it.isConnectedByRiver }) return false

            val edgeToSeaPriority = if (viableNeighbors.none { it.isConnectedByRiver && it.edgeLeadsToSea }) 9 else -1
            val rng = GameContext(tile = tile).stateBasedRandom("RiverGenerator.continueRiverOn")
            val choice = viableNeighbors
                .groupBy { it.getPriority(edgeToSeaPriority) }
                .maxBy { it.key }.value
                .random(rng)

            return tile.setConnectedByRiver(choice.otherTile, newValue = true, convertTerrains = true)
        }
    }
}
