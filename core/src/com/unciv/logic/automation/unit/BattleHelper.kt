package com.unciv.logic.automation.unit

import com.unciv.view.AttackableTileView
import com.unciv.view.ForeignCityView
import com.unciv.view.MapUnitView
import com.unciv.view.hasMovement
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly

object BattleHelper {

    /** Returns true if the unit cannot further move this turn - NOT if an attack was successful! */
    fun tryAttackNearbyEnemy(unitView: MapUnitView, stayOnTile: Boolean = false): Boolean {
        if (unitView.hasUnique(UniqueType.CannotAttack)) return false
        val distanceToTiles = unitView.getDistanceToTiles()
        // Melee land units are crippled when attacking from water - they should not embark to attack,
        // and if already embarked they should find a place to disembark instead
        val mustAttackFromLand = unitView.getBaseUnit().isMelee() && unitView.getBaseUnit().isLandUnit && !unitView.hasUnique(UniqueType.CanMoveOnWater)
            && !unitView.hasUnique(UniqueType.AttackAcrossCoast) // No penalty for attacking from water
        val attackableEnemies = unitView.getAttackableEnemies(stayOnTile = stayOnTile)
            .filter { !mustAttackFromLand || it.getTileToAttackFrom().isLand }
            // Only take enemies we can fight without dying or are made to die (or valid collateral siege sacrifice)
            .filter {
                val defender = it.getCombatant() ?: it.getTileToAttack().getCombatant()
                unitView.hasUnique(UniqueType.SelfDestructs) || (defender != null &&
                unitView.getDamageFromTerrain(it.getTileToAttackFrom()) <= 0 &&
                (unitView.asCombatant().calculateDamageToAttacker(defender) < unitView.unitHealth
                    || canMakeSacrificialCollateralAttack(unitView, it, defender)))
            }

        val enemyTileToAttack = chooseAttackTarget(unitView, attackableEnemies)

        if (enemyTileToAttack != null) {
            val tileToAttack = enemyTileToAttack.getTileToAttack()
            if (tileToAttack.militaryUnits.isEmpty() && unitView.getBaseUnit().isRanged()
                && unitView.canMoveTo(tileToAttack)
                && distanceToTiles.containsKey(tileToAttack)) { // Since the 'getAttackableEnemies' could return a tile we attack at range but cannot reach
                // Ranged units should move to caputre a civilian unit instead of attacking it
                unitView.tryMoveToTile(tileToAttack)
            } else {
                if (unitView.tryMovePreparingAttack(enemyTileToAttack, true))
                    unitView.attackOrNuke(enemyTileToAttack)
            }
        }
        return !unitView.hasMovement()
    }

    fun tryDisembarkUnitToAttackPosition(unitView: MapUnitView): Boolean {
        if (!unitView.getBaseUnit().isMelee() || !unitView.getBaseUnit().isLandUnit || !unitView.isEmbarked()) return false

        val attackableEnemiesNextTurn = unitView.getAttackableEnemies()
                // Only take enemies we can fight without dying
                .filter {
                    val defender = it.getCombatant() ?: it.getTileToAttack().getCombatant()
                    defender != null && unitView.asCombatant().calculateDamageToAttacker(defender) < unitView.unitHealth
                }
                .filter { it.getTileToAttackFrom().isLand }

        val enemyTileToAttackNextTurn = chooseAttackTarget(unitView, attackableEnemiesNextTurn)

        if (enemyTileToAttackNextTurn != null) {
            unitView.tryMoveToTile(enemyTileToAttackNextTurn.getTileToAttackFrom())
            return true
        }
        return false
    }

    /**
     * Choses the best target in attackableEnemies, this could be a city or a unit.
     */
    @Readonly
    private fun chooseAttackTarget(unit: MapUnitView, attackableEnemies: List<AttackableTileView>): AttackableTileView? {
        // Get the highest valued attackableEnemy
        var highestAttackValue = 0
        var attackTile: AttackableTileView? = null
        // We always have to calculate the attack value even if there is only one attackableEnemy
        for (attackableEnemy in attackableEnemies) {
            val tileToAttack = attackableEnemy.getTileToAttack()
            val tempAttackValue = if (tileToAttack.isCityCenter())
                getCityAttackValue(unit, tileToAttack.getCity()!!, attackableEnemy)
            else getUnitAttackValue(unit, attackableEnemy)
            if (tempAttackValue > highestAttackValue) {
                highestAttackValue = tempAttackValue
                attackTile = attackableEnemy
            }
        }
        // todo For air units, prefer to attack tiles with lower intercept chance
        // Only return that tile if it is actually a good tile to attack
        return if (highestAttackValue > 30) attackTile else null
    }

    @Readonly
    private fun canMakeSacrificialCollateralAttack(
        unitView: MapUnitView,
        attackableTile: AttackableTileView,
        defender: com.unciv.view.CombatantView
    ): Boolean {
        if (!unitView.hasUnique(UniqueType.CollateralDamage)) return false
        val tileToAttack = attackableTile.getTileToAttack()
        if (tileToAttack.militaryUnits.size < 3) return false
        if (unitView.asCombatant().calculateDamageToDefender(defender) <= 0) return false
        val civ = unitView.civ()
        val friendlyAttackersNearby = tileToAttack.getExploredTilesInDistance(2)
            .sumOf { tile -> tile.militaryUnits.count { u -> u.civ() == civ && u != unitView } }
        return friendlyAttackersNearby >= 2
    }

    @Readonly
    private fun getCollateralDamageBonus(attacker: MapUnitView, tileToAttack: com.unciv.view.TileView): Int {
        val collateralUnique = attacker.getMatchingUniques(UniqueType.CollateralDamage).firstOrNull() ?: return 0
        val extraDefenders = (tileToAttack.militaryUnits.size - 1).coerceAtLeast(0)
        if (extraDefenders == 0) return 0
        val maxTargets = collateralUnique.params[1].toIntOrNull() ?: 0
        val hitTargets = extraDefenders.coerceAtMost(maxTargets)
        return hitTargets * 25
    }

    @Readonly
    private fun getCiv4CityAttackValue(
        attacker: MapUnitView,
        city: ForeignCityView,
        attackableEnemy: AttackableTileView,
        canCaptureCity: Boolean
    ): Int {
        val centerTile = city.getCenterTile()
        if (centerTile.militaryUnits.isEmpty()) {
            return if (canCaptureCity) 10000 else 0
        }
        val defender = attackableEnemy.getCombatant() ?: centerTile.getCombatant() ?: city.asCombatant()
        val expectedDamage = attacker.asCombatant().calculateDamageToDefender(defender)
        if (expectedDamage <= 0 && !attacker.hasUnique(UniqueType.CollateralDamage)) return 0
        var attackValue = 140 - defender.getHealth() + expectedDamage + getCollateralDamageBonus(attacker, centerTile)
        if (attacker.getBaseUnit().isProbablySiegeUnit()) attackValue += 100
        return attackValue
    }

    /**
     * Returns a value which represents the attacker's motivation to attack a city.
     * Siege units will almost always attack cities.
     * Base value is 100(Mele) 110(Ranged) standard deviation is around 80 to 130
     */
    @Readonly
    private fun getCityAttackValue(attacker: MapUnitView, city: ForeignCityView, attackableEnemy: AttackableTileView): Int {
        val centerTile = city.getCenterTile()
        val canCaptureCity = attacker.getBaseUnit().isMelee() && !attacker.hasUnique(UniqueType.CannotCaptureCities)
        if (centerTile.getRuleset().modOptions.hasUnique(UniqueType.Civ4StackCombat)) {
            return getCiv4CityAttackValue(attacker, city, attackableEnemy, canCaptureCity)
        }

        val attackerUnit = attacker.asCombatant()
        val cityUnit = city.asCombatant()
        
        if (city.getHealth() == 1)
            return if (canCaptureCity) 10000 // Capture the city immediately!
            else 0 // No reason to attack, we won't make any difference
        
        if (canCaptureCity && city.getHealth() <= attackerUnit.calculateDamageToDefender(cityUnit).coerceAtLeast(1))
            return 10000
            

        if (attacker.getBaseUnit().isMelee()) {
            val battleDamage = attackerUnit.calculateDamageToAttacker(cityUnit)
            if (attacker.unitHealth - battleDamage * 2 <= 0 && !attacker.hasUnique(UniqueType.SelfDestructs)) {
                // The more fiendly units around the city, the more willing we should be to just attack the city
                val friendlyUnitsAroundCity = city.getCenterTile().getExploredTilesInDistance(3)
                    .sumOf { it.militaryUnits.count { u -> u.civ() == attacker.civ() } }
                // If we have more than 4 other units around the city, go for it
                if (friendlyUnitsAroundCity < 5) {
                    val attackerHealthModifier = 1.0 + 1.0 / friendlyUnitsAroundCity.coerceAtLeast(1)
                    if (attacker.unitHealth - battleDamage * attackerHealthModifier <= 0)
                        return 0 // We'll probably die next turn if we attack the city
                }
            }
        }

        var attackValue = 100
        // Siege units should really only attack the city
        if (attacker.getBaseUnit().isProbablySiegeUnit()) attackValue += 100
        // Ranged units don't take damage from the city
        else if (attacker.getBaseUnit().isRanged()) attackValue += 10
        // Lower health cities have a higher priority to attack ranges from -20 to 30
        attackValue -= (city.getHealth() - 60) / 2

        // Add value based on number of units around the city
        val defendingCityCiv = city.owningCiv()
        for (tile in city.getCenterTile().getExploredTilesInDistance(2)) {
            for (militaryUnit in tile.militaryUnits) {
                if (militaryUnit.civ().isAtWarWith(attacker.civ()))
                    attackValue -= 5
                if (militaryUnit.civ().isAtWarWith(defendingCityCiv))
                    attackValue += 15
            }
        }

        return attackValue
    }

    /**
     * Returns a value which represents the attacker's motivation to attack a unit.
     * Base value is 100 and standard deviation is around 80 to 130
     */
    @Readonly
    private fun getUnitAttackValue(attacker: MapUnitView, attackTile: AttackableTileView): Int {
        // Base attack value, there is nothing there...
        var attackValue = Int.MIN_VALUE
        // Prioritize attacking military
        val tileToAttack = attackTile.getTileToAttack()
        val defenderCombatant = attackTile.getCombatant() ?: tileToAttack.getCombatant()
        val militaryUnit = tileToAttack.firstMilitaryUnit()
        val civilianUnit = tileToAttack.civilianUnit
        if (militaryUnit != null && defenderCombatant != null) {
            val expectedDamage = attacker.asCombatant().calculateDamageToDefender(defenderCombatant)
            val collateralBonus = getCollateralDamageBonus(attacker, tileToAttack)
            if (expectedDamage <= 0 && collateralBonus == 0) return 0
            attackValue = 200 - defenderCombatant.getHealth() + // continuously prioritise lower-health units
                expectedDamage + collateralBonus
        } else if (civilianUnit != null) {
            attackValue = 50
            // Only melee units should really attack/capture civilian units, ranged units may be able to capture by moving
            if (attacker.getBaseUnit().isMelee() || attacker.getDistanceToTiles().containsKey(tileToAttack)) {
                if (civilianUnit.getBaseUnit().isGreatPerson) {
                    attackValue += 150
                }
                if (civilianUnit.hasUnique(UniqueType.FoundCity, ignoreConditionals = true)) attackValue += 60
            } else if (attacker.getBaseUnit().isRanged() && !civilianUnit.hasUnique(UniqueType.Uncapturable)) {
                return 10 // Don't shoot civilians that we can capture!
            }
        }
        // Prioritise closer units as they are generally more threatening to this unit
        // Moving around less means we are straying less into enemy territory
        // Average should be around 2.5-5 early game and up to 35 for tanks in late game
        attackValue += (attackTile.getMovementLeftAfterMovingToAttackTile() * 5).toInt()

        return attackValue
    }
}
