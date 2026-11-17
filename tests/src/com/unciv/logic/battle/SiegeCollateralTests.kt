package com.unciv.logic.battle

import com.unciv.logic.city.managers.CityTurnManager
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class SiegeCollateralTests {

    private val testGame = TestGame()

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(4)
        testGame.ruleset.modOptions.uniques.add(UniqueType.AllowUnitStacking.text)
        testGame.ruleset.modOptions.uniques.add(UniqueType.Civ4StackCombat.text)
    }

    @Test
    fun `city defense bonus protects garrison and bombard reduces it then regenerates`() {
        val attackerCiv = testGame.addCiv()
        val defenderCiv = testGame.addCiv()
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar()

        val cityTile = testGame.getTile(0, 0)
        val adjacentTile = testGame.getTile(0, 1)

        val wallBuilding = testGame.createBuilding("[50]% City Defense")
        val city = testGame.addCity(defenderCiv, cityTile)
        city.cityConstructions.addBuilding(wallBuilding)

        val garrison = testGame.addUnit("Warrior", defenderCiv, cityTile)
        val siegeBase = testGame.createBaseUnit(
            uniques = arrayOf("Can bombard city defenses by [15]% per turn")
        ).apply { strength = 5 }
        val siege = testGame.addUnit(siegeBase.name, attackerCiv, adjacentTile)

        assertEquals(50, city.getCityDefenseBonus())
        val defMods = BattleDamage.getDefenceModifiers(
            MapUnitCombatant(siege),
            MapUnitCombatant(garrison),
            adjacentTile
        )
        assertEquals(50, defMods["City Defense"])

        val invoked = UnitActions.invokeUnitAction(siege, UnitActionType.Bombard)
        assertTrue(invoked)
        assertEquals(35, city.getCityDefenseBonus())
        assertEquals(15, city.defenseDamageTaken)
        assertEquals(0f, siege.currentMovement)
        assertFalse(siege.canAttack())

        CityTurnManager(city).endTurn()
        assertEquals(10, city.defenseDamageTaken)
        assertEquals(40, city.getCityDefenseBonus())
    }

    @Test
    fun `max attack damage caps defender damage and withdraws attacker alive`() {
        val attackerCiv = testGame.addCiv()
        val defenderCiv = testGame.addCiv()
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar()

        val attackerTile = testGame.getTile(0, 0)
        val defenderTile = testGame.getTile(0, 1)

        val siegeBase = testGame.createBaseUnit(
            uniques = arrayOf("Maximum [75]% damage dealt to primary defender")
        ).apply { strength = 50 }
        val siege = testGame.addUnit(siegeBase.name, attackerCiv, attackerTile)
        val defender = testGame.addUnit("Warrior", defenderCiv, defenderTile)

        Battle.attack(MapUnitCombatant(siege), MapUnitCombatant(defender))

        assertTrue("Siege unit should survive after hitting MaxAttackDamage cap", !siege.isDestroyed)
        assertFalse("Defender should not be killed past MaxAttackDamage cap", defender.isDestroyed)
        assertTrue("Defender health should be at least 25 HP", defender.health >= 25)
        assertEquals("Withdrawing siege should remain on its own tile", attackerTile, siege.getTile())
    }

    @Test
    fun `outmatched siege unit dies normally before defender reaches cap`() {
        val attackerCiv = testGame.addCiv()
        val defenderCiv = testGame.addCiv()
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar()

        val attackerTile = testGame.getTile(0, 0)
        val defenderTile = testGame.getTile(0, 1)

        val siegeBase = testGame.createBaseUnit(
            uniques = arrayOf("Maximum [75]% damage dealt to primary defender")
        ).apply { strength = 2 }
        val siege = testGame.addUnit(siegeBase.name, attackerCiv, attackerTile)
        val defenderBase = testGame.createBaseUnit().apply { strength = 40 }
        val defender = testGame.addUnit(defenderBase.name, defenderCiv, defenderTile)

        Battle.attack(MapUnitCombatant(siege), MapUnitCombatant(defender))

        assertTrue("Severely outmatched siege should die normally", siege.isDestroyed)
        assertFalse(defender.isDestroyed)
        assertTrue(defender.health > 25)
    }

    @Test
    fun `collateral damage hits up to maxTargets other units and respects immunity`() {
        val attackerCiv = testGame.addCiv()
        val defenderCiv = testGame.addCiv()
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar()

        val attackerTile = testGame.getTile(0, 0)
        val stackTile = testGame.getTile(0, 1)

        val siegeBase = testGame.createBaseUnit(
            uniques = arrayOf(
                "Deals [20]% collateral damage to up to [2] units",
                "Maximum [75]% damage dealt to primary defender"
            )
        ).apply { strength = 10 }
        val siege = testGame.addUnit(siegeBase.name, attackerCiv, attackerTile)

        val primaryDefenderBase = testGame.createBaseUnit().apply { strength = 15 }
        val primaryDefender = testGame.addUnit(primaryDefenderBase.name, defenderCiv, stackTile)

        val immuneBase = testGame.createBaseUnit(
            uniques = arrayOf("Immune to collateral damage")
        ).apply { strength = 8 }
        val immuneUnit = testGame.addUnit(immuneBase.name, defenderCiv, stackTile)

        val other1 = testGame.addUnit("Warrior", defenderCiv, stackTile)
        val other2 = testGame.addUnit("Warrior", defenderCiv, stackTile)
        val other3 = testGame.addUnit("Warrior", defenderCiv, stackTile)

        val bestDefender = Battle.getMapCombatantOfTile(stackTile, MapUnitCombatant(siege))!!
        assertEquals(primaryDefender, (bestDefender as MapUnitCombatant).unit)

        Battle.attack(MapUnitCombatant(siege), bestDefender)

        assertEquals("Immune unit should take 0 collateral damage", 100, immuneUnit.health)
        assertTrue("First collateral target should be damaged", other1.health in 1 until 100)
        assertTrue("Second collateral target should be damaged", other2.health in 1 until 100)
        assertEquals("Third unit exceeds maxTargets=2 and should be untouched", 100, other3.health)
    }
}
