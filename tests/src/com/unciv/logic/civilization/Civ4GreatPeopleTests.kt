package com.unciv.logic.civilization

import com.unciv.logic.city.CityFlags
import com.unciv.logic.city.managers.CityTurnManager
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.logic.map.mapunit.actions.UnitActionsGreatPerson
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.Promotion
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class Civ4GreatPeopleTests {

    private val testGame = TestGame()

    private fun MutableCollection<String>.addIfMissing(value: String) {
        if (value !in this) add(value)
    }

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(4)
        testGame.ruleset.modOptions.uniques.addIfMissing(UniqueType.Civ4GreatPeopleMode.text)
        testGame.ruleset.modOptions.uniques.addIfMissing(UniqueType.AllowUnitStacking.text)

        // Ensure Great People units have Civ IV uniques & lightbulb preferences in the test ruleset
        testGame.ruleset.units["Great Scientist"]?.apply {
            uniques.addIfMissing("Settling in a city provides [+1 Production, +6 Science]")
            lightbulbPreferences = arrayListOf("Writing", "Calendar", "Philosophy")
            setRuleset(testGame.ruleset)
        }
        testGame.ruleset.units["Great Artist"]?.apply {
            uniques.addIfMissing("Settling in a city provides [+3 Gold, +12 Culture]")
            uniques.addIfMissing("Can create a Great Work for [4000] Culture in a city")
            uniques.addIfMissing("Can hurry technology research")
            lightbulbPreferences = arrayListOf("Drama and Poetry", "Writing")
            setRuleset(testGame.ruleset)
        }
        testGame.ruleset.units["Great General"]?.apply {
            uniques.addIfMissing("Settling in a city provides [2] XP to trained military units")
            uniques.addIfMissing("Can attach to a military unit as a Warlord")
            setRuleset(testGame.ruleset)
        }

        // Add Warlord + Warlord-only promotions for testing
        if ("Warlord" !in testGame.ruleset.unitPromotions) {
            testGame.ruleset.unitPromotions["Warlord"] = Promotion().apply {
                name = "Warlord"
                uniques.add("Unavailable")
                uniques.add("[-100]% Gold cost of upgrading")
            }
        }
        if ("Morale" !in testGame.ruleset.unitPromotions) {
            testGame.ruleset.unitPromotions["Morale"] = Promotion().apply {
                name = "Morale"
                uniques.add("[+1] Movement")
                uniques.add("Only available <for units with [Warlord]>")
                unitTypes = listOf("Sword")
            }
        }
    }

    @Test
    fun `per-city mixed GP pool escalates threshold, resets city pool, and suppresses Civ V pool`() {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(0, 0))

        val gppBuilding = Building().apply {
            name = "TestGPBuilding"
            greatPersonPoints["Great Scientist"] = 60
            greatPersonPoints["Great Artist"] = 40
        }
        testGame.ruleset.buildings[gppBuilding.name] = gppBuilding
        city.cityConstructions.addBuilding(gppBuilding)

        assertEquals(100, civ.greatPeople.getCiv4PointsRequiredForNextGreatPerson())

        // Run city endTurn: 60 + 40 = 100 >= 100 -> spawns 1 GP, clears city pool, escalates threshold to 200
        CityTurnManager(city).endTurn()

        assertEquals(1, civ.greatPeople.civ4GreatPeopleBorn)
        assertEquals(200, civ.greatPeople.getCiv4PointsRequiredForNextGreatPerson())
        assertTrue(city.cityGreatPersonPoints.isEmpty())
        assertTrue(civ.units.getCivUnits().any { it.baseUnit.name == "Great Scientist" || it.baseUnit.name == "Great Artist" })

        // Verify odds calculation on partial points
        city.cityGreatPersonPoints["Great Scientist"] = 75
        city.cityGreatPersonPoints["Great Artist"] = 25
        val odds = civ.greatPeople.getCiv4GreatPersonOdds(city.cityGreatPersonPoints)
        assertEquals(75f, odds["Great Scientist"] ?: 0f, 0.01f)
        assertEquals(25f, odds["Great Artist"] ?: 0f, 0.01f)

        // Verify Civ V empire GP pool is suppressed
        civ.greatPeople.greatPersonPointsCounter["Great Scientist"] = 500
        assertNull(civ.greatPeople.getNewGreatPerson())
    }

    @Test
    fun `lightbulb prefers ruleset lightbulbPreferences and grants base plus pop-scaled beakers`() {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        city.population.setPopulation(5)
        // Ensure Pottery is researched so Writing is researchable
        civ.tech.addTechnology("Pottery", showNotification = false)
        assertTrue(civ.tech.canBeResearched("Writing"))

        val gs = testGame.addUnit("Great Scientist", civ, testGame.getTile(0, 0))
        val targetTech = UnitActionsGreatPerson.getCiv4LightbulbTech(gs)
        assertEquals("Writing", targetTech?.name)

        val expectedBeakers = ((1500 + 3 * 5) * civ.gameInfo.speed.scienceCostModifier).toInt()
        assertEquals(expectedBeakers, UnitActionsGreatPerson.getCiv4LightbulbBeakers(gs))

        val invoked = UnitActions.invokeUnitAction(gs, UnitActionType.HurryResearch)
        assertTrue(invoked)
        assertTrue(civ.tech.isResearched("Writing"))
        assertTrue(gs.isDestroyed)
    }

    @Test
    fun `settling Super Specialists grants unique-driven city stats and military instructor XP`() {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(0, 0))

        val gs = testGame.addUnit("Great Scientist", civ, testGame.getTile(0, 0))
        assertTrue(UnitActions.invokeUnitAction(gs, UnitActionType.SettleGreatPerson))
        assertEquals(1, city.settledGreatPeople["Great Scientist"])
        assertTrue(gs.isDestroyed)

        val settledStats = city.cityStats.baseStatTree.children["Settled Great People"]?.totalStats
        assertNotNull(settledStats)
        assertEquals(1f, settledStats!!.production, 0.01f)
        assertEquals(6f, settledStats.science, 0.01f)

        // Settle a Great General as Military Instructor (+2 XP to trained military units)
        val gg = testGame.addUnit("Great General", civ, testGame.getTile(0, 0))
        assertTrue(UnitActions.invokeUnitAction(gg, UnitActionType.SettleGreatPerson))
        assertEquals(1, city.settledGreatPeople["Great General"])

        val warrior = testGame.addUnit("Warrior", civ, testGame.getTile(1, 0))
        warrior.baseUnit.addConstructionBonuses(warrior, city.cityConstructions)
        assertEquals(2, warrior.promotions.XP)
    }

    @Test
    fun `Great Work adds 4000 city culture, expands borders, and clears resistance`() {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        city.setFlag(CityFlags.Resistance, 5)
        assertTrue(city.isInResistance())
        val tilesBefore = city.tiles.size

        val ga = testGame.addUnit("Great Artist", civ, testGame.getTile(0, 0))
        assertTrue(UnitActions.invokeUnitAction(ga, UnitActionType.CreateGreatWork))

        assertFalse(city.isInResistance())
        assertTrue(city.tiles.size > tilesBefore)
        assertTrue(ga.isDestroyed)
    }

    @Test
    fun `Great General Warlord attachment splits 20 XP evenly, grants Warlord free upgrades, and unlocks Warlord promotions`() {
        val civ = testGame.addCiv()
        testGame.addCity(civ, testGame.getTile(0, 0))
        civ.tech.addTechnology("Iron Working", showNotification = false)

        val tile = testGame.getTile(0, 0)
        val warrior1 = testGame.addUnit("Warrior", civ, tile)
        val warrior2 = testGame.addUnit("Warrior", civ, tile)
        val warrior3 = testGame.addUnit("Warrior", civ, tile)
        val gg = testGame.addUnit("Great General", civ, tile)

        val actions = UnitActions.getUnitActions(gg, UnitActionType.AttachWarlord).toList()
        assertEquals(3, actions.size)

        // Attach to warrior1
        actions[0].action!!.invoke()

        assertTrue(gg.isDestroyed)
        assertTrue("Warlord" in warrior1.promotions.promotions)
        assertFalse("Warlord" in warrior2.promotions.promotions)
        // 20 XP split across 3 units -> 7, 7, 6 (total = 20)
        assertEquals(20, warrior1.promotions.XP + warrior2.promotions.XP + warrior3.promotions.XP)
        assertEquals(7, warrior1.promotions.XP)

        // Free upgrade cost (0 gold) for Warlord unit
        val swordsman = testGame.ruleset.units["Swordsman"]!!
        assertEquals(0, warrior1.upgrade.getCostOfUpgrade(swordsman))
        assertTrue(warrior2.upgrade.getCostOfUpgrade(swordsman) > 0)

        // Warlord-only promotion Morale is available to warrior1, not warrior2
        assertTrue(warrior1.promotions.getAvailablePromotions().any { it.name == "Morale" })
        assertFalse(warrior2.promotions.getAvailablePromotions().any { it.name == "Morale" })
    }

    @Test
    fun `Golden Ages ignore Civ V happiness and require escalating distinct Great Person types`() {
        val civ = testGame.addCiv()
        testGame.addCity(civ, testGame.getTile(0, 0))

        // Happiness must not trigger a Golden Age in Civ IV mode
        civ.goldenAges.storedHappiness = 10000
        TurnManager(civ).endTurn()
        assertFalse(civ.goldenAges.isGoldenAge())

        // 1st Golden Age: requires 1 GP
        val gs1 = testGame.addUnit("Great Scientist", civ, testGame.getTile(0, 0))
        assertEquals(1, civ.goldenAges.getGreatPeopleRequiredForGoldenAge())
        assertTrue(UnitActions.invokeUnitAction(gs1, UnitActionType.StartGoldenAge))
        assertTrue(civ.goldenAges.isGoldenAge())
        assertEquals(1, civ.goldenAges.goldenAgesTriggeredByGreatPeople)
        assertTrue(gs1.isDestroyed)

        // 2nd Golden Age: requires 2 DISTINCT GP types
        val gs2 = testGame.addUnit("Great Scientist", civ, testGame.getTile(0, 0))
        val gs3 = testGame.addUnit("Great Scientist", civ, testGame.getTile(0, 1))
        assertEquals(2, civ.goldenAges.getGreatPeopleRequiredForGoldenAge())
        // Two identical Great Scientists cannot trigger 2nd Golden Age
        assertFalse(UnitActions.invokeUnitAction(gs2, UnitActionType.StartGoldenAge))
        assertFalse(gs2.isDestroyed)
        assertFalse(gs3.isDestroyed)

        // Add a Great Artist on another tile -> empire-wide fallback finds 2 distinct types
        val ga = testGame.addUnit("Great Artist", civ, testGame.getTile(1, 0))
        val gaAction = UnitActions.getUnitActions(gs2, UnitActionType.StartGoldenAge).first()
        assertNotNull(gaAction.confirmation)
        assertNotNull(gaAction.action)
        gaAction.action!!.invoke()

        assertEquals(2, civ.goldenAges.goldenAgesTriggeredByGreatPeople)
        assertTrue(gs2.isDestroyed)
        assertTrue(ga.isDestroyed)
        assertFalse(gs3.isDestroyed)
    }
}
