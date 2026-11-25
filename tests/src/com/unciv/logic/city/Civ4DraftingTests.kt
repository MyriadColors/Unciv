package com.unciv.logic.city

import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class Civ4DraftingTests {

    private val testGame = TestGame()

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(4)
    }

    @Test
    fun `draftUnit spawns foot soldier, reduces pop, adds draft anger, and enforces per-turn cap`() {
        val civ = testGame.addCiv()
        civ.tech.addTechnology("Nationalism")
        civ.civics.switchCivics(mapOf("Legal" to "Nationhood"))
        civ.anarchyTurns = 0

        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        city.population.setPopulation(9)

        assertTrue(city.cityConstructions.canDraftUnit())
        val drafted = city.cityConstructions.draftUnit()
        assertNotNull(drafted)
        assertEquals(8, city.population.population)
        assertEquals(10, city.draftAngerTurns)
        assertEquals(1, civ.draftsThisTurn)
        assertEquals(-3f, city.cityStats.happinessList["Draft anger"])

        // Exhaust empire per-turn cap (3)
        civ.draftsThisTurn = 3
        assertFalse(city.cityConstructions.canDraftUnit())
    }

    @Test
    fun `canDraftUnit rejects low population and puppeted or razing cities`() {
        val civ = testGame.addCiv()
        civ.tech.addTechnology("Nationalism")
        civ.civics.switchCivics(mapOf("Legal" to "Nationhood"))
        civ.anarchyTurns = 0

        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        city.population.setPopulation(5)
        assertFalse("Pop 5 should not be draftable", city.cityConstructions.canDraftUnit())

        city.population.setPopulation(8)
        city.isPuppet = true
        assertFalse("Puppet city should not be draftable", city.cityConstructions.canDraftUnit())

        city.isPuppet = false
        city.isBeingRazed = true
        assertFalse("Razing city should not be draftable", city.cityConstructions.canDraftUnit())
    }
}
