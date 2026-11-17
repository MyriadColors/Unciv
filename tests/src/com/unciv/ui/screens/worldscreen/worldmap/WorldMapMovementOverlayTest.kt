package com.unciv.ui.screens.worldscreen.worldmap

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.GridCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.MapArrowType
import com.unciv.ui.components.MiscArrowTypes
import com.unciv.ui.components.UnitMovementMemoryType
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import com.unciv.view.GameView
import com.unciv.view.MapUnitView
import com.unciv.view.TileView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@RunWith(BaseTestRunner::class)
class WorldMapMovementOverlayTest {
    private lateinit var game: TestGame
    private lateinit var selectedCiv: Civilization
    private lateinit var enemy: Civilization
    private lateinit var restrictedView: GameView
    private lateinit var spectatorView: GameView

    private data class Arrow(val from: GridCoord, val to: GridCoord, val type: MapArrowType)

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(4)
        selectedCiv = game.addCiv(isPlayer = true)
        enemy = game.addCiv()
        val spectator = game.addCiv(game.ruleset.nations[Constants.spectator]!!, isPlayer = true)
        spectator.viewableTiles = game.tileMap.values.toSet()
        restrictedView = GameView(game.gameInfo, selectedCiv, spectatorMode = true)
        spectatorView = GameView(game.gameInfo, spectator, spectatorMode = true)
    }

    @Test
    fun foreignHistoryIsHiddenOnExploredTilesOutsideCurrentVision() {
        val unit = unitWithHistory(enemy, GridCoord(-1, 0), GridCoord.Zero)
        unit.attacksSinceTurnStart.add(GridCoord(1, 0))
        for (tile in game.tileMap.values) tile.setExplored(selectedCiv, true)
        selectedCiv.viewableTiles = emptySet()

        assertTrue(drawOverlay(restrictedView).isEmpty())

        selectedCiv.viewableTiles = game.tileMap.values.toSet()
        assertEquals(setOf(
            Arrow(GridCoord(-1, 0), GridCoord.Zero, UnitMovementMemoryType.UnitMoved),
            Arrow(GridCoord.Zero, GridCoord(1, 0), MiscArrowTypes.UnitHasAttacked)
        ), drawOverlay(restrictedView).toSet())
    }

    @Test
    fun spectatorArrowsIncludeEndpointsUnexploredBySelectedCivilization() {
        val unit = unitWithHistory(enemy, GridCoord(-1, 0), GridCoord.Zero)
        unit.attacksSinceTurnStart.add(GridCoord(1, 0))
        enemy.attacksSinceTurnStart.add(Civilization.HistoricalAttackMemory(null, GridCoord(2, 0), GridCoord(2, 1)))
        assertFalse(game.getTile(GridCoord.Zero).isExplored(selectedCiv))
        assertTrue(drawOverlay(restrictedView).isEmpty())

        assertEquals(setOf(
            Arrow(GridCoord(-1, 0), GridCoord.Zero, UnitMovementMemoryType.UnitMoved),
            Arrow(GridCoord.Zero, GridCoord(1, 0), MiscArrowTypes.UnitHasAttacked),
            Arrow(GridCoord(2, 0), GridCoord(2, 1), MiscArrowTypes.UnitHasAttacked)
        ), drawOverlay(spectatorView).toSet())
    }

    @Test
    fun selectedUnitStillFiltersHistoryWhenMapPerspectiveChanges() {
        val selectedUnit = unitWithHistory(selectedCiv, GridCoord(-1, 0), GridCoord.Zero)
        selectedUnit.attacksSinceTurnStart.add(GridCoord(0, 1))
        val otherUnit = unitWithHistory(enemy, GridCoord(2, 0), GridCoord(3, 0))
        otherUnit.attacksSinceTurnStart.add(GridCoord(3, 1))
        otherUnit.attacksSinceTurnStart.add(GridCoord.Zero)

        assertEquals(setOf(
            Arrow(GridCoord(-1, 0), GridCoord.Zero, UnitMovementMemoryType.UnitMoved),
            Arrow(GridCoord.Zero, GridCoord(0, 1), MiscArrowTypes.UnitHasAttacked),
            Arrow(GridCoord(3, 0), GridCoord.Zero, MiscArrowTypes.UnitHasAttacked)
        ), drawOverlay(spectatorView, restrictedView.getMapUnitView(selectedUnit)).toSet())
    }

    @Test
    fun spectatorPerspectiveKeepsPlannedRoutesForSelectedCivilization() {
        val selectedUnit = game.addUnit("Warrior", selectedCiv, game.getTile(0, 0))
        selectedUnit.action = "moveTo 1,0"
        val otherOwnUnit = game.addUnit("Warrior", selectedCiv, game.getTile(-3, 0))
        otherOwnUnit.action = "moveTo -2,0"
        val enemyUnit = game.addUnit("Warrior", enemy, game.getTile(3, 0))
        enemyUnit.action = "moveTo 3,1"

        assertEquals(setOf(
            Arrow(GridCoord.Zero, GridCoord(1, 0), MiscArrowTypes.UnitMoving),
            Arrow(GridCoord(-3, 0), GridCoord(-2, 0), MiscArrowTypes.UnitMoving)
        ), drawOverlay(spectatorView, restrictedView.getMapUnitView(selectedUnit)).toSet())
    }

    private fun unitWithHistory(civ: Civilization, from: GridCoord, to: GridCoord): MapUnit {
        val unit = game.addUnit("Warrior", civ, game.getTile(to))
        unit.movementMemories = arrayListOf(MapUnit.UnitMovementMemory(from, UnitMovementMemoryType.UnitMoved))
        return unit
    }

    /** Run the real overlay method, intercepting drawing so the test needs no graphics context. */
    private fun drawOverlay(gameView: GameView, selectedUnit: MapUnitView? = null): List<Arrow> {
        val unitTable = mock(UnitTable::class.java)
        `when`(unitTable.selectedUnit).thenReturn(selectedUnit)
        val screen = mock(WorldScreen::class.java) { invocation ->
            if (invocation.method.name.startsWith("getBottomUnitTable")) unitTable
            else Answers.RETURNS_DEFAULTS.answer(invocation)
        }
        `when`(screen.selectedGameView).thenReturn(restrictedView)
        val arrows = ArrayList<Arrow>()
        val holder = mock(WorldMapHolder::class.java) { invocation ->
            if (invocation.method.name == "addArrow") {
                val from = invocation.getArgument<TileView>(0).position()
                val to = invocation.getArgument<TileView>(1).position()
                // Zero-length segments recorded when a unit has not moved have no visible arrow.
                if (from != to) arrows.add(Arrow(from, to, invocation.getArgument(2)))
                null
            } else Answers.RETURNS_DEFAULTS.answer(invocation)
        }
        WorldMapHolder::class.java.getDeclaredField("worldScreen").apply {
            isAccessible = true
            set(holder, screen)
        }
        val plannedUnits = restrictedView.civView.getUnits().asSequence()
        val updateMovementOverlay = WorldMapHolder::class.java.methods.single {
            it.name.startsWith("updateMovementOverlay") && it.parameterCount == 2
        }
        updateMovementOverlay.invoke(doCallRealMethod().`when`(holder), gameView, plannedUnits)
        updateMovementOverlay.invoke(holder, gameView, plannedUnits)
        return arrows
    }
}
