package com.unciv.logic.city

import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class Civ4CityEconomyTests {

    private val testGame = TestGame()

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(4)
    }

    @Test
    fun `local happiness produces angry citizens when population exceeds happy cap`() {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        city.population.setPopulation(6)
        city.cityStats.update()

        // Base happy cap = 4 + Palace (+1) = 5, population = 6 -> 1 angry citizen
        assertEquals(1, city.getAngryCitizens())
    }

    @Test
    fun `civ4 city maintenance is zero for single capital and positive for distant second city`() {
        val civ = testGame.addCiv()
        val capital = testGame.addCity(civ, testGame.getTile(0, 0))
        capital.population.setPopulation(3)
        capital.cityStats.update()
        assertEquals(0f, capital.cityStats.getCiv4CityMaintenance(), 0.001f)

        val secondCity = testGame.addCity(civ, testGame.getTile(4, 0))
        secondCity.population.setPopulation(5)
        secondCity.cityStats.update()
        assertTrue(secondCity.cityStats.getCiv4CityMaintenance() > 0f)
    }

    @Test
    fun `slavery whipping sacrifices population, adds whip anger, and completes construction`() {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        city.cityConstructions.addBuilding(testGame.createBuilding(UniqueType.CanWhipProduction.text))
        city.population.setPopulation(5)
        val monument = testGame.createBuilding()
        monument.cost = 30
        city.cityConstructions.setCurrentConstruction(monument.name)

        assertTrue(city.cityConstructions.canWhipConstruction(monument))
        assertTrue(city.cityConstructions.whipConstruction(monument))
        assertEquals(4, city.population.population)
        assertEquals(10, city.whipAngerTurns)
        assertTrue(city.cityConstructions.isBuilt(monument.name))
    }

    @Test
    fun `universal suffrage hurries construction with gold`() {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        city.cityConstructions.addBuilding(testGame.createBuilding(UniqueType.CanHurryProductionWithGold.text))
        val monument = testGame.createBuilding()
        monument.cost = 20
        city.cityConstructions.setCurrentConstruction(monument.name)
        civ.addGold(100)

        val expectedCost = city.cityConstructions.getGoldHurryCost(monument)
        assertTrue(city.cityConstructions.canHurryConstructionWithGold(monument))
        assertTrue(city.cityConstructions.hurryConstructionWithGold(monument))
        assertEquals(100 - expectedCost, civ.gold)
        assertTrue(city.cityConstructions.isBuilt(monument.name))
    }

    @Test
    fun `culture ring tiers expand borders at 10 culture and track per-tile culture percentage`() {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        assertEquals(1, city.expansion.getCultureLevel())

        city.expansion.nextTurn(15f)
        assertEquals(2, city.expansion.getCultureLevel())
        assertEquals(100, testGame.getTile(0, 0).getCulturePercentage(civ.civName))
    }

    @Test
    fun `culture slider converts city gold into culture`() {
        val civ = testGame.addCiv()
        testGame.ruleset.modOptions.uniques.add(UniqueType.ConvertGoldToScience.text)
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        civ.tech.goldPercentConvertedToScience = 0f
        civ.tech.goldPercentConvertedToCulture = 0.5f
        city.cityStats.update()

        assertTrue(city.cityStats.finalStatList.containsKey("Gold -> Culture"))
    }

    @Test
    fun `per-city great person points accumulate independently`() {
        val civ = testGame.addCiv()
        val city1 = testGame.addCity(civ, testGame.getTile(0, 0))
        val city2 = testGame.addCity(civ, testGame.getTile(4, 0))
        city1.cityGreatPersonPoints.add("Great Scientist", 40)
        assertEquals(40, city1.cityGreatPersonPoints["Great Scientist"])
        assertEquals(0, city2.cityGreatPersonPoints["Great Scientist"])
    }

    @Test
    fun `three legendary cities milestone completes when 3 cities reach culture level 6`() {
        val civ = testGame.addCiv()
        val c1 = testGame.addCity(civ, testGame.getTile(0, 0))
        val c2 = testGame.addCity(civ, testGame.getTile(3, 0))
        val c3 = testGame.addCity(civ, testGame.getTile(-3, 0))
        c1.expansion.cultureStored = 50000
        c2.expansion.cultureStored = 50000
        c3.expansion.cultureStored = 50000

        val milestone = com.unciv.models.ruleset.Milestone("Have 3 cities with Legendary Culture", testGame.ruleset.victories.values.first())
        assertTrue(milestone.hasBeenCompletedBy(civ))
    }
}
