# Tier 1 Civ IV Core Combat & City Mechanics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Tier 1 Civilization IV combat mechanics (First Strikes, Siege max-damage cap, Mounted flanking vs. siege), stateful local happiness gaps (War Weariness, Hereditary Rule military police, Motherland unhappiness), and full Nationhood unit drafting (engine, UI, and defensive AI) for the `BasicSquare` ruleset.

**Architecture:** All mechanics are driven by `UniqueType` entries wired into `android/assets/jsons/BasicSquare/` so non-`BasicSquare` rulesets are unaffected. Combat pre-rounds, HP floors, and post-combat flanking live in `Battle.kt`; stateful `warWeariness` and `draftsThisTurn` live on `Civilization.kt`; `draftAngerTurns` and drafting live on `City.kt` / `CityConstructions.kt` / `CityStats.kt` alongside existing Slavery whipping.

**Tech Stack:** Kotlin (JVM), LibGDX, Gradle (`.\gradlew.bat :tests:test`), JUnit 4 (`BaseTestRunner`, `TestGame`).

**Spec:** [tier1_civ4_implementation_plan.md](file:///C:/Users/Pedro/.gemini/antigravity-cli/brain/13edd9f0-5a4c-42cc-878f-067e24f1297e/tier1_civ4_implementation_plan.md)

## Global Constraints

- Zero regex usage anywhere in production or test code; use explicit string primitives only.
- All new serialized fields on `Civilization` and `City` must have backward-compatible defaults (`HashMap()`, `0`) so existing saves load cleanly.
- All mechanics must be gated by `UniqueType` or `modOptions.useCiv4LocalHappiness` / `UniqueType.Civ4StackCombat` so standard rulesets remain unchanged.
- Preserve existing code formatting manually; do not run automated formatters.

## Review Focus

1. Equal First Strikes (`attackerFirstStrikes == defenderFirstStrikes`) must run 0 pre-rounds and not alter standard combat (pinned in Task 2).
2. A siege unit attacking a defender already at or below its `SiegeMaxDamageCap` HP floor must deal 0 primary damage and never kill the defender (pinned in Task 2).
3. Mounted flanking damage must not trigger if the mounted attacker dies in primary combat, and must skip non-matching or non-enemy units on the tile (pinned in Task 2).
4. War weariness from civs currently at peace must not add unhappiness, and mitigation uniques (`Jail`, `Police State`, `Mount Rushmore`) must clamp at 0 (never producing positive happiness) (pinned in Task 3).
5. Nationhood drafting must reject cities with population `< 6`, puppeted/razing cities, or when `civ.draftsThisTurn` has reached the per-turn empire cap (pinned in Task 4).

---

### Task 1: Ruleset Uniques & `BasicSquare` JSON Wiring

**Files:**
- Modify: `core/src/com/unciv/models/ruleset/unique/UniqueType.kt`
- Modify: `android/assets/jsons/BasicSquare/Units.json`
- Modify: `android/assets/jsons/BasicSquare/Civics.json`
- Modify: `android/assets/jsons/BasicSquare/Buildings.json`
- Test: `tests/src/com/unciv/uniques/UniqueTypeTests.kt` (or existing ruleset validation test)

**Interfaces:**
- Consumes: Existing `UniqueType.FirstStrikes`, `UniqueType.Civ4StackCombat`, `UniqueType.CollateralDamage`.
- Produces:
  - `UniqueType.SiegeMaxDamageCap` (`"Cannot reduce defender below [amount]% HP"`)
  - `UniqueType.FlankingDamage` (`"Deals [amount]% flanking damage to [mapUnitFilter]"`)
  - `UniqueType.HappinessPerGarrisonedUnit` (`"[+amount] Happiness per garrisoned military unit"`)
  - `UniqueType.WarWearinessModifier` (`"[amount]% War Weariness"`)
  - `UniqueType.CanDraftUnits` (`"Can draft up to [amount] units per turn"`)

- [ ] **Step 1: Add the 5 new `UniqueType` enum entries in `UniqueType.kt`**
  Define `SiegeMaxDamageCap`, `FlankingDamage`, `HappinessPerGarrisonedUnit`, `WarWearinessModifier`, and `CanDraftUnits` with appropriate `UniqueTarget` sets (`Unit`, `Promotion`, `Global`, `Building`, `Policy`/`Civic`).

- [ ] **Step 2: Wire the uniques into `BasicSquare` JSONs**
  - `Units.json`: Add `"Cannot reduce defender below [25]% HP"` to `Catapult`, `[20]%` to `Trebuchet`/`Cannon`, `[15]%` to `Artillery`; add `"Deals [25]% flanking damage to [Siege]"` to `Horse Archer`, `Knight`, `Cuirassier`, `Cavalry`.
  - `Civics.json`: Add `"[+1] Happiness per garrisoned military unit"` to `Hereditary Rule`; add `"Can draft up to [3] units per turn"` to `Nationhood`; add `"[-50]% War Weariness"` to `Police State`.
  - `Buildings.json`: Add `"[-25]% War Weariness"` to `Jail` and `Mount Rushmore`.

- [ ] **Step 3: Run ruleset validation tests to verify JSONs parse cleanly**
  Run: `.\gradlew.bat :tests:test --tests "com.unciv.testing.BasicSquare*" --tests "com.unciv.uniques.*"`
  Expected: PASS with 0 ruleset validation errors.

- [ ] **Step 4: Commit**
  ```pwsh
  git add core/src/com/unciv/models/ruleset/unique/UniqueType.kt android/assets/jsons/BasicSquare/Units.json android/assets/jsons/BasicSquare/Civics.json android/assets/jsons/BasicSquare/Buildings.json
  git commit -m "feat(basicSquare): add UniqueTypes and ruleset wiring for Tier 1 Civ IV mechanics"
  ```

---

### Task 2: Civ IV Combat Mechanics (First Strikes, Siege Cap, Mounted Flanking)

**Files:**
- Modify: `core/src/com/unciv/logic/battle/Battle.kt`
- Create: `tests/src/com/unciv/logic/battle/Civ4CombatMechanicsTests.kt`

**Interfaces:**
- Consumes: `UniqueType.FirstStrikes`, `UniqueType.SiegeMaxDamageCap`, `UniqueType.FlankingDamage`.
- Produces:
  - First Strike pre-round resolution inside `Battle.takeDamage(...)`.
  - `minDefenderHealth` calculation from `UniqueType.SiegeMaxDamageCap` in `Battle.takeDamage(...)`.
  - `Battle.applyFlankingDamage(attacker: ICombatant, defender: ICombatant, attackedTile: Tile)`.

- [ ] **Step 1: Write the failing tests in `tests/src/com/unciv/logic/battle/Civ4CombatMechanicsTests.kt`**
  Using `@RunWith(BaseTestRunner::class)` and `TestGame()`:
  - `first strikes grant attacker uncontested pre-rounds` — assert attacker with First Strikes takes less damage / deals more damage than without; also assert equal First Strikes runs 0 pre-rounds.
  - `siege unit cannot reduce primary defender below max damage cap` — assert Catapult attacking full-HP defender and already-low-HP defender (`<= 25` HP) leaves defender at `>= 25` HP and never kills it.
  - `surviving mounted unit deals flanking damage to enemy siege in stack` — assert Knight surviving attack damages Catapult in defending tile, skips non-Siege units, and does not trigger if Knight dies.

- [ ] **Step 2: Run test to verify it fails**
  Run: `.\gradlew.bat :tests:test --tests "com.unciv.logic.battle.Civ4CombatMechanicsTests"`
  Expected: FAIL on all 3 tests.

- [ ] **Step 3: Implement First Strikes, Siege HP floor, and `applyFlankingDamage` in `Battle.kt`**
  - In `takeDamage()`: sum `UniqueType.FirstStrikes` on attacker and defender; run `abs(netFirstStrikes)` pre-rounds where only the advantaged side can land hits (stopping early if defender reaches `minDefenderHealth` or dies).
  - In `takeDamage()`: compute `minDefenderHealth = max(existingMin, siegeCapHp)` from `UniqueType.SiegeMaxDamageCap` on `attacker`.
  - Add `applyFlankingDamage(attacker, defender, attackedTile)` called after primary combat when `!attacker.isDefeated()`; apply flanking damage to matching enemy `militaryUnits` on `attackedTile` (excluding `primaryDefenderUnit`), capping damage at `targetUnit.health - 1`.

- [ ] **Step 4: Run test to verify it passes**
  Run: `.\gradlew.bat :tests:test --tests "com.unciv.logic.battle.Civ4CombatMechanicsTests"`
  Expected: PASS.

- [ ] **Step 5: Commit**
  ```pwsh
  git add core/src/com/unciv/logic/battle/Battle.kt tests/src/com/unciv/logic/battle/Civ4CombatMechanicsTests.kt
  git commit -m "feat(combat): implement First Strikes, Siege max-damage cap, and Mounted flanking vs Siege"
  ```

---

### Task 3: Stateful Local Happiness (War Weariness, Hereditary Rule, Motherland)

**Files:**
- Modify: `core/src/com/unciv/logic/civilization/Civilization.kt`
- Modify: `core/src/com/unciv/logic/battle/Battle.kt`
- Modify: `core/src/com/unciv/logic/city/CityStats.kt`
- Create: `tests/src/com/unciv/logic/city/Civ4LocalHappinessTests.kt`

**Interfaces:**
- Consumes: `UniqueType.HappinessPerGarrisonedUnit`, `UniqueType.WarWearinessModifier`, `Tile.tileCulture`.
- Produces:
  - `Civilization.warWeariness: HashMap<String, Float>`
  - `Civilization.addWarWeariness(enemyCivName: String, amount: Float)`
  - `Civilization.decayWarWeariness()`
  - Local happiness breakdown entries `"Military Police"`, `"War Weariness"`, and `"Yearn for Motherland"` in `CityStats.updateCityHappiness()`.

- [ ] **Step 1: Write the failing tests in `tests/src/com/unciv/logic/city/Civ4LocalHappinessTests.kt`**
  - `hereditary rule grants happiness per garrisoned military unit` — assert 3 garrisoned units grant `+3` local happiness under Hereditary Rule.
  - `war weariness scales with casualties, ignores civs at peace, and clamps mitigation at zero` — assert `addWarWeariness` reduces local happiness while at war, contributes 0 when at peace, and `Police State` + `Jail` + `Mount Rushmore` (`-100%`) yields 0 unhappiness (never positive).
  - `conquered city suffers motherland unhappiness proportional to foreign tile culture` — assert captured city with 80% foreign `tileCulture` gets `"Yearn for Motherland"` unhappiness.

- [ ] **Step 2: Run test to verify it fails**
  Run: `.\gradlew.bat :tests:test --tests "com.unciv.logic.city.Civ4LocalHappinessTests"`
  Expected: FAIL.

- [ ] **Step 3: Implement `warWeariness` on `Civilization.kt`, combat hook in `Battle.kt`, and happiness sources in `CityStats.kt`**
  - `Civilization.kt`: add `var warWeariness = HashMap<String, Float>()`, `addWarWeariness(enemyCivName, amount)`, and `decayWarWeariness()` called in `startTurn()` (decay by 1% at war, 5% at peace, prune `< 0.5f`).
  - `Battle.kt`: call `addWarWeariness` when a military unit dies in combat (higher weight for offensive losses outside own territory).
  - `CityStats.kt`: in `updateCityHappiness()`, add `"Military Police"`, `"War Weariness"` (clamped `>= 0` after `WarWearinessModifier`), and `"Yearn for Motherland"` (`city.civ != city.foundingCivObject`).

- [ ] **Step 4: Run test to verify it passes**
  Run: `.\gradlew.bat :tests:test --tests "com.unciv.logic.city.Civ4LocalHappinessTests" --tests "com.unciv.logic.city.Civ4CityEconomyTests"`
  Expected: PASS.

- [ ] **Step 5: Commit**
  ```pwsh
  git add core/src/com/unciv/logic/civilization/Civilization.kt core/src/com/unciv/logic/battle/Battle.kt core/src/com/unciv/logic/city/CityStats.kt tests/src/com/unciv/logic/city/Civ4LocalHappinessTests.kt
  git commit -m "feat(happiness): implement War Weariness, Hereditary Rule military police, and Motherland unhappiness"
  ```

---

### Task 4: Nationhood Unit Drafting (Core Engine & Anger Lifecycle)

**Files:**
- Modify: `core/src/com/unciv/logic/civilization/Civilization.kt`
- Modify: `core/src/com/unciv/logic/city/City.kt`
- Modify: `core/src/com/unciv/logic/city/managers/CityTurnManager.kt`
- Modify: `core/src/com/unciv/logic/city/CityConstructions.kt`
- Modify: `core/src/com/unciv/logic/city/CityStats.kt`
- Create: `tests/src/com/unciv/logic/city/Civ4DraftingTests.kt`

**Interfaces:**
- Consumes: `UniqueType.CanDraftUnits`.
- Produces:
  - `Civilization.draftsThisTurn: Int` (reset to `0` in `startTurn()`)
  - `City.draftAngerTurns: Int` (decremented in `CityTurnManager.nextTurn()`, `-3` happiness in `CityStats.updateCityHappiness()`)
  - `CityConstructions.getDraftableUnit(): BaseUnit?`
  - `CityConstructions.getDraftPopulationCost(unit: BaseUnit): Int`
  - `CityConstructions.canDraftUnit(): Boolean`
  - `CityConstructions.draftUnit(): MapUnit?`

- [ ] **Step 1: Write the failing tests in `tests/src/com/unciv/logic/city/Civ4DraftingTests.kt`**
  - `draftUnit spawns foot soldier, reduces population, adds draft anger, and increments draftsThisTurn`
  - `canDraftUnit rejects population below 6, puppeted cities, razing cities, and exceeding per-turn cap`
  - `draftAngerTurns adds -3 local happiness and decays each turn`

- [ ] **Step 2: Run test to verify it fails**
  Run: `.\gradlew.bat :tests:test --tests "com.unciv.logic.city.Civ4DraftingTests"`
  Expected: FAIL.

- [ ] **Step 3: Implement drafting engine across `Civilization.kt`, `City.kt`, `CityTurnManager.kt`, `CityConstructions.kt`, and `CityStats.kt`**
  - Mirror `whipConstruction()` / `whipAngerTurns` conventions.
  - `getDraftableUnit()` picks the highest-strength buildable non-unique-blocked Melee/Gunpowder foot unit for `city.civ`.
  - `draftUnit()` deducts pop, adds `draftAngerTurns += 10`, increments `civ.draftsThisTurn`, spawns unit at `city.location`, and calls `city.cityStats.update()`.

- [ ] **Step 4: Run test to verify it passes**
  Run: `.\gradlew.bat :tests:test --tests "com.unciv.logic.city.Civ4DraftingTests" --tests "com.unciv.logic.city.Civ4CityEconomyTests"`
  Expected: PASS.

- [ ] **Step 5: Commit**
  ```pwsh
  git add core/src/com/unciv/logic/civilization/Civilization.kt core/src/com/unciv/logic/city/City.kt core/src/com/unciv/logic/city/managers/CityTurnManager.kt core/src/com/unciv/logic/city/CityConstructions.kt core/src/com/unciv/logic/city/CityStats.kt tests/src/com/unciv/logic/city/Civ4DraftingTests.kt
  git commit -m "feat(drafting): implement Nationhood unit drafting engine and draft anger lifecycle"
  ```

---

### Task 5: Nationhood Unit Drafting (CityScreen UI & Defensive AI)

**Files:**
- Modify: `core/src/com/unciv/ui/screens/cityscreen/CityConstructionsTable.kt` (or wherever the Slavery Whip button is rendered in `ui/screens/cityscreen/`)
- Modify: `core/src/com/unciv/logic/automation/civilization/NextTurnAutomation.kt`
- Modify: `tests/src/com/unciv/logic/automation/Civ4AiTests.kt`

**Interfaces:**
- Consumes: `CityConstructions.canDraftUnit()`, `CityConstructions.getDraftableUnit()`, `CityConstructions.getDraftPopulationCost()`, `CityConstructions.draftUnit()`.
- Produces:
  - `"Draft"` button in `CityScreen` next to Whip/Hurry controls.
  - Defensive AI drafting in `NextTurnAutomation.kt`.

- [ ] **Step 1: Write the failing AI test in `tests/src/com/unciv/logic/automation/Civ4AiTests.kt`**
  - `ai drafts defensive unit when at war with enemy near city under Nationhood` — assert AI civ with `Nationhood` and enemy unit within 3 tiles of a pop-8 city drafts a unit and spawns it on the city tile.

- [ ] **Step 2: Run test to verify it fails**
  Run: `.\gradlew.bat :tests:test --tests "com.unciv.logic.automation.Civ4AiTests"`
  Expected: FAIL on the new test.

- [ ] **Step 3: Implement `CityScreen` Draft button and defensive AI drafting**
  - Locate the existing Slavery Whip button in `core/src/com/unciv/ui/screens/cityscreen/` and add a sibling `"Draft [unitName] (-[popCost] Pop)"` button gated by `city.cityConstructions.canDraftUnit()` that calls `draftUnit()` and refreshes `cityScreen.update()`.
  - In `NextTurnAutomation.kt`, when `civInfo.hasUnique(UniqueType.CanDraftUnits)` and `civInfo.isAtWar()`, iterate cities with `canDraftUnit()`, check if an enemy military unit is within 3 tiles and `city.getAngryCitizens() <= 1`, and call `draftUnit()`.

- [ ] **Step 4: Run full test suite to verify everything compiles and passes**
  Run: `.\gradlew.bat :tests:test`
  Expected: PASS with 0 failures.

- [ ] **Step 5: Commit**
  ```pwsh
  git add core/src/com/unciv/ui/screens/cityscreen/ core/src/com/unciv/logic/automation/civilization/NextTurnAutomation.kt tests/src/com/unciv/logic/automation/Civ4AiTests.kt
  git commit -m "feat(drafting): add CityScreen Draft button and defensive AI drafting"
  ```
