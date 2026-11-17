package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.BaseTestRunner
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class FreshWaterTests {
    private var map = TileMap()
    private var ruleSet = Ruleset()

    @Before
    fun initTheWorld() {
        RulesetCache.loadRulesets(noMods = true)
        ruleSet = RulesetCache.getVanillaRuleset()
        map = TileMap(7, 7, ruleSet, worldWrap = false)
        // Lake at (0, 0) gives fresh water to all 8 surrounding square tiles
        map[0, 0].baseTerrain = "Lakes"
        // Cardinal river between (2, 0) and (2, -1) gives fresh water to (2, 0) and (2, -1) only
        map[2, 0].setConnectedByRiver(map[2, -1], true)
        map.setTransients(ruleSet, false)
    }

    @Test
    fun isAdjacentToFreshWater() {
        val expectedFresh = setOf(
            GridCoord(-1, 1), GridCoord(0, 1), GridCoord(1, 1),
            GridCoord(-1, 0), GridCoord(1, 0),
            GridCoord(-1, -1), GridCoord(0, -1), GridCoord(1, -1),
            GridCoord(2, 0), GridCoord(2, -1)
        )
        for (tile in map.values) {
            val isFresh = tile.isAdjacentTo(Constants.freshWater)
            val shouldFresh = tile.position in expectedFresh
            Assert.assertEquals("Fresh water mismatch at $tile", shouldFresh, isFresh)
        }
    }
}
