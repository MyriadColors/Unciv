package com.unciv.logic.city

import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class Civ4LocalHappinessTests {

    private val testGame = TestGame()

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(4)
    }

    @Test
    fun `hereditary rule grants happiness per garrisoned military unit`() {
        val civ = testGame.addCiv()
        civ.tech.addTechnology("Monarchy")
        civ.civics.switchCivics(mapOf("Government" to "Hereditary Rule"))
        civ.anarchyTurns = 0

        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        testGame.addUnit("Warrior", civ, city.getCenterTile())
        testGame.addUnit("Warrior", civ, city.getCenterTile())
        testGame.addUnit("Warrior", civ, city.getCenterTile())
        city.cityStats.update()

        assertEquals(3f, city.cityStats.happinessList["Military Police"])
    }

    @Test
    fun `war weariness applies only at war and clamps mitigation at zero`() {
        val civ = testGame.addCiv()
        val enemy = testGame.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)

        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        city.population.setPopulation(10)
        civ.addWarWeariness(enemy.civName, 5f)
        city.cityStats.update()

        // At peace: no war weariness unhappiness
        assertNull(city.cityStats.happinessList["War Weariness"])

        // Declare war: 5 * 10 / 10 = 5 unhappiness
        civ.getDiplomacyManager(enemy)!!.declareWar()
        city.cityStats.update()
        assertEquals(-5f, city.cityStats.happinessList["War Weariness"])

        // Police State (-50%) reduces to floor(5 * 0.5) = 2 unhappiness
        civ.tech.addTechnology("Fascism")
        civ.civics.switchCivics(mapOf("Government" to "Police State"))
        civ.anarchyTurns = 0
        city.cityStats.update()
        assertEquals(-2f, city.cityStats.happinessList["War Weariness"])
    }

    @Test
    fun `conquered city suffers motherland unhappiness from foreign tile culture`() {
        val founder = testGame.addCiv()
        val conqueror = testGame.addCiv()
        val tile = testGame.getTile(0, 0)
        val city = testGame.addCity(founder, tile)
        city.population.setPopulation(8)
        tile.tileCulture[founder.civName] = 80f
        tile.tileCulture[conqueror.civName] = 20f

        city.moveToCiv(conqueror)
        city.cityStats.update()

        val motherland = city.cityStats.happinessList["Yearn for Motherland"] ?: 0f
        assertTrue("Conquered city with 80% foreign culture should have motherland unhappiness", motherland < 0f)
    }
}
