package com.unciv.logic.civilization

import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.tile.TerrainType
import com.unciv.models.ruleset.unique.UniqueType
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
import kotlin.random.Random

@RunWith(BaseTestRunner::class)
class Civ4ReligionTests {

    private val testGame = TestGame()
    private lateinit var religionName: String
    private lateinit var secondReligionName: String

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(4)
        testGame.ruleset.modOptions.uniques.add(UniqueType.Civ4ReligionMode.text)
        if (testGame.ruleset.religions.size < 2) {
            testGame.ruleset.religions.add("TestBuddhism")
            testGame.ruleset.religions.add("TestHinduism")
        }
        religionName = testGame.ruleset.religions[0]
        secondReligionName = testGame.ruleset.religions[1]
    }

    @Test
    fun `tech founding picks non-capital city, blocks re-founding, and grants Holy City culture`() {
        val civ1 = testGame.addCiv()
        val civ2 = testGame.addCiv()
        val capital = testGame.addCity(civ1, testGame.getTile(0, 0))
        val secondCity = testGame.addCity(civ1, testGame.getTile(2, 0))
        testGame.addCity(civ2, testGame.getTile(-2, 0))

        val techName = "TestPolytheism"
        val tech = com.unciv.models.ruleset.tech.Technology().apply {
            name = techName
            column = testGame.ruleset.technologies.values.first().column
            uniques.add("Founds [$religionName] if not yet founded")
        }
        testGame.ruleset.technologies[techName] = tech

        civ1.tech.addTechnology(techName, showNotification = false)

        assertTrue(civ1.civ4Religion.isReligionFounded(religionName))
        assertTrue(secondCity.isHolyCityOf(religionName))
        assertFalse(capital.isHolyCityOf(religionName))
        assertTrue(religionName in secondCity.presentReligions)
        assertEquals(5f, secondCity.cityStats.baseStatTree.children["Holy City"]?.totalStats?.culture ?: 0f, 0.01f)

        // Second civ researching the same tech must not re-found it
        civ2.tech.addTechnology(techName, showNotification = false)
        assertTrue(secondCity.isHolyCityOf(religionName))
        assertFalse(civ2.cities.first().isHolyCityOf(religionName))
    }

    @Test
    fun `shrine gold, state religion happiness, and anarchy rules work correctly`() {
        val civ = testGame.addCiv()
        val city1 = testGame.addCity(civ, testGame.getTile(0, 0))
        val city2 = testGame.addCity(civ, testGame.getTile(2, 0))

        val shrine = Building().apply {
            name = "TestShrine"
            uniques.add("[1] Gold for each city following [$religionName]")
        }
        testGame.ruleset.buildings[shrine.name] = shrine

        civ.civ4Religion.foundReligion(religionName, preferredCity = city1)
        civ.civ4Religion.spreadReligionToCity(religionName, city2)
        city1.cityConstructions.addBuilding(shrine)
        city1.cityStats.update()

        assertEquals(2f, city1.cityStats.baseStatTree.children["Shrine"]?.totalStats?.gold ?: 0f, 0.01f)

        // Switching state religion grants +1 happiness and 1 turn of anarchy
        civ.civ4Religion.setStateReligion(religionName)
        assertEquals(religionName, civ.civ4Religion.stateReligion)
        assertEquals(1, civ.anarchyTurns)
        assertEquals(1f, city1.cityStats.happinessList["State Religion"] ?: 0f, 0.01f)

        // Switching during Golden Age causes 0 anarchy
        civ.anarchyTurns = 0
        civ.goldenAges.enterGoldenAge()
        civ.civ4Religion.setStateReligion(null)
        assertNull(civ.civ4Religion.stateReligion)
        assertEquals(0, civ.anarchyTurns)
    }

    @Test
    fun `missionary spread, passive trade-network spread, and Theocracy block work`() {
        val civ1 = testGame.addCiv()
        val civ2 = testGame.addCiv()
        val city1 = testGame.addCity(civ1, testGame.getTile(0, 0))
        val city2 = testGame.addCity(civ1, testGame.getTile(1, 0))
        val foreignCity = testGame.addCity(civ2, testGame.getTile(-2, 0))

        civ1.civ4Religion.foundReligion(religionName, preferredCity = city1)

        // Connect city2 to capital with road
        val road = com.unciv.models.ruleset.tile.TileImprovement().apply { name = "Road" }
        testGame.ruleset.tileImprovements["Road"] = road
        testGame.getTile(0, 0).improvement = "Road"
        testGame.getTile(1, 0).improvement = "Road"
        civ1.cache.updateCitiesConnectedToCapital()

        // Boost passive spread to 100% via building unique
        val spreadBoostBuilding = Building().apply {
            name = "SpreadBooster"
            uniques.add("[1000]% passive religion spread chance")
        }
        testGame.ruleset.buildings[spreadBoostBuilding.name] = spreadBoostBuilding
        city1.cityConstructions.addBuilding(spreadBoostBuilding)

        civ1.civ4Religion.endTurn(Random(0))
        assertTrue(religionName in city2.presentReligions)

        // Missionary active spread
        val missionaryBase = testGame.createBaseUnit()
        missionaryBase.uniques.add("Can spread [$religionName]")
        val missionary = testGame.addUnit(missionaryBase.name, civ2, foreignCity.getCenterTile())
        val spreadAction = UnitActions.getUnitActions(missionary, UnitActionType.SpreadReligion).firstOrNull()
        assertNotNull(spreadAction?.action)
        spreadAction!!.action!!.invoke()
        assertTrue(religionName in foreignCity.presentReligions)

        // Theocracy (BlocksNonStateReligionSpread) blocks non-state religion spread
        val theocracyBuilding = Building().apply {
            name = "TheocracyFlag"
            uniques.add("Prevents spread of non-state religions")
        }
        testGame.ruleset.buildings[theocracyBuilding.name] = theocracyBuilding
        foreignCity.cityConstructions.addBuilding(theocracyBuilding)
        civ2.civ4Religion.setStateReligion(religionName, applyAnarchy = false)

        assertFalse(civ1.civ4Religion.canSpreadReligionToCity(secondReligionName, foreignCity))
        assertFalse(civ1.civ4Religion.spreadReligionToCity(secondReligionName, foreignCity))
    }

    @Test
    fun `AI auto-adopts state religion and diplomacy modifiers reflect shared or conflicting religions`() {
        val aiCiv = testGame.addCiv(isPlayer = false)
        val otherCiv = testGame.addCiv(isPlayer = false)
        val aiCity = testGame.addCity(aiCiv, testGame.getTile(0, 0))
        val otherCity = testGame.addCity(otherCiv, testGame.getTile(-2, 0))

        aiCiv.diplomacyFunctions.makeCivilizationsMeet(otherCiv)

        aiCiv.civ4Religion.foundReligion(religionName, preferredCity = aiCity)
        TurnManager(aiCiv).endTurn()

        assertEquals(religionName, aiCiv.civ4Religion.stateReligion)

        // Same state religion -> BelieveSameReligion (+5)
        otherCiv.civ4Religion.spreadReligionToCity(religionName, otherCity)
        otherCiv.civ4Religion.setStateReligion(religionName, applyAnarchy = false)
        assertTrue(aiCiv.getDiplomacyManager(otherCiv)!!.hasModifier(DiplomaticModifiers.BelieveSameReligion))
        assertFalse(aiCiv.getDiplomacyManager(otherCiv)!!.hasModifier(DiplomaticModifiers.BelieveDifferentReligion))

        // Different state religion -> BelieveDifferentReligion (-3)
        otherCiv.civ4Religion.foundReligion(secondReligionName, preferredCity = otherCity)
        otherCiv.civ4Religion.setStateReligion(secondReligionName, applyAnarchy = false)
        assertTrue(aiCiv.getDiplomacyManager(otherCiv)!!.hasModifier(DiplomaticModifiers.BelieveDifferentReligion))
        assertFalse(aiCiv.getDiplomacyManager(otherCiv)!!.hasModifier(DiplomaticModifiers.BelieveSameReligion))
    }
}
