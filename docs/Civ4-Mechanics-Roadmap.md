# Civilization IV Mechanics Architecture & Roadmap (`BasicSquare`)

This document serves as a persistent engineering reference for the Civilization IV mechanics in this Unciv fork (`BasicSquare` ruleset). It tracks what is already implemented and specifies the design, data models, engine touchpoints, and ruleset integration for all remaining Base Civ IV, *Warlords*, and *Beyond the Sword* systems.

---

## 1. Current Baseline (Already Implemented)

The following Civ IV systems are implemented and verified in `core/` and `android/assets/jsons/BasicSquare/`:

| System | Key Files |
| :--- | :--- |
| **8-Direction Square Grid & Fat Cross Borders** | `Tile.kt`, `CityExpansionManager.kt` |
| **Unit Stacking, Best Defender, Collateral Damage, Siege Max-Damage Cap** | `Battle.kt`, `Tile.kt`, `Units.json` |
| **First Strikes, Immune to First Strikes, Mounted Flanking vs. Siege** | `Battle.kt`, `UniqueType.kt`, `Units.json` |
| **City Defense % & Siege Bombardment** | `City.kt`, `UnitActionsSiege.kt`, `BattleDamage.kt` |
| **5-Category Civics, Civic Upkeep & Anarchy** | `CivicManager.kt`, `Civics.json` |
| **Slavery Whipping, Universal Suffrage Gold-Rushing, Nationhood Drafting (+ UI & AI)** | `CityConstructions.kt`, `CityConstructionsTable.kt`, `CivicManager.kt` |
| **Tech-Founded State Religions, Holy Cities, Passive Spread** | `Civ4ReligionManager.kt`, `Religions.json` |
| **City Health & Excess Unhealthiness Food Penalty** | `City.kt`, `CityStats.kt` |
| **Local Happiness (Base, Pop, Whip/Draft Anger, Luxuries, Military Police, War Weariness, Motherland)** | `CityStats.kt`, `Civilization.kt`, `Buildings.json` (`Jail`, `Mount Rushmore`) |
| **Automatic Trade Routes (Dynamic Slots, Strict Connectivity, Foreign & Overseas Multipliers)** | `CityStats.kt`, `Techs.json`, `Civics.json`, `Buildings.json` |
| **Cultural Revolts & Peaceful City Flipping** | `CityExpansionManager.kt`, `City.kt` |
| **Auto-Upgrading Improvements (`Cottage -> Town`), Forest Chopping, Chain Irrigation** | `CityTurnManager.kt`, `TileImprovementFunctions.kt`, `Techs.json` |
| **Civ IV Victory Milestones & Spaceship 10-Turn Launch/Travel + Capital-Capture Abort** | `Victory.kt`, `VictoryManager.kt`, `CityConquestFunctions.kt` |

---

## 2. Base Civ IV Strategic Epics

### 2.1 Civ IV Great People Overhaul
**Current State**: `GreatPersonManager.kt` uses Civ V per-GP-type pools, and `GoldenAgeManager.kt` uses Civ V stored-happiness thresholds.

**Design Specification**:
- **Mixed-Probability Per-City GP Pool**:
  - Replace/gate separate per-GP pools with a single empire-wide escalating threshold (`100, 200, 300, ...` scaled by game speed) and per-city `cityGreatPersonPoints: Counter<String>` (already partially tracked on `City`).
  - When a city's total `cityGreatPersonPoints.values.sum() >= threshold`, roll the spawned GP type weighted by each GP's share of points in that city (`stateBasedRandom`), reset that city's pool, and increment the empire threshold.
- **Great Person Actions** (`UnitActionsGreatPerson.kt`):
  - **Lightbulb Tech**: Consumes the GP to grant `baseBeakers + (popMultiplier * totalEmpirePop)` research toward the top unresearched tech matching the GP's flavor/preference list (e.g., Great Scientist: *Writing → Mathematics → Philosophy → Education → Printing Press → Liberalism*).
  - **Settle as Super Specialist**: Consumes the GP inside a city, adding a permanent counter on `City` (e.g., `settledGreatPeople: Counter<String>`) that yields fixed stats per turn (e.g., Great Scientist: `+1 Production, +6 Science`; Great Merchant: `+1 Food, +6 Gold`; Great Engineer: `+3 Production, +3 Science`; Great Prophet: `+2 Production, +5 Gold`; Great Artist: `+3 Gold, +12 Culture`; Great General / Military Instructor: `+2 XP` to trained units).
  - **Great Work (Culture Bomb)**: Consumes a Great Artist in a city to add `+4000` instant culture to `city.expansion.cultureStored` and immediately clear city resistance.
  - **Great General Warlord Attachment**: Attaches a Great General to a military unit on the same tile, distributing `+20 XP` evenly across all friendly military units on the tile, granting free upgrades for that unit, and unlocking Warlord-only promotions (`Leadership`, `Medic III`, `Morale`, `Tactics`).
- **Escalating GP-Cost Golden Ages** (`GoldenAgeManager.kt`):
  - 1st Golden Age requires `1` Great Person; 2nd requires `2` **distinct** Great Person types; 3rd requires `3` distinct types, etc.
  - Track `goldenAgesTriggeredByGreatPeople: Int` on `GoldenAgeManager`.

**Primary Files**: `GreatPersonManager.kt`, `GoldenAgeManager.kt`, `City.kt`, `CityStats.kt`, `UnitActionsGreatPerson.kt`, `BasicSquare/Units.json`, `BasicSquare/UnitPromotions.json`.

---

### 2.2 Naval Transports Carrying Land Units
**Current State**: Land units use Civ V embarkation (`MapUnit.isEmbarked()`); `MapUnit.isTransportTypeOf()` only carries air units/missiles (`UniqueType.CarryAirUnits`).

**Design Specification**:
- **Ruleset Flag & Cargo Capacity**:
  - Add `UniqueType.NoEmbarkation("Land units cannot embark", UniqueTarget.ModOptions)` and `UniqueType.CarryLandUnits("Can carry [amount] land units", UniqueTarget.Unit)`.
  - Wire onto `BasicSquare/ModOptions.json` and naval transports in `Units.json` (`Galley`: 2, `Caravel`: 1 spy/missionary/explorer, `Galleon`: 3, `Transport`: 4).
- **Data Model (`MapUnit.kt`)**:
  - Add `var transportedByUnitId: Int = -1` (or `cargoUnitIds: ArrayList<Int>`) on `MapUnit` with transient lookup helpers (`getCargoUnits()`, `getTransportUnit()`, `hasCargoSpace()`).
  - Moving a transport updates the `currentTile` of all embarked cargo units in lockstep; destroying a transport destroys all cargo units on board.
- **Boarding, Unloading & Amphibious Assault** (`UnitMovement.kt`, `UnitActions.kt`, `Battle.kt`):
  - A land unit adjacent to (or in a coastal city with) a friendly transport with free cargo space can move onto the transport's water tile to board (consuming remaining movement).
  - A cargo unit on a transport adjacent to land can move onto an empty land tile to unload, or attack an enemy unit on an adjacent land tile (applying the `-50%` amphibious penalty unless the unit has `Amphibious`).
- **AI Naval Transport Routing** (`UnitAutomation.kt`, `NextTurnAutomation.kt`):
  - Pair idle transports with land invasion stacks targeting enemy coastal tiles; load up to capacity, pathfind to staging coast, unload/assault.

**Primary Files**: `MapUnit.kt`, `UnitMovement.kt`, `Battle.kt`, `UnitActions.kt`, `UnitAutomation.kt`, `BasicSquare/Units.json`, `BasicSquare/ModOptions.json`.

---

### 2.3 Unified Commerce & Multi-Sliders
**Current State**: `BasicSquare/ModOptions.json` has `"Can convert gold to science with sliders"` and `CityStats.kt` supports `goldPercentConvertedToScience` / `goldPercentConvertedToCulture`, but tile/trade yields are still direct Gold rather than unified Civ IV Commerce.

**Design Specification**:
- **Commerce Pool**:
  - Treat raw tile + trade route Gold in `CityStats` (when `ConvertGoldToScience` / `UnifiedCommerceMode` is active) as **Commerce** before building % multipliers (`Library`, `Market`, `Bank`, `Theatre`, `Jail`).
  - Split Commerce by empire-wide sliders (`scienceSlider`, `cultureSlider`, `espionageSlider`, remainder = `goldSlider`, all in 10% increments summing to 100%).
  - Apply building % multipliers **after** the slider split (so Libraries multiply slider Science, Markets/Banks multiply slider Gold, Theatres multiply slider Culture).
- **UI & AI**:
  - Add compact `+ / -` slider controls for Science, Culture (unlocked at *Drama*), and Espionage (unlocked at *Alphabet*) in the top bar of `WorldScreen`.
  - Update `NextTurnAutomation.automateGoldToSciencePercentage()` to maintain positive treasury while maximizing Science (or Culture when pursuing Cultural Victory).

**Primary Files**: `CityStats.kt`, `CivInfoStatsForNextTurn.kt`, `TechManager.kt`, `WorldScreenTopBar.kt`, `NextTurnAutomation.kt`.

---

### 2.4 Barbarian Cities & Wild Animals
**Current State**: `BarbarianManager.kt` spawns Civ V Barbarian Encampments and standard military units.

**Design Specification**:
- **Early Wild Animals**:
  - Add `UniqueType.WildAnimal("Cannot enter cultural borders", UniqueTarget.Unit)` and units (`Lion`, `Wolf`, `Bear`, `Panther`) in `BasicSquare/Units.json`.
  - Before turn ~25 (or before average era reaches Classical), `BarbarianManager` spawns animals in fog-of-war tiles; animals cannot enter any civ's cultural borders and despawn/stop spawning once human barbarians appear.
- **Barbarian-Founded Cities**:
  - Instead of placing Civ V encampments in `BasicSquare`, periodically found actual Barbarian cities (`barbarianCiv.addCity(tile)`) in distant fog-of-war tiles with good food/resource scores, garrisoned by 2–3 Archers/Warriors.
  - Barbarian cities grow, build units/workers, and can be captured or razed by major civs just like normal cities.

**Primary Files**: `BarbarianManager.kt`, `UnitMovement.kt`, `UniqueType.kt`, `BasicSquare/Units.json`.

---

### 2.5 Air Combat & Economy Polish
- **Bomber Missions (`AirInterception.kt`, `UnitActions.kt`)**:
  - Allow Air units (`Fighter`, `Bomber`) to run **Bombard City Defenses** (reducing `city.defenseDamageTaken` just like siege) and **Pillage Tile Improvement** missions from range (subject to enemy Fighter interception).
- **SDI Project (`Nuke.kt`, `Buildings.json`)**:
  - Add `UniqueType.InterceptNukesChance("[amount]% chance to intercept nuclear missiles", UniqueTarget.Building, UniqueTarget.Global)` and wire `75%` onto the `SDI` National Project (`Laser` tech). Evaluate in `Nuke.kt` before detonation.
- **Colonial Maintenance & Economic Inflation (`CityStats.kt`, `CivInfoStatsForNextTurn.kt`)**:
  - **Colonial Maintenance**: Cities on a different continent from the capital incur additional maintenance scaling with the number of cities on that overseas continent (zeroed by `State Property` civic).
  - **Inflation**: Late-game empire expense multiplier (`0%` until turn ~100, slowly ramping over turns) applied to total maintenance + civic upkeep + unit upkeep.

---

## 3. *Warlords* & *Beyond the Sword* Expansion Epics

### 3.1 Vassal States & Capitulation (*Warlords*)
**Current State**: Absent (`DiplomacyManager.kt`, `TradeOfferType.kt`).

**Design Specification**:
- **Data Model (`Civilization.kt`, `DiplomacyManager.kt`)**:
  - Add `var masterCivName: String? = null` and `var isCapitulatedVassal: Boolean = false` on `Civilization`.
  - Add `TradeOfferType.VassalAgreement` (peaceful voluntary vassalage, requires `Feudalism`, cancellable after 10 turns) and **Capitulation** (offered in peace treaties when a civ is losing badly; cannot be cancelled unless vassal exceeds 50% of master's land/pop or loses 50% of its own land since capitulating).
- **Gameplay Rules**:
  - Master and vassal automatically share wars and peace treaties (`DeclareWar.kt`, `DiplomacyManager.makePeace()`).
  - Master gets `+1` happiness in all cities per vassal, open borders, and line-of-sight; master can demand 1 resource tribute (refusal declares war).
  - Vassal's land and population count **50%** toward the master's `Civ4Domination` victory milestone.

**Primary Files**: `Civilization.kt`, `DiplomacyManager.kt`, `DeclareWar.kt`, `TradeOfferType.kt`, `Victory.kt`, `DiplomacyScreen.kt`.

---

### 3.2 Civ IV Espionage System (*Beyond the Sword*)
**Current State**: `EspionageManager.kt` implements Civ V discrete spies stealing techs; `BasicSquare/Units.json` and `Specialists.json` have `// TODO` stubs for BTS Espionage.

**Design Specification**:
- **Espionage Points (EP) & Weights**:
  - Add `espionagePoints: HashMap<String, Int>` (accumulated EP spent against each rival civ) and `espionageWeights: HashMap<String, Int>` (relative allocation priority per rival) on `Civilization`.
  - Each turn, empire Espionage output (from `Palace`, `Courthouse`, `Jail`, `Security Bureau`, `Intelligence Agency`, `Scotland Yard`, `Spy` specialists, and the Espionage slider) is distributed across known civs proportional to `espionageWeights`.
- **Passive Visibility Thresholds** (comparing our EP vs. target's total EP):
  - **Demographics**: View rival's power/GNP/crop yield graphs.
  - **City Visibility**: See rival's current research and city tile visibility.
  - **Investigate City**: Open rival's city screen read-only.
- **Active Spy Missions** (on `Spy` unit inside rival territory, spending EP from `espionagePoints[target]`):
  - *Destroy Improvement*, *Sabotage Production*, *Poison Water* (`+8` unhealthiness decaying over 8 turns), *Foment Unhappiness* (`+8` unhappiness decaying over 8 turns), *Support City Revolt* (1 turn resistance + drops city defense to 0% for the turn), *Steal Technology*, *Influence Civics / Religion*.
  - Stationary bonus (`-10%` EP cost per turn fortified, up to `-50%`).

**Primary Files**: `EspionageManager.kt`, `Civilization.kt`, `UnitActions.kt`, `BasicSquare/Units.json`, `BasicSquare/Buildings.json`, `BasicSquare/Specialists.json`, new `EspionageOverviewTab.kt`.

---

### 3.3 Corporations (*Beyond the Sword*)
**Current State**: Only the `Corporation` tech exists; `Units.json` has a `// TODO` for Great Merchants founding corporations.

**Design Specification**:
- **Ruleset Schema (`Corporations.json`)**:
  - Define the 7 BtS corporations (*Sid's Sushi Co.*, *Mining Inc.*, *Cereal Mills*, *Aluminum Co.*, *Standard Ethanol*, *Creative Constructions*, *Civilized Jewelers*), each specifying: `requiredTech`, `founderGreatPerson`, `consumedResources`, `yieldPerResource`, and `competingCorporations`.
- **State & Mechanics**:
  - Track `headquartersCityId` per corporation on `GameInfo` and `presentCorporations: HashSet<String>` on `City`.
  - Founding builds the HQ (`+4 Gold` per city with the corporation). Cities with the corporation can train **Executive** units to spread it to domestic or foreign cities (blocked under `Mercantilism` for foreign corps, and disabled empire-wide under `State Property`).
  - Each city with a corporation gains `yieldPerResource * countOfAvailableResources` in `CityStats.kt` and pays **Corporate Maintenance** scaling with resource count and city size.

**Primary Files**: New `Corporation.kt` ruleset model, `BasicSquare/Corporations.json`, `City.kt`, `CityStats.kt`, `UnitActions.kt`, `BasicSquare/Units.json`.

---

### 3.4 Apostolic Palace & Population-Weighted UN (*Beyond the Sword*)
**Current State**: `VictoryManager.kt` implements Civ V 1-vote-per-civ UN voting.

**Design Specification**:
- **Population-Weighted Voting**:
  - Replace 1-vote-per-civ (when `BasicSquare` / Civ IV Diplomacy mode is active) with `votes = sum(city.population.population)` for eligible cities (all cities for UN; cities containing the AP's religion for the Apostolic Palace, doubled for full members sharing the AP's state religion).
- **Resolutions Cycle**:
  - Every N turns, the elected Resident (AP) or Secretary-General (UN) proposes a resolution:
    - *Open Borders with all members*, *Stop War against member*, *Declare War on infidel* (AP), *Assign City to rightful owner*, *Global Civic* (UN: Universal Suffrage / Free Speech / Emancipation / Environmentalism), *Nuclear Non-Proliferation* (UN), or **Diplomatic / Religious Victory** vote.
  - Members may vote **Yes**, **No**, **Abstain**, or **Never! (Defy)** — defying blocks a passing resolution at the cost of `+5` unhappiness ("The world considers you a villain!") in cities until a future resolution passes.

**Primary Files**: `VictoryManager.kt`, `DiplomacyManager.kt`, `CityStats.kt`, `BasicSquare/Buildings.json` (`Apostolic Palace`), `BasicSquare/VictoryTypes.json`, new `ResolutionVotePopup.kt`.

---

## 4. Recommended Implementation Order

1. **Civ IV Great People Overhaul** (highest strategic replayability impact; self-contained in `GreatPersonManager` / `GoldenAgeManager` / `UnitActions`).
2. **Unified Commerce & Multi-Sliders** (unlocks authentic Civ IV economy, Colosseum culture-rate happiness, and feeds directly into Espionage).
3. **Barbarian Cities & Wild Animals** (medium effort, transforms early-game exploration).
4. **Naval Transports Carrying Land Units** (high AI complexity; best tackled as a dedicated movement/AI epic).
5. **Vassal States & Capitulation** (clean diplomacy/war expansion).
6. **Civ IV Espionage → Corporations → Apostolic Palace & UN** (*Beyond the Sword* endgame suite).
