package com.unciv.logic.battle

import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class Civ4CombatMechanicsTests {

    private val testGame = TestGame()

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(4)
    }

    @Test
    fun `siege unit cannot reduce primary defender below max damage cap`() {
        val attackerCiv = testGame.addCiv()
        val defenderCiv = testGame.addCiv()
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar()

        val catapult = testGame.addUnit("Catapult", attackerCiv, testGame.getTile(0, 0))
        val warrior = testGame.addUnit("Warrior", defenderCiv, testGame.getTile(1, 0))
        warrior.health = 25

        Battle.attack(MapUnitCombatant(catapult), MapUnitCombatant(warrior))

        assertFalse("Siege unit should not kill primary defender at cap", warrior.isDestroyed)
        assertEquals(25, warrior.health)
    }

    @Test
    fun `surviving mounted unit deals flanking damage to enemy siege in stack`() {
        val attackerCiv = testGame.addCiv()
        val defenderCiv = testGame.addCiv()
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar()

        val knight = testGame.addUnit("Knight", attackerCiv, testGame.getTile(0, 0))
        val defenderTile = testGame.getTile(1, 0)
        val warrior = testGame.addUnit("Warrior", defenderCiv, defenderTile)
        val catapult = testGame.addUnit("Catapult", defenderCiv, defenderTile)
        val archer = testGame.addUnit("Archer", defenderCiv, defenderTile)

        Battle.attack(MapUnitCombatant(knight), MapUnitCombatant(warrior))

        assertFalse("Knight should survive against Warrior", knight.isDestroyed)
        assertTrue("Catapult in stack should take flanking damage", catapult.health < 100)
        assertEquals("Non-siege unit should not take flanking damage", 100, archer.health)
    }

    @Test
    fun `first strikes grant attacker uncontested pre-rounds before defender retaliates`() {
        val attackerCiv = testGame.addCiv()
        val defenderCiv = testGame.addCiv()
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar()

        val attacker = testGame.addUnit("Longbowman", attackerCiv, testGame.getTile(0, 0))
        val defender = testGame.addUnit("Warrior", defenderCiv, testGame.getTile(1, 0))
        defender.health = 5

        val damage = Battle.attack(MapUnitCombatant(attacker), MapUnitCombatant(defender))

        assertTrue("Defender with 5 HP should be defeated in First Strike pre-rounds", defender.isDestroyed)
        assertEquals("Attacker should take 0 damage when First Strikes finish defender", 0, damage.defenderDealt)
    }
}
