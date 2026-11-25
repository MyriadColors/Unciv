package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class Civ4IrrigationTests {

    private val testGame = TestGame()

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(4)
    }

    @Test
    fun `civil service enables chain irrigation from fresh water and biology removes fresh water requirement`() {
        val civ = testGame.addCiv()
        civ.tech.addTechnology("Agriculture")
        val city = testGame.addCity(civ, testGame.getTile(0, 0))

        // Set up: (1,0) is Lakes (fresh water), (2,0) is Plains with Farm, (3,0) is dry Plains
        val lakeTile = testGame.getTile(1, 0)
        lakeTile.baseTerrain = "Lakes"
        lakeTile.setTerrainTransients()

        val irrigatedTile = testGame.getTile(2, 0)
        irrigatedTile.baseTerrain = Constants.plains
        irrigatedTile.setTerrainTransients()
        irrigatedTile.setImprovement("Farm", civ)
        city.expansion.takeOwnership(irrigatedTile)

        val dryTile = testGame.getTile(3, 0)
        dryTile.baseTerrain = Constants.plains
        dryTile.setTerrainTransients()
        city.expansion.takeOwnership(dryTile)

        val isolatedDryTile = testGame.getTile(-3, 0)
        isolatedDryTile.baseTerrain = Constants.plains
        isolatedDryTile.setTerrainTransients()
        city.expansion.takeOwnership(isolatedDryTile)

        val farm = testGame.ruleset.tileImprovements["Farm"]!!

        // Before Civil Service: dryTile is adjacent to irrigated Farm, but chain irrigation is not unlocked
        assertTrue(dryTile.improvementFunctions.isIrrigatedByChain())
        assertFalse(isolatedDryTile.improvementFunctions.isIrrigatedByChain())

        // After Civil Service: chain irrigation unlocks Farm on dryTile
        civ.tech.addTechnology("Civil Service")
        assertTrue(dryTile.improvementFunctions.canBuildImprovement(farm, civ.state))

        // After Biology: isolated dry tile can also build Farm without fresh water
        civ.tech.addTechnology("Biology")
        assertTrue(isolatedDryTile.improvementFunctions.canBuildImprovement(farm, civ.state))
    }
}
