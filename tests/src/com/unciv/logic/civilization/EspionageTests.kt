package com.unciv.logic.civilization

import com.unciv.logic.map.GridCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class EspionageTests {
    private val testGame = TestGame()

    val civA = testGame.addCiv()
    val civB = testGame.addCiv()
    @Before
    fun setup() {
        testGame.gameInfo.gameParameters.espionageEnabled = true
        civA.diplomacyFunctions.makeCivilizationsMeet(civB)
        testGame.makeHexagonalMap(3)
    }

    @Test
    fun `Espionage manager add spy`() {
        val espionageManagerA = civA.espionageManager
        assertEquals(0, espionageManagerA.spyList.size)
        espionageManagerA.addSpy()
        assertEquals(1, espionageManagerA.spyList.size)
    }

    @Test
    fun `Espionage check spy effectiveness reduction unique`() {
        val espionageManagerA = civA.espionageManager
        val spy = espionageManagerA.addSpy()
        val city = civB.addCity(GridCoord(1,1))
        spy.moveTo(city)
        assertEquals(1.0, spy.getEfficiencyModifier(), 0.1)
        val constabulary = testGame.createBuilding("[-25]% enemy spy effectiveness [in this city]")
        city.cityConstructions.addBuilding(constabulary)
        assertEquals(0.75, spy.getEfficiencyModifier(), 0.1)
    }

    @Test
    fun `Spy effectiveness can't go below zero`() {
        val espionageManagerA = civA.espionageManager
        val spy = espionageManagerA.addSpy()
        val city = civB.addCity(GridCoord(1,1))
        spy.moveTo(city)
        repeat(5) {
            city.cityConstructions.addBuilding(testGame.createBuilding("[-25]% enemy spy effectiveness [in this city]"))
        }
        assertTrue(spy.getEfficiencyModifier() >= 0)
    }

}
