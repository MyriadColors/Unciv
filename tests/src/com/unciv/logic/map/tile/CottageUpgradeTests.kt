package com.unciv.logic.map.tile

import com.unciv.Constants
import com.unciv.logic.city.managers.CityTurnManager
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class CottageUpgradeTests {

    private val testGame = TestGame()

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(3)
    }

    @Test
    fun `worked improvement increments turns and upgrades at threshold while unworked does not`() {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        city.population.setPopulation(1)

        val hamlet = testGame.createTileImprovement()
        val cottage = testGame.createTileImprovement("Upgrades to [${hamlet.name}] after [3] turns worked")

        val workedTile = testGame.getTile(1, 0)
        val unworkedTile = testGame.getTile(0, 1)
        workedTile.setImprovement(cottage.name, civ)
        unworkedTile.setImprovement(cottage.name, civ)

        city.workedTiles = hashSetOf(workedTile.position)
        city.lockedTiles = hashSetOf(workedTile.position)

        val turnManager = CityTurnManager(city)
        turnManager.endTurn()
        assertEquals(1, workedTile.improvementWorkedTurns)
        assertEquals(cottage.name, workedTile.improvement)
        assertEquals(0, unworkedTile.improvementWorkedTurns)

        turnManager.endTurn()
        assertEquals(2, workedTile.improvementWorkedTurns)
        assertEquals(cottage.name, workedTile.improvement)

        turnManager.endTurn()
        assertEquals(hamlet.name, workedTile.improvement)
        assertEquals(0, workedTile.improvementWorkedTurns)
        assertEquals(0, unworkedTile.improvementWorkedTurns)
    }

    @Test
    fun `growth rate unique doubles worked turn progression`() {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        city.population.setPopulation(5)

        val hamlet = testGame.createTileImprovement()
        val cottage = testGame.createTileImprovement("Upgrades to [${hamlet.name}] after [4] turns worked")
        val emancipationBuilding = testGame.createBuilding("[${cottage.name}] improvements grow [+100]% faster")
        city.cityConstructions.addBuilding(emancipationBuilding)

        val workedTile = testGame.getTile(1, 0)
        workedTile.setImprovement(cottage.name, civ)
        city.workedTiles = hashSetOf(workedTile.position)

        val turnManager = CityTurnManager(city)
        turnManager.endTurn()
        assertEquals(2, workedTile.improvementWorkedTurns)
        assertEquals(cottage.name, workedTile.improvement)

        turnManager.endTurn()
        assertEquals(hamlet.name, workedTile.improvement)
        assertEquals(0, workedTile.improvementWorkedTurns)
    }

    @Test
    fun `pillaging and replacement reset worked turns and pillaged tile does not progress`() {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        city.population.setPopulation(5)

        testGame.ruleset.tileImprovements[Constants.repair] = TileImprovement().apply { name = Constants.repair }
        val hamlet = testGame.createTileImprovement()
        val cottage = testGame.createTileImprovement("Upgrades to [${hamlet.name}] after [5] turns worked")
        val farm = testGame.createTileImprovement()

        val workedTile = testGame.getTile(1, 0)
        workedTile.setImprovement(cottage.name, civ)
        city.workedTiles = hashSetOf(workedTile.position)

        val turnManager = CityTurnManager(city)
        turnManager.endTurn()
        turnManager.endTurn()
        assertEquals(2, workedTile.improvementWorkedTurns)

        // Pillaging resets counter and blocks further progression
        workedTile.setPillaged()
        assertTrue(workedTile.improvementIsPillaged)
        assertEquals(0, workedTile.improvementWorkedTurns)

        turnManager.endTurn()
        assertEquals(0, workedTile.improvementWorkedTurns)

        // Repairing and working again, then replacing with another improvement resets counter
        workedTile.setRepaired()
        turnManager.endTurn()
        assertEquals(1, workedTile.improvementWorkedTurns)

        workedTile.setImprovement(farm.name, civ)
        assertEquals(farm.name, workedTile.improvement)
        assertEquals(0, workedTile.improvementWorkedTurns)
    }
}
