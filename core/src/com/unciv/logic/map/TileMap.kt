package com.unciv.logic.map

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapgenerator.MapLandmassGenerator
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.tile.TerrainType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueMap
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.utils.addToMapOfSets
import com.unciv.utils.contains
import yairm210.purity.annotations.Readonly
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/** An Unciv map with all properties as produced by the [map editor][com.unciv.ui.screens.mapeditorscreen.MapEditorScreen]
 * or [MapGenerator][com.unciv.logic.map.mapgenerator.MapGenerator]; or as part of a running [game][GameInfo].
 *
 * Note: Will be Serialized -> Take special care with lateinit and lazy!
 *
 * @param initialCapacity Passed to constructor of [tileList]
 */
class TileMap(initialCapacity: Int = 10) : IsPartOfGameInfoSerialization {
    //region Fields, Serialized

    var mapParameters = MapParameters()

    var tileList = ArrayList<Tile>(initialCapacity)

    /** Structure geared for simple serialization by Gdx.Json (which is a little blind to kotlin collections, especially HashSet)
     * @param position [Vector2] of the location
     * @param nation Name of the nation
     */
    data class StartingLocation(
        val position: GridCoord = GridCoord.Zero,
        val nation: String = "",
        val usage: Usage = Usage.default // default for maps saved pior to this feature
    ) : IsPartOfGameInfoSerialization {
        /** How a starting location may be used when the map is loaded for a new game */
        enum class Usage(val label: String) {
            /** Starting location only */
            Normal("None"),
            /** Use for "Select players from starting locations" */
            Player("Player"),
            /** Use as first Human player */
            Human("Human")
            ;
            companion object {
                val default get() = Player
            }
        }
    }
    val startingLocations = arrayListOf<StartingLocation>()

    /** Optional freeform text a mod map creator can set for their "customers" */
    var description = ""

    //endregion
    //region Fields, Transient

    /** Attention: lateinit will _stay uninitialized_ while in MapEditorScreen! */
    @Transient
    lateinit var gameInfo: GameInfo

    /** Keep a copy of the [Ruleset] object passed to setTransients, for now only to allow subsequent setTransients without. Copied on [clone]. */
    @Transient
    var ruleset: Ruleset? = null

    data class TerrainListData(
        val uniques: UniqueMap,
        val terrainNameSet: Set<String>
    ){
        companion object{
            val EMPTY = TerrainListData(UniqueMap.EMPTY, emptySet())
        }
    }

    @Transient
    var tileUniqueMapCache = ConcurrentHashMap<List<String>, TerrainListData>()

    @Transient
    var tileMatrix = ArrayList<ArrayList<Tile?>>() // this works several times faster than a hashmap, the performance difference is really astounding

    @Transient var leftX = 0
    @Transient var bottomY = 0
    @Transient var width = 0

    @delegate:Transient
    val maxLatitude: Int by lazy { if (values.isEmpty()) 0 else values.maxOf { abs(it.latitude) } }

    @delegate:Transient
    val maxLongitude: Int by lazy { if (values.isEmpty()) 0 else values.maxOf { abs(it.longitude) } }

    @delegate:Transient
    val naturalWonders: Set<String> by lazy { tileList.asSequence().filter { it.isNaturalWonder() }.map { it.naturalWonder!! }.toSet() }

    @delegate:Transient
    val resources: Set<String> by lazy { tileList.asSequence().mapNotNull { it.resource }.toSet() }

    @delegate:Transient
    val resourceObjects: Set<TileResource> by lazy { tileList.asSequence().mapNotNull { it.tileResource }.toSet() }

    // Excluded from Serialization by having no own backing field
    val values: Collection<Tile>
        get() = tileList

    @Transient
    val startingLocationsByNation = HashMap<String, HashSet<Tile>>()

    @Transient
    /** Continent ID to Continent size */
    val continentSizes = HashMap<Int, Int>()

    @Transient
    /** Continent IDs sorted by size descending */
    val continentsSortedBySize = ArrayList<Int>()

    //endregion
    //region Constructors

    /**
     * creates a hexagonal map of given radius (filled with grassland)
     *
     * To help you visualize how Unciv hexagonal coordinate system works, here's a small example:
     *
     * ```
     *          _____         _____         _____
     *         /     \       /     \       /     \
     *   _____/ 2, 0  \_____/  1, 1 \_____/  0,2  \_____
     *  /     \       /     \       /     \       /     \
     * / 2,-1  \_____/  1,0  \_____/  0, 1 \_____/  -1,2 \
     * \       /     \       /     \       /     \       /
     *  \_____/ 1,-1  \_____/  0,0  \_____/  -1,1 \_____/
     *  /     \       /     \       /     \       /     \
     * / 1 ,-2 \_____/ 0,-1  \_____/ -1,0  \_____/ -2,1  \
     * \       /     \       /     \       /     \       /
     *  \_____/ 0,-2  \_____/ -1,-1 \_____/ -2,0  \_____/
     *  /     \       /     \       /     \       /     \
     * / 0,-3  \_____/ -1,-2 \_____/ -2,-1 \_____/ -3,0  \
     * \       /     \       /     \       /     \       /
     *  \_____/       \_____/       \_____/       \_____/
     * ```
     *
     * The rules are simple if you think about your X and Y axis as diagonal w.r.t. a standard carthesian plane. As such:
     *
     * moving "up": increase both X and Y by one
     * moving "down": decrease both X and Y by one
     * moving "up-right" and "down-left": moving along Y axis
     * moving "up-left" and "down-right": moving along X axis
     *
     * Tip: you can always use the in-game map editor if you have any doubt,
     * and the "secret" options can turn on coordinate display on the main map.
     */
    /** creates a square map of given Chebyshev [radius] (side = 2*radius + 1, filled with grassland) */
    constructor(radius: Int, ruleset: Ruleset, worldWrap: Boolean = false)
            : this(2 * radius + 1, 2 * radius + 1, ruleset, worldWrap)

    /** creates a rectangular map of given width and height (filled with grassland) */
    constructor(width: Int, height: Int, ruleset: Ruleset, worldWrap: Boolean = false)
            : this(width * height) {
        startingLocations.clear()
        val firstAvailableLandTerrain = MapLandmassGenerator.getInitializationTerrain(ruleset, TerrainType.Land)

        // world-wrap maps must always have an even width, so round down
        val wrapAdjustedWidth = if (worldWrap && width % 2 != 0) width - 1 else width
        mapParameters.mapSize = MapSize(wrapAdjustedWidth, height)
        mapParameters.worldWrap = worldWrap

        // Even widths will have coordinates ranging -x..(x-1), not -x..x
        // e.g. w=4 -> -2..1, w=5 -> -2..2, w=6 -> -3..2, w=7 -> -3..3
        for (column in -wrapAdjustedWidth / 2 .. (wrapAdjustedWidth - 1) / 2)
            for (row in -height / 2 .. (height - 1) / 2)
                tileList.add(Tile().apply {
                    position = GridMath.getTileCoordsFromColumnRow(column, row).asSerializable()
                    baseTerrain = firstAvailableLandTerrain
                })

        setTransients(ruleset)
    }

    //endregion
    //region Operators and Standards

    /** @return a deep-copy clone of the serializable fields, no transients initialized */
    fun clone(): TileMap {
        val toReturn = TileMap(tileList.size)
        toReturn.tileList.addAll(tileList.asSequence().map { it.clone() })
        toReturn.mapParameters = mapParameters
        toReturn.ruleset = ruleset

        // Note during normal play this is empty. Supported for MapEditorScreen.getMapCloneForSave.
        toReturn.startingLocations.clear()
        toReturn.startingLocations.ensureCapacity(startingLocations.size)
        toReturn.startingLocations.addAll(startingLocations)

        toReturn.description = description
        toReturn.tileUniqueMapCache = tileUniqueMapCache

        return toReturn
    }

    @Readonly operator fun contains(vector: Vector2) = contains(vector.x.toInt(), vector.y.toInt())
    @Readonly operator fun contains(coord: GridCoord) = contains(coord.x, coord.y)
    @Readonly fun contains(x: Int, y: Int) = getOrNull(x, y) != null

    @Readonly operator fun get(vector: Vector2) = get(vector.x.toInt(), vector.y.toInt())
    @Readonly operator fun get(coord: GridCoord) = get(coord.x, coord.y)
    @Readonly operator fun get(x: Int, y: Int) = tileMatrix[x - leftX][y - bottomY]!!

    /** @return tile at hex coordinates ([x],[y]) or null if they are outside the map. Does *not* respect world wrap, use [getIfTileExistsOrNull] for that. */
    @Readonly
    fun getOrNull (x: Int, y: Int): Tile? =
            tileMatrix.getOrNull(x - leftX)?.getOrNull(y - bottomY)

    //endregion
    //region Pure Functions

    /** Can we access [gameInfo]? e.g. for MapEditor use where there is a map but no game */
    fun hasGameInfo() = ::gameInfo.isInitialized

    /** @return All tiles in a hexagon of radius [distance], including the tile at [origin] and all up to [distance] steps away.
     *  Respects map edges and world wrap. */
    @Readonly
    @Deprecated(message = "forEachTileInDistance is faster. If not viable, then this can still be used",
        replaceWith = ReplaceWith("forEachTileInDistance"))
    fun getTilesInDistance(origin: GridCoord, distance: Int): Sequence<Tile> =
        @Suppress("DEPRECATION")
        getTilesInDistanceRange(origin, 0..distance)

    @Readonly
    @Deprecated(message = "forEachTileInDistanceRange is faster. If not viable, then this can still be used",
        replaceWith = ReplaceWith("forEachTileInDistanceRange"))
    fun getTilesInDistanceRange(origin: GridCoord, range: IntRange): Sequence<Tile> =
        @Suppress("DEPRECATION")
        range.asSequence().flatMap { getTilesAtDistance(origin, it) }

    /** @return All tiles in a square Chebyshev ring 1 tile wide around [origin] with [distance]. Contains [origin] if and only if [distance] <= 0.
     *  Respects map edges and world wrap. */
    @Readonly
    @Deprecated(message = "forEachTileAtDistance is faster. If not viable, then this can still be used",
        replaceWith = ReplaceWith("forEachTileAtDistance"))
    fun getTilesAtDistance(origin: GridCoord, distance: Int): Sequence<Tile> =
        if (distance <= 0)
            sequenceOf(get(origin))
        else
            sequence {
                val centerX = origin.x
                val centerY = origin.y
                for (dx in -distance..distance) {
                    yield(getIfTileExistsOrNull(centerX + dx, centerY + distance))
                    yield(getIfTileExistsOrNull(centerX + dx, centerY - distance))
                }
                for (dy in (-distance + 1) until distance) {
                    yield(getIfTileExistsOrNull(centerX - distance, centerY + dy))
                    yield(getIfTileExistsOrNull(centerX + distance, centerY + dy))
                }
            }.filterNotNull()

    @Readonly
    fun forEachTileInDistance(origin: GridCoord, distance: Int, op: (Tile)->Unit)
        = forEachTileInDistance(origin, distance, {true}, op)
    @Readonly
    fun forEachTileInDistance(origin: GridCoord, distance: Int, filter: (Tile)->Boolean, op: (Tile)->Unit) {
        for (i in 0..distance)
            forEachTileAtDistance(origin, i, filter, op)
    }

    @Readonly
    fun forEachTileInDistanceRange(origin: GridCoord, range: IntRange, op: (Tile)->Unit)
        = forEachTileInDistanceRange(origin, range, {true}, op)
    @Readonly
    fun forEachTileInDistanceRange(origin: GridCoord, range: IntRange, filter: (Tile)->Boolean, op: (Tile)->Unit) {
        for (i in range)
            forEachTileAtDistance(origin, i, filter, op)
    }

    @Readonly
    fun forEachTileAtDistance(origin: GridCoord, distance: Int, op: (Tile)->Unit)
        = forEachTileAtDistance(origin, distance, {true}, op)
    @Readonly
    fun forEachTileAtDistance(origin: GridCoord, distance: Int, filter: (Tile)->Boolean, op: (Tile)->Unit) {
        if (distance <= 0) {
            val tile = get(origin)
            if (filter(tile)) op(tile)
        } else if (distance == 1) {
            for (tile in get(origin).neighbors) {
                if (filter(tile))
                    op(tile)
            }
        } else {
            val centerX = origin.x
            val centerY = origin.y
            var tile: Tile?
            for (dx in -distance..distance) {
                tile = getIfTileExistsOrNull(centerX + dx, centerY + distance)
                if (tile != null && filter(tile)) op(tile)
                tile = getIfTileExistsOrNull(centerX + dx, centerY - distance)
                if (tile != null && filter(tile)) op(tile)
            }
            for (dy in (-distance + 1) until distance) {
                tile = getIfTileExistsOrNull(centerX - distance, centerY + dy)
                if (tile != null && filter(tile)) op(tile)
                tile = getIfTileExistsOrNull(centerX + distance, centerY + dy)
                if (tile != null && filter(tile)) op(tile)
            }
        }
    }

    /** @return all tiles within [rectangle], respecting world edges and wrap. */
    fun getTilesInRectangle(rectangle: Rectangle) = sequence {
            val x = rectangle.x.toInt()
            val y = rectangle.y.toInt()
            for (worldColumnNumber in x until x + rectangle.width.toInt()) {
                for (worldRowNumber in y until y + rectangle.height.toInt()) {
                    val coords = GridMath.getTileCoordsFromColumnRow(worldColumnNumber, worldRowNumber)
                    yield(getIfTileExistsOrNull(coords.x, coords.y))
                }
            }
        }.filterNotNull()

    /** @return tile at grid coordinates ([x],[y]) or null if they are outside the map. Respects map edges and world wrap. */
    @Readonly
    fun getIfTileExistsOrNull(x: Int, y: Int): Tile? {
        val tile = getOrNull(x, y)
        if (tile != null) return tile

        if (!mapParameters.worldWrap || width <= 0)
            return null

        val wrappedX = ((x - leftX).mod(width)) + leftX
        return getOrNull(wrappedX, y)
    }

    @Readonly
    fun getNeighborDirection(tile: Tile, otherTile: Tile): NeighborDirection? {
        val dy = otherTile.position.y - tile.position.y
        if (dy !in -1..1) return null
        var dx = otherTile.position.x - tile.position.x
        if (mapParameters.worldWrap && width > 2) {
            if (dx > 1) dx -= width
            else if (dx < -1) dx += width
        }
        if (dx !in -1..1 || (dx == 0 && dy == 0)) return null
        return NeighborDirection.fromDelta(dx, dy)
    }

    @Readonly
    fun getNeighborTile(tile: Tile, direction: NeighborDirection): Tile? =
        getIfTileExistsOrNull(tile.position.x + direction.dx, tile.position.y + direction.dy)

    @Readonly
    fun getNeighborTilePositionAsWorldCoords(tile: Tile, otherTile: Tile): Vector2 {
        val dir = getNeighborDirection(tile, otherTile) ?: return Vector2.Zero
        return GridMath.getDirectionToWorldVector(dir)
    }

    /**
     * Returns the closest position to (0, 0) outside the map which can be wrapped
     * to the position of the given vector
     */
    @Readonly
    fun getUnwrappedPosition(position: GridCoord): GridCoord {
        if (!contains(position))
            return position

        val unwrappedLeft = GridCoord.of(position.x + width, position.y)
        val unwrappedRight = GridCoord.of(position.x - width, position.y)

        return if (abs(unwrappedRight.x) < abs(unwrappedLeft.x))
            unwrappedRight
        else
            unwrappedLeft
    }

    data class ViewableTile(val tile: Tile, val maxHeightSeenToTile: Int, val isVisible: Boolean, val isAttackable: Boolean)

    /** @return List of tiles visible from location [position] for a unit with sight range [sightDistance] */
    @Readonly
    fun getViewableTiles(position: GridCoord, sightDistance: Int, forAttack: Boolean = false): List<Tile> {
        val aUnitHeight = get(position).unitHeight
        val viewableTiles = mutableListOf(ViewableTile(
            get(position),
            aUnitHeight,
            isVisible = true,
            isAttackable = false
        ))

        for (i in 1..sightDistance+1) { // in each layer,
            // This is so we don't use tiles in the same distance to "see over",
            // that is to say, the "viewableTiles.contains(it) check will return false for neighbors from the same distance
            val tilesToAddInDistanceI = ArrayList<ViewableTile>()

            forEachTileAtDistance(position, i) { cTile -> // for each tile in that layer,
                val cTileHeight = cTile.tileHeight

                // For the sightdistance+1 layer - that's "one out of sight" - it's only visible if it's higher than the current tile
                if (i == sightDistance + 1 && (cTileHeight <= aUnitHeight || forAttack))
                    return@forEachTileAtDistance

                /*
            Okay so, if we're looking at a tile from height a to one with height c with a MAXIMUM HEIGHT of b in the middle,
            we have several scenarios:
            1. a>=b - I can see everything, b does not hide c (equals is 'flat plain' or 'string of hills' or 'hill viewing over forests')
            3. a<b
                3.1 b>=c - b hides c (hills hide other hills, forests, etc)
                3.2 b<c - c is tall enough I can see it over b (hill+forest, mountain)

            This can all be summed up as "I can see c if a=>b || c>b"
            */
                val bMinimumHighestSeenTerrainSoFar = viewableTiles
                    .filter { it.tile.aerialDistanceTo(cTile) == 1 }
                    .minOf { it.maxHeightSeenToTile }

                tilesToAddInDistanceI.add(ViewableTile(
                    cTile,
                    max(cTileHeight, bMinimumHighestSeenTerrainSoFar),
                    aUnitHeight >= bMinimumHighestSeenTerrainSoFar || cTileHeight > bMinimumHighestSeenTerrainSoFar,
                    aUnitHeight >= bMinimumHighestSeenTerrainSoFar || cTile.unitHeight > bMinimumHighestSeenTerrainSoFar,
                ))
            }
            viewableTiles.addAll(tilesToAddInDistanceI)
        }

        if (forAttack) return viewableTiles.filter { it.isAttackable }.map { it.tile }

        return viewableTiles.filter { it.isVisible }.map { it.tile }
    }

    /** Build a list of incompatibilities of a map with a ruleset for the new game loader
     *
     *  Is run before setTransients, so make do without startingLocationsByNation
     */
    fun getRulesetIncompatibility(ruleset: Ruleset): HashSet<String> {
        val rulesetIncompatibilities = HashSet<String>()
        for (set in values.map { it.getRulesetIncompatibility(ruleset) })
            rulesetIncompatibilities.addAll(set)

        // All the rest is to find missing nations
        try { // This can fail if the map contains a resource that isn't in the ruleset, in Tile.tileResource
            setTransients(ruleset)
        } catch (_: Exception) {
            return rulesetIncompatibilities
        }
        setStartingLocationsTransients()
        for ((_, nationName) in startingLocations) {
            if (nationName !in ruleset.nations)
                rulesetIncompatibilities.add("Nation [$nationName] does not exist in ruleset!")
        }
        rulesetIncompatibilities.remove("")
        return rulesetIncompatibilities
    }

    fun isWaterMap(): Boolean {
        assignContinents(AssignContinentsMode.Ensure)
        val bigIslands = continentSizes.count { it.value > 20 }
        val players = gameInfo.gameParameters.players.size
        return bigIslands >= players
    }

    fun usingArchipelagoRegions(): Boolean {
        val totalLand = continentSizes.values.sum().toFloat()
        val largestContinent = continentSizes.values.maxOf { it }.toFloat()
        return largestContinent / totalLand < 0.25f
    }

    //endregion
    //region State-Changing Methods

    /** Initialize transients - without, most operations, like [get] from coordinates, will fail.
     * @param ruleset Required unless this is a clone of an initialized TileMap including one
     * @param setUnitCivTransients when false Civ-specific parts of unit initialization are skipped, for the map editor.
     */
    fun setTransients(ruleset: Ruleset? = null, setUnitCivTransients: Boolean = true) {
        mapParameters.assertSquareCompatible()
        if (ruleset != null) this.ruleset = ruleset
        check(this.ruleset != null) { "TileMap.setTransients called without ruleset" }
        check(tileList.isNotEmpty()) { "No tiles were found in the save?!" }

        if (tileMatrix.isEmpty()) {
            val topY = tileList.maxOf { it.position.y }
            bottomY = tileList.minOf { it.position.y }
            val rightX = tileList.maxOf { it.position.x }
            leftX = tileList.minOf { it.position.x }
            width = rightX - leftX + 1

            tileMatrix.ensureCapacity(width)
            repeat (width) {
                val row = ArrayList<Tile?>(topY - bottomY + 1)
                repeat(topY - bottomY + 1) { row.add(null) }
                tileMatrix.add(row)
            }
        } else {
            check(tileMatrix.size in (-2 * leftX)..(3 - 2 * leftX)) {
                "TileMap.setTransients called on existing tileMatrix of different size"
            }
        }

        for (tileInfo in values) {
            tileMatrix[tileInfo.position.x - leftX][tileInfo.position.y - bottomY] = tileInfo
        }
        for ((index, tileInfo) in values.withIndex()) {
            tileInfo.tileMap = this
            tileInfo.zeroBasedIndex = index
            tileInfo.ruleset = this.ruleset!!
        }
        for (tileInfo in values) {
            // Do ***NOT*** call Tile.setTerrainTransients before the tileMatrix is complete -
            // setting transients might trigger the neighbors lazy (e.g. thanks to convertHillToTerrainFeature).
            // When that lazy runs, some directions might be omitted because getIfTileExistsOrNull
            // looks at tileMatrix. Thus filling Tiles into tileMatrix and setting their
            // transients in the same loop will leave incomplete cached `neighbors`.
            // Similarly, ruleset must be set on ALL tiles before setTerrainTransients runs on ANY tile,
            // since transient setup can evaluate unique conditionals (e.g. isAdjacentTo) that read
            // ruleset on neighbor tiles not yet reached in iteration order.
            tileInfo.setTerrainTransients()
            tileInfo.setUnitTransients(setUnitCivTransients)
        }

        val minColumn = tileList.minOf { GridMath.getColumn(it.position) }
        val maxColumn = tileList.maxOf { GridMath.getColumn(it.position) }
        width = maxColumn - minColumn + 1
    }

    /** Initialize Civilization.neutralRoads based on Tile.roadOwner
     *  - which Civ owns roads on which neutral tiles */
    fun setNeutralTransients() {
        for (tileInfo in values) {
            tileInfo.setOwnerTransients()
        }
    }

    fun removeMissingTerrainModReferences(ruleSet: Ruleset) {
        // This will run before setTransients, so do not rely e.g. on Tile.ruleset being available.
        // That rules out Tile.removeTerrainFeature, which refreshes object/unique caches
        for (tile in this.values) {
            tile.removeMissingTerrainModReferences(ruleSet)
        }
        for (startingLocation in startingLocations.toList())
            if (startingLocation.nation !in ruleSet.nations)
                startingLocations.remove(startingLocation)
    }

    /** Tries to place the [unitName] into the [Tile] closest to the given [position]
     * @param position where to try to place the unit (or close - max 10 tiles distance)
     * @param unitName name of the [BaseUnit][com.unciv.models.ruleset.unit.BaseUnit] to create and place
     * @param civInfo civilization to assign unit to
     * @return created [MapUnit] or null if no suitable location was found
     * */
    fun placeUnitNearTile(
        position: GridCoord,
        unitName: String,
        civInfo: Civilization,
        unitId: Int? = null
    ): MapUnit? {
        val unit = gameInfo.ruleset.units[unitName]!!
        return placeUnitNearTile(position, unit, civInfo, unitId)
    }

    /** Tries to place the [baseUnit] into the [Tile] closest to the given [position]
     * @param position where to try to place the unit (or close - max 10 tiles distance)
     * @param baseUnit [BaseUnit][com.unciv.models.ruleset.unit.BaseUnit] to create and place
     * @param civInfo civilization to assign unit to
     * @return created [MapUnit] or null if no suitable location was found
     * */
    fun placeUnitNearTile(
            position: GridCoord,
            baseUnit: BaseUnit,
            civInfo: Civilization,
            unitId: Int? = null
    ): MapUnit? {
        val unit = baseUnit.newMapUnit(civInfo, unitId)

        fun getPassableNeighbours(tile: Tile) =
                tile.neighbors.filter { unit.movement.canPassThrough(it) }

        // both the civ name and actual civ need to be in here in order to calculate the canMoveTo...Darn
        unit.assignOwner(civInfo, updateCivInfo = false)
        // remember our first owner
        unit.originalOwner = civInfo.civID

        var unitToPlaceTile: Tile? = null
        // try to place at the original point (this is the most probable scenario)
        val currentTile = get(position)
        unit.currentTile = currentTile  // temporary
        unit.cache.state = GameContext(unit)
        if (unit.movement.canMoveTo(currentTile)) unitToPlaceTile = currentTile

        // if it's not suitable, try to find another tile nearby
        if (unitToPlaceTile == null) {
            class TileCandidates(
                val primary: MutableList<Tile> = mutableListOf(),
                val secondary: MutableList<Tile> = mutableListOf()
            ) {
                constructor(isRestrictedLandUnit: Boolean, tiles: Iterable<Tile>) : this() {
                    if (!isRestrictedLandUnit) {
                        primary.addAll(tiles)
                    }
                    else for (tile in tiles)
                        if (!isRestrictedLandUnit || tile.isLand) primary += tile
                        else secondary += tile
                }
                val allTiles get() = primary.asSequence() + secondary
            }

            val isRestrictedLandUnit = unit.baseUnit.isLandUnit && !unit.cache.canMoveOnWater
            var tryCount = 0
            val checkedCandidates = mutableSetOf<Tile>()
            var potentialCandidates = TileCandidates(isRestrictedLandUnit, getPassableNeighbours(currentTile).asIterable())
            while (unitToPlaceTile == null && tryCount++ < 10) {
                unitToPlaceTile = potentialCandidates.primary.firstOrNull { unit.movement.canMoveTo(it) } // Land units should prefer to go into land tiles
                    ?: potentialCandidates.secondary.firstOrNull { unit.movement.canMoveTo(it) }
                if (unitToPlaceTile != null) continue
                // if it's not found yet, let's check their neighbours
                checkedCandidates.addAll(potentialCandidates.allTiles)
                val newCandidates = potentialCandidates.allTiles
                    .flatMap { getPassableNeighbours(it).filter { it !in checkedCandidates } }
                    .toSet()
                potentialCandidates = TileCandidates(isRestrictedLandUnit, newCandidates)
            }
        }

        if (unitToPlaceTile == null) {
            civInfo.units.removeUnit(unit, updateCivInfo = false) // since we added it to the civ units in the previous assignOwner
            return null // we didn't actually create a unit...
        }

        // only once we know the unit can be placed do we add it to the civ's unit list
        unit.putInTile(unitToPlaceTile)
        unit.currentMovement = unit.getMaxMovement().toFloat()
        unit.addMovementMemory()

        // Only once we add the unit to the civ we can activate addPromotion, because it will try to update civ viewable tiles
        for (promotion in unit.baseUnit.promotions)
            unit.promotions.addPromotion(promotion, true)

        civInfo.forEachMatchingUnique(UniqueType.UnitsGainPromotion) { unique ->
            if (unit.matchesFilter(unique.params[0]))
                unit.promotions.addPromotion(unique.params[1], true)
        }

        // And update civ stats, since the new unit changes both unit upkeep and resource consumption
        civInfo.updateStatsForNextTurn()

        if (unit.getResourceRequirementsPerTurn().isNotEmpty())
            civInfo.cache.updateCivResources()

        return unit
    }


    /** Strips all units and starting locations from [TileMap] for specified [Player]
     * Operation in place
     *
     * Currently unreachable code
     *
     * @param player units of this player will be removed
     */
    @Suppress("unused")
    fun stripPlayer(player: Player) {
        tileList.forEach {
            for (unit in it.getUnits()) if (unit.owner == player.chosenCiv) unit.removeFromTile()
        }
        startingLocations.removeAll { it.nation == player.chosenCiv }
        startingLocationsByNation.remove(player.chosenCiv)
    }

    /** Finds all units and starting location of [Player] and changes their [Nation]
     * Operation in place
     *
     * Currently unreachable code
     *
     * @param player player whose all units will be changed
     * @param newNation new nation to be set up
     */
    @Suppress("unused")
    fun switchPlayersNation(player: Player, newNation: Nation) {
        val newCiv = Civilization(newNation)
        tileList.forEach {
            for (unit in it.getUnits()) if (unit.owner == player.chosenCiv) {
                unit.owner = newCiv.civID
                unit.civ = newCiv
                unit.setTransients(newCiv.gameInfo.ruleset)
            }
        }
        for (element in startingLocations.filter { it.nation != player.chosenCiv }) {
            startingLocations.remove(element)
            if (startingLocations.none { it.nation == newCiv.civID && it.position == element.position })
                startingLocations.add(StartingLocation(element.position, newCiv.civID))
        }
        setStartingLocationsTransients()
    }

    /**
     *  Initialize startingLocations transients
     */
    fun setStartingLocationsTransients() {
        startingLocationsByNation.clear()
        for ((position, nationName) in startingLocations) {
            startingLocationsByNation.addToMapOfSets(nationName, get(position))
        }
    }

    /** Adds a starting position, maintaining the transients
     *
     * Note: Will not replace an existing StartingLocation to update its [usage]
     * @return true if the starting position was not already stored as per [Collection]'s add */
    fun addStartingLocation(
        nationName: String,
        tile: Tile,
        usage: StartingLocation.Usage = StartingLocation.Usage.Player
    ): Boolean {
        if (startingLocationsByNation.contains(nationName, tile)) return false
        startingLocations.add(StartingLocation(tile.position, nationName, usage))
        return startingLocationsByNation.addToMapOfSets(nationName, tile)
    }

    /** Removes a starting position, maintaining the transients
     * @return true if the starting position was removed as per [Collection]'s remove */
    fun removeStartingLocation(nationName: String, tile: Tile): Boolean {
        if (!startingLocationsByNation.contains(nationName, tile)) return false
        startingLocations.removeAll { it.position == tile.position && it.nation == nationName }
        return startingLocationsByNation[nationName]!!.remove(tile)
        // we do not clean up an empty startingLocationsByNation[nationName] set - not worth it
    }

    /** Removes all starting positions for [nationName], maintaining the transients */
    fun removeStartingLocations(nationName: String) {
        if (startingLocationsByNation[nationName] == null) return
        startingLocations.removeAll { it.nation == nationName }
        startingLocationsByNation[nationName]!!.clear()
    }

    /** Removes all starting positions for [position], rebuilding the transients */
    fun removeStartingLocations(position: GridCoord) {
        startingLocations.removeAll { it.position == position }
        setStartingLocationsTransients()
    }

    /** Clears starting positions, e.g. after GameStarter is done with them. Does not clear the pseudo-improvements. */
    fun clearStartingLocations() {
        startingLocations.clear()
        startingLocationsByNation.clear()
    }

    /** Behaviour of [assignContinents] */
    enum class AssignContinentsMode { Assign, Reassign, Ensure, Clear }

    /** Set a continent id for each tile, so we can quickly see which tiles are connected.
     *  Can also be called on saved maps.
     *  @param mode As follows:
     *  [Assign][AssignContinentsMode.Assign] = initial assign, throw if tiles have continents.
     *  [Reassign][AssignContinentsMode.Reassign] = clear continent data and redo for map editor.
     *  [Ensure][AssignContinentsMode.Ensure] = regenerate continent sizes from tile data, and if that is empty, Assign.
     *  @throws Exception when `mode==Assign` and any land tile already has a continent ID
     *  @return A map of continent sizes (continent ID to tile count)
     */
    fun assignContinents(mode: AssignContinentsMode) {
        if (mode == AssignContinentsMode.Clear) {
            values.forEach { it.clearContinent() }
            continentSizes.clear()
            continentsSortedBySize.clear()
            return
        }

        if (mode == AssignContinentsMode.Ensure) {
            if (continentSizes.isNotEmpty()) return
            for (tile in values) {
                val continent = tile.getContinent()
                if (continent == -1) continue
                continentSizes[continent] = 1 + (continentSizes[continent] ?: 0)
            }
            if (continentSizes.isNotEmpty()) {
                updateContinentsSortedBySize()
                return
            }
        }

        var landTiles = values.filter { it.isLand && !it.isImpassible() }
        var currentContinent = 0
        continentSizes.clear()

        if (mode == AssignContinentsMode.Reassign)
            values.forEach { it.clearContinent() }

        while (landTiles.any()) {
            val bfs = BFS(landTiles[landTiles.size / 2]) { it.isLand && !it.isImpassible() }
            bfs.stepToEnd()
            bfs.getReachedTiles().forEach {
                it.setContinent(currentContinent)
            }
            val continent = bfs.getReachedTiles()
            continentSizes[currentContinent] = continent.size

            currentContinent++
            landTiles = landTiles.filter { it !in continent }
        }
        updateContinentsSortedBySize()
    }

    private fun updateContinentsSortedBySize() {
        continentsSortedBySize.clear()
        continentsSortedBySize.addAll(continentSizes.entries.sortedByDescending { it.value }.map { it.key })
    }
    //endregion

    /** Class to parse only the parameters and starting locations out of a map file */
    class Preview {
        val mapParameters = MapParameters()
        private val startingLocations = arrayListOf<StartingLocation>()
        fun getDeclaredNations() = startingLocations.asSequence()
            .filter { it.usage != StartingLocation.Usage.Normal }
            .map { it.nation }
            .distinct()
        fun getNationsForHumanPlayer() = startingLocations.asSequence()
            .filter { it.usage == StartingLocation.Usage.Human }
            .map { it.nation }
            .distinct()
    }
}
