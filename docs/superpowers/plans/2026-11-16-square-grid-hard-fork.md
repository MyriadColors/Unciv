# Square Grid Hard Fork (Civ 3/4 Style) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert Unciv into a square-grid-only hard fork in the style of Civilization 3/4 with top-down orthogonal rendering, 8-way Chebyshev movement, cardinal-only rivers, modder-configurable Fat Cross city radius, and a `SquareBasic` default tileset.

**Architecture:** Remove hexagonal and flat-earth hex modes completely, renaming `HexCoord`/`HexMath` to `GridCoord`/`GridMath` with top-down orthogonal projection and Chebyshev distance. Update `TileMap`, `Tile` (2 cardinal river flags), `RiverGenerator` (4-way grid vertices), `PathingMap` (8-neighbor bitmasks), `City` (`ModConstants.cityWorkRange` + `cityWorkRangeExcludeCorners`), and `TileGroupMap`/`TileLayer*`/`MinimapTile` to operate natively on square grids, while rejecting legacy hex saves/maps on load.

**Tech Stack:** Kotlin, LibGDX (Scene2D, Gdx Json), JUnit 4, Gradle (`./gradlew test`).

**Spec:** [implementation_plan.md](file:///C:/Users/Pedro/.gemini/antigravity-cli/brain/d9376b75-8bdf-48da-80ee-cc8ede859c51/implementation_plan.md)

## Global Constraints

- Square-grid only: `MapShape` supports only `rectangular`; hexagonal and flat-earth modes are removed.
- Top-down orthogonal projection: tile `(x, y)` maps to world `(x, y)` at 1:1 aspect ratio (`groupSize × groupSize`).
- Distance metric: Chebyshev `max(abs(dx), abs(dy))` for movement, sight, and attack range; diagonal moves cost 1×.
- Rivers: Cardinal edges only (`hasBottomRiver`, `hasRightRiver`); diagonal moves and diagonal corners ignore river penalties and `isAdjacentToRiver()`.
- Unit stacking: Keep Unciv's 1-unit-per-tile (1UPT) rules.
- City work range: Modder-configurable via `ModConstants.cityWorkRange` (default `2`) and `ModConstants.cityWorkRangeExcludeCorners` (default `true` → 21-tile Fat Cross).
- Legacy saves/maps: Reject hex-era saves/maps on load with an explicit incompatibility error.
- Tilesets: Support 4 cardinal `Edges/` transitions (`Top`, `Bottom`, `Left`, `Right`) and bundle `SquareBasic` as default tileset.
- Zero-regex and zero-auto-formatter policy: No regexes or automated formatting tools in code or commands.

## Review Focus

1. **Horizontal World-Wrap Seam on Pathfinding & Rendering:** A unit or border crossing `x = width/2 - 1` to `x = -width/2` wraps cleanly in `getIfTileExistsOrNull` and `getUnwrappedNearestTo` without off-by-one gaps or false river connections (tested in Task 1 & Task 3).
2. **Diagonal Move Past a Cardinal River:** A unit moving diagonally between `(0, 0)` and `(1, 1)` when `(0, 0)` has `hasRightRiver = true` and `hasBottomRiver = true` must NOT pay the river-crossing penalty and `isConnectedByRiver` must return `false` (tested in Task 4).
3. **City Fat Cross Corner Exclusion & Modder Override:** With defaults (`cityWorkRange = 2`, `cityWorkRangeExcludeCorners = true`), `(±2, ±2)` are excluded (21 tiles); when a mod sets `cityWorkRangeExcludeCorners = false`, all 25 tiles in the 5×5 square are included (tested in Task 6).
4. **Legacy Hex Save / Map Rejection:** Loading a JSON save or map lacking `gridFormat = "square"` (or having `shape = "Hexagonal"`) throws an explicit incompatibility exception instead of silently loading skewed coordinates (tested in Task 3).
5. **Non-Square / Even-Dimension Map Bounds:** Generating or wrapping a rectangular map with odd or even `width`/`height` (e.g. `23×15` vs `24×16`) never produces out-of-bounds `tileMatrix` lookups or asymmetric wrap distances (tested in Task 3).

---

### Task 1: `GridMath`, `GridCoord`, and `NeighborDirection`

**Files:**
- Rename & Modify: `core/src/com/unciv/logic/map/HexMath.kt` → `core/src/com/unciv/logic/map/GridMath.kt`
- Modify: `core/src/com/unciv/logic/map/NeighborDirection.kt`
- Modify: `core/src/com/unciv/json/UncivJson.kt`
- Modify: All ~61 referencing files in `core/src` and `tests/src` (`HexCoord` → `GridCoord`, `HexMath` → `GridMath`)
- Test: `tests/src/com/unciv/logic/map/GridMathTests.kt` (renamed from `HexmathTests.kt`)

**Interfaces:**
- Consumes: `com.badlogic.gdx.math.Vector2`, `com.badlogic.gdx.utils.Json`
- Produces:
  - `data class GridCoord(val x: Int = 0, val y: Int = 0)`
  - `object GridMath`: `getDistance(a: GridCoord, b: GridCoord): Int`, `grid2WorldCoords(coord: GridCoord): Vector2`, `world2GridCoords(world: Vector2): Vector2`, `getZeroBasedIndex(x: Int, y: Int): Int`, `getGridCoordsAtDistance(origin: GridCoord, distance: Int, maxDistance: Int, worldWrap: Boolean): List<GridCoord>`, `getUnwrappedNearestTo(unwrapCoord: GridCoord, staticCoord: GridCoord, mapWidth: Int): Vector2`, `tilesAndNeighborUniqueIndex(tile: Tile, neighbor: Tile): Int`
  - `enum class NeighborDirection(val dx: Int, val dy: Int, val isCardinal: Boolean)`

- [ ] **Step 1: Write failing square-grid math tests in `tests/src/com/unciv/logic/map/GridMathTests.kt`**

```kotlin
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
fun squareZeroBasedIndexIsOneToOne() {
    val seen = hashSetOf<Int>()
    for (ring in 1..20) {
        val ringStart = (2 * ring - 1) * (2 * ring - 1)
        val ringEnd = (2 * ring + 1) * (2 * ring + 1) - 1
        for (coord in GridMath.getGridCoordsAtDistance(GridCoord.Zero, ring, 20, false)) {
            val idx = GridMath.getZeroBasedIndex(coord.x, coord.y)
            Assert.assertTrue(idx in ringStart..ringEnd)
            Assert.assertTrue(seen.add(idx))
        }
    }
}

@Test
fun rectangularWorldWrapNearest() {
    val unwrapped = GridMath.getUnwrappedNearestTo(GridCoord.of(9, 0), GridCoord.of(-9, 0), mapWidth = 20)
    Assert.assertEquals(-11f, unwrapped.x, 0.001f)
    Assert.assertEquals(0f, unwrapped.y, 0.001f)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.unciv.logic.map.GridMathTests"`
Expected: FAIL (class `GridMath` / `GridCoord` not found).

- [ ] **Step 3: Implement `GridMath.kt`, `GridCoord`, and `NeighborDirection.kt` and rename references**
- Create `GridMath.kt` with `GridCoord` (preserving `Serializer` JSON format `{"x":..,"y":..}`), Chebyshev `getDistance`, orthogonal `grid2WorldCoords` / `world2GridCoords`, square-ring `getZeroBasedIndex` (`(2*ring - 1)^2` base), `* 8` neighbor indexing in `tilesAndNeighborUniqueIndex`, and X-axis `getUnwrappedNearestTo(..., mapWidth)`.
- Update `NeighborDirection.kt` to the 8 compass directions (`North`, `NorthEast`, `East`, `SouthEast`, `South`, `SouthWest`, `West`, `NorthWest`) with `dx`, `dy`, `isCardinal`.
- Update `UncivJson.kt` and all `HexCoord`/`HexMath` references across `core/src` and `tests/src` using `edit_file`.

- [ ] **Step 4: Run `GridMathTests` to verify it passes**

Run: `./gradlew test --tests "com.unciv.logic.map.GridMathTests"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src tests/src
git commit -m "refactor: replace HexCoord/HexMath with square GridCoord/GridMath and 8-way NeighborDirection"
```

---

### Task 2: `MapParameters`, `MapSize`, and Legacy Save Rejection

**Files:**
- Modify: `core/src/com/unciv/logic/map/MapParameters.kt`
- Modify: `core/src/com/unciv/logic/map/MapSize.kt`
- Modify: `core/src/com/unciv/ui/screens/newgamescreen/MapParametersTable.kt`
- Test: `tests/src/com/unciv/logic/map/SquareMapCompatibilityTests.kt`

**Interfaces:**
- Consumes: `GridMath`, `GridCoord`
- Produces:
  - `MapParameters.gridFormat: String = "square"`
  - `MapParameters.assertSquareCompatible()` (throws `IllegalArgumentException` with explicit message if `gridFormat != "square"` or `shape != MapShape.rectangular`)
  - `MapShape.allValues = listOf(rectangular)`

- [ ] **Step 1: Write failing test for legacy hex save/map rejection**

```kotlin
@Test
fun rejectsLegacyHexMapParameters() {
    val legacyParams = MapParameters().apply {
        gridFormat = ""
        shape = "Hexagonal"
    }
    Assert.assertThrows(IllegalArgumentException::class.java) {
        legacyParams.assertSquareCompatible()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.unciv.logic.map.SquareMapCompatibilityTests"`
Expected: FAIL (`gridFormat` / `assertSquareCompatible` unresolved).

- [ ] **Step 3: Implement `gridFormat` guard and remove hex `MapShape` options**
- In `MapParameters.kt`: remove `hexagonal` and `flatEarth` from `MapShape`, default `shape = MapShape.rectangular`, add `var gridFormat = "square"`, and implement `assertSquareCompatible()`.
- In `MapSize.kt`: remove hex-radius fallback math; treat `width` and `height` as primary dimensions.
- In `MapParametersTable.kt`: remove hex/flat-earth shape options from the UI selector.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.unciv.logic.map.SquareMapCompatibilityTests"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/com/unciv/logic/map/MapParameters.kt core/src/com/unciv/logic/map/MapSize.kt core/src/com/unciv/ui/screens/newgamescreen/MapParametersTable.kt tests/src/com/unciv/logic/map/SquareMapCompatibilityTests.kt
git commit -m "feat: enforce square MapShape and reject legacy hex map/save formats"
```

---

### Task 3: `TileMap` Rectangular Grid, 8-Way Neighbors & World Wrap

**Files:**
- Modify: `core/src/com/unciv/logic/map/TileMap.kt`
- Test: `tests/src/com/unciv/logic/map/SquareTileMapTests.kt`

**Interfaces:**
- Consumes: `GridCoord`, `GridMath`, `NeighborDirection`, `MapParameters.assertSquareCompatible()`
- Produces:
  - `TileMap(width: Int, height: Int, ruleset: Ruleset, worldWrap: Boolean)`
  - `getIfTileExistsOrNull(x: Int, y: Int): Tile?` (wraps X across `width`)
  - `getUnwrappedPosition(position: GridCoord): GridCoord`
  - `getNeighborDirection(tile: Tile, otherTile: Tile): NeighborDirection?`
  - `getNeighborTile(tile: Tile, direction: NeighborDirection): Tile?`
  - `getLeftSharedNeighbor` / `getRightSharedNeighbor` for 4 cardinal edges

- [ ] **Step 1: Write failing tests for rectangular `TileMap` bounds, 8 neighbors, and even/odd wrap**

```kotlin
@Test
fun squareTileMapNeighborsAndWrapBounds() {
    val ruleset = Ruleset().apply {
        terrains["Plains"] = Terrain().apply { name = "Plains"; type = TerrainType.Land }
    }
    val mapOdd = TileMap(23, 15, ruleset, worldWrap = true)
    val leftEdge = mapOdd[mapOdd.leftX, 0]
    val rightEdge = mapOdd[mapOdd.leftX + mapOdd.width - 1, 0]
    Assert.assertTrue(leftEdge.neighbors.contains(rightEdge))
    Assert.assertEquals(8, mapOdd[0, 0].neighbors.count())

    val mapEven = TileMap(24, 16, ruleset, worldWrap = true)
    val leftEven = mapEven[mapEven.leftX, 0]
    val rightEven = mapEven[mapEven.leftX + mapEven.width - 1, 0]
    Assert.assertTrue(leftEven.neighbors.contains(rightEven))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.unciv.logic.map.SquareTileMapTests"`
Expected: FAIL (6 neighbors / hex wrap mismatch).

- [ ] **Step 3: Update `TileMap.kt` to rectangular square-grid topology**
- Generate `x in -width/2 until width - width/2` and `y in -height/2 until height - height/2` so both odd and even `width`/`height` produce exact `width × height` tiles.
- Update `getIfTileExistsOrNull` and `getUnwrappedPosition` for X-only modular wrapping (`leftX` to `leftX + width - 1`).
- Update `forEachTileAtDistance`, `getTilesAtDistance`, `getViewableTiles`, and `setTransients` (calling `mapParameters.assertSquareCompatible()`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.unciv.logic.map.SquareTileMapTests"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/com/unciv/logic/map/TileMap.kt tests/src/com/unciv/logic/map/SquareTileMapTests.kt
git commit -m "feat: convert TileMap to rectangular square grid with 8-way neighbors and X-wrap"
```

---

### Task 4: Cardinal Rivers in `Tile.kt` & `RiverGenerator.kt`

**Files:**
- Modify: `core/src/com/unciv/logic/map/tile/Tile.kt`
- Modify: `core/src/com/unciv/logic/map/mapgenerator/RiverGenerator.kt`
- Modify: `tests/src/com/unciv/logic/map/FreshWaterTests.kt`

**Interfaces:**
- Consumes: `TileMap.getNeighborDirection`, `NeighborDirection`
- Produces:
  - `Tile.hasBottomRiver: Boolean`, `Tile.hasRightRiver: Boolean`
  - `Tile.isConnectedByRiver(otherTile: Tile): Boolean` (false for diagonals)
  - `Tile.setConnectedByRiver(otherTile: Tile, newValue: Boolean, convertTerrains: Boolean = false): Boolean`
  - `RiverGenerator.RiverCoordinate` (4-way vertex traversal)

- [ ] **Step 1: Write failing tests for cardinal-only rivers and diagonal non-connection**

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.unciv.logic.map.FreshWaterTests"`
Expected: FAIL (`hasRightRiver` / cardinal-only behavior not implemented).

- [ ] **Step 3: Implement 2-edge cardinal rivers in `Tile.kt` and 4-way vertex `RiverCoordinate` in `RiverGenerator.kt`**
- Replace `hasBottomRightRiver`/`hasBottomLeftRiver` with `hasBottomRiver` and `hasRightRiver`.
- Map `North`/`South`/`East`/`West` in `isConnectedByRiver` and `setConnectedByRiver`; return `false` for diagonal neighbors.
- Update `RiverGenerator.RiverCoordinate` to walk 4-way grid corners.

- [ ] **Step 4: Run `FreshWaterTests` to verify it passes**

Run: `./gradlew test --tests "com.unciv.logic.map.FreshWaterTests"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/com/unciv/logic/map/tile/Tile.kt core/src/com/unciv/logic/map/mapgenerator/RiverGenerator.kt tests/src/com/unciv/logic/map/FreshWaterTests.kt
git commit -m "feat: implement Civ 4 cardinal-only river edges and 4-way river generator"
```

---

### Task 5: 8-Neighbor `PathingMap` & Movement Tests

**Files:**
- Modify: `core/src/com/unciv/logic/map/pathingmap/PathingMap.kt`
- Modify: `core/src/com/unciv/logic/map/pathingmap/PathingMapCache.kt`
- Test: `tests/src/com/unciv/logic/map/PathingMapTest.kt`, `tests/src/com/unciv/logic/map/UnitMovementTests.kt`

**Interfaces:**
- Consumes: `Tile.neighbors` (8 neighbors), `MovementCost.getMovementCostBetweenAdjacentTiles`, `Tile.isConnectedByRiver`
- Produces: 8-neighbor-safe `PathingMap` and `PathingMapCache`

- [ ] **Step 1: Write failing test for diagonal movement past a cardinal river**

```kotlin
@Test
fun diagonalMoveIgnoresCardinalRiverPenalty() {
    // Unit on (0,0) with hasRightRiver=true and hasBottomRiver=true moves to (1,-1) at cost 1f
    val cost = MovementCost.getMovementCostBetweenAdjacentTiles(unit, tile00, tile1_neg1)
    Assert.assertEquals(1f, cost, 0.001f)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.unciv.logic.map.UnitMovementTests"`
Expected: FAIL or neighbor-bitmask overflow in `PathingMap`.

- [ ] **Step 3: Update `PathingMap.kt` and `PathingMapCache.kt` for 8 neighbors**
- Replace any 6-neighbor bitmask/array-stride assumptions with 8-neighbor bounds.

- [ ] **Step 4: Run pathing and movement tests to verify they pass**

Run: `./gradlew test --tests "com.unciv.logic.map.PathingMapTest" --tests "com.unciv.logic.map.PathfindingTests" --tests "com.unciv.logic.map.UnitMovementTests"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/com/unciv/logic/map/pathingmap tests/src/com/unciv/logic/map
git commit -m "feat: support 8-way neighbors in PathingMap and verify diagonal river bypass"
```

---

### Task 6: Modder-Configurable City Fat Cross (`ModConstants`)

**Files:**
- Modify: `core/src/com/unciv/models/ModConstants.kt`
- Modify: `core/src/com/unciv/logic/city/City.kt`
- Modify: `core/src/com/unciv/logic/city/managers/CityPopulationManager.kt`
- Modify: `core/src/com/unciv/logic/city/managers/CityExpansionManager.kt`
- Modify: `core/src/com/unciv/logic/city/managers/CityTurnManager.kt`
- Modify: `core/src/com/unciv/view/CityView.kt`
- Test: `tests/src/com/unciv/logic/city/SquareCityFatCrossTest.kt`

**Interfaces:**
- Consumes: `TileMap`, `GridCoord`, `ModConstants`
- Produces:
  - `ModConstants.cityWorkRange = 2`
  - `ModConstants.cityWorkRangeExcludeCorners = true`
  - `City.isTileInWorkRange(tile: Tile): Boolean`

- [ ] **Step 1: Write failing tests for 21-tile Fat Cross default and `cityWorkRangeExcludeCorners = false` override**

```kotlin
@Test
fun fatCrossHas21TilesAndRespectsModderOverride() {
    // Default: cityWorkRange = 2, cityWorkRangeExcludeCorners = true -> 21 tiles, (2,2) excluded
    Assert.assertEquals(21, city.tilesInRange.size)
    Assert.assertFalse(city.isTileInWorkRange(map[2, 2]))

    // Override: cityWorkRangeExcludeCorners = false -> 25 tiles, (2,2) included
    ruleset.modOptions.constants.cityWorkRangeExcludeCorners = false
    city.setTransients(civ)
    Assert.assertEquals(25, city.tilesInRange.size)
    Assert.assertTrue(city.isTileInWorkRange(map[2, 2]))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.unciv.logic.city.SquareCityFatCrossTest"`
Expected: FAIL (`cityWorkRangeExcludeCorners` / `isTileInWorkRange` not defined).

- [ ] **Step 3: Implement `cityWorkRangeExcludeCorners` in `ModConstants.kt` and `isTileInWorkRange` in `City.kt`**
- Default `cityWorkRange = 2`, `cityWorkRangeExcludeCorners = true`.
- In `City.isTileInWorkRange(tile)`: check `abs(dx) <= range && abs(dy) <= range`, and when `cityWorkRangeExcludeCorners` is true, require `abs(dx) + abs(dy) <= 2 * range - 1` (excludes the 4 corners `(±range, ±range)`).
- Update `City.tilesInRange`, `CityPopulationManager`, `CityExpansionManager`, `CityTurnManager`, and `CityView` to use `isTileInWorkRange` / `tilesInRange`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.unciv.logic.city.SquareCityFatCrossTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/com/unciv/models/ModConstants.kt core/src/com/unciv/logic/city core/src/com/unciv/view/CityView.kt tests/src/com/unciv/logic/city/SquareCityFatCrossTest.kt
git commit -m "feat: add modder-configurable Fat Cross city work range in ModConstants"
```

---

### Task 7: Square Rendering (`TileGroupMap`, Layers, `MinimapTile`) & `SquareBasic` Tileset

**Files:**
- Modify: `core/src/com/unciv/ui/components/tilegroups/TileGroupMap.kt`
- Modify: `core/src/com/unciv/ui/components/tilegroups/TileSetStrings.kt`
- Modify: `core/src/com/unciv/ui/components/tilegroups/layers/TileLayer.kt`
- Modify: `core/src/com/unciv/ui/components/tilegroups/layers/TileLayerBorders.kt`
- Modify: `core/src/com/unciv/ui/components/tilegroups/layers/TileLayerTerrain.kt`
- Modify: `core/src/com/unciv/ui/components/tilegroups/layers/TileLayerOverlay.kt`
- Modify: `core/src/com/unciv/ui/components/tilegroups/layers/TileLayerMisc.kt`
- Modify: `core/src/com/unciv/ui/screens/worldscreen/minimap/MinimapTile.kt`
- Modify: `core/src/com/unciv/models/metadata/GameSettings.kt` (default tileset `"SquareBasic"`)
- Create: `android/assets/Images/TileSets/SquareBasic/` sprites + `android/assets/jsons/TileSets/SquareBasic.json`

**Interfaces:**
- Consumes: `GridMath.grid2WorldCoords`, `NeighborDirection` (4 cardinal for borders/edges, 8 for roads), `Tile.hasBottomRiver`, `Tile.hasRightRiver`
- Produces: Orthogonal square rendering, 4-cardinal `Edges/` support (`Top`, `Bottom`, `Left`, `Right`), 4-edge borders, 2-edge rivers, and `SquareBasic` default tileset.

- [ ] **Step 1: Create `SquareBasic` tileset assets and `SquareBasic.json`**
- Add `Square.png`, `CrosshatchSquare.png`, `UnexploredTile.png`, `Highlight.png`, `Crosshair.png`, `Border-Inner-*.png`, `Border-Outer-*.png`, `Tiles/River-Bottom.png`, `Tiles/River-Right.png`, `Road.png`, `Railroad.png`.

- [ ] **Step 2: Update `TileGroupMap.kt` and `TileLayer.kt` for orthogonal `groupSize × groupSize` layout**
- Position tiles at `(x * groupSize, y * groupSize)`, sort draw order by `y` descending, and compute `drawTopX` / `maxVisibleMapWidth` from `width * groupSize`.

- [ ] **Step 3: Update `TileSetStrings.kt`, `TileLayerBorders.kt`, `TileLayerTerrain.kt`, `TileLayerOverlay.kt`, `TileLayerMisc.kt`, and `MinimapTile.kt`**
- `TileLayerBorders`: draw borders only for the 4 cardinal neighbors (`0°, 90°, 180°, 270°`).
- `TileSetStrings` & `TileLayerTerrain`: resolve `Top`, `Bottom`, `Left`, `Right` `Edges/` transitions and 2 cardinal river sprites (`River-Bottom`, `River-Right`).
- `TileLayerMisc`: orient roads and arrows across all 8 directions.
- `MinimapTile`: use square tile sprites at `(x * tileSize, y * tileSize)` with 4-edge borders.

- [ ] **Step 4: Run full automated test suite**

Run: `./gradlew test`
Expected: PASS across all tests with zero compilation or runtime errors.

- [ ] **Step 5: Commit**

```bash
git add core/src/com/unciv/ui core/src/com/unciv/models/metadata/GameSettings.kt android/assets
git commit -m "feat: implement orthogonal square tile rendering, 4-cardinal edges/borders, and SquareBasic tileset"
```
