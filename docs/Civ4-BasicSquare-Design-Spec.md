# Civilization IV Alignment Specification & Implementation Status for `BasicSquare`

## 1. Executive Summary & Current Status

This document defines the technical and ruleset design specification for Unciv's built-in square-grid ruleset ([`BasicSquare`](../android/assets/jsons/BasicSquare)) and core Kotlin engine aligned with **Civilization IV**.

> [!NOTE]
> **Implementation Status (November 2026)**:
>
> - **Implemented in Core Engine & `BasicSquare` Ruleset**:
>   - 8-direction Square Grid (`GridMath`, `GridCoord`) and 21-tile "Fat Cross" (`cityWorkRange = 2`, `cityWorkRangeExcludeCorners = true`)
>   - Unit Stacking (`UniqueType.AllowUnitStacking`, `Tile.militaryUnits`, `UnitStackContextMenu`) and Civ IV Stack Combat (`UniqueType.Civ4StackCombat`, `Battle.getBestDefender`, undefended city instant capture)
>   - Siege City Defense Bombardment (`UnitActionsSiege` / `UniqueType.CanBombardCityDefenses`), Collateral Damage (`UniqueType.CollateralDamage`, `UniqueType.CollateralDamageBonus`, `UniqueType.ImmuneToCollateralDamage`), and Primary Defender Damage Cap (`UniqueType.MaxAttackDamage`)
>   - Native Civics & Anarchy (`CivicManager`, `Civics.json`, `UniqueType.CivicsMode`, `UniqueType.NoAnarchy`, `UniqueType.CivicUpkeepDiscount`, `CivicPickerScreen`)
>   - Native City Health (`UniqueType.CityHealthMode`, `City.getHealthBreakdown()`, `UniqueType.CityHealth`, `UniqueType.NoBuildingUnhealthiness`)
>   - Civ IV Local Happiness (`ModOptions.useCiv4LocalHappiness`, `City.getAngryCitizens()`, `whipAngerTurns`), Cultural Ring Borders & Tile Culture Flipping (`ModOptions.useCiv4CultureBorders`, `Tile.tileCulture`), City Maintenance (`CityStats.getCiv4CityMaintenance()`), Per-City Trade Routes (`CityStats.getStatsFromTradeRoute()`), Per-City Great Person Pool (`City.cityGreatPersonPoints`), Slavery Whip (`UniqueType.CanWhipProduction`, `CityConstructions.whipConstruction`), and Gold Hurry (`UniqueType.CanHurryProductionWithGold`, `CityConstructions.hurryConstructionWithGold`)
>   - Civ IV Religion (`Civ4ReligionManager`, `UniqueType.Civ4ReligionMode`, `UniqueType.FoundReligionOnTech`, `UniqueType.CanSpreadCiv4Religion`, passive trade-network spread, State Religion happiness/diplomacy, `UniqueType.ShrineGoldForReligion`, `UniqueType.NoStateReligion`, `UniqueType.HappinessPerPresentReligion`)
>   - Auto-Upgrading Improvements (`UniqueType.ImprovementUpgradesAfterTurns`: `Cottage -> Hamlet -> Village -> Town`, `UniqueType.ImprovementGrowthRate`)
>   - Civ IV Victory Milestones (`MilestoneType.ThreeLegendaryCities`, `MilestoneType.Civ4Domination`)
>   - Orthogonal Tech Tree Line Router (`TechLineRouter`)
>   - Full Civ IV AI & Automation Adaptation (see [AI & Automation Architecture](./Developers/AI-Architecture.md))
> - **Intentional Adjustments from Initial Spec**:
>   - **City-States Removed from `BasicSquare`**: `CityStateTypes.json` and `Quests.json` in `BasicSquare` are empty (`[]`) and City-State nations were removed from `BasicSquare/Nations.json` for a pure Civ IV major-civilization setup.
>   - **Asset-Only Mod Restriction & Offline Single-Player**: External mods are restricted to asset-only (`ModCompatibility.isAssetOnlyMod`) and online multiplayer is disabled (`PlatformSpecific.supportsOnlineMultiplayer = false`).

### Design Pillars

1. **True Civ IV Core Mechanics in Kotlin:** Replace Unciv's JSON-only workarounds with first-class engine support for unit stacking, best-defender stack combat, siege city-defense bombardment and collateral damage, native Civics switching with Anarchy, native City Health/Unhealthiness, passive trade-route/missionary Religion spread with State Religion, and auto-upgrading `Cottage → Hamlet → Village → Town` improvements.
2. **Complete Civ IV + Expansions Content:** Incorporate technologies, units, buildings, wonders, promotions, civics, and all 11 leader traits from Base Civ IV, Warlords, and Beyond the Sword.
3. **Ruleset-Gated Engine Behavior:** Gate all Civ IV engine mechanics behind explicit `ModOptions` / `UniqueType` flags so core Unciv architecture remains clean, testable, and extensible.
4. **Visual Asset Completeness:** Import open/Creative Commons icons from `yairm210/Civ-IV` into `android/Images.*` for Civ IV-specific units, buildings, techs, and improvements.

---

## 2. Reference Research: Existing Civ IV Mods for Unciv

Three community repositories were analyzed to determine which patterns to adopt directly and which to upgrade into native Kotlin engine mechanics:

| Repository | Scope | Adopted Directly | Upgraded to Native Engine |
| :--- | :--- | :--- | :--- |
| **`yairm210/Civ-IV`** | Base Civ IV ruleset (21 JSONs + CC icons) | `ModOptions` city strength scaling, Gold-to-Science slider (`Can convert gold to science with sliders`), OR-tech prerequisites (`Unavailable <before discovering ...>`), 1–40 unit combat scale, `25 HP` heal on promotion, 5 Civic categories, 7 Religions, Terrain/Improvement yields, CC Noun Project icons | Replace `Revolution` National Wonder with native Civics switching + Anarchy; replace 40-line `Health` pseudo-resource with native `City.health`; replace manual `Cottage`–`Town` worker upgrades with automatic turn-based growth; replace 1UPT with unit stacking + collateral damage; replace Faith/Pantheon religion with passive spread + State Religion |
| **`RobLoach/Civ-IV---Warlords`** | Warlords expansion | `Charismatic`, `Imperialistic`, and `Protective` traits; `Great General` mechanics; Warlords nations, unique buildings, and units | — |
| **`RobLoach/Civ-IV---Beyond-the-Sword`** | Beyond the Sword expansion | `Aesthetics`, `Literature`, `Drama`, `Military Science` techs; `Cuirassier`, `Airship`, `Privateer`, `Paratrooper`; BtS buildings, wonders, and nations | — |

---

## 3. Phase 1: Core Engine Design Specifications (Kotlin)

### 3.1 Unit Stacking & True Civ IV Stack Combat

**Affected Files:**

- `core/src/com/unciv/models/ruleset/unique/UniqueType.kt`
- `core/src/com/unciv/logic/map/tile/Tile.kt`
- `core/src/com/unciv/logic/battle/Battle.kt`
- `core/src/com/unciv/logic/battle/BattleDamage.kt`
- `core/src/com/unciv/logic/battle/TargetHelper.kt`
- `core/src/com/unciv/logic/battle/CityCombatant.kt`

#### Data Model & Ruleset Gating

- Add a new `UniqueType` on `ModOptions`:
  - `AllowUnitStacking("Allows unlimited military units per tile", UniqueTarget.ModOptions)`
  - `Civ4StackCombat("Uses Civ IV stack combat (no 2-tile ranged land attacks, best defender auto-selected)", UniqueTarget.ModOptions)`
- On `Tile` (`Tile.kt`):
  - **Direct Replacement:** Replace `var militaryUnit: MapUnit? = null` with `var militaryUnits = ArrayList<MapUnit>(0)` (matching `airUnits`), updating all ~42 referencing files in `core/src` to use `militaryUnits` and explicit helper methods (`firstMilitaryUnit()`, `hasMilitaryUnit()`, `getBestDefender(attacker)`). This enforces a single source of truth at compile time with zero dual-field sync bugs.
  - Update `Tile.getUnits()`, `Tile.clone()`, and `Tile.removeUnit()` to operate on `militaryUnits`.

#### Movement & Pathfinding

- When `AllowUnitStacking` is active:
  - Friendly military units can enter and end their turn on tiles already occupied by friendly military units without swapping or blocking.
  - Enemy tiles with `militaryUnits` remain impassable unless attacking.
  - Add UI support on `WorldScreen` (unit stack panel / cycling) to select individual units in a stack or issue group-move orders.

#### Best-Defender Selection & City Capture

- When an attacker targets a tile containing one or more enemy `militaryUnits`:
  1. Evaluate every unit in `targetTile.militaryUnits` as a `MapUnitCombatant` against the attacker using `BattleDamage` modifiers (base strength, terrain/river/city defense, fortification, unit-type bonuses such as `Spearman` +100% vs `Mounted`, `Axeman` +50% vs `Sword`).
  2. Select the unit with the **highest effective defensive strength** (tie-breaking by highest HP, then lowest XP/value).
  3. Resolve melee combat between the attacker and the selected best defender.
- **No 2-Tile Land/City Ranged Combat:**
  - When `Civ4StackCombat` is active, land `Archery` and `Siege` units attack adjacent tiles directly (using `TargetHelper` melee targeting) instead of Civ V 2-tile ranged bombardment.
  - Cities do **not** perform Civ V ranged city bombardment and have no independent HP pool when undefended: if a city tile has **0** `militaryUnits` remaining after combat (or is empty), the attacker immediately enters and captures the city.
  - Stationed `militaryUnits` inside a city receive the city's defensive bonus (`cityDefenseBonus` from Culture + `Walls`/`Castle`) plus any `City Garrison` promotions.

---

### 3.2 Siege Bombardment & Collateral Damage

**Affected Files:**

- `core/src/com/unciv/models/ruleset/unique/UniqueType.kt`
- `core/src/com/unciv/logic/battle/Battle.kt`
- `core/src/com/unciv/logic/city/City.kt`
- `core/src/com/unciv/ui/screens/worldscreen/unit/actions/UnitActions.kt` (unit action registration)

#### City Defense % & Bombard Action

- Add `var cityDefenseBonus: Int = 0` (or `damageTakenToDefense: Int = 0` subtracted from max cultural + building defense) to `City`.
  - Cultural defense tiers: `0%` (0 culture), `20%`, `40%`, `60%`, `80%`, `100%` (Legendary), or `Walls` (+50% vs pre-Gunpowder), `Castle` (+50% additional, +100% total vs pre-Gunpowder).
- Add `UniqueType`:
  - `CanBombardCityDefenses("Can bombard city defenses by [amount]% per turn", UniqueTarget.Unit)`
- Register a **Bombard** unit action when a Siege unit is adjacent to an enemy city with `cityDefenseBonus > 0`:
  - Consumes the unit's attack/movement for the turn.
  - Reduces the city's effective defense bonus by the unit's bombard rate (halved if the city has `Walls`/`Castle` against non-Gunpowder siege, matching Civ IV).

#### Collateral Damage & Attack Damage Cap

- Add `UniqueType`s:
  - `CollateralDamage("Deals collateral damage to up to [amount] other units in stacked tiles", UniqueTarget.Unit)`
  - `MaxAttackDamage("Maximum [amount]% damage dealt to primary defender", UniqueTarget.Unit)` (e.g., `75%` for `Catapult`, `80%` for `Trebuchet`, `85%` for `Cannon`, `90%` for `Artillery`).
- When a Siege unit attacks a stacked tile:
  1. **Cap-Triggered Withdrawal + Normal Death:** Primary combat resolves against the best defender. If the defender is already at or reaches `(100 - MaxAttackDamage)%` HP, the Siege unit automatically withdraws (cannot kill the defender). If the Siege unit loses *before* the defender reaches the cap, the Siege unit is destroyed normally (after applying collateral damage).
  2. Up to `N` **other** `militaryUnits` on the target tile (excluding units immune to collateral damage, e.g., `Siege` vs `Siege` if configured) take collateral damage proportional to `(attackerStrength / defenderStrength)` and scaled by `Barrage I–III` promotions.

---

### 3.3 Native Civics Switching & Anarchy (`CivicManager` & `Civics.json`)

**Affected Files:**

- `core/src/com/unciv/models/ruleset/Civic.kt` (new ruleset model + `Civics.json` loader in `Ruleset.kt`)
- `core/src/com/unciv/logic/civilization/managers/CivicManager.kt` (new dedicated manager on `Civilization`)
- `core/src/com/unciv/logic/civilization/Civilization.kt`
- `core/src/com/unciv/logic/civilization/managers/TurnManager.kt`
- `core/src/com/unciv/ui/screens/pickerscreens/` (dedicated Civics picker screen)

#### Dedicated `CivicManager` & `Civics.json`

- Add `ModOptions` unique: `CivicsMode("Uses Civ IV Civics instead of Social Policies", UniqueTarget.ModOptions)`.
- Introduce a dedicated `CivicCategory` / `Civic` ruleset model loaded from `Civics.json` (cleanly separated from `PolicyManager` and `Policies.json`), with its own `UniqueMap` (`civicUniques`) and `RulesetValidator` checks.
- In `CivicManager`:
  - All 5 Civic categories (`Government`, `Legal`, `Labor`, `Economy`, `Religion`) are active from turn 0, each defaulting to its starting civic (`Despotism`, `Barbarism`, `Tribalism`, `Decentralization`, `Paganism`).
  - Players do **not** spend Culture to unlock Civics; instead, Civics are unlocked by Technologies (or Wonders like `The Pyramids` unlocking all `Government` civics).
  - Selecting a new Civic in a category replaces the active Civic in that category.
  - **Civic Upkeep:** Sum the upkeep of all 5 active Civics (Low / Medium / High, scaled by city count and population, halved by the `Organized` trait) and deduct it as empire maintenance each turn.

#### Anarchy Turns

- Add `var anarchyTurns: Int = 0` on `Civilization`.
- When a player confirms Civic changes in the Civics/Policies screen:
  - Calculate `anarchyTurns` based on the number of Civics changed and game speed (e.g., 1 turn for 1–2 civics on Standard, +1 for additional civics).
  - If the civilization has `"No Anarchy when changing Civics"` (`Spiritual` trait) or is currently in a Golden Age, `anarchyTurns = 0`.
- During `TurnManager.startTurn()` / `CityStats`:
  - While `civ.anarchyTurns > 0`, cities produce `0` Food/Production/Gold/Science/Culture, pause unit/building construction and growth, and decrement `anarchyTurns` by 1 each turn.

---

### 3.4 Native City Health & Unhealthiness

**Affected Files:**

- `core/src/com/unciv/models/ruleset/unique/UniqueType.kt`
- `core/src/com/unciv/logic/city/City.kt`
- `core/src/com/unciv/logic/city/CityStats.kt`
- `core/src/com/unciv/ui/screens/cityscreen/`

#### Health Calculation

- Add `UniqueType`s:
  - `CityHealth("[amount] Health", UniqueTarget.Building, UniqueTarget.Global, UniqueTarget.Resource)` (supports positive Health and negative Unhealthiness, plus conditionals like `<with [Corn]>` or `<in [River] cities>`).
- On `City`:
  - Compute `getHealthBreakdown()`:
    - **Positive Health:** Difficulty base (e.g., `+2` to `+4` + `Expansive` `+2`), Fresh Water (`+2`), surrounding `Forest` tiles (`+0.5` each, rounded down), connected Bonus Resources (`Wheat`, `Corn`, `Rice`, `Fish`, `Crab`, `Clam`, `Cow`, `Pig`, `Sheep`, `Deer`), and Health buildings (`Aqueduct` +2, `Hospital` +3, `Public Transportation` +1/+1 per Oil, `Recycling Center` removes building unhealthiness, `Granary`/`Grocer`/`Supermarket`/`Harbor` conditional +1 per resource).
    - **Unhealthiness:** `city.population.population` (1 per pop), surrounding `Flood plains` (`+0.4` each) and `Jungle` (`+0.4` each), and industrial buildings (`Forge` +1, `Factory` +1/+2 with Coal/Oil, `Coal Plant` +2, `Airport` +1, `Industrial Park` +2).
  - **Net Penalty:** If `unhealthiness > health`, `val excessUnhealthiness = unhealthiness - health` subtracts `excessUnhealthiness` Food per turn in `CityStats` (after tile yields, before population growth).
- Display `Health / Unhealthiness` alongside `Happiness` in the City Screen header and tooltips.

---

### 3.5 Passive Religion Spread & State Religion (`Civ4ReligionManager`)

**Affected Files:**

- `core/src/com/unciv/logic/civilization/managers/Civ4ReligionManager.kt` (new dedicated manager on `Civilization`)
- `core/src/com/unciv/logic/civilization/managers/TurnManager.kt`
- `core/src/com/unciv/logic/city/City.kt` (`presentReligions: HashSet<String>`)
- `core/src/com/unciv/logic/civilization/diplomacy/DiplomacyManager.kt`

#### Dedicated `Civ4ReligionManager` & City `presentReligions`

- Add `ModOptions` unique: `Civ4ReligionMode("Uses Civ IV tech-founded religions and State Religion", UniqueTarget.ModOptions)`.
- Keep Civ V's `ReligionManager` untouched; when `Civ4ReligionMode` is enabled, `Civ4ReligionManager` handles religion state cleanly without Faith currency or Pantheon phases:
  - Cities can contain **multiple** religions simultaneously (tracking presence `Set<String>` rather than Civ V's single-majority follower count).
  - **Passive Spread:** Each turn, cities with **no** religion have a small probability of receiving a religion from connected trade-network cities (increased by `Shrine`s, reduced by `Theocracy` civic).
  - **State Religion:** Civilizations can choose a `stateReligion` from religions present in their empire (triggering 1 turn of Anarchy unless `Spiritual`), unlocking State Religion civic effects (`Organized Religion`, `Theocracy`, `Pacifism`) and diplomacy modifiers (`+` for shared State Religion, `-` for heathen State Religion).

---

### 3.6 Auto-Upgrading Tile Improvements (`Cottage` → `Town`)

**Affected Files:**

- `core/src/com/unciv/models/ruleset/unique/UniqueType.kt`
- `core/src/com/unciv/logic/map/tile/Tile.kt`
- `core/src/com/unciv/logic/city/City.kt`

- Add `UniqueType`:
  - `ImprovementUpgradesAfterTurns("Upgrades to [improvementName] after [amount] turns worked", UniqueTarget.Improvement)`
- On `Tile`:
  - Add serialized `var improvementWorkedTurns: Int = 0`.
  - Reset `improvementWorkedTurns = 0` if the improvement is pillaged, replaced, or removed.
- In `City` turn processing (`nextTurn`):
  - For each tile currently worked by a citizen of this city:
    - If `tile.getTileImprovement()` has `ImprovementUpgradesAfterTurns`, increment `tile.improvementWorkedTurns` by `1` (or `2` if the owning civ has `"Cottage improvements grow [+100]% faster"` from `Emancipation` civic).
    - When `improvementWorkedTurns >= threshold`, replace `tile.improvement` with the target `improvementName` and reset `improvementWorkedTurns = 0`.

---

## 4. Phase 2: `BasicSquare` Ruleset & Asset Specifications

All files reside in `android/assets/jsons/BasicSquare/` and `android/Images.*`.

### 4.1 Visual Assets (`android/Images.*`)

- Import CC Noun Project icons from `yairm210/Civ-IV` (`Images.Constructions`, `Images.TechPolicyIcons`, `Images.Tilesets`, `Images.UnitIcons` / `NationIcons`) for all new Civ IV entries:
  - **Improvements:** `Cottage`, `Hamlet`, `Village`, `Town`, `Forest Preserve`, `Watermill`, `Windmill`, `Workshop`
  - **Buildings & Wonders:** `Grocer`, `Supermarket`, `Courthouse`, `Drydock`, `Levee`, `Coal Plant`, `Jail`, `Security Bureau`, `Bunker`, `Public Transportation`, `Industrial Park`, `Spiral Minaret`, `University of Sankore`, `Versailles`, `Hollywood`, `Broadway`, `Rock 'n' Roll`, `Three Gorges Dam`, `Space Elevator`, `Mt. Rushmore`, `West Point`, `Wall Street`, `Globe Theatre`, `National Park`, `Apollo Program`, `SS` parts, plus Unique Buildings (`Hammam`, `Ziggurat`, `Madrassa`, `Ger`, `Ikhanda`, `Rathaus`, `Dike`, `Feitoria`, `Baray`, `Sacrificial Altar`, `Seowon`, `Dun`, `Terrace`, `Totem Pole`, etc.)
  - **Units:** `Axeman`, `Maceman`, `Grenadier`, `War Elephant`, `Cuirassier`, `Airship`, `Great General`, `Privateer`, plus Unique Units (`Praetorian`, `Redcoat`, `Cossack`, `Cho-Ko-Nu`, `Samurai`, `Keshik`, `Immortal`, `Fast Worker`, `Skirmisher`, `Quechua`, `Hwacha`, `Cataphract`, `Oromo Warrior`, `Landsknecht`, `Ballista Elephant`, `Vulture`, `Carrack`, `East Indiaman`, etc.)

### 4.2 JSON File-by-File Alignment

| File | Key Changes |
| :--- | :--- |
| **`ModOptions.json`** | Enable `"Can convert gold to science with sliders"`, `"Allows unlimited military units per tile"`, `"Uses Civ IV stack combat..."`, `"Uses Civ IV Civics..."`, `"Uses Civ IV tech-founded religions..."`, and Civ IV `cityStrength*` constants (`cityStrengthBase: 1.0`, `cityStrengthPerPop: 0.05`, etc.). |
| **`GlobalUniques.json`** | Per-city Happiness baseline (`[+6 Happiness] [in your cities]`, `[-1 Happiness] per [1] population`, `[+1 Happiness] <with a garrison>`); all **11 Leader Traits** (`Aggressive`, `Creative`, `Expansive`, `Financial`, `Industrious`, `Organized`, `Philosophical`, `Spiritual`, `Charismatic`, `Imperialistic`, `Protective`). |
| **`Civics.json`** | **New file:** 5 Civic categories (`Government`, `Legal`, `Labor`, `Economy`, `Religion`) with 5 Civics each, tech prerequisites, upkeep tiers, and `Emancipation` cottage growth bonus (replacing Civ V `Policies.json` usage in `CivicsMode`). |
| **`Techs.json`** | Complete Civ IV + BtS tech tree (starting with `Fishing`, `The Wheel`, `Agriculture`, `Hunting`, `Mysticism`, `Mining`; including `Aesthetics`, `Literature`, `Drama`, `Military Science`), OR-prerequisites (`Unavailable <before discovering ...>`), and Civ IV quotes. |
| **`Units.json` & `UnitTypes.json`** | Civ IV 1–40 strength scale, 1-move infantry / 2-move mounted, `25 HP` heal on promotion, `Animal` barbarians, Siege `Bombard` + `CollateralDamage` + `MaxAttackDamage`, Warlords `Great General`, BtS `Airship` / `Cuirassier` / `Privateer`. |
| **`UnitPromotions.json`** | Civ IV promotion tree: `Combat I–V`, `City Raider I–III`, `City Garrison I–III`, `Barrage I–III`, `Drill I–IV`, `Flanking I–II`, `Accuracy`, `Ambush`, `Blitz`, `Charge`, `Pinch`, `Shock`, `Formation`, `Cover`, `Medic I–II`, `Leadership`, `Tactics`. |
| **`Buildings.json` & `Specialists.json`** | Civ IV + Warlords + BtS buildings, National Wonders, World Wonders, Shrines, `SS` spaceship parts; `Specialists.json` with `Artist`, `Engineer`, `Scientist`, `Merchant`, `Priest` (all 3 GPP). |
| **`Terrains.json`, `TileImprovements.json`, `TileResources.json`** | Civ IV terrain yields, `Cottage → Hamlet → Village → Town` with `ImprovementUpgradesAfterTurns`, `Forest Preserve`, `Watermill`, `Windmill`, `Workshop`, irrigation chaining, and native `CityHealth` bonuses on resources. |
| **`Religions.json` & `Beliefs.json`** | 7 Civ IV religions (`Buddhism`, `Hinduism`, `Judaism`, `Christianity`, `Confucianism`, `Taoism`, `Islam`) tied to founding techs, Monasteries, Temples, Cathedrals, and Shrines. |
| **`Nations.json` & `Personalities.json`** | All Base Civ IV + Warlords + BtS civilizations and leaders with 2 traits each, Civ IV UUs/UBs, and AI personalities. |
| **`VictoryTypes.json`, `Eras.json`, `Speeds.json`, `Difficulties.json`** | Civ IV Space Race, Cultural (3 Legendary cities / Utopia), Conquest/Domination, Diplomatic (UN), Time; Civ IV era start percentages and turn/year pacing. |
| **`CityStateTypes.json` & `Quests.json`** | **Preserved** as an optional Civ V hybrid feature. |

---

## 5. Phase 3: Granular Execution Roadmap & Verification Plan

### Granular Phase Breakdown

To keep engine changes manageable, compilable, and independently testable, implementation is split into 8 phases (with sub-phases `1A–1D`, `2A–2B`, `4A–4B`, `7A–7C`):

1. **Phase 1A — `Tile.militaryUnits` & Movement Core:** Add `AllowUnitStacking` / `Civ4StackCombat` in `UniqueType.kt`; replace `Tile.militaryUnit` with `militaryUnits = ArrayList<MapUnit>(0)` in `Tile.kt`; update `MapUnit.kt`, `UnitMovement.kt`, `MovementCost.kt`, `UnitTurnManager.kt`, `GameInfo.kt`, `GameStarter.kt`.
2. **Phase 1B — `militaryUnits` Migration (Logic & AI):** Update `City.kt`, `Diplomacy*`, `TurnManager.kt`, `models/ruleset/*`, and all `logic/automation/*` files.
3. **Phase 1C — `militaryUnits` Migration (UI & Stack Selection):** Update `TileLayerUnitFlag.kt`, `TileLayerUnitSprite.kt`, `TileSetStrings.kt`, `TileView.kt`, `CityButton.kt`, `TileDescription.kt`, `UnitTable.kt` (compile gate: `./gradlew core:compileKotlin`).
4. **Phase 1D — Best-Defender & Stack Combat:** Update `Battle.kt`, `BattleDamage.kt`, `BattleUnitCapture.kt`, `TargetHelper.kt`, `CityCombatant.kt` (test gate: `StackCombatTests`).
5. **Phase 2A — Siege City Defense Bombardment:** Add `City.cityDefenseBonus`, `CanBombardCityDefenses`, and `Bombard` unit action in `UnitActions.kt` & `Battle.kt`.
6. **Phase 2B — Siege Collateral Damage & Cap Withdrawal:** Add `CollateralDamage` and `MaxAttackDamage`, cap-triggered withdrawal + collateral loop in `Battle.kt` (test gate: `SiegeCollateralTests`).
7. **Phase 3 — Auto-Upgrading Improvements:** Add `ImprovementUpgradesAfterTurns` and `Tile.improvementWorkedTurns` + `City.kt` turn progression (test gate: `CottageUpgradeTests`).
8. **Phase 4A — `Civics.json` Model & Loader:** Create `Civic.kt` (`CivicCategory`, `Civic`), `Ruleset.kt` loader, and `RulesetValidator.kt` checks.
9. **Phase 4B — `CivicManager`, Upkeep & Anarchy UI:** Create `CivicManager.kt`, `anarchyTurns` on `Civilization.kt` / `TurnManager.kt` / `CityStats.kt`, and `CivicPickerScreen.kt` (test gate: `CivicsAnarchyTests`).
10. **Phase 5 — Native City Health:** Add `CityHealth` unique, `City.getHealthBreakdown()`, food penalty in `CityStats.kt`, and City Screen UI (test gate: `CityHealthTests`).
11. **Phase 6 — Dedicated `Civ4ReligionManager`:** Create `Civ4ReligionManager.kt`, `City.presentReligions`, tech-triggered founding, passive spread, and `stateReligion` diplomacy (test gate: `Civ4ReligionTests`).
12. **Phase 7 (`7A–7C`) — Assets & `BasicSquare` JSONs:** Import `yairm210/Civ-IV` icons into `android/Images.*` and migrate all `BasicSquare/*.json` files (Base + Warlords + BtS, preserving `CityStateTypes.json` & `Quests.json`).
13. **Phase 8 — Test Alignment & Playtest:** Update Civ V-hardcoded assertions in `tests/src` and run full automated + Desktop playtest verification.

### Verification Plan

#### Automated Tests

1. **Existing Ruleset & Translation Gates:**

   ```pwsh
   ./gradlew test --tests "com.unciv.testing.BasicTests" --tests "com.unciv.uniques.RulesetValidatorTests" --tests "com.unciv.logic.TranslationTests"
   ```

2. **New Engine Unit Tests (in `tests/src/com/unciv/`):**
   - `StackCombatTests`: Multi-unit stacking on `Tile`, best-defender selection, immediate capture of undefended cities, disabled 2-tile land ranged attacks.
   - `SiegeCollateralTests`: City defense % reduction via `Bombard`, collateral damage across stacked units, and `MaxAttackDamage` cap.
   - `CivicsAnarchyTests`: Switching civics across 5 categories, `anarchyTurns` blocking city yields, and `Spiritual` trait bypassing anarchy.
   - `CityHealthTests`: Health vs. Unhealthiness calculation and food penalty when `unhealthiness > health`.
   - `Civ4ReligionTests`: Tech-triggered religion founding, Holy City assignment, passive spread, and State Religion bonuses.
   - `CottageUpgradeTests`: `Cottage → Hamlet → Village → Town` automatic progression after `N` turns worked.

#### Manual Verification

1. Launch the Desktop client (`./gradlew desktop:run`).
2. Start a New Game using `BasicSquare`.
3. Verify stack movement & best-defender combat, Siege `Bombard` & collateral damage, Civics switching & Anarchy, City Health UI breakdown, Gold/Science/Culture sliders, `Cottage` auto-growth, and Religion founding/spread alongside City-States.
