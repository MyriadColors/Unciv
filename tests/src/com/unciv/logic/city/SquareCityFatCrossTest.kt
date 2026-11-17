package com.unciv.logic.city

import com.unciv.logic.map.GridCoord
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class SquareCityFatCrossTest {
    @Test
    fun fatCrossHas21TilesAndRespectsModderOverride() {
        val game = TestGame()
        game.makeRectangularMap(7, 7)
        val civ = game.addCiv()
        val city = game.addCity(civ, game.getTile(GridCoord.Zero))

        Assert.assertEquals(21, city.tilesInRange.size)
        Assert.assertFalse(city.isTileInWorkRange(game.getTile(GridCoord.of(2, 2))))
        Assert.assertTrue(city.isTileInWorkRange(game.getTile(GridCoord.of(2, 1))))

        game.ruleset.modOptions.constants.cityWorkRangeExcludeCorners = false
        city.setTransients(civ)
        Assert.assertEquals(25, city.tilesInRange.size)
        Assert.assertTrue(city.isTileInWorkRange(game.getTile(GridCoord.of(2, 2))))
    }
}
