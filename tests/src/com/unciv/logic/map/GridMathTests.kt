package com.unciv.logic.map

import com.badlogic.gdx.math.Vector2
import com.unciv.json.json
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.Terrain
import com.unciv.models.ruleset.tile.TerrainType
import org.junit.Assert
import org.junit.Test
import kotlin.random.Random
import kotlin.random.nextInt

class GridMathTests {

    @Test
    fun zeroIndexed() {
        Assert.assertEquals(0, GridMath.getZeroBasedIndex(0, 0))
    }

    @Test
    fun chebyshevDistanceAndSquareRings() {
        Assert.assertEquals(1, GridMath.getDistance(GridCoord.Zero, GridCoord.of(1, 1)))
        Assert.assertEquals(2, GridMath.getDistance(GridCoord.Zero, GridCoord.of(-2, 1)))
        val ring1 = GridMath.getGridCoordsAtDistance(GridCoord.Zero, 1, 10, false)
        Assert.assertEquals(8, ring1.size)
        val ring2 = GridMath.getGridCoordsAtDistance(GridCoord.Zero, 2, 10, false)
        Assert.assertEquals(16, ring2.size)
    }

    @Test
    fun testMappingIsOneToOne() {
        val seenCoordsMapping = hashSetOf<Int>()
        for (ring in 1..20) {
            val coords = GridMath.getGridCoordsAtDistance(GridCoord.Zero, ring, 20, false)
            val ringStart = (2 * ring - 1) * (2 * ring - 1)
            val ringEnd = (2 * ring + 1) * (2 * ring + 1) - 1
            for (coord in coords) {
                val mapping = GridMath.getZeroBasedIndex(coord.x, coord.y)
                Assert.assertFalse("Duplicate coords found: $coord", seenCoordsMapping.contains(mapping))
                Assert.assertTrue("Coords $coord should be in ring $ring, actual mapping $mapping", mapping in ringStart..ringEnd)
                seenCoordsMapping.add(mapping)
            }
        }
    }

    @Test
    fun testTileNeighborMappingIsNonConflicting() {
        val ruleset = Ruleset()
        ruleset.terrains["Plains"] = Terrain().apply {
            name = "Plains"
            type = TerrainType.Land
        }
        val tileMap = TileMap(21, 21, ruleset, false)
        val seenCoordsMapping = hashSetOf<Int>()

        for (tile in tileMap.values) {
            for (neighbor in tile.neighbors) {
                val index = GridMath.tilesAndNeighborUniqueIndex(tile, neighbor)
                Assert.assertFalse("Duplicate index found: $index", seenCoordsMapping.contains(index))
                seenCoordsMapping.add(index)
            }
        }
    }

    @Test
    fun rectangularWorldWrapNearest() {
        val unwrapped = GridMath.getUnwrappedNearestTo(GridCoord.of(9, 0), GridCoord.of(-9, 0), mapWidth = 20)
        Assert.assertEquals(-11f, unwrapped.x, 0.001f)
        Assert.assertEquals(0f, unwrapped.y, 0.001f)
    }

    @Test
    fun testGridCoordVector2SerDeser() {
        val json = json()
        for (x in -2..2)
            for (y in -2..2) {
                val gridCoord = GridCoord(x, y)
                val vector2 = Vector2(x.toFloat(), y.toFloat())

                val serialized = json.toJson(gridCoord)
                val vector2Deserialized = json.fromJson(Vector2::class.java, serialized)
                Assert.assertEquals(vector2, vector2Deserialized)

                val vector2Serialized = json.toJson(vector2)
                val gridCoordDeserialized = json.fromJson(GridCoord::class.java, vector2Serialized)
                Assert.assertEquals(gridCoord, gridCoordDeserialized)
            }
    }

    @Test
    fun testSerializedGridCoordIsCompact() {
        val json = json()
        val input = GridCoord.of(-42, 42)
        val actual = json.toJson(input)
        val expected = """{"x":-42,"y":42}"""
        Assert.assertEquals(expected, actual)
    }

    @Test
    fun testGridCoordFormatAndParseBack() {
        val rnd = Random(42)
        fun rndCoord() = rnd.nextInt(-20..20)

        repeat(42) {
            val expected = GridCoord.of(rndCoord(), rndCoord())
            val prettyString = expected.toPrettyString()
            val actual = GridCoord.fromString(prettyString)
            Assert.assertEquals(expected, actual)
        }
    }
}
