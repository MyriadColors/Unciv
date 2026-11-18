# AI & Automation Architecture

Unciv's AI and automation layer lives in `core/src/com/unciv/logic/automation/` (with civilization-level managers in `core/src/com/unciv/logic/civilization/managers/`). It drives both AI civilizations during `TurnManager.automateTurn()` and automated units/cities for human players.

---

## 1. The `com.unciv.view` Purity Boundary

Most automation classes operate on read-only view wrappers from `core/src/com/unciv/view/` rather than mutating domain objects (`Civilization`, `City`, `Tile`, `MapUnit`) directly:

- **`GameView` & `CivView`**: Player-perspective view of the game and active civilization.
- **`ForeignCivView` & `ForeignCityView`**: Information visible to the viewing civilization about foreign empires and cities (respecting exploration and espionage).
- **`CityView`**: Self-owned (or spied) city view exposing stats, health (`getHealthBreakdown()`), local happiness (`getAngryCitizens()`, `getWhipAngerTurns()`), religion (`getPresentReligions()`), and 21-tile Fat Cross workable tiles (`getWorkableTiles()`).
- **`TileView` & `MapUnitView`**: Enforce fog-of-war visibility (`getVisibleUnits()`, `getCombatant()`) and expose state-changing actions exclusively via `try*` methods (`tryMoveToTile`, `tryHeadTowards`, `tryInvokeUnitAction`, `attackOrNuke`).
- **`AttackableTileView`**: Wraps `AttackableTile` (produced by `TargetHelper.getAttackableEnemies`), pairing the attack origin tile, target tile, remaining movement, and the matchup-specific `Battle.getBestDefender` combatant (`getCombatant()`).

---

## 2. Turn Execution Pipeline (`NextTurnAutomation.kt`)

`NextTurnAutomation.automateCivMoves(civInfo, tradeAndChangeState)` executes the top-level AI turn sequence:

1. **Barbarians & Spectators**: Early-outs to `BarbarianAutomation` or returns immediately for spectators.
2. **Alerts & Diplomacy** (`DiplomacyAutomation`, `TradeAutomation`): Responds to demands and trade offers, evaluates war declarations, peace treaties, open borders, defensive pacts, and luxury exchanges.
3. **Policies & Civics**:
   - Calls `adoptPolicy(civView)` when Civ V policies are available.
   - Calls `civInfo.civics.automateCivics()` when `UniqueType.CivicsMode` is active.
4. **Tech & Gold Slider**:
   - `TurnManager.startTurn()` runs `automateGoldToSciencePercentage(civView)` when `UniqueType.ConvertGoldToScience` is enabled.
   - `chooseTechToResearch(civView)` selects the next technology.
5. **Gold Spending & Hurry Production** (`UseGoldAutomation.useGold`):
   - Upgrades military units (`UnitAutomation.tryUpgradeUnit`).
   - Under `UniqueType.CanHurryProductionWithGold` (Universal Suffrage), finishes in-progress city constructions with gold (`hurryConstructionWithGold()`) when treasury exceeds safety reserves.
   - Purchases constructions or tiles when applicable.
6. **Unit Automation** (`automateUnits`):
   - Sorts all units by `getUnitPriority(unit, isAtWar, cache)` (ascending: Settlers `1`, Civilians `2`, Air units `2..5`, Siege/Collateral units `+5`, Ranged `+10`, Melee `+30`, Great Generals `+100`).
   - Applies unit promotions (`applyPromotions`), coordinates settler escorts (`automateSettlerEscorting`), executes coordinated city sieges (`automateCityConquer`), and moves remaining units via `UnitAutomation.automateUnitMoves`.
7. **Espionage & City Production** (`EspionageAutomation`, `automateCities`):
   - Assigns spies, evaluates city annexing, and invokes `city.cityConstructions.chooseNextConstruction()` (`ConstructionAutomation`).
8. **Slavery Whip & Expansion**:
   - Invokes `automateSlaveryWhip(civInfo)` when `UniqueType.CanWhipProduction` is active.
   - Evaluates `trainSettler(civView)` and diplomatic victory voting (`tryVoteForDiplomaticVictory`).

---

## 3. Civics & Hurry Production AI

### Live Empire-State Civic Evaluation (`CivicManager.kt`)
- `CivicManager.evaluateCivic(civic)` dynamically scores every `Unique` on a `Civic` against the civilization's current empire state:
  - **Per-City & Capital Modifiers**: Scales `Stats` and `StatPercentBonusCities` by actual city yields (e.g., `Bureaucracy` `+50% Production/Gold in capital`, `Free Speech` `+100% Culture`).
  - **Specialist, Building & Improvement Synergies**: Counts matching specialists (`Representation`), buildings (`Nationhood` Barracks), and tile improvements (`Universal Suffrage` / `Free Speech` Towns, `Caste System` / `State Property` Workshops and Watermills, `Environmentalism` Windmills and Forest Preserves).
  - **Upgrading Improvements**: Scores `ImprovementGrowthRate` (`Emancipation`) by the count of owned tiles with `UniqueType.ImprovementUpgradesAfterTurns` (`Cottage`, `Hamlet`, `Village`).
  - **Health & Religion**: Weights `CityHealth` (`Environmentalism`) by the number of cities near or below their health cap, and evaluates `HappinessPerPresentReligion` / `NoStateReligion` (`Free Religion`) against state-religion civics (`Organized Religion`, `Theocracy`, `Pacifism`).
  - **Upkeep & Anarchy Batching**: Subtracts `civic.upkeep.costMultiplier * (0.4f * numCities + 0.1f * totalPop)` (adjusted by `CivicUpkeepDiscount` and treasury pressure). `automateCivics()` batches desired changes across all 5 categories into a single `switchCivics(desiredCivics)` call when the total score gain exceeds the anarchy cost threshold (`expectedAnarchy * 4f`, or `0` with `UniqueType.NoAnarchy` / Golden Age).

### Slavery Whip (`NextTurnAutomation.automateSlaveryWhip`) & Gold Hurry (`UseGoldAutomation.useGold`)
- **Slavery (`UniqueType.CanWhipProduction`)**:
  - **Emergency Wartime Whip**: Whips a military defender immediately (`popCost <= 2`) if enemy military units are within 3 tiles of the city.
  - **Economic / Angry-Citizen Whip**: When `city.whipAngerTurns == 0`, whips the current construction if `city.getAngryCitizens() >= popCost` (culling non-working angry citizens) or if `city.population.population >= 4`, local `netHappiness >= 1f`, and `popCost == 1`.
- **Gold Hurry (`UniqueType.CanHurryProductionWithGold`)**:
  - Hurries the active non-perpetual construction when `civ.gold - hurryCost >= reserveGold` (`50` at war, `150` at peace).

---

## 4. Civ IV Religion, Missionaries & Great People AI

- **Dynamic State Religion Switching (`Civ4ReligionManager.processAiStateReligion`)**:
  - Scores each religion present in the empire by `cityCount + (2 if ownsHolyCity)`.
  - Adopts the highest-scoring religion when `stateReligion == null`, and switches state religion when a rival religion exceeds the current state religion's score by `>= 2` (`>= 1` if `NoAnarchy` or in a Golden Age).
- **Missionary Construction (`ConstructionAutomation.addMissionaryChoice`)**:
  - When `civInfo.isCiv4ReligionEnabled()` and `stateReligion != null`, queues a unit with `UniqueType.CanSpreadCiv4Religion` (`Missionary`) if domestic cities or friendly Open-Borders foreign cities lack `stateReligion` and fewer than 2 missionaries are active.
- **Missionary Movement (`ReligiousUnitAutomation.automateMissionary`)**:
  - `MapUnitView.maySpreadReligion()` checks `UniqueType.CanSpreadCiv4Religion` when `isCiv4ReligionEnabled()`.
  - Targets unconverted domestic cities first (nearest by distance), then unconverted Open-Borders foreign cities where `canSpreadReligionToCity` is true, moving adjacent/into the city and invoking `UnitActionType.SpreadReligion`.
- **Great Person Multi-Unique Placement (`SpecificUnitAutomation.automateImprovementPlacer`)**:
  - Evaluates all `UniqueType.ConstructImprovementInstantly` uniques on a Great Person (e.g., `Religious Shrine` and `Great Prophet Specialist`), prioritizing Holy Cities and the capital for Shrine improvements, with a fallback to any workable land tile if no tile exceeds average base terrain stats.

---

## 5. Stack Warfare, Siege Bombardment & Collateral Combat AI

When `UniqueType.AllowUnitStacking` and `UniqueType.Civ4StackCombat` are active:

- **Siege Recognition (`BaseUnit.isProbablySiegeUnit`)**:
  - Recognizes units with `UniqueType.CanBombardCityDefenses` or `UniqueType.CollateralDamage` (such as `BasicSquare` Catapult, Cannon, Artillery, Frigate, Ironclad, Destroyer, Battleship) in addition to Civ V ranged city-bonus siege units.
- **Stack Staging & Rallying (`HeadTowardsEnemyCityAutomation`)**:
  - `cannotTakeCitySoon()` compares total nearby friendly stack strength (with a `1.5x` multiplier for siege units against multi-unit defenders) against the combined strength of `city.getCenterTile().militaryUnits` scaled by `1f + city.getCityDefenseBonus() / 100f`.
  - When additional forces are needed, `headToLandingGrounds()` rallies attacking units onto a single shared staging tile 3–5 tiles outside the enemy city (preferring tiles already occupied by friendly military units).
- **Strip Defenses First (`UnitAutomation.tryBombardCityDefenses`)**:
  - Siege units move first among land/naval military units (`getUnitPriority` offset `+5`).
  - Before attacking, a unit with `UniqueType.CanBombardCityDefenses` adjacent to an enemy city with `city.getCityDefenseBonus() > 0` and defending military units invokes `UnitActionType.Bombard` (`UnitActionsSiege`) to strip cultural/building city defense bonuses.
- **Best-Defender Combatant & `MaxAttackDamage` (`TargetHelper`, `TileView`, `BattleDamage`, `BattleHelper`)**:
  - `TargetHelper.getAttackableEnemies` passes `MapUnitCombatant(unit)` to `Battle.getMapCombatantOfTile(tile, attacker)` so `AttackableTileView.getCombatant()` always reflects the matchup-specific `Battle.getBestDefender`.
  - `BattleDamage.calculateDamageToDefender` clamps estimated damage when the attacker has `UniqueType.MaxAttackDamage` so siege units do not attack defenders already at their damage cap expecting a kill.
  - `BattleHelper.tryAttackNearbyEnemy` allows units with `UniqueType.CollateralDamage` to make sacrificial collateral attacks (`canMakeSacrificialCollateralAttack`) against stacks of `>= 3` enemy military units when the primary defender can take damage and at least 2 friendly military units are within 2 tiles to follow up.
  - `BattleHelper.getCityAttackValue` scores undefended enemy cities (`militaryUnits.isEmpty()`) at `10000` for immediate melee capture, and adds `+25` per extra collateral target (`getCollateralDamageBonus`) when scoring stacked tiles.
- **Multi-Unit Garrisons (`UnitAutomation.tryGarrisoningLandUnit`)**:
  - Allows stacking up to 2–3 defenders in the capital and wartime border cities when `AllowUnitStacking` is active and total army size permits.

---

## 6. Worker AI, Cottage Maturation, Forest Health & 21-Tile Fat Cross

- **21-Tile Fat Cross (`WorkerAutomation`, `CityLocationTileRanker`)**:
  - `WorkerAutomation.isAutomationWorkableTile`, `getImprovementRanking`, and `tryHeadTowardsUndevelopedCity` use `city.isTileInWorkRange(tile)` and `city.getWorkableTiles()` (excluding the 4 outer corners `dx + dy > 2 * range - 1` when `cityWorkRangeExcludeCorners = true`).
  - `CityLocationTileRanker.rankTileToSettle` excludes the 4 unworkable `(±2, ±2)` corners when scoring settlement sites.
- **Upgrading Improvement Valuation & Preservation (`WorkerAutomation`)**:
  - `isPartOfUpgradeChain` identifies any improvement that has `UniqueType.ImprovementUpgradesAfterTurns` or is the target of one (`Cottage -> Hamlet -> Village -> Town`).
  - `shouldPreserveUpgradingImprovement` prevents automated workers from overwriting an existing unpillaged upgrading improvement unless a newly revealed Strategic or Luxury resource requires a resource-specific improvement.
  - `getUpgradeChainBonus` walks the upgrade chain to compute a discounted future yield bonus (accelerated by `UniqueType.ImprovementGrowthRate` and adjusted for `UniqueType.ConvertGoldToScience`), dampening cottage priority when the owning city's food surplus is `< 2` on low-food terrain so workers build Farms first when food is needed.
- **Forest Health Awareness (`WorkerAutomation`)**:
  - Under `UniqueType.CityHealthMode`, every 2 Forests in city range grant `+1 Health` (`City.getHealthBreakdown()`). When an owning city has `netHealth <= 0`, `WorkerAutomation` suppresses the `+0.5` forest-chop tempo priority and applies a `-1 Food` penalty in `getImprovementRanking` for non-resource forest removals.
- **Citizen Tile Assignment & Local Caps (`Automation.kt`, `ConstructionAutomation.kt`)**:
  - `Automation.rankStatsForCityWork` adds a `+12f` maturation bonus to unpillaged tiles with `UniqueType.ImprovementUpgradesAfterTurns` when the city is not starving.
  - `Automation.getFoodModWeight` checks `city.getAngryCitizens() > 0` (returning `0f`) and `netHappiness <= 0f` (returning `0.5f`) when `ModOptions.useCiv4LocalHappiness` is enabled, and scales growth by `0.75f` when `netHealth < 0` in `CityHealthMode`.
  - `ConstructionAutomation.getBuildingStatsFromUniques` proactively values `UniqueType.CityHealth` and `UniqueType.NoBuildingUnhealthiness` when a city's `netHealth <= 2`.
