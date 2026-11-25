package com.unciv.logic.civilization

import com.unciv.models.ruleset.MilestoneType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class Civ4SpaceshipTests {

    private val testGame = TestGame()

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(4)
    }

    @Test
    fun `spaceship auto-launches with travel countdown and capital capture aborts flight`() {
        val civ = testGame.addCiv()
        val enemy = testGame.addCiv()
        val capital = testGame.addCity(civ, testGame.getTile(0, 0))
        testGame.addCity(civ, testGame.getTile(3, 0)) // Secondary city so civ survives capital loss
        testGame.addCity(enemy, testGame.getTile(-3, 0))

        val ssVictory = testGame.ruleset.victories["Scientific"]!!
        val ssMilestone = ssVictory.milestoneObjects.last()

        // Complete the final Spaceship building in capital
        capital.cityConstructions.addBuilding("Spaceship")

        testGame.gameInfo.turns = 100
        civ.victoryManager.checkSpaceshipLaunch()
        assertEquals(100, civ.victoryManager.spaceshipLaunchedTurn)
        assertFalse("Should not win immediately on launch turn", ssMilestone.hasBeenCompletedBy(civ))

        // Advance 9 turns: still in flight
        testGame.gameInfo.turns = 109
        assertFalse("Should still be in flight after 9 turns", ssMilestone.hasBeenCompletedBy(civ))

        // Advance to 10 turns: arrival!
        testGame.gameInfo.turns = 110
        assertTrue("Should win after 10 turns of travel", ssMilestone.hasBeenCompletedBy(civ))

        // Reset to turn 105 and capture capital -> flight aborts
        testGame.gameInfo.turns = 105
        capital.moveToCiv(enemy)
        assertEquals(-1, civ.victoryManager.spaceshipLaunchedTurn)
        assertFalse(ssMilestone.hasBeenCompletedBy(civ))
    }
}
