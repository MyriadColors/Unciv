package com.unciv.logic.map

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.JsonValue
import com.unciv.logic.map.tile.Tile
import yairm210.purity.annotations.Immutable
import yairm210.purity.annotations.Pure
import yairm210.purity.annotations.Readonly
import yairm210.purity.annotations.ReturnsNewInstance
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Suppress("MemberVisibilityCanBePrivate", "unused")
object GridMath {

    @Pure @ReturnsNewInstance
    fun getVectorForAngle(angle: Float): Vector2 {
        return Vector2(sin(angle.toDouble()).toFloat(), cos(angle.toDouble()).toFloat())
    }

    /**
     * @param radius Chebyshev radius around origin tile
     * @return The number of tiles in a square of radius [radius] including origin tile: (2*radius + 1)^2
     */
    @Pure
    fun getNumberOfTilesInSquareRadius(radius: Int): Int {
        if (radius < 0) return 0
        val side = 2 * radius + 1
        return side * side
    }

    /** Inverse of [getNumberOfTilesInSquareRadius] - get equivalent fractional square radius for an Area */
    @Pure
    fun getSquareRadiusForArea(numberOfTiles: Int): Float =
        if (numberOfTiles < 1) 0f else ((sqrt(numberOfTiles.toFloat()) - 1f) / 2f).coerceAtLeast(0f)

    @Readonly fun getLatitude(coord: GridCoord): Int = coord.y

    @Readonly fun getLongitude(coord: GridCoord): Int = coord.x

    @Pure
    fun gridFromLatLong(latitude: Int, longitude: Int): Vector2 =
        Vector2(longitude.toFloat(), latitude.toFloat())

    @Readonly
    fun worldFromLatLong(vector: Vector2, tileSize: Float): Vector2 =
        Vector2(vector.x * tileSize, vector.y * tileSize)

    @Pure
    fun getEquivalentRectangularSize(radius: Int, ratio: Float = 0.65f): GridCoord {
        if (radius < 0) return GridCoord.Zero
        val nTiles = getNumberOfTilesInSquareRadius(radius)
        val width = sqrt(nTiles.toFloat() / ratio).roundToInt()
        val height = (width * ratio).roundToInt()
        return GridCoord.of(width, height)
    }

    @Pure
    fun getEquivalentSquareRadius(width: Int, height: Int): Int =
        getSquareRadiusForArea(width * height).roundToInt()

    /**
     * @param unwrapCoord Grid coordinate to unwrap.
     * @param staticCoord Reference grid coordinate.
     * @param mapWidth Total width of the rectangular map in tiles.
     *
     * @return The closest grid coordinate to [staticCoord] that is horizontally equivalent to [unwrapCoord] under world wrap.
     */
    @Readonly
    fun getUnwrappedNearestTo(unwrapCoord: GridCoord, staticCoord: GridCoord, mapWidth: Int): Vector2 {
        if (mapWidth <= 0) return unwrapCoord.toVector2()
        val halfWidth = mapWidth / 2
        val wrappedX = (unwrapCoord.x - staticCoord.x + halfWidth).mod(mapWidth) - halfWidth + staticCoord.x
        return Vector2(wrappedX.toFloat(), unwrapCoord.y.toFloat())
    }

    @Pure
    fun grid2WorldCoords(coord: GridCoord): Vector2 =
        Vector2(coord.x.toFloat(), coord.y.toFloat())

    @Readonly
    fun world2GridCoords(worldCoord: Vector2): Vector2 =
        Vector2(worldCoord.x, worldCoord.y)

    @Pure fun getRow(coord: GridCoord): Int = coord.y

    @Pure fun getColumn(coord: GridCoord): Int = coord.x

    @Pure
    fun getTileCoordsFromColumnRow(column: Int, row: Int): GridCoord =
        GridCoord.of(column, row)

    @Pure
    fun roundGridCoords(coord: Vector2): Vector2 =
        Vector2(coord.x.roundToInt().toFloat(), coord.y.roundToInt().toFloat())

    @Readonly
    fun getGridCoordsAtDistance(origin: GridCoord, distance: Int, maxDistance: Int, worldWrap: Boolean): List<GridCoord> {
        if (distance <= 0) return listOf(origin.cpy())
        val coords = ArrayList<GridCoord>(8 * distance)
        // Top & bottom edges of the Chebyshev ring
        for (dx in -distance..distance) {
            coords.add(origin.plus(dx, distance))
            coords.add(origin.plus(dx, -distance))
        }
        // Left & right edges excluding corners already added
        for (dy in (-distance + 1) until distance) {
            coords.add(origin.plus(-distance, dy))
            coords.add(origin.plus(distance, dy))
        }
        return coords
    }

    @Readonly
    fun getGridCoordsInDistance(origin: GridCoord, distance: Int, worldWrap: Boolean): List<GridCoord> {
        val coords = mutableListOf<GridCoord>()
        for (i in 0..distance) {
            coords += getGridCoordsAtDistance(origin, i, distance, worldWrap)
        }
        return coords
    }

    /** Chebyshev distance from [origin] to [destination] without respecting world-wrap */
    @Pure
    fun getDistance(origin: Vector2, destination: Vector2): Int =
        getDistance(origin.x.roundToInt(), origin.y.roundToInt(), destination.x.roundToInt(), destination.y.roundToInt())

    @Pure
    fun getDistance(origin: GridCoord, destination: GridCoord): Int =
        getDistance(origin.x, origin.y, destination.x, destination.y)

    @Pure
    fun getDistance(originX: Int, originY: Int, destinationX: Int, destinationY: Int): Int =
        max(abs(originX - destinationX), abs(originY - destinationY))

    @Immutable
    private val directionToWorldVectorMap: Map<NeighborDirection, Vector2> =
        NeighborDirection.entries.associateWith { Vector2(it.dx.toFloat(), it.dy.toFloat()) }

    @Pure
    fun getDirectionToWorldVector(direction: NeighborDirection): Vector2 =
        directionToWorldVectorMap[direction] ?: Vector2.Zero

    @Readonly
    fun getDistanceFromEdge(position: GridCoord, mapParameters: MapParameters): Int {
        val height = mapParameters.mapSize.height
        val width = mapParameters.mapSize.width
        val leftX = -width / 2
        val rightX = leftX + width - 1
        val bottomY = -height / 2
        val topY = bottomY + height - 1
        val left = if (mapParameters.worldWrap) Int.MAX_VALUE else position.x - leftX
        val right = if (mapParameters.worldWrap) Int.MAX_VALUE else rightX - position.x
        val bottom = position.y - bottomY
        val top = topY - position.y
        return minOf(left, right, top, bottom)
    }

    /**
     * Maps from square grid positions (centered on 0,0) to positive integers starting from 0.
     * Place 0 is (0,0), places 1..8 are Chebyshev ring 1, 9..24 are ring 2, etc.
     */
    @Pure
    fun getZeroBasedIndex(x: Int, y: Int): Int {
        if (x == 0 && y == 0) return 0
        val ring = max(abs(x), abs(y))
        val prevSide = 2 * ring - 1
        val ringStart = prevSide * prevSide
        val positionInRing = when {
            y == ring && x < ring -> x + ring // Top edge: x in [-ring, ring-1] -> 0..(2*ring-1)
            x == ring && y > -ring -> 2 * ring + (ring - y) // Right edge: y in [ring, -ring+1]
            y == -ring && x > -ring -> 4 * ring + (ring - x) // Bottom edge: x in [ring, -ring+1]
            else -> 6 * ring + (y + ring) // Left edge: x == -ring, y in [-ring, ring-1]
        }
        return ringStart + positionInRing
    }

    fun tilesAndNeighborUniqueIndex(tile: Tile, neighbor: Tile): Int {
        val dir = tile.tileMap.getNeighborDirection(tile, neighbor)
            ?: error("Tiles ${tile.position} and ${neighbor.position} are not neighbors")
        return tile.zeroBasedIndex * 8 + dir.ordinal
    }
}

/**
 * Holds a position on our square-grid [TileMap].
 * Serializes 1:1 with Vector2 {"x":..,"y":..} format.
 */
@Suppress("RemoveRedundantQualifierName")
data class GridCoord(val x: Int = 0, val y: Int = 0) {

    @Pure fun plus(coord: GridCoord): GridCoord = GridCoord.of(x + coord.x, y + coord.y)
    @Pure fun plus(plusX: Int, plusY: Int): GridCoord = GridCoord.of(x + plusX, y + plusY)
    @Pure fun minus(coord: GridCoord): GridCoord = GridCoord.of(x - coord.x, y - coord.y)
    @Pure fun times(int: Int): GridCoord = GridCoord.of(x * int, y * int)

    @Pure fun toVector2(): Vector2 = Vector2(x.toFloat(), y.toFloat())

    @Pure fun eq(x: Int, y: Int): Boolean = this.x == x && this.y == y

    @Pure fun cpy() = this
    @Pure fun toGridCoord() = this
    @Pure fun asSerializable() = GridCoord(x, y)

    fun toPrettyString(): String = "($x,$y)"

    companion object {
        val Zero = GridCoord.of(0, 0)
        @Pure
        fun of(x: Int, y: Int): GridCoord = GridCoord(x, y)

        fun fromString(string: String): GridCoord {
            val commaPos = string.indexOf(',')
            if (commaPos < 1 || !string.startsWith('(') || !string.endsWith(')'))
                throw NumberFormatException("\"$string\" is not a valid GridCoord representation")
            val x = string.substring(1, commaPos).toInt()
            val y = string.substring(commaPos + 1, string.length - 1).toInt()
            return GridCoord(x, y)
        }
    }

    class Serializer : Json.Serializer<GridCoord> {
        override fun write(json: Json, coord: GridCoord, knownType: Class<*>?) {
            json.writeObjectStart()
            if (coord.x != 0)
                json.writeValue("x", coord.x)
            if (coord.y != 0)
                json.writeValue("y", coord.y)
            json.writeObjectEnd()
        }

        override fun read(json: Json, jsonData: JsonValue, type: Class<*>?): GridCoord {
            val x = json.readValue("x", Float::class.java, 0f, jsonData)
            val y = json.readValue("y", Float::class.java, 0f, jsonData)
            return GridCoord(x.toInt(), y.toInt())
        }
    }
}

@JvmInline
value class InlineGridCoord(val coords: Int = 0) {
    val x: Int
        get() = coords shr 16

    val y: Int
        get() = (coords shl 16) shr 16

    override fun toString(): String = "GridCoord(x=${x}, y=${y})"

    companion object {
        @Pure
        fun of(x: Int, y: Int): InlineGridCoord {
            require(x in -32768..32767) { "X value must be in range -32768..32767" }
            require(y in -32768..32767) { "Y value must be in range -32768..32767" }
            return InlineGridCoord((x shl 16) or (y and 0xFFFF))
        }
    }
}

@Pure fun Vector2.toGridCoord() = GridCoord.of(this.x.toInt(), this.y.toInt())
