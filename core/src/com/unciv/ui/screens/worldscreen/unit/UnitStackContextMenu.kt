package com.unciv.ui.screens.worldscreen.unit

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UnitIconGroup
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.view.MapUnitView
import com.unciv.view.TileView

/**
 * Floating Civ IV-style right-click context menu for selecting individual units
 * or triggering stack-wide selection on a tile with multiple friendly units.
 */
class UnitStackContextMenu(
    private val worldScreen: WorldScreen,
    tileView: TileView,
    stageX: Float,
    stageY: Float
) : Table() {

    private val friendlyUnits: List<MapUnitView> = tileView.militaryUnits
        .filterIsInstance<MapUnitView>()
        .filter { it.civ() == worldScreen.selectedCiv }

    init {
        background = BaseScreen.skinStrings.getUiBackground(
            path = "General/RoundedEdgeRectangle",
            tintColor = ImageGetter.CHARCOAL.cpy().apply { a = 0.95f }
        )
        pad(6f)
        defaults().pad(2f).left().fillX()

        add("Units on Tile (${friendlyUnits.size})".toLabel(Color.GOLD, 14)).padBottom(4f).row()

        for (unit in friendlyUnits) {
            val rowTable = Table()
            rowTable.background = BaseScreen.skinStrings.getUiBackground(
                path = "General/RoundedEdgeRectangle",
                tintColor = Color(0.18f, 0.22f, 0.28f, 0.9f)
            )
            rowTable.pad(4f, 6f, 4f, 6f)
            rowTable.add(UnitIconGroup(unit, 24f)).padRight(6f)

            val hpText = if (unit.unitHealth < 100) " (${unit.unitHealth} HP)" else ""
            val movesText = " [${unit.currentMovement}/${unit.getMaxMovement()}]"
            rowTable.add("${unit.displayName()}$hpText$movesText".toLabel(Color.WHITE, 13)).expandX().left()

            rowTable.onClick {
                worldScreen.bottomUnitTable.selectUnit(unit)
                worldScreen.shouldUpdate = true
                remove()
            }
            add(rowTable).row()
        }

        if (friendlyUnits.isNotEmpty()) {
            addSeparator()

            val selectAllBtn = "Select All (${friendlyUnits.size})".toTextButton(hideIcons = true)
            selectAllBtn.onClick {
                worldScreen.bottomUnitTable.selectUnit(friendlyUnits.first())
                for (u in friendlyUnits.drop(1)) {
                    worldScreen.bottomUnitTable.selectUnit(u, append = true)
                }
                worldScreen.shouldUpdate = true
                remove()
            }
            add(selectAllBtn).row()

            val idleUnits = friendlyUnits.filter { it.isIdle() }
            if (idleUnits.isNotEmpty()) {
                val selectIdleBtn = "Select Idle (${idleUnits.size})".toTextButton(hideIcons = true)
                selectIdleBtn.onClick {
                    worldScreen.bottomUnitTable.selectUnit(idleUnits.first())
                    for (u in idleUnits.drop(1)) {
                        worldScreen.bottomUnitTable.selectUnit(u, append = true)
                    }
                    worldScreen.shouldUpdate = true
                    remove()
                }
                add(selectIdleBtn).row()
            }
        }

        pack()

        val stage = worldScreen.stage
        val clampedX = stageX.coerceIn(4f, (stage.width - width - 4f).coerceAtLeast(4f))
        val clampedY = (stageY - height).coerceIn(4f, (stage.height - height - 4f).coerceAtLeast(4f))
        setPosition(clampedX, clampedY)

        stage.addActor(this)
        toFront()

        stage.addListener(object : InputListener() {
            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                val local = stageToLocalCoordinates(Vector2(x, y))
                if (local.x < 0 || local.x > width || local.y < 0 || local.y > height) {
                    remove()
                    stage.removeListener(this)
                }
                return false
            }

            override fun keyDown(event: InputEvent?, keycode: Int): Boolean {
                if (keycode == Input.Keys.ESCAPE) {
                    remove()
                    stage.removeListener(this)
                    return true
                }
                return false
            }
        })
    }

    private fun addSeparator() {
        val sep = Table()
        sep.background = BaseScreen.skinStrings.getUiBackground("General/RoundedEdgeRectangle", tintColor = Color.GRAY)
        add(sep).height(1f).padTop(3f).padBottom(3f).fillX().row()
    }
}
