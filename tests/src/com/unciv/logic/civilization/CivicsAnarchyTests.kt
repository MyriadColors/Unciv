package com.unciv.logic.civilization

import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.models.ruleset.Civic
import com.unciv.models.ruleset.CivicCategory
import com.unciv.models.ruleset.CivicUpkeep
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
class CivicsAnarchyTests {

    private val testGame = TestGame()

    private lateinit var requiredTechName: String

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(3)
        testGame.ruleset.modOptions.uniques.add(UniqueType.CivicsMode.text)
        requiredTechName = testGame.ruleset.technologies.keys.last()
        setupTestCivics()
    }

    private fun setupTestCivics() {
        val govCategory = CivicCategory().apply {
            name = "Government"
            defaultCivic = "Despotism"
        }
        val despotism = Civic().apply {
            name = "Despotism"
            category = govCategory
            upkeep = CivicUpkeep.Low
        }
        val hereditaryRule = Civic().apply {
            name = "Hereditary Rule"
            category = govCategory
            upkeep = CivicUpkeep.Medium
            requiredTech = requiredTechName
            uniques.add("[+1 Happiness] [in all cities]")
        }
        govCategory.civics.add(despotism)
        govCategory.civics.add(hereditaryRule)

        testGame.ruleset.civicCategories[govCategory.name] = govCategory
        testGame.ruleset.civics[despotism.name] = despotism
        testGame.ruleset.civics[hereditaryRule.name] = hereditaryRule
    }

    @Test
    fun `default civics initialize and tech requirement gates adoption`() {
        val civ = testGame.addCiv()
        civ.civics.initDefaults()

        assertEquals("Despotism", civ.civics.activeCivics["Government"])
        val hereditary = testGame.ruleset.civics["Hereditary Rule"]!!
        assertFalse(civ.civics.canAdoptCivic(hereditary))

        civ.tech.addTechnology(requiredTechName)
        assertTrue(civ.civics.canAdoptCivic(hereditary))
    }

    @Test
    fun `switching civics triggers anarchy which freezes city output and decrements on endTurn`() {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        val building = testGame.createBuilding()
        city.cityConstructions.setCurrentConstruction(building.name)
        civ.civics.initDefaults()
        civ.tech.addTechnology(requiredTechName)

        val anarchy = civ.civics.switchCivics(mapOf("Government" to "Hereditary Rule"))
        assertEquals(1, anarchy)
        assertEquals(1, civ.anarchyTurns)
        assertTrue(civ.isInAnarchy())

        city.cityStats.update()
        assertEquals(0f, city.cityStats.currentCityStats.production, 0.01f)
        assertEquals(0f, city.cityStats.currentCityStats.food, 0.01f)

        val storedProdBefore = city.cityConstructions.getWorkDone(building.name)
        TurnManager(civ).endTurn()
        assertEquals(storedProdBefore, city.cityConstructions.getWorkDone(building.name))
        assertEquals(0, civ.anarchyTurns)
        assertFalse(civ.isInAnarchy())
    }

    @Test
    fun `NoAnarchy unique and Golden Age bypass anarchy`() {
        val spiritualCiv = testGame.addCiv(UniqueType.NoAnarchy.text)
        spiritualCiv.civics.initDefaults()
        spiritualCiv.tech.addTechnology(requiredTechName)
        assertEquals(0, spiritualCiv.civics.switchCivics(mapOf("Government" to "Hereditary Rule")))
        assertEquals(0, spiritualCiv.anarchyTurns)

        val goldenCiv = testGame.addCiv()
        goldenCiv.civics.initDefaults()
        goldenCiv.tech.addTechnology(requiredTechName)
        goldenCiv.goldenAges.enterGoldenAge(10)
        assertEquals(0, goldenCiv.civics.switchCivics(mapOf("Government" to "Hereditary Rule")))
        assertEquals(0, goldenCiv.anarchyTurns)
    }

    @Test
    fun `civic upkeep scales with population and respects CivicUpkeepDiscount`() {
        val normalCiv = testGame.addCiv()
        val city1 = testGame.addCity(normalCiv, testGame.getTile(0, 0))
        city1.population.setPopulation(16) // 0.4 * 1 + 0.1 * 16 = 2.0; Medium (1.0) -> 2, Low (0.5) -> 1
        normalCiv.civics.initDefaults()
        assertEquals(1, normalCiv.civics.getCivicUpkeep())

        val organizedCiv = testGame.addCiv("[-50]% Civic upkeep")
        val city2 = testGame.addCity(organizedCiv, testGame.getTile(1, 0))
        city2.population.setPopulation(16)
        organizedCiv.civics.initDefaults()
        organizedCiv.tech.addTechnology(requiredTechName)
        organizedCiv.civics.switchCivics(mapOf("Government" to "Hereditary Rule"))
        // Raw upkeep = 1.0 * 2.0 = 2.0, with -50% -> 1
        assertEquals(1, organizedCiv.civics.getCivicUpkeep())
    }
}
