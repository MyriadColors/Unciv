# Project structure

Since LibGDX, and therefore Unciv, are built for multi-platform support, the project structure is built accordingly.

99% of the code is in the [core](https://github.com/yairm210/Unciv/tree/master/core) project, which contains all the platform-independent code.

The [desktop](https://github.com/yairm210/Unciv/tree/master/desktop) and [android](https://github.com/yairm210/Unciv/tree/master/android) folders contain platform-specific things, and the Android folder also contains the game Images (`android/Images.*`) and the all-important Assets (`android/assets/`), which are required for running from Desktop as well, so we bundle them up into the `.jar` file when releasing.

The [tests](https://github.com/yairm210/Unciv/tree/master/tests) folder contains tests that can be run manually via Gradle with `./gradlew tests:test`, and are run automatically by GitHub Actions on every push and pull request.

The [server](https://github.com/yairm210/Unciv/tree/master/server) folder contains the sources for `UncivServer`, which is packaged into its own separate jar.

## Translations

Before we get to the Classes, a word on Languages. Unciv is playable in several handfuls of languages, and there's magic to support that. Whenever you include a new string in code you will need to give it a quick evaluation - will users see it, and if so, what do I need to do to support its translations. Sometimes you may not need to do anything, sometimes you will add a line to the [translation templates](https://github.com/yairm210/Unciv/blob/master/android/assets/jsons/translations/template.properties), and sometimes you will adapt the string formatting to support the translations. For details, see [the 'Translation generation - for developers' chapter](../Translating/Translation-generation.md).

## Major classes

Civ, and therefore Unciv, is a game with endless interconnectivity - everything affects everything else.

In order to have some semblance of order, we'll go over the main classes in the order in which they are serialized.

So yes, you can - for instance - get the center tile of a city, a `Tile`, directly from `City`. But delving into all the connections would only harm the point of this overview, that's what the actual code is for ;)

The Game State (`com.unciv.logic`):

- `GameInfo`
  - `Civilization`
    - `City`
  - `TileMap` (8-direction square grid using `GridMath` and `GridCoord`)
    - `Tile`
      - `MapUnit` (`militaryUnits: ArrayList<MapUnit>`, `civilianUnit: MapUnit?`, `airUnits: ArrayList<MapUnit>`)
  - `Ruleset` (unique in that it is not part of the serialized save state)

The View / Purity Layer (`com.unciv.view`):

- `GameView`
  - `CivView` / `ForeignCivView`
  - `CityView` / `ForeignCityView`
  - `TileMapView` / `TileView`
  - `MapUnitView` / `ForeignMapUnitView`
  - `CombatantView` / `AttackableTileView` / `DiplomacyManagerView`

The UI (`com.unciv.ui`):

- `MainMenuScreen`
- `NewGameScreen`
- `WorldScreen`
- `CityScreen`
- `MapEditorScreen`
- Picker Screens - `TechPickerScreen` (with `TechLineRouter`), `CivicPickerScreen`, `PolicyPickerScreen`, `ImprovementPickerScreen`, `PromotionPickerScreen`, `PantheonPickerScreen`, `ReligiousBeliefsPickerScreen`, `GreatPersonPickerScreen`, `DiplomaticVotePickerScreen`

## Game State

### The Game - `GameInfo`

First off, let's clarify: When we say "The Game", we mean the *state* of the game (what turn it is, who the players are, what each one has etc) and not the *UI* of the game.

That is, The Game is the *currently played* game, not *Unciv*.

The game contains four major parts:

- The list of the players, or civilizations - `List<Civilization>`
- The map upon which the game is played - `TileMap`
- The ruleset by which the game is played - `Ruleset`. This includes what technologies, civics, buildings, units etc. are available, and IS NOT serialized and deserialized, but comes straight from the game files - more on that later.
- Parameters unique to this game (`GameParameters`) - difficulty, game speed, victory conditions, etc.

When we save the game, or load the game, we're actually serializing and deserializing this class, which means that this class is the root of the entire game state.

Most objects in the "state tree" have a transient reference to their parent, meaning the tree can be traversed in-code in all directions, and frequently is.

### A Civilization - `Civilization`

This represents one of the players of the game, and NOT a specific nation - meaning, not France, but rather "Player X who is France in this game". In another game, there will be another France.

As one of the focal points of the game, it contains a lot of important information, the most important of which are:

- The list of cities the civilization has - `List<City>`
- Which nation this is - references a certain `Nation` (part of the ruleset)
- Various Managers for the different aspects of the civilization:
  - `TechManager` (`tech`)
  - `CivicManager` (`civics` — Civ IV civics, upkeep, and revolution anarchy)
  - `Civ4ReligionManager` (`civ4Religion` — Civ IV tech-founded holy cities, state religion, and passive trade-network spread)
  - `PolicyManager` (`policies`) and `ReligionManager` (`religionManager`)
  - `UnitManager` (`units`), `TurnManager`, `GoldenAgeManager` (`goldenAges`), `GreatPersonManager` (`greatPeople`), `VictoryManager` (`victoryManager`), `EspionageManager` (`espionageManager`), `QuestManager` (`questManager`), `RuinsManager` (`ruinsManager`), and `diplomacy: HashMap<String, DiplomacyManager>`

### A City - `City`

This contains the information about a specific city.

Beyond basic information like name, location on map, local health (`getHealthBreakdown()`), local angry citizens (`getAngryCitizens()`), `whipAngerTurns`, `defenseDamageTaken`, `presentReligions`, `holyCityOfReligions`, and `cityGreatPersonPoints`, the most important classes it contains are:

- Calculating the yield, maintenance, trade routes, health penalty, and local happiness of the city - `CityStats`
- Managers for the various aspects - `CityPopulationManager` (`population`), `CityConstructions` (`cityConstructions`), `CityExpansionManager` (`expansion`), `CityReligionManager` (`religion`), `CityEspionageManager` (`espionage`), and `CityTurnManager`
- The tiles controlled and worked by the city (within the 21-tile Civ IV Fat Cross defined by `City.isTileInWorkRange`) - only their locations are permanently saved in `City`, the actual information is in the `Tile` in the `TileMap`

### The map - `TileMap` & `GridMath`

`TileMap` acts as a wrapper and spatial index over the orthogonal 8-direction square grid (`GridMath`, `GridCoord`, `NeighborDirection`, and `PathingMap`).

### A tile - `Tile`

Each tile is comprised of several layers, and so has information for each.

Tiles have, primarily:

- A base terrain - Grassland, Plains, Desert, Tundra, Coast, Ocean, etc. References a `Terrain` (part of the ruleset)
- Terrain features - `terrainFeatures: List<String>` (Forest, Jungle, Flood plains, Oasis, Fallout, etc.) and `naturalWonder: String?`
- Square-edge rivers - `hasRightRiver: Boolean` and `hasBottomRiver: Boolean`
- An optional resource - Iron, Copper, Wheat, Dye, etc. References a `TileResource` (part of the ruleset)
- An improvement built on the tile, if any (`improvement: String?`, `improvementWorkedTurns: Int` for upgrading improvements like `Cottage -> Hamlet -> Village -> Town`), and `roadStatus`
- Per-civilization tile culture (`tileCulture: HashMap<String, Int>`) used for Civ IV cultural border flipping
- The units that are currently on the tile:
  - `militaryUnits: ArrayList<MapUnit>` (supporting unlimited friendly unit stacking under `UniqueType.AllowUnitStacking`)
  - `civilianUnit: MapUnit?`
  - `airUnits: ArrayList<MapUnit>`

### A unit on the map - `MapUnit`

Unlike buildings, Unit in Unciv has two meanings. One is a *Type* of unit (like Spearman), and one is a specific instance of a unit (say, a Babylonian Spearman, at a certain position, with X health).

`MapUnit` is a specific instance of a unit, whereas `BaseUnit` is the type of unit.

Main information:

- A name - references a specific `BaseUnit`
- Health and Movement (`UnitMovement`)
- Promotion status - `UnitPromotions`
- Unit actions - `UnitActions` (`UnitActionsSiege`, `UnitActionsReligion`, `UnitActionsPillage`, `UnitActionsGreatPerson`, etc.)

### Ruleset - `Ruleset`

Since ruleset definitions remain the same for every game, they are not saved in the save file, but rather loaded from JSON files in `android/assets/jsons/BasicSquare/`.

Each class in the game state references ruleset objects by name:

- `Technology` (`Techs.json`) - referenced in `Civilization.tech`
- `CivicCategory` & `Civic` (`Civics.json`) - referenced in `Civilization.civics`
- `Nation` (`Nations.json`) & `Personality` (`Personalities.json`) - referenced in `Civilization`
- `PolicyBranch` & `Policy` (`Policies.json`) - referenced in `Civilization.policies`
- `Building` (`Buildings.json`) & `Specialist` (`Specialists.json`) - referenced in `City.cityConstructions` and `City.population`
- `BaseUnit` (`Units.json`), `UnitType` (`UnitTypes.json`), `Promotion` (`UnitPromotions.json`), `UnitNameGroup` (`UnitNameGroups.json`) - referenced in `MapUnit`
- `Terrain` (`Terrains.json`), `TileResource` (`TileResources.json`), `TileImprovement` (`TileImprovements.json`), `RuinReward` (`Ruins.json`) - referenced in `Tile`
- `Era` (`Eras.json`), `Speed` (`Speeds.json`), `Difficulty` (`Difficulties.json`), `Victory` (`VictoryTypes.json`), `ModOptions` (`ModOptions.json`), `GlobalUniques` (`GlobalUniques.json`)

## The View & Purity Layer (`com.unciv.view`)

To prevent UI screens and AI automation (`com.unciv.logic.automation`) from accidentally bypassing fog-of-war visibility rules or mutating domain state during read-only evaluations, `com.unciv.view` provides player-perspective wrappers (`GameView`, `CivView`, `ForeignCivView`, `CityView`, `ForeignCityView`, `TileMapView`, `TileView`, `MapUnitView`, `ForeignMapUnitView`, `AttackableTileView`, `CombatantView`, `DiplomacyManagerView`).

All read queries on views are annotated with `@Readonly` (verified at compile time by the Purity compiler plugin), while state mutations go through explicit `try*` action methods. See [AI & Automation Architecture](./AI-Architecture.md) for details on how AI uses the view layer.

## UI

`UncivGame` is the 'base' class for the UI, from which everything starts, but it itself doesn't do much.

When we change a screen, we're changing a value in `UncivGame`, the interesting stuff happens in the screens themselves.

### The main menu - `MainMenuScreen`

This is what the user sees when first entering the game. It acts as a hub to loading games, managing asset mods, options etc, without loading an actual game upfront.

### Starting a new game - `NewGameScreen`

Configures `GameSetupInfo` (`GameParameters` and `MapParameters`), divided into:

- `GameOptionsTable` - difficulty, game speed, victory types, etc.
- `MapOptionsTable` / `MapParametersTable` - either from a preexisting map file or generated square map (`MapSize`, `MapGenerator`).
- `PlayerPickerTable` - which civilizations are in the game and who controls them.

### The World Screen - `WorldScreen`

90% of the game is spent on this screen, so naturally it's the fullest, with the most things happening.

Most notable are:

- The map itself - `WorldMapHolder` (wrapping `TileGroupMap<WorldTileGroup>`) - with each rendered tile being a `WorldTileGroup`, plus `UnitStackContextMenu` for selecting/grouping stacked units
- The information panels - `WorldScreenTopBar` for stats and resources, `UnitTable` for the currently selected unit(s), `UnitActionsTable`, `TileInfoTable` for the selected tile, `BattleTable` for combat preview, and `NotificationsScroll`
- The minimap - `MinimapHolder`
- `TechPolicyDiplomacyButtons` linking to `TechPickerScreen`, `CivicPickerScreen` (or `PolicyPickerScreen`), and `EmpireOverviewScreen`
- `NextTurnButton`

### The city screen - `CityScreen`

The second-most important screen.

Notable parts:

- `CityStatsTable` - displays city yields, health breakdown (`Health: X / Y`), happiness, buildings, and specialists (`SpecialistAllocationTable`, `CitizenManagementTable`)
- `CityConstructionsTable` (bottom left) - available constructions and production queue
- `CityScreenTileTable` and `ConstructionInfoTable` (bottom right) - selected tile or construction details
- `CityMapHolder` - interactive view of the city's 21-tile Fat Cross
