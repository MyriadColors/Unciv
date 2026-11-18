package com.unciv.logic.automation

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.unit.ReligiousUnitAutomation
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.map.GridCoord
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stats
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import com.unciv.view.MapUnitCombatantView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class Civ4AiTests {

    private val testGame = TestGame()

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(5)
    }

    @Test
    fun `ai civics automation switches out of default civics when superior civics are unlocked`() {
        val civ = testGame.addCiv()
        testGame.addCity(civ, testGame.getTile(0, 0))
        civ.civics.initDefaults()

        assertEquals("Despotism", civ.civics.getActiveCivic("Government")?.name)
        assertEquals("Barbarism", civ.civics.getActiveCivic("Legal")?.name)
        assertEquals("Tribalism", civ.civics.getActiveCivic("Labor")?.name)

        civ.tech.addTechnology("Monarchy")
        civ.tech.addTechnology("Civil Service")
        civ.tech.addTechnology("Bronze Working")

        val anarchyTurns = civ.civics.automateCivics()
        assertTrue(anarchyTurns >= 1)
        assertNotEquals("Despotism", civ.civics.getActiveCivic("Government")?.name)
        assertEquals("Bureaucracy", civ.civics.getActiveCivic("Legal")?.name)
        assertEquals("Slavery", civ.civics.getActiveCivic("Labor")?.name)
    }

    @Test
    fun `slavery whip and universal suffrage gold hurry automation complete constructions`() {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        city.cityConstructions.addBuilding(testGame.createBuilding(UniqueType.CanWhipProduction.text))
        city.population.setPopulation(6)
        city.cityStats.update()

        val granary = testGame.createBuilding()
        granary.cost = 30
        city.cityConstructions.setCurrentConstruction(granary.name)

        civ.civics.automateSlaveryWhip()
        assertTrue(city.cityConstructions.isBuilt(granary.name))
        assertEquals(5, city.population.population)

        // Gold hurry test
        city.cityConstructions.addBuilding(testGame.createBuilding(UniqueType.CanHurryProductionWithGold.text))
        val library = testGame.createBuilding()
        library.cost = 25
        city.cityConstructions.setCurrentConstruction(library.name)
        city.cityConstructions.addProductionPoints(5)
        civ.addGold(300)

        UseGoldAutomation.useGold(civ)
        assertTrue(city.cityConstructions.isBuilt(library.name))
    }

    @Test
    fun `civ4 religion ai switches state religion and missionary spreads state religion`() {
        val civ = testGame.addCiv()
        val city1 = testGame.addCity(civ, testGame.getTile(0, 0))
        val city2 = testGame.addCity(civ, testGame.getTile(0, 3))
        val city3 = testGame.addCity(civ, testGame.getTile(3, 0))
        val city4 = testGame.addCity(civ, testGame.getTile(3, 3))

        civ.civ4Religion.foundReligion("Buddhism", city1)
        civ.civ4Religion.endTurn()
        assertEquals("Buddhism", civ.civ4Religion.stateReligion)
        civ.anarchyTurns = 0

        // Hinduism is founded in city2 and spreads to city3 and city4 -> score 5 (3 cities + 2 holy city) vs Buddhism's 3
        city1.presentReligions.clear()
        city1.presentReligions.add("Buddhism")
        city2.presentReligions.clear()
        city3.presentReligions.clear()
        city4.presentReligions.clear()
        civ.civ4Religion.foundReligion("Hinduism", city2)
        civ.civ4Religion.spreadReligionToCity("Hinduism", city3)
        civ.civ4Religion.spreadReligionToCity("Hinduism", city4)
        civ.civ4Religion.endTurn()
        assertEquals("Hinduism", civ.civ4Religion.stateReligion)

        // Automated Missionary at city1 spreads Hinduism to city1
        city1.presentReligions.remove("Hinduism")
        val missionary = testGame.addUnit("Missionary", civ, city1.getCenterTile())
        val gameView = GameView(testGame.gameInfo, civ)
        val missionaryView = gameView.getMapUnitView(missionary)
        assertTrue(missionaryView.maySpreadReligion())

        ReligiousUnitAutomation.automateMissionary(missionaryView)
        assertTrue("Hinduism" in city1.presentReligions)
    }

    @Test
    fun `siege unit is recognized as siege, bombards city defenses, and caps max attack damage`() {
        val attackerCiv = testGame.addCiv()
        val defenderCiv = testGame.addCiv()
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar()

        val enemyCity = testGame.addCity(defenderCiv, testGame.getTile(0, 0))
        enemyCity.cityConstructions.addBuilding(testGame.createBuilding("[50]% City Defense"))
        val archer = testGame.addUnit("Archer", defenderCiv, enemyCity.getCenterTile())
        val spearman = testGame.addUnit("Spearman", defenderCiv, enemyCity.getCenterTile())
        assertTrue(enemyCity.getCityDefenseBonus() > 0)

        val catapult = testGame.addUnit("Catapult", attackerCiv, testGame.getTile(0, 1))
        assertTrue(catapult.baseUnit.isProbablySiegeUnit())

        // TileView.getCombatant should return a MapUnitCombatantView (best defender) on defended city under Civ4StackCombat
        val gameView = GameView(testGame.gameInfo, attackerCiv)
        val enemyCityTileView = gameView.getTile(enemyCity.getCenterTile())
        val combatantView = enemyCityTileView.getCombatant()
        assertTrue(combatantView is MapUnitCombatantView)

        // Catapult MaxAttackDamage (75%) prevents damage below 25 HP
        archer.health = 25
        val cappedDamage = BattleDamage.calculateDamageToDefender(
            MapUnitCombatant(catapult),
            MapUnitCombatant(archer),
            catapult.currentTile,
            0.5f
        )
        assertEquals(0, cappedDamage)
        archer.health = 100
        spearman.health = 100

        // Automated Catapult adjacent to defended city with defense bonus executes Bombard
        val initialDefenseDamage = enemyCity.defenseDamageTaken
        UnitAutomation.automateUnitMoves(gameView.getMapUnitView(catapult), AutomationCache())
        assertTrue(enemyCity.defenseDamageTaken > initialDefenseDamage)
    }

    @Test
    fun `worker automation respects fat cross corners, preserves cottages, and citizen ranking boosts upgrading tiles`() {
        val civ = testGame.addCiv()
        civ.tech.addTechnology("Pottery")
        civ.tech.addTechnology("Agriculture")
        val city = testGame.addCity(civ, testGame.getTile(0, 0))

        // Corner tile (2, 2) is outside the 21-tile Fat Cross
        val cornerTile = testGame.getTile(2, 2)
        val fatCrossEdgeTile = testGame.getTile(2, 1)
        city.expansion.takeOwnership(fatCrossEdgeTile)

        assertFalse(city.isTileInWorkRange(cornerTile))
        assertTrue(city.isTileInWorkRange(fatCrossEdgeTile))

        // Place a Town on fatCrossEdgeTile and ensure WorkerAutomation does not overwrite it
        fatCrossEdgeTile.baseTerrain = "Grassland"
        fatCrossEdgeTile.setTerrainTransients()
        fatCrossEdgeTile.setImprovement("Town", civ)
        val worker = testGame.addUnit("Worker", civ, fatCrossEdgeTile)
        val gameView = GameView(testGame.gameInfo, civ)
        val workerAutomation = civ.getWorkerAutomation()
        workerAutomation.automateWorkerAction(
            gameView.getMapUnitView(worker),
            hashSetOf(),
            AutomationCache()
        )
        assertEquals("Town", fatCrossEdgeTile.improvement)
        assertNull(fatCrossEdgeTile.improvementInProgress)

        // Citizen tile ranking gives maturation bonus to Cottage over unimproved tile with identical immediate stats
        fatCrossEdgeTile.setImprovement("Cottage", civ)
        val baseRank = Automation.rankStatsForCityWork(Stats(food = 2f, gold = 1f), city, false, null)
        val cottageRank = Automation.rankStatsForCityWork(Stats(food = 2f, gold = 1f), city, false, fatCrossEdgeTile)
        assertTrue(cottageRank > baseRank)
    }
}
