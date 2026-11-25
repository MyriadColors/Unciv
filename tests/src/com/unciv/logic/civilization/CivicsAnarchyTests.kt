package com.unciv.logic.civilization

import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.models.ruleset.Civic
import com.unciv.models.ruleset.CivicCategory
import com.unciv.models.ruleset.CivicUpkeep
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class CivicsAnarchyTests {

    private val testGame = TestGame()

    private lateinit var requiredTechName: String

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(3)
        testGame.ruleset.modOptions.uniques.add(UniqueType.CivicsMode.text)
        requiredTechName = testGame.ruleset.technologies.keys.last()
        setupTestCivics()
    }

    private fun setupTestCivics() {
        testGame.ruleset.civicCategories.clear()
        testGame.ruleset.civics.clear()
        val govCategory = CivicCategory().apply {
            name = "Government"
            defaultCivic = "Despotism"
        }
        val despotism = Civic().apply {
            name = "Despotism"
            category = govCategory
            upkeep = CivicUpkeep.Low
        }
        val hereditaryRule = Civic().apply {
            name = "Hereditary Rule"
            category = govCategory
            upkeep = CivicUpkeep.Medium
            requiredTech = requiredTechName
            uniques.add("[+1 Happiness] [in all cities]")
        }
        govCategory.civics.add(despotism)
        govCategory.civics.add(hereditaryRule)

        testGame.ruleset.civicCategories[govCategory.name] = govCategory
        testGame.ruleset.civics[despotism.name] = despotism
        testGame.ruleset.civics[hereditaryRule.name] = hereditaryRule
    }

    @Test
    fun `default civics initialize and tech requirement gates adoption`() {
        val civ = testGame.addCiv()
        civ.civics.initDefaults()

        assertEquals("Despotism", civ.civics.activeCivics["Government"])
        val hereditary = testGame.ruleset.civics["Hereditary Rule"]!!
        assertFalse(civ.civics.canAdoptCivic(hereditary))

        civ.tech.addTechnology(requiredTechName)
        assertTrue(civ.civics.canAdoptCivic(hereditary))
    }

    @Test
    fun `switching civics triggers anarchy which freezes city output and decrements on endTurn`() {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        val building = testGame.createBuilding()
        city.cityConstructions.setCurrentConstruction(building.name)
        civ.civics.initDefaults()
        civ.tech.addTechnology(requiredTechName)

        val anarchy = civ.civics.switchCivics(mapOf("Government" to "Hereditary Rule"))
        assertEquals(1, anarchy)
        assertEquals(1, civ.anarchyTurns)
        assertTrue(civ.isInAnarchy())

        city.cityStats.update()
        assertEquals(0f, city.cityStats.currentCityStats.production, 0.01f)
        assertEquals(0f, city.cityStats.currentCityStats.food, 0.01f)

        val storedProdBefore = city.cityConstructions.getWorkDone(building.name)
        TurnManager(civ).endTurn()
        assertEquals(storedProdBefore, city.cityConstructions.getWorkDone(building.name))
        assertEquals(0, civ.anarchyTurns)
        assertFalse(civ.isInAnarchy())
    }

    @Test
    fun `NoAnarchy unique and Golden Age bypass anarchy`() {
        val spiritualCiv = testGame.addCiv(UniqueType.NoAnarchy.text)
        spiritualCiv.civics.initDefaults()
        spiritualCiv.tech.addTechnology(requiredTechName)
        assertEquals(0, spiritualCiv.civics.switchCivics(mapOf("Government" to "Hereditary Rule")))
        assertEquals(0, spiritualCiv.anarchyTurns)

        val goldenCiv = testGame.addCiv()
        goldenCiv.civics.initDefaults()
        goldenCiv.tech.addTechnology(requiredTechName)
        goldenCiv.goldenAges.enterGoldenAge(10)
        assertEquals(0, goldenCiv.civics.switchCivics(mapOf("Government" to "Hereditary Rule")))
        assertEquals(0, goldenCiv.anarchyTurns)
    }

    @Test
    fun `civic upkeep scales with population and respects CivicUpkeepDiscount`() {
        val normalCiv = testGame.addCiv()
        val city1 = testGame.addCity(normalCiv, testGame.getTile(0, 0))
        city1.population.setPopulation(16) // 0.4 * 1 + 0.1 * 16 = 2.0; Medium (1.0) -> 2, Low (0.5) -> 1
        normalCiv.civics.initDefaults()
        assertEquals(1, normalCiv.civics.getCivicUpkeep())

        val organizedCiv = testGame.addCiv("[-50]% Civic upkeep")
        val city2 = testGame.addCity(organizedCiv, testGame.getTile(1, 0))
        city2.population.setPopulation(16)
        organizedCiv.civics.initDefaults()
        organizedCiv.tech.addTechnology(requiredTechName)
        organizedCiv.civics.switchCivics(mapOf("Government" to "Hereditary Rule"))
        // Raw upkeep = 1.0 * 2.0 = 2.0, with -50% -> 1
        assertEquals(1, organizedCiv.civics.getCivicUpkeep())
    }

    @Test
    fun `Representation grants happiness in pop 6+ cities and science from all specialists`() {
        val game = TestGame()
        game.makeHexagonalMap(4)
        val civ = game.addCiv()
        val bigCity = game.addCity(civ, game.getTile(0, 0))
        val smallCity = game.addCity(civ, game.getTile(3, 0))
        bigCity.population.setPopulation(6)
        smallCity.population.setPopulation(3)
        civ.civics.initDefaults()
        civ.tech.addTechnology("Constitution")

        civ.civics.switchCivics(mapOf("Government" to "Representation"))
        civ.anarchyTurns = 0
        bigCity.cityStats.update()
        smallCity.cityStats.update()

        assertEquals(3f, bigCity.cityStats.happinessList["Civic"] ?: 0f, 0.01f)
        assertEquals(0f, smallCity.cityStats.happinessList["Civic"] ?: 0f, 0.01f)

        val artistStats = bigCity.cityStats.getStatsOfSpecialist("Artist")
        val baseArtistScience = game.ruleset.specialists["Artist"]!!.science
        assertEquals(baseArtistScience + 3f, artistStats.science, 0.01f)
    }

    @Test
    fun `Organized Religion, Pacifism, and Theocracy apply only in cities with Civ4 state religion`() {
        val game = TestGame()
        game.makeHexagonalMap(4)
        val civ = game.addCiv()
        val religiousCity = game.addCity(civ, game.getTile(0, 0))
        val secularCity = game.addCity(civ, game.getTile(3, 0))
        civ.civics.initDefaults()
        civ.civ4Religion.foundReligion("Buddhism", religiousCity)
        civ.civ4Religion.setStateReligion("Buddhism", applyAnarchy = false)
        secularCity.presentReligions.clear()

        civ.tech.addTechnology("Monotheism")
        civ.tech.addTechnology("Philosophy")
        civ.tech.addTechnology("Theology")
        civ.tech.addTechnology("Feudalism")

        // Organized Religion: +25% building production only in religiousCity
        civ.civics.switchCivics(mapOf("Religion" to "Organized Religion"))
        civ.anarchyTurns = 0
        val building = game.createBuilding()
        religiousCity.cityConstructions.setCurrentConstruction(building.name)
        secularCity.cityConstructions.setCurrentConstruction(building.name)
        religiousCity.cityStats.update()
        secularCity.cityStats.update()
        assertTrue(religiousCity.cityStats.statPercentBonusTree.totalStats.production > secularCity.cityStats.statPercentBonusTree.totalStats.production)

        // Pacifism: +100% GP generation only in religiousCity
        civ.civics.switchCivics(mapOf("Religion" to "Pacifism"))
        civ.anarchyTurns = 0
        assertEquals(100, religiousCity.getGreatPersonPercentageBonus() - secularCity.getGreatPersonPercentageBonus())

        // Theocracy: +2 starting XP for military units only in religiousCity
        civ.civics.switchCivics(mapOf("Religion" to "Theocracy"))
        civ.anarchyTurns = 0
        val warrior = game.ruleset.units["Warrior"]!!
        val relUnit1 = game.addUnit("Warrior", civ, religiousCity.getCenterTile())
        val secUnit1 = game.addUnit("Warrior", civ, secularCity.getCenterTile())
        warrior.addConstructionBonuses(relUnit1, religiousCity.cityConstructions)
        warrior.addConstructionBonuses(secUnit1, secularCity.cityConstructions)
        assertEquals(2, relUnit1.promotions.XP)
        assertEquals(0, secUnit1.promotions.XP)

        // Vassalage: +2 starting XP in all cities
        civ.civics.switchCivics(mapOf("Legal" to "Vassalage"))
        civ.anarchyTurns = 0
        val relUnit2 = game.addUnit("Warrior", civ, religiousCity.getCenterTile())
        val secUnit2 = game.addUnit("Warrior", civ, secularCity.getCenterTile())
        warrior.addConstructionBonuses(relUnit2, religiousCity.cityConstructions)
        warrior.addConstructionBonuses(secUnit2, secularCity.cityConstructions)
        assertEquals(4, relUnit2.promotions.XP)
        assertEquals(2, secUnit2.promotions.XP)
    }

    @Test
    fun `State Property zeroes distance maintenance and Mercantilism blocks foreign trade routes`() {
        val game = TestGame()
        game.makeHexagonalMap(6)
        val civ = game.addCiv()
        val otherCiv = game.addCiv()
        val capital = game.addCity(civ, game.getTile(0, 0))
        val farCity = game.addCity(civ, game.getTile(4, 0))
        val foreignCity = game.addCity(otherCiv, game.getTile(-3, 0))
        farCity.population.setPopulation(6)
        foreignCity.population.setPopulation(10)
        civ.civics.initDefaults()
        otherCiv.civics.initDefaults()

        // State Property: distance maintenance drops to 0
        val maintenanceBefore = farCity.cityStats.getCiv4CityMaintenance()
        civ.tech.addTechnology("Communism")
        civ.civics.switchCivics(mapOf("Economy" to "State Property"))
        civ.anarchyTurns = 0
        val maintenanceAfter = farCity.cityStats.getCiv4CityMaintenance()
        assertTrue(maintenanceAfter < maintenanceBefore)

        // Mercantilism: blocks foreign trade routes (foreign city gives 2x multiplier when open borders exist)
        civ.diplomacyFunctions.makeCivilizationsMeet(otherCiv)
        civ.getDiplomacyManager(otherCiv)!!.hasOpenBorders = true
        otherCiv.getDiplomacyManager(civ)!!.hasOpenBorders = true
        // Connect capital, farCity, and foreignCity via roads so all are eligible trade partners
        for (x in -3..4) game.getTile(x, 0).roadStatus = com.unciv.logic.map.tile.RoadStatus.Road
        civ.cache.updateCitiesConnectedToCapital()
        otherCiv.cache.updateCitiesConnectedToCapital()

        capital.cityStats.update()
        val goldWithForeign = capital.cityStats.baseStatTree.children["Trade routes"]?.totalStats?.gold ?: 0f

        civ.tech.addTechnology("Banking")
        civ.civics.switchCivics(mapOf("Economy" to "Mercantilism"))
        civ.anarchyTurns = 0
        capital.cityStats.update()
        val goldUnderMercantilism = capital.cityStats.baseStatTree.children["Trade routes"]?.totalStats?.gold ?: 0f
        assertTrue(goldUnderMercantilism < goldWithForeign)
    }

    @Test
    fun `Caste System grants unlimited Artist, Scientist, Merchant specialists and unassigns on switch`() {
        val game = TestGame()
        game.makeHexagonalMap(4)
        val civ = game.addCiv()
        val city = game.addCity(civ, game.getTile(0, 0))
        city.population.setPopulation(5)
        civ.civics.initDefaults()
        civ.tech.addTechnology("Code of Laws")

        assertEquals(0, city.population.getMaxSpecialists()["Artist"])
        civ.civics.switchCivics(mapOf("Labor" to "Caste System"))
        civ.anarchyTurns = 0

        assertEquals(5, city.population.getMaxSpecialists()["Artist"])
        assertEquals(5, city.population.getMaxSpecialists()["Scientist"])
        assertEquals(5, city.population.getMaxSpecialists()["Merchant"])

        city.population.specialistAllocations.add("Artist", 3)
        assertEquals(3, city.population.specialistAllocations["Artist"])

        // Switching out of Caste System unassigns excess Artist specialists
        civ.civics.switchCivics(mapOf("Labor" to "Tribalism"))
        civ.anarchyTurns = 0
        assertEquals(0, city.population.specialistAllocations["Artist"])
    }

    @Test
    fun `Emancipation applies unhappiness pressure to rival civs without Emancipation`() {
        val game = TestGame()
        game.makeHexagonalMap(4)
        val civA = game.addCiv()
        val civB = game.addCiv()
        val cityA = game.addCity(civA, game.getTile(0, 0))
        val cityB = game.addCity(civB, game.getTile(3, 0))
        cityA.population.setPopulation(6)
        cityB.population.setPopulation(6)
        civA.civics.initDefaults()
        civB.civics.initDefaults()
        civA.diplomacyFunctions.makeCivilizationsMeet(civB)

        civA.tech.addTechnology("Democracy")
        civA.civics.switchCivics(mapOf("Labor" to "Emancipation"))
        civA.anarchyTurns = 0

        cityA.cityStats.update()
        cityB.cityStats.update()
        assertEquals(0f, cityA.cityStats.happinessList["We demand Emancipation!"] ?: 0f, 0.01f)
        assertTrue((cityB.cityStats.happinessList["We demand Emancipation!"] ?: 0f) < 0f)
    }

    @Test
    fun `combining civic switch and state religion switch applies additive anarchy`() {
        val game = TestGame()
        game.makeHexagonalMap(4)
        val civ = game.addCiv()
        val city = game.addCity(civ, game.getTile(0, 0))
        civ.civics.initDefaults()
        civ.civ4Religion.foundReligion("Buddhism", city)
        civ.tech.addTechnology("Bronze Working")

        civ.civics.switchCivics(mapOf("Labor" to "Slavery"))
        assertEquals(1, civ.anarchyTurns)
        civ.civ4Religion.setStateReligion("Buddhism")
        assertEquals(2, civ.anarchyTurns)
    }
}
