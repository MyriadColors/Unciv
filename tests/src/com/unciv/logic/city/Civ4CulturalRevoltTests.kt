package com.unciv.logic.city

import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class Civ4CulturalRevoltTests {

    private val testGame = TestGame()

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(4)
    }

    @Test
    fun `foreign cultural dominance triggers revolt on first hit and flips city on second`() {
        val owner = testGame.addCiv()
        val influencer = testGame.addCiv()
        testGame.addCity(owner, testGame.getTile(-2, 0)) // Capital (exempt)
        val borderCity = testGame.addCity(owner, testGame.getTile(1, 0))
        testGame.addCity(influencer, testGame.getTile(3, 0))

        borderCity.getCenterTile().tileCulture[owner.civName] = 20f
        borderCity.getCenterTile().tileCulture[influencer.civName] = 80f

        // First revolt puts city in resistance
        borderCity.expansion.processCulturalRevoltCheck(forceRevoltRoll = true)
        assertEquals(1, borderCity.culturalRevoltCount)
        assertTrue(borderCity.isInResistance())
        assertEquals(owner, borderCity.civ)

        // Clear resistance and trigger 2nd revolt -> city flips to influencer
        borderCity.removeFlag(CityFlags.Resistance)
        borderCity.expansion.processCulturalRevoltCheck(forceRevoltRoll = true)
        assertEquals(influencer, borderCity.civ)
        assertEquals(0, borderCity.culturalRevoltCount)
    }

    @Test
    fun `strong garrison suppresses cultural revolt and capitals never flip`() {
        val owner = testGame.addCiv()
        val influencer = testGame.addCiv()
        val capital = testGame.addCity(owner, testGame.getTile(-2, 0))
        val borderCity = testGame.addCity(owner, testGame.getTile(1, 0))
        testGame.addCity(influencer, testGame.getTile(3, 0))

        // Capital should never revolt even with 90% foreign culture
        capital.getCenterTile().tileCulture[owner.civName] = 10f
        capital.getCenterTile().tileCulture[influencer.civName] = 90f
        capital.expansion.processCulturalRevoltCheck(forceRevoltRoll = true)
        assertEquals(0, capital.culturalRevoltCount)
        assertFalse(capital.isInResistance())

        // Strong garrison suppresses revolt in border city
        borderCity.getCenterTile().tileCulture[owner.civName] = 20f
        borderCity.getCenterTile().tileCulture[influencer.civName] = 80f
        testGame.addUnit("Knight", owner, borderCity.getCenterTile())
        testGame.addUnit("Knight", owner, borderCity.getCenterTile())
        borderCity.expansion.processCulturalRevoltCheck(forceRevoltRoll = true)
        assertEquals(0, borderCity.culturalRevoltCount)
        assertFalse(borderCity.isInResistance())
    }
}
