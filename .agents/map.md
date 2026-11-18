# Conceptual Map: Unciv (Civ IV BasicSquare Engine)

## 1. System Overview
- **Core Domain**: Open-source 4X turn-based strategy game engine and desktop/mobile client written in Kotlin on LibGDX, implementing Civilization IV mechanics on an 8-direction square grid via the `BasicSquare` base ruleset.
- **Tech Stack & Runtime**: Kotlin 2.4.21, JDK 25, LibGDX, Gradle multi-project (`core`, `desktop`, `android`, `server`, `tests`), Detekt 2.0 static analysis, Purity annotations (`yairm210.purity.annotations`).
- **Primary Artifacts**: Desktop application (`desktop`), Android APK (`android`), headless AI Monte Carlo simulator (`ConsoleLauncher`), and JUnit test suite (`tests`).

## 2. Subsystem Directory & Ownership
| Directory / Subsystem | Primary Responsibility | Key Files & Entry Points |
| :--- | :--- | :--- |
| `core/src/com/unciv/logic/civilization/` | Civilization state, diplomacy, civics, religion, tech, turn lifecycle | `Civilization.kt`, `managers/CivicManager.kt`, `managers/Civ4ReligionManager.kt`, `managers/TechManager.kt`, `managers/TurnManager.kt`, `diplomacy/DiplomacyManager.kt` |
| `core/src/com/unciv/logic/city/` | City economy, local happiness, health, fat-cross borders, hurry production | `City.kt`, `CityStats.kt`, `CityConstructions.kt`, `managers/CityExpansionManager.kt`, `managers/CityPopulationManager.kt`, `managers/CityTurnManager.kt` |
| `core/src/com/unciv/logic/battle/` | Stack combat, best-defender selection, collateral damage, max damage cap | `Battle.kt`, `BattleDamage.kt`, `TargetHelper.kt`, `MapUnitCombatant.kt`, `CityCombatant.kt` |
| `core/src/com/unciv/logic/map/` | Square grid coordinate math, pathfinding, tiles, stacked units, siege/religion actions | `GridMath.kt`, `TileMap.kt`, `tile/Tile.kt`, `mapunit/MapUnit.kt`, `mapunit/movement/UnitMovement.kt`, `mapunit/actions/UnitActionsSiege.kt`, `mapunit/actions/UnitActionsReligion.kt` |
| `core/src/com/unciv/logic/automation/` | AI decision-making across civs, cities, military stacks, siege, workers, missionaries | `Automation.kt`, `civilization/NextTurnAutomation.kt`, `civilization/UseGoldAutomation.kt`, `city/ConstructionAutomation.kt`, `unit/UnitAutomation.kt`, `unit/BattleHelper.kt`, `unit/HeadTowardsEnemyCityAutomation.kt`, `unit/WorkerAutomation.kt`, `unit/ReligiousUnitAutomation.kt`, `unit/SpecificUnitAutomation.kt` |
| `core/src/com/unciv/view/` | Read-only player-perspective view wrappers and action gates for UI and AI | `View.kt`, `GameView.kt`, `CivView.kt`, `ForeignCivView.kt`, `CityView.kt`, `ForeignCityView.kt`, `TileMapView.kt`, `TileView.kt`, `MapUnitView.kt`, `AttackableTileView.kt` |
| `core/src/com/unciv/models/ruleset/` | Ruleset loading, Civics, Units, Buildings, Improvements, Uniques, ModOptions | `Ruleset.kt`, `Civic.kt`, `ModOptions.kt`, `ModConstants.kt`, `Victory.kt`, `unique/UniqueType.kt`, `unique/Unique.kt`, `validation/ModCompatibility.kt` |
| `core/src/com/unciv/ui/` | LibGDX Scene2D screens, square-grid map rendering, picker screens, stack menus | `screens/worldscreen/WorldScreen.kt`, `screens/worldscreen/unit/UnitStackContextMenu.kt`, `screens/cityscreen/CityScreen.kt`, `screens/pickerscreens/CivicPickerScreen.kt`, `screens/pickerscreens/TechLineRouter.kt`, `components/tilegroups/TileGroupMap.kt` |
| `android/assets/jsons/BasicSquare/` | Built-in Civ IV base ruleset JSON definitions | `ModOptions.json`, `Civics.json`, `Religions.json`, `Units.json`, `Buildings.json`, `Techs.json`, `TileImprovements.json`, `Terrains.json`, `Nations.json`, `Personalities.json`, `VictoryTypes.json` |

## 3. Core Domain Types & Contracts
- **`GameInfo`** (`core/src/com/unciv/logic/GameInfo.kt`): Root serializable game state holding `civilizations`, `tileMap`, `ruleset`, `gameParameters`, and `turns`. Exposes `isReligionEnabled()` and `isCiv4ReligionEnabled()`.
- **`Civilization`** (`core/src/com/unciv/logic/civilization/Civilization.kt`): Owns `tech: TechManager`, `policies: PolicyManager`, `civics: CivicManager`, `civ4Religion: Civ4ReligionManager`, `religionManager: ReligionManager`, `units: UnitManager`, `cities: List<City>`, `anarchyTurns: Int`.
- **`CivicManager`** (`core/src/com/unciv/logic/civilization/managers/CivicManager.kt`): Manages active Civ IV civics per `CivicCategory`, calculates civic upkeep (`getCivicUpkeep()`), enforces revolution anarchy (`switchCivics()`), and runs AI civic evaluation (`evaluateCivic()`, `automateCivics()`).
- **`Civ4ReligionManager`** (`core/src/com/unciv/logic/civilization/managers/Civ4ReligionManager.kt`): Manages tech-founded holy cities (`foundReligion()`), state religion (`stateReligion`, `setStateReligion()`), passive trade-network religion spread (`processPassiveSpread()`), and AI state religion selection (`processAiStateReligion()`).
- **`City`** (`core/src/com/unciv/logic/city/City.kt`): Owns `population: CityPopulationManager`, `cityConstructions: CityConstructions`, `cityStats: CityStats`, `expansion: CityExpansionManager`, `presentReligions: HashSet<String>`, `holyCityOfReligions: HashSet<String>`, `defenseDamageTaken: Int`, `whipAngerTurns: Int`, `cityGreatPersonPoints: Counter<String>`. Defines the 21-tile Fat Cross via `isTileInWorkRange(tile)` and health breakdown via `getHealthBreakdown()`.
- **`Tile`** (`core/src/com/unciv/logic/map/tile/Tile.kt`): Orthogonal square grid tile with 8 neighbors (`GridMath`, Chebyshev distance = 1), `militaryUnits: ArrayList<MapUnit>`, `civilianUnit: MapUnit?`, `airUnits: ArrayList<MapUnit>`, `improvementWorkedTurns: Int`, `tileCulture: HashMap<String, Int>`, `hasRightRiver: Boolean`, `hasBottomRiver: Boolean`.
- **`GameView` & View Hierarchy** (`core/src/com/unciv/view/`): Wraps mutable domain objects (`Civilization`, `City`, `Tile`, `MapUnit`) in player-perspective `@Readonly` views so UI and AI cannot accidentally read fog-of-war hidden state or mutate state outside explicit `try*` action methods.

## 4. Primary Data Flows & Lifecycles
- **Turn Lifecycle** (`TurnManager.kt`):
  1. `TurnManager.startTurn()`: Initializes default civics (`civics.initDefaults()`), runs AI gold-to-science slider (`NextTurnAutomation.automateGoldToSciencePercentage`), refreshes `CityTurnManager.startTurn()`, resets unit movement.
  2. `TurnManager.automateTurn()` -> `NextTurnAutomation.automateCivMoves(civInfo)`: Executes diplomacy, trade, civic adoption (`civics.automateCivics()`), tech selection, gold usage & gold-hurry (`UseGoldAutomation.useGold`), unit movement & stack combat (`automateUnits`, `automateCityConquer`, `UnitAutomation.automateUnitMoves`), city production selection (`ConstructionAutomation.chooseNextConstruction`), Slavery pop-whipping (`automateSlaveryWhip`), settler training, and diplomatic voting.
  3. `TurnManager.endTurn()`: If `civInfo.isInAnarchy()`, skips yields/science/production and decrements `anarchyTurns`. Otherwise advances tech research, city turn end (`CityTurnManager.endTurn()` — healing `defenseDamageTaken`, decrementing `whipAngerTurns`, spawning per-city Great People at 100 GPP, advancing `improvementWorkedTurns` for worked `Cottage -> Hamlet -> Village -> Town` tiles, expanding cultural rings in `CityExpansionManager.nextTurn()`), and `civ4Religion.endTurn()`.
- **Stack Combat & Siege Lifecycle** (`UnitActionsSiege.kt`, `TargetHelper.kt`, `Battle.kt`, `BattleDamage.kt`):
  1. Siege units adjacent to an enemy city with `city.getCityDefenseBonus() > 0` invoke `UnitActionType.Bombard` (`UnitActionsSiege.getBombardActions`) to increase `city.defenseDamageTaken`.
  2. When attacking a tile under `UniqueType.Civ4StackCombat`, `Battle.getMapCombatantOfTile(tile, attacker)` selects `Battle.getBestDefender(tile, attacker)` (even on city centers if `tile.militaryUnits.isNotEmpty()`). Undefended cities fall immediately on melee attack.
  3. Primary defender damage is clamped by `UniqueType.MaxAttackDamage`, and `Battle.applyCollateralDamage` deals splash damage to up to `N` additional military units in `attackedTile.militaryUnits`.

## 5. Entry Points & Registries
- **Desktop Entry Point**: `desktop/src/com/unciv/app/desktop/DesktopLauncher.kt` (also runs `UniqueDocsWriter` and `UiElementDocsWriter` when `!isRunFromJAR`).
- **Headless AI Simulation Entry Point**: `desktop/src/com/unciv/app/desktop/ConsoleLauncher.kt` -> `core/src/com/unciv/logic/simulation/Simulation.kt`.
- **Unit Actions Registry**: `core/src/com/unciv/logic/map/mapunit/actions/UnitActions.kt` (`actionTypeToFunctions` map, including `UnitActionType.Bombard` and `UnitActionType.SpreadReligion`).
- **Ruleset Registry**: `core/src/com/unciv/models/ruleset/Ruleset.kt` (`RulesetFile` enum loading `android/assets/jsons/BasicSquare/*.json`).

## 6. Architectural Invariants & Gotchas
- **Serialization Contract (`IsPartOfGameInfoSerialization`)**: LibGDX `Json` requires zero-arg constructors, concrete collection types (`ArrayList`, `HashMap`, never `List`/`Map` interfaces on serialized fields), and `@delegate:Transient` on all `by lazy` properties. Transient back-references (`civInfo`, `city`, `ruleset`) must be restored in `setTransients()`.
- **ModOptions Feature Flags**: Civ IV mechanics are controlled by `ModOptions` uniques (`Civ4StackCombat`, `AllowUnitStacking`, `CivicsMode`, `CityHealthMode`, `Civ4ReligionMode`, `ConvertGoldToScience`) and boolean properties (`useCiv4LocalHappiness = true`, `useCiv4CultureBorders = true`) plus `ModConstants` (`cityWorkRange = 2`, `cityWorkRangeExcludeCorners = true`).
- **Zero-Regex Unique Parsing**: `Unique` placeholder extraction (`TranslationExtensions.parsePairs`) uses deterministic character-by-character bracket scanning, never regular expressions.
- **Asset-Only Modding & Offline Status**: `ModCompatibility.isAssetOnlyMod` restricts external mods to audiovisual/skin assets (excluding rulesets and tilesets), and `PlatformSpecific.supportsOnlineMultiplayer` is `false`.
