package com.unciv.logic.city

import com.unciv.Constants
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class CityHealthTests {

    private val testGame = TestGame()

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(3)
        testGame.ruleset.modOptions.uniques.add(UniqueType.CityHealthMode.text)
    }

    @Test
    fun `fresh water, forests, and dirty terrain features contribute expected health`() {
        val civ = testGame.addCiv()
        val center = testGame.getTile(0, 0)
        center.hasBottomRiver = true

        testGame.getTile(1, 0).addTerrainFeature("Forest")
        testGame.getTile(0, 1).addTerrainFeature("Forest") // 2 forests -> +1 health
        testGame.getTile(-1, 0).addTerrainFeature("Jungle")
        testGame.getTile(0, -1).addTerrainFeature("Jungle")
        testGame.getTile(1, -1).addTerrainFeature("Flood plains") // 3 dirty -> 1 unhealthiness

        val city = testGame.addCity(civ, center)
        city.population.setPopulation(1) // 1 unhealthiness from pop

        val breakdown = city.getHealthBreakdown()
        assertEquals(3, breakdown.health) // 2 fresh water + 1 forests
        assertEquals(2, breakdown.unhealthiness) // 1 pop + 1 terrain
        assertEquals(0, breakdown.excessUnhealthiness)
    }

    @Test
    fun `excess unhealthiness subtracts food in CityStats`() {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        city.population.setPopulation(5) // 5 unhealthiness, 0 health
        city.cityStats.update()

        val breakdown = city.getHealthBreakdown()
        assertEquals(0, breakdown.health)
        assertEquals(5, breakdown.unhealthiness)
        assertEquals(5, breakdown.excessUnhealthiness)
        assertEquals(-5f, city.cityStats.finalStatList["Unhealthiness"]?.food ?: 0f, 0.01f)
    }

    @Test
    fun `buildings, resources, and NoBuildingUnhealthiness affect breakdown`() {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        city.population.setPopulation(2)

        val granary = testGame.createBuilding("[+2] Health")
        val factory = testGame.createBuilding("[-3] Health")
        city.cityConstructions.addBuilding(granary)
        city.cityConstructions.addBuilding(factory)

        val corn = TileResource().apply {
            name = "TestCorn"
            resourceType = ResourceType.Bonus
            uniques.add("[+1] Health")
            improvedBy = listOf("Farm")
            setTransients(testGame.ruleset)
        }
        testGame.ruleset.tileResources[corn.name] = corn
        val farm = TileImprovement().apply { name = "Farm" }
        testGame.ruleset.tileImprovements[farm.name] = farm

        val resourceTile = testGame.getTile(1, 0)
        resourceTile.tileResource = corn
        resourceTile.setImprovement(farm.name, civ)
        civ.cache.updateCivResources()

        var breakdown = city.getHealthBreakdown()
        assertEquals(3, breakdown.health) // 2 granary + 1 corn
        assertEquals(5, breakdown.unhealthiness) // 2 pop + 3 factory
        assertEquals(2, breakdown.excessUnhealthiness)

        val recyclingCenter = testGame.createBuilding(UniqueType.NoBuildingUnhealthiness.text)
        city.cityConstructions.addBuilding(recyclingCenter)
        breakdown = city.getHealthBreakdown()
        assertEquals(3, breakdown.health)
        assertEquals(2, breakdown.unhealthiness) // factory -3 ignored
        assertEquals(0, breakdown.excessUnhealthiness)
    }
}
