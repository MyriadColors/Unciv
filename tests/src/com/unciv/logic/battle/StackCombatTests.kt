package com.unciv.logic.battle

import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class StackCombatTests {

    private val testGame = TestGame()

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(4)
        testGame.ruleset.modOptions.uniques.add(UniqueType.AllowUnitStacking.text)
        testGame.ruleset.modOptions.uniques.add(UniqueType.Civ4StackCombat.text)
    }

    @Test
    fun `friendly military units can stack on the same tile`() {
        val civ = testGame.addCiv()
        val tile1 = testGame.getTile(0, 0)
        val tile2 = testGame.getTile(0, 1)
        val unit1 = testGame.addUnit("Warrior", civ, tile1)
        val unit2 = testGame.addUnit("Warrior", civ, tile2)

        assertTrue(unit2.movement.canMoveTo(tile1))
        unit2.movement.moveToTile(tile1)

        assertEquals(2, tile1.militaryUnits.size)
        assertTrue(tile1.militaryUnits.contains(unit1))
        assertTrue(tile1.militaryUnits.contains(unit2))
    }

    @Test
    fun `best defender is selected when attacking a stack`() {
        val attackerCiv = testGame.addCiv()
        val defenderCiv = testGame.addCiv()
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar()

        val attackerTile = testGame.getTile(0, 0)
        val stackTile = testGame.getTile(0, 1)

        val horseman = testGame.addUnit("Horse Archer", attackerCiv, attackerTile)
        testGame.addUnit("Warrior", defenderCiv, stackTile)
        val spearman = testGame.addUnit("Spearman", defenderCiv, stackTile)

        val selected = Battle.getBestDefender(stackTile, MapUnitCombatant(horseman))
        assertSame(spearman, selected)

        val combatant = Battle.getMapCombatantOfTile(stackTile, MapUnitCombatant(horseman))
        assertTrue(combatant is MapUnitCombatant)
        assertSame(spearman, (combatant as MapUnitCombatant).unit)
    }

    @Test
    fun `attacking defended city fights garrison and empty city is captured immediately`() {
        val attackerCiv = testGame.addCiv()
        val defenderCiv = testGame.addCiv()
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar()

        val city = testGame.addCity(defenderCiv, testGame.getTile(0, 1))
        val cityTile = city.getCenterTile()
        val garrison = testGame.addUnit("Warrior", defenderCiv, cityTile)
        val attacker = testGame.addUnit("Warrior", attackerCiv, testGame.getTile(0, 0))

        // Defended city targets the garrisoned unit, not CityCombatant
        val defendedTarget = Battle.getMapCombatantOfTile(cityTile, MapUnitCombatant(attacker))
        assertTrue(defendedTarget is MapUnitCombatant)
        assertSame(garrison, (defendedTarget as MapUnitCombatant).unit)

        // Empty city targets CityCombatant and is captured immediately on melee attack
        garrison.removeFromTile()
        val emptyTarget = Battle.getMapCombatantOfTile(cityTile, MapUnitCombatant(attacker))
        assertTrue(emptyTarget is CityCombatant)

        Battle.attack(MapUnitCombatant(attacker), emptyTarget!!)
        assertSame(attackerCiv, city.civ)
        assertSame(cityTile, attacker.getTile())
    }

    @Test
    fun `city bombardment and two-tile land ranged attacks are disabled under Civ4StackCombat`() {
        val attackerCiv = testGame.addCiv()
        val defenderCiv = testGame.addCiv()
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar()

        val city = testGame.addCity(attackerCiv, testGame.getTile(0, 0))
        val enemy = testGame.addUnit("Warrior", defenderCiv, testGame.getTile(0, 2))
        val archer = testGame.addUnit("Archer", attackerCiv, testGame.getTile(0, 0))

        assertFalse(city.canBombard())
        assertTrue(TargetHelper.getBombardableTiles(city).toList().isEmpty())

        val attackable = TargetHelper.getAttackableEnemies(
            archer,
            archer.movement.getDistanceToTiles(),
            stayOnTile = true
        )
        assertTrue(attackable.none { it.tileToAttack == enemy.getTile() })
    }
}
