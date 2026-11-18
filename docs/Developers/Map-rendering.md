# Map rendering

## Introduction - how does LibGDX render images?

Images in LibGDX are displayed on screen by a `SpriteBatch` (`TextureArraySpriteBatch`), which uses OpenGL to bind texture atlases in memory and draw quads on screen.
The actual drawing is very fast, whereas texture state changes are comparatively expensive.
Therefore, we pack source images (`ImagePacker.packImages()`) into 2048×2048 texture atlases per category (`android/Images.Icons`, `android/Images.Tilesets`, `android/Images.Flags`, `android/Images.Tech`, etc., plus unpacked icons under `android/assets/Images/`) and use `TextureArraySpriteBatch` (an LFU multi-texture unit cache) to batch draw calls across multiple bound texture units.

## Orthogonal Square Grid Geometry

Unciv renders an orthogonal 8-direction square map (`GridMath.kt`):

- **Square Tile Dimensions**: Each `TileGroup` has `groupSize = 50f` (`tileImageSize = groupSize`, `tileImageOrigin = groupSize / 2f`, `setSize(groupSize, groupSize)`).
- **World Coordinates**: `TileGroupMap` positions tiles using `GridMath.grid2WorldCoords(tile.position) * groupSize` (`(coord.x * 50f, coord.y * 50f)`).
- **Descending-Y Z-Order**: Tiles in `TileGroupMap` and `TileLayerTerrain` are sorted descending by `position().y` so overlapping terrain and feature sprites layer consistently from top to bottom.
- **Cardinal Borders & Square-Edge Rivers**:
  - `TileLayerBorders` draws borders along the 4 cardinal neighbor edges (skipping diagonal neighbors where `relativeWorldPosition.x != 0f && relativeWorldPosition.y != 0f`) with 0°/90°/180°/-90° rotations.
  - `TileLayerTerrain` renders rivers on the right and bottom tile edges (`rightRiverImage` for `tile.hasRightRiver`, `bottomRiverImage` for `tile.hasBottomRiver`).
- **Tileset Lookup**: `TileSetStrings` resolves `"Square"` / `"CrosshatchSquare"` sprites from `SquareBasic` (with fallback to `"Hexagon"` / `"CrosshatchHexagon"` for legacy compatibility).

## Layering

Each map tile is comprised of several layers, and for *visual clarity* and *batching performance*, each layer is rendered across all visible tiles before the next layer begins.

The `TileLayer` subclasses are plain Kotlin classes (not scene-graph actors) that own the `Image` / `Actor` objects for a single tile and maintain a dirty-check cache key (`TileLayer.getUpdateKey` / `UpdateKey` / `invalidate`) so layers only rebuild their actors when visible tile state actually changes.

- When a `TileGroup` is used standalone (such as in the Civilopedia or Map Editor brush preview), its images are added directly to the `TileGroup`.
- When placed in a `TileGroupMap`, the images are moved into shared `TileMapLayer` containers — one container per layer type, in fixed render order:

```
TileGroup (standalone)          TileGroupMap (full map, in render order)
  └── all tile actors             ├── 1. TerrainMapLayer      (TileLayerTerrain: base terrain, features, rivers)
                                  ├── 2. FeaturesMapLayer     (TileLayerFeatures: roads & railroads)
                                  ├── 3. BordersMapLayer      (TileLayerBorders: 4-cardinal cultural borders)
                                  ├── 4. ResourceMapLayer     (TileLayerResource: resource icons)
                                  ├── 5. ImprovementMapLayer  (TileLayerImprovement: tile improvements)
                                  ├── 6. tileGroupLayer       (TileGroups: click detection, below MiscMapLayer)
                                  ├── 7. MiscMapLayer         (TileLayerMisc: worked icons, arrows, overlays)
                                  ├── 8. YieldMapLayer        (TileLayerYield: food/prod/gold yield badges)
                                  ├── 9. UnitSpriteMapLayer   (TileLayerUnitSprite: pixel unit sprites)
                                  ├── 10. OverlayMapLayer     (TileLayerOverlay: fog of war, unexplored, crosshair)
                                  ├── 11. UnitFlagMapLayer    (TileLayerUnitFlag: unit icons & stack count badges)
                                  └── 12. CityButtonMapLayer  (TileLayerCityButton: city name/pop/health/religion bar)
```

Notice that `tileGroupLayer` sits below `MiscMapLayer` so that interactive elements in `MiscMapLayer` (such as citizen `workedIcon` toggles in `CityScreen`) receive pointer hits before the underlying tile group. `BordersMapLayer` and `CityButtonMapLayer` also use an expanded frustum culling margin (`groupSize * 1.5f`) so city bars and border segments extending outside a tile's 50×50 bounds are not clipped during camera panning.

## Debugging

Android Studio's built-in profiler has a CPU profiler which is perfect for this.
Boot up the game on your Android device or Desktop, open a game, start recording CPU, pan and zoom the map (`WorldMapHolder.setupZoomPanListeners` disables `shouldAct` and `shouldHit` while panning/zooming), and stop recording.
Select the "GL Thread" from the list of threads, and change visualization to a flame graph to inspect rendering hotspots.
